/*
 * Copyright 2025 The wgsl-fuzz Project Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.wgslfuzz.semanticspreservingtransformations

import com.wgslfuzz.core.Attribute
import com.wgslfuzz.core.GlobalDecl
import com.wgslfuzz.core.ShaderJob
import com.wgslfuzz.core.Statement
import com.wgslfuzz.core.createShaderJob
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Unit tests for [findLocalVariableCandidates], the v2 scope walk that DivergentLocalInjection's
 * `applyV2` depends on. Rather than going through `applyV2` and diffing emitted WGSL, these call
 * the walk directly (it is `internal`, and tests share its package) and assert on the three things
 * it produces for a sample @compute body.
 *
 * The intended semantics being pinned here are:
 *
 *  1. **candidates / declarations** -- one [LocalVariableTarget] per function-scope `var` in any
 *     scope whose type has a scalar leaf, carrying the [Statement.Compound] it was declared in and
 *     its statement index within that compound. A `var` in a `for` header is *not* a candidate: it
 *     is scoped to the loop, not to any compound, so there is no compound index to record.
 *  2. **childrenCompound** -- one child [CompoundInfo] per nested compound reachable from this
 *     scope's statements, in source order. This includes control-flow bodies (`if` then/else
 *     branches, `for`/`while`/`loop` bodies, switch clause bodies), not just bare `{ ... }` blocks.
 *  3. **reads** -- `(name, statementIndex)` for each read of a tracked local appearing in this
 *     scope's *own* statements, excluding anything inside a nested child compound, which owns its
 *     own reads. A read in a control-flow *header* (an `if` condition, a `for` condition) belongs
 *     to the enclosing scope, at the index of the control-flow statement. An assignment *target*
 *     is a write, not a read, so it is not recorded.
 *
 * Point 3 is what [applyV2] consumes: it takes the minimum index over the reads of the chosen
 * target to decide how late the restore statement may be injected.
 *
 * Reads are compared as distinct sets throughout, so that whether `x = x + x` records one entry or
 * two stays an open decision -- `applyV2` only ever takes a minimum over them, so duplicates carry
 * no information either way.
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
     *   0 var a, 1 var s, 2 var v, 3 `a = a + 1`, 4 bare block, 5 if/else, 6 for, 7 `a = a + 1`
     * Child scopes, in source order: bare block, if-then, if-else, for body.
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
          a = a + 1;
          {
            var b: i32 = a;
            b = b + 1;
          }
          if (a > 0) {
            var c: i32 = 2;
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

        assertEquals(root.compound, byName["a"]!!.declCompound, "a is declared in the entry point body")
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
    fun `reads record the name and the index of the reading statement`() {
        val (root, _) = walk(nestedScopes)
        assertEquals(
            setOf(
                "a" to 3, // a = a + 1
                "a" to 5, // if (a > 0)      -- header read, belongs to this scope
                "a" to 6, // for (...; i < a; ...)
                "a" to 7, // a = a + 1
            ),
            readsOf(root),
            "reads inside nested scopes must not leak into the enclosing scope",
        )
    }

    @Test
    fun `each nested scope owns its own reads`() {
        val (root, _) = walk(nestedScopes)
        assertEquals(setOf("a" to 0, "b" to 1), readsOf(descend(root, 0)), "bare block")
        assertEquals(setOf("c" to 1), readsOf(descend(root, 1)), "if-then branch")
        assertEquals(emptySet<Pair<String, Int>>(), readsOf(descend(root, 2)), "if-else branch: `a = 3` writes a")
        assertEquals(setOf("v" to 0, "a" to 0), readsOf(descend(root, 3)), "for body")
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
     * The walk stops at a disqualifying exit ([isDisqualifyingExit]), because statements after it
     * may or may not run. `q` is declared before the `return` and is a candidate; `r` is declared
     * after it and must not be.
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
          }
          p = p + 1;
        }
        """.trimIndent()

    @Test
    fun `the walk stops at a return`() {
        val (_, candidates) = walk(earlyExit)
        assertEquals(
            listOf("p", "q"),
            candidates.map { lhsBaseIdentifierName(it.target) },
            "r is declared after the return, so it must not be a candidate",
        )
    }
}