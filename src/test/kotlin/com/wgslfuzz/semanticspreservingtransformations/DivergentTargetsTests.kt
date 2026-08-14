package com.wgslfuzz.semanticspreservingtransformations

import com.wgslfuzz.core.Attribute
import com.wgslfuzz.core.GlobalDecl
import com.wgslfuzz.core.ShaderJob
import com.wgslfuzz.core.Statement
import com.wgslfuzz.core.createShaderJob
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [findLocalVariableCandidates], the v2 scope walk that [DivergentLocalInjection] depends on.
 *
 * The intended semantics being pinned here are:
 *
 *  1. **candidates / declarations** 
 *  One [LocalVariableTarget] per function-scope `var` in any scope whose type has a scalar leaf
 *  Carries the [Statement.Compound] it was declared in and its statement index within that compound. 
 *  A `var` in a `for` header is *not* a candidate: it is scoped to the loop, not to any compound, so there is no compound index to record.
 *  2. **childrenCompound** 
 *  One child [CompoundInfo] per nested compound reachable from this scope's statements, in source order.
 *  Includes control-flow bodies (`if` then/else branches, `for`/`while`/`loop` bodies, switch clause bodies), not just bare `{ ... }` blocks.
 *  3. **reads**
 *  `(name, statementIndex)` for each read of a tracked local appearing in this scope's *own* statements,
 *  Excludes anything inside a nested child compound, which owns its own reads. 
 *  A read in a control-flow *header* (an `if` condition, a `for` condition) belongs to the enclosing scope, at the index of the control-flow statement. 
 *  An assignment *target* is a write, not a read, so it is not recorded.
 */
class DivergentTargetsTests {
    private fun computeBody(shaderText: String): Pair<ShaderJob, Statement.Compound> {
        val shaderJob = createShaderJob(shaderText, emptyList())
        val entryPoint =
            shaderJob.tu.globalDecls
                .filterIsInstance<GlobalDecl.Function>()
                .first { fn -> fn.attributes.any { it is Attribute.Compute } }
        return shaderJob to entryPoint.body
    }

    private fun walk(shaderText: String): Pair<CompoundInfo, List<LocalVariableTarget>> {
        val (shaderJob, body) = computeBody(shaderText)
        return findLocalVariableCandidates(shaderJob, body)
    }

    /** (name, declIndex, scalar leaf type) per candidate, in the order the walk produced them. */
    private fun summarise(candidates: List<LocalVariableTarget>): List<Triple<String?, Int?, String>> =
        candidates.map { Triple(lhsBaseIdentifierName(it.target), it.declIndex, it.targetType.toString()) }

    private fun readsOf(info: CompoundInfo): Set<Pair<String, Int>> = info.reads.toSet()

    /** Follows a path of child indices down from [info], e.g. `descend(root, 1, 0)`. */
    private fun descend(
        info: CompoundInfo,
        vararg path: Int,
    ): CompoundInfo = path.fold(info) { acc, index -> acc.childrenCompound[index] }

    /**
     * Root statement indices:
     *   0 var a, 1 var s, 2 var v, 3 `let d`, 4 `a = a + 1`, 5 bare block, 6 if/else, 7 for,
     *   8 `a = a + 1`
     * Root child scopes, in source order: bare block, if-then, if-else, for body.
     *
     * The if-then branch nests one level deeper: its statements are `0 var c`, `1 for`, `2 s.x = c`,
     * and the for body is its only child. That for reads `a` in its header -- which belongs to the
     * if-then branch, at the for's index 1 -- and `c` in its body.
     */
    private val nestedScopes =
        """
        struct S {
          x: i32,
          y: f32,
        }

        @compute @workgroup_size(1)
        fn main(@builtin(local_invocation_id) lid: vec3<u32>) {
          var a: i32 = 1;
          var s: S;
          var v: vec4<f32>;
          let d: i32 = 1;
          a = a + 1;
          {
            var b: i32 = a;
            b = b + 1;
          }
          if (a > 0) {
            var c: i32 = 2;
            for (var i: i32 = 0; i < a; i = i + 1) {
              c = c + 1;
            }
            s.x = c;
          } else {
            a = 3;
          }
          for (var i: i32 = 0; i < a; i++) {
            v[0] = v[0] + f32(a);
          }
          a = a + 1;
        }
        """.trimIndent()

    @Test
    fun `every scalar-reachable var in every scope becomes a candidate`() {
        val (_, candidates) = walk(nestedScopes)
        assertEquals(
            listOf(
                // declared in the entry point body
                Triple("a", 0, "I32"),
                Triple("s", 1, "I32"), // first scalar leaf of S is member x
                Triple("v", 2, "F32"), // first scalar leaf of vec4<f32> is element 0
                // `d` should not be present since it is a let, not a var
                // declared in nested scopes
                Triple("b", 0, "I32"), // bare block
                Triple("c", 0, "I32"), // if-then branch
            ),
            summarise(candidates),
        )
    }

    @Test
    fun `a candidate records the compound it was declared in`() {
        val (root, candidates) = walk(nestedScopes)
        val byName = candidates.associateBy { lhsBaseIdentifierName(it.target) }

        assertEquals(
            root.compound, 
            byName["a"]!!.declCompound, 
            "a is declared in the entry point body"
        )
        assertEquals(
            root.compound, 
            byName["s"]!!.declCompound, 
            "s is declared in the entry point body"
        )
        assertEquals(
            root.compound, 
            byName["v"]!!.declCompound, 
            "v is declared in the entry point body"
        )
        assertEquals(
            descend(root, 0).compound,
            byName["b"]!!.declCompound,
            "b is declared in the bare block, not the entry point body",
        )
        assertEquals(
            descend(root, 1).compound,
            byName["c"]!!.declCompound,
            "c is declared in the if-then branch",
        )
    }

    @Test
    fun `a var declared in a for header is not a candidate`() {
        val (_, candidates) = walk(nestedScopes)
        assertEquals(
            emptyList<String?>(),
            candidates.map { lhsBaseIdentifierName(it.target) }.filter { it == "i" },
            "`i` is scoped to the for loop, not to any compound, so it has no declIndex to record",
        )
    }

    @Test
    fun `control flow bodies are child scopes, in source order`() {
        val (root, _) = walk(nestedScopes)
        assertEquals(
            4,
            root.childrenCompound.size,
            "expected: bare block, if-then branch, if-else branch, for body",
        )
        // ---- nested scopes ----
        assertEquals(
            listOf(0, 1, 0, 0),
            root.childrenCompound.map { it.childrenCompound.size },
            "only the if-then branch has a child of its own: the for body nested inside it",
        )
        assertEquals(
            (descend(root, 1).compound.statements[1] as Statement.For).body,
            descend(root, 1, 0).compound,
            "the if-then branch's only child is the body of the for at its statement 1",
        )
    }

    @Test
    fun `each child scope wraps the right compound`() {
        val (root, _) = walk(nestedScopes)
        val declNamesIn = { info: CompoundInfo ->
            info.compound.statements.filterIsInstance<Statement.Variable>().map { it.name }
        }
        assertEquals(listOf("b"), declNamesIn(root.childrenCompound[0]), "child 0 is the bare block")
        assertEquals(listOf("c"), declNamesIn(root.childrenCompound[1]), "child 1 is the if-then branch")
        assertEquals(emptyList<String>(), declNamesIn(root.childrenCompound[2]), "child 2 is the if-else branch")
        assertEquals(emptyList<String>(), declNamesIn(root.childrenCompound[3]), "child 3 is the for body")
    }

    @Test
    fun `reads records the name and the index of the reading statement`() {
        val (root, _) = walk(nestedScopes)
        assertEquals(
            setOf(
                "a" to 4, // a = a + 1        -- `let d` at 3 reads nothing, but still takes an index
                "a" to 6, // if (a > 0)       -- header read, belongs to this scope
                "a" to 7, // for (...; i < a; ...)
                "a" to 8, // a = a + 1
            ),
            readsOf(root),
            "reads inside nested scopes must not leak into the enclosing scope",
        )
    }

    @Test
    fun `each nested scope owns its own reads`() {
        val (root, _) = walk(nestedScopes)
        assertEquals(setOf("a" to 0, "b" to 1), readsOf(descend(root, 0)), "bare block")
        assertEquals(
            setOf("a" to 1, "c" to 2), // `for (...; i < a; ...)` header, then `s.x = c`
            readsOf(descend(root, 1)),
            "if-then branch: the nested for's header read belongs here, at the for's own index",
        )
        assertEquals(emptySet<Pair<String, Int>>(), readsOf(descend(root, 2)), "if-else branch: `a = 3` writes a")
        assertEquals(setOf("v" to 0, "a" to 0), readsOf(descend(root, 3)), "for body")
        assertEquals(
            setOf("c" to 0), // `c = c + 1`
            readsOf(descend(root, 1, 0)),
            "the for body nested in the if-then branch owns only its own read of c",
        )
    }

    @Test
    fun `an assignment target is a write, not a read`() {
        val (root, _) = walk(nestedScopes)
        // Statement 3 is `a = a + 1`: `a` on the right is a read, `a` on the left is not, so the
        // pair (a, 3) is present but the scope must not also claim s is read by `s.x = c`.
        assertEquals(
            emptySet<Pair<String, Int>>(),
            readsOf(descend(root, 1)).filter { it.first == "s" }.toSet(),
            "`s.x = c` writes s; it does not read it",
        )
    }

    /**
     * `n` is a module-scope variable. The first block declares a local that shadows it; the second
     * block reads the module-scope one. The walk must not treat the second block's `n` as a read of
     * a tracked local -- the first block's declaration is out of scope by then.
     */
    private val siblingScopes =
        """
        var<private> n: i32 = 0;

        @compute @workgroup_size(1)
        fn main() {
          {
            var n: i32 = 1;
            n = n + 1;
          }
          {
            var z: i32 = n;
          }
        }
        """.trimIndent()

    @Test
    fun `a declaration does not stay in scope for a sibling block`() {
        val (root, _) = walk(siblingScopes)
        assertEquals(setOf("n" to 1), readsOf(descend(root, 0)), "first block reads its own local n")
        assertEquals(
            emptySet<Pair<String, Int>>(),
            readsOf(descend(root, 1)),
            "`var z = n` reads the module-scope n, which is not a tracked local",
        )
    }

    /**
     * walk() continues to traverse statements after an exit [escapeCompound].
     * Reads and nested scopes are still analysed, but declarations are dropped since they might not be reached.
     * Conservative analysis.
     */
    private val earlyExit =
        """
        @compute @workgroup_size(1)
        fn main(@builtin(local_invocation_id) lid: vec3<u32>) {
          var p: i32 = 1;
          if (lid.x == 0u) {
            var q: i32 = 2;
            return;
            var r: i32 = 3;
            p = p + r;
            {
              var w: i32 = 4;
            }
          }
          p = p + 1;
          var s: i32 = p;
        }
        """.trimIndent()

    @Test
    fun `a declaration after an exit is still a candidate`() {
        val (_, candidates) = walk(earlyExit)
        assertEquals(
            listOf("p", "q", "r", "w", "s"),
            candidates.map { lhsBaseIdentifierName(it.target) },
            "All declarations are recorded, including those after an exit. " +
                    "Safe behaviour since injections are always after the target declaration.",
        )
    }

    @Test
    fun `a read after an exit is still recorded`() {
        val (root, _) = walk(earlyExit)
        assertEquals(
            setOf("p" to 3, "r" to 3),
            readsOf(descend(root, 0)),
            "`p = p + r`. Both `p` and `r` are tracked candidates. (declarations after exit are still registered)",
        )
        assertEquals(
            setOf("p" to 2, "p" to 3),
            readsOf(root),
            "`p = p + 1` and `var s: i32 = p`",
        )
    }

    /**
     * A compound after an escape is still analysed and still gets a [CompoundInfo], so applyV2 can
     * inject into it: it either runs in full or not at all, so a pair placed inside it is intact
     * either way.
     */
    @Test
    fun `a compound after an exit still gets a scope`() {
        val (shaderJob, body) = computeBody(earlyExit)
        val thenBranch = (body.statements[1] as Statement.If).thenBranch
        val blockAfterExit = thenBranch.statements[4] as Statement.Compound

        val (root, _) = findLocalVariableCandidates(shaderJob, body)
        val scopes = root.scopesByCompound()

        assertTrue(thenBranch in scopes, "the then-branch itself is analysed")
        assertTrue(blockAfterExit in scopes, "the block after the return is analysed too")
        assertEquals(
            listOf(blockAfterExit),
            root.childrenCompound[0].childrenCompound.map { it.compound },
            "the then-branch registers its nested block even though that block sits after the return",
        )
    }

    @Test
    fun `a scope records the index of every statement that can escape it`() {
        val (root, _) = walk(earlyExit)
        assertEquals(listOf(1), descend(root, 0).escapeIndices, "the return is statement 1 of the then-branch")
        assertEquals(
            listOf(1),
            root.escapeIndices,
            "the if at index 1 of the body escapes it too: the return inside leaves the function",
        )
        assertEquals(
            emptyList<Int>(),
            descend(walk(nestedScopes).first, 0).escapeIndices,
            "a scope with no escape records none",
        )
    }

    /**
     * A loop-exit guard the statement in which the exit is located in (exit could be nested within), not the specific line where the exit is on. A perturb/restore pair must not straddle it.
     * In example below, th escape index for the while-loop's body is 1 (not 2 or null)
     */
    private val breakInsideIf =
        """
        @compute @workgroup_size(1)
        fn main(@builtin(local_invocation_id) lid: vec3<u32>) {
          var x: i32 = 1;
          for (var i = 0; i < 10; i++) {
            while (true) {
              x = x + 1;
              if (lid.x == 0u) {
                break;
              }
              x = x - 1;
              continue;
            }
            x = x + 2;
          }
        }
        """.trimIndent()

    @Test
    fun `a break nested inside an if escapes the loop body`() {
        val (root, _) = walk(breakInsideIf)
        val forBody = descend(root, 0)
        val whileBody = descend(forBody, 0)

        assertEquals(
            listOf(1, 3),
            whileBody.escapeIndices,
            "1: if containing the break, " +
                    "3: `continue`",
        )
        assertEquals(
            emptyList<Int>(),
            forBody.escapeIndices,
            "the while captures its own break and continue, so it does not escape the for body",
        )
        assertEquals(emptyList<Int>(), root.escapeIndices, "nothing escapes the function body")
    }

    @Test
    fun `a switch captures break but not continue`() {
        val (root, _) = walk(
            """
            @compute @workgroup_size(1)
            fn main(@builtin(local_invocation_id) lid: vec3<u32>) {
              var x: i32 = 1;
              loop {
                switch (x) {
                  case 0: { break; }
                  default: { x = x + 1; }
                }
                switch (x) {
                  case 0: { continue; }
                  default: { x = x + 1; }
                }
                break;
              }
            }
            """.trimIndent(),
        )
        val loopBody = descend(root, 0)
        assertEquals(
            listOf(1, 2),
            loopBody.escapeIndices,
            "the switch whose clause breaks is captured by that switch -- ie. the `break` only escapes the switch itself." +
                "only the switch with `continue` (index 1) and the loop's own break (index 2) escape the loop's body",
        )
    }

    /**
     * `nestedScopes` covers bare blocks, if-then, if-else and a `for` body. 
     * `controlFlow` covers the rest of the ways a [Statement.Compound] can be introduced,
     *  each of which `splitStatement` reaches through a different arm of [traverse]:
     *  a `while` body, a `loop` body, a `continuing` block, switch clause bodies, `else if` chain.
     *
     * None of these statements IS a Compound -- `While`, `Loop`, `Switch` and `If` are sibling
     * subtypes of [Statement] -- so `splitStatement` falls through to `traverse` and picks their
     * bodies up one level down. WGSL has no single-statement bodies, so every one of these fields
     * is typed Compound and is therefore unconditionally a scope.
     *
     * Root statement indices:
     *   0 var a, 1 var b, 2 while, 3 loop, 4 switch, 5 if/else-if/else
     */
    private val controlFlow =
        """
        @compute @workgroup_size(1)
        fn main(@builtin(local_invocation_id) lid: vec3<u32>) {
          var a: i32 = 1;
          var b: i32 = 2;
          while (a < 10) {
            var c: i32 = a;
            a = a + c;
          }
          loop {
            b = b + 1;
            continuing {
              b = b + 1;
              break if (b > 5);
            }
          }
          switch (a) {
            case 0: {
              var e: i32 = 1;
              b = e;
            }
            default: {
              b = a;
            }
          }
          if (a > 0) {
            if (a == 1) {
              a = 0;
            }
            a = 1;
          } else if (b > 0) {
            b = 1;
          } else {
            a = 2;
          }
        }
        """.trimIndent()

    @Test
    fun `every control flow form contributes its body as a child scope`() {
        val (root, _) = walk(controlFlow)
        assertEquals(
            8,
            root.childrenCompound.size,
            "expected: while body, loop body, continuing block, case clause, default clause, " +
                "if-then, else-if-then, else",
            // splitStatement traverses:
            // - Statement.Loop, capturing .body and .continuingStatement.statements as compounds.
            // - Statement.Switch, capturing each clause's .compoundStatement as a compound.
            // - Statement.If, capturing .thenBranch, and recursing through .elseBranch -- which is
            //   itself a Statement.If for an `else if`, so the whole chain flattens to siblings.
            //
            // The nested `if (a == 1) { ... }` is NOT a root child: it lives inside .thenBranch which is a Statement.Compound
            // It is reached only when walk() recurses into that scope, so it lands at descend(root, 5, 0). 
        )
    }

    @Test
    fun `a while body is a scope that owns its reads and declarations`() {
        val (root, candidates) = walk(controlFlow)
        val c = candidates.single { lhsBaseIdentifierName(it.target) == "c" }

        assertEquals(descend(root, 0).compound, c.declCompound, "c is declared in the while body")
        assertEquals(
            setOf("a" to 0, "a" to 1, "c" to 1), // `var c = a;` then `a = a + c;`
            readsOf(descend(root, 0)),
            "the while body owns these; only the `a < 10` header read belongs to the enclosing scope",
        )
    }

    @Test
    fun `a loop contributes both its body and its continuing block`() {
        val (root, _) = walk(controlFlow)
        // traverse visits Loop.body before Loop.continuingStatement,
        // Loop.ContinuingStatement.statements is itself a Compound, so the continuing block is a scope in its own right.
        assertEquals(setOf("b" to 0), readsOf(descend(root, 1)), "loop body: `b = b + 1`")
        assertEquals(setOf("b" to 0), readsOf(descend(root, 2)), "continuing block: `b = b + 1`")
    }

    @Test
    fun `switch clause bodies are scopes and their declarations are candidates`() {
        val (root, candidates) = walk(controlFlow)
        val e = candidates.single { lhsBaseIdentifierName(it.target) == "e" }

        assertEquals(descend(root, 3).compound, e.declCompound, "e is declared in the case clause")
        assertEquals(0, e.declIndex)
        assertEquals(setOf("e" to 1), readsOf(descend(root, 3)), "case clause: `b = e`")
        assertEquals(setOf("a" to 0), readsOf(descend(root, 4)), "default clause: `b = a`")
    }

    @Test
    fun `a read in a control flow header belongs to the enclosing scope`() {
        val (root, _) = walk(controlFlow)
        assertEquals(
            setOf(
                "a" to 2, // while (a < 10)
                "b" to 3, // break if (b > 5)  -- see the break-if test below
                "a" to 4, // switch (a)
                "a" to 5, // if (a > 0)
                "b" to 5, // } else if (b > 0) -- the else-if chain flattens, so still index 5
                // `if (a == 1)` is nested INSIDE the if-then branch, so its header read is not here
            ),
            readsOf(root),
            "headers are evaluated in the enclosing scope, at the control-flow statement's index",
        )
        // The rule applies at every depth: the nested if's condition belongs to the scope that
        // contains the nested if -- the outer if-then branch -- at the nested if's own index there.
        assertEquals(
            setOf("a" to 0), // if (a == 1)   -- statement 0 of the if-then branch
            readsOf(descend(root, 5)),
            "the nested if's header read belongs to the branch containing it, not to the root",
        )
        assertEquals(
            emptySet<Pair<String, Int>>(),
            readsOf(descend(root, 5, 0)),
            "`a = 0;` writes a, so the nested then-branch records no read at all",
        )
    }

    /**
     * `else if` is not a nested scope: [Statement.If.elseBranch] is an ElseBranch, which a
     * [Statement.If] also implements, so splitStatement recurses straight through it. The three
     * branch bodies come out as SIBLING children of the enclosing compound, and the else-if
     * condition's read is attributed to the outer `if`'s statement index. That matches WGSL: an
     * else-if condition is evaluated in the enclosing scope, not inside either branch.
     */
    @Test
    fun `an else-if chain flattens into sibling scopes`() {
        val (root, _) = walk(controlFlow)
        assertEquals(setOf("b" to 5), readsOf(root).filter { it.first == "b" && it.second == 5 }.toSet())
        // The three branches are siblings of each other, NOT nested one inside the next -- but a
        // genuinely nested `if` inside a branch still nests, which is what separates "the chain is
        // flat" from "nothing ever nests".
        assertEquals(
            listOf(1, 0, 0),
            (5..7).map { descend(root, it).childrenCompound.size },
            "the if-then branch owns the nested if's then-branch; the else-if and else are leaves",
        )
        assertEquals(
            (descend(root, 5).compound.statements[0] as Statement.If).thenBranch,
            descend(root, 5, 0).compound,
            "that child is the then-branch of the `if (a == 1)` nested inside the outer if-then",
        )
    }

    /**
     * `break if` sits in [ContinuingStatement.breakIfExpr], which traverse visits as a SIBLING of
     * the continuing block rather than inside it, so its reads land on the enclosing compound at the
     * loop's statement index. That is imprecise but safe in the conservative direction: attributing
     * the read outward can only shrink the enclosing scope's injection window, never widen it.
     */
    @Test
    fun `a break-if read is attributed to the scope containing the loop`() {
        val (root, _) = walk(controlFlow)
        assertTrue("b" to 3 in readsOf(root), "the loop is statement 3 of the entry point body")
        assertFalse(
            readsOf(descend(root, 2)).any { it.second > 0 },
            "the continuing block records only its own statements, not the break-if",
        )
    }
}