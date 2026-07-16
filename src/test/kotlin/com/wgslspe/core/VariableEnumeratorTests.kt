package com.wgslspe.core

import com.wgslfuzz.core.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.collections.get
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

private fun emitToText(tu: TranslationUnit): String = ByteArrayOutputStream().also { AstWriter(PrintStream(it)).emit(tu) }.toString()

// Compares emitted text rather than deepEquals (JSON) so that SourceSpan metadata — which differs
// between a rewritten tree and a re-parsed expected program — does not affect equality.
fun equalTu(skeleton: TranslationUnit, accept: List<TranslationUnit>) : Int {
    val skeletonText = emitToText(skeleton)
    for ((idx, tu) in accept.withIndex()) {
        if (skeletonText == emitToText(tu)) {
            return idx
        }
    }
    return -1
}

class VariableEnumeratorTests {

    // -------------------------------------------------------------------------
    // collectSkeletalCandidates — literals excluded
    // -------------------------------------------------------------------------

    @Test
    fun literalOnlyProgramHasNoCandidates() {
        // The return expression is a literal — nothing to replace.
        val src = """
            fn f() -> i32 {
              return 1i;
            }
        """.trimIndent()
        val tu = parseFromString(src, LoggingParseErrorListener())
        val env = resolve(tu)
        val (decls, usages) = collectSkeletalCandidates(tu, env)
        assertTrue(usages.isEmpty(), "Expected no candidates but got ${usages.size}")
    }

    // -------------------------------------------------------------------------
    // collectSkeletalCandidates — compound expression found
    // -------------------------------------------------------------------------

    @Test
    fun literalOperandIsNotCandidate() {
        // `a + 1i` has one binary expression candidate; the literal `1i` is excluded.
        // `a` is an Identifier with i32 type -> included.
        val src = """
            fn f(a: i32) -> i32 {
              return a + 1i;
            }
        """.trimIndent()
        val tu = parseFromString(src, LoggingParseErrorListener())
        val env = resolve(tu)
        val (decls, usages) = collectSkeletalCandidates(tu, env)
        val kinds = usages.map { it.identifier::class.simpleName }
        assertEquals(kinds.size, 1, "Expected to find only 1 candidate")
        assertTrue(kinds.contains("Identifier"), "Expected Identifier among candidates: $kinds")
        assertTrue(!kinds.contains("IntLiteral"), "IntLiteral should be excluded: $kinds")
    }

    // -------------------------------------------------------------------------
    // collectSkeletalCandidates — scope is captured per expression
    // -------------------------------------------------------------------------

    @Test
    fun lhsRhsAreCandidates() {
        // The initializer `a + 1i` of `var x` must NOT see `x` in scope (x isn't declared yet).
        val src = """
            fn f(a: i32) -> i32 {
              var x : i32 = a + 1i;
              return x;
            }
        """.trimIndent()
        val tu = parseFromString(src, LoggingParseErrorListener())
        val env = resolve(tu)
        val (decls, usages) = collectSkeletalCandidates(tu, env)
        val nodes = usages.map { it.identifier }
        assertEquals(2, nodes.size, "Expected exactly 3 candidates but got ${nodes.size}: $nodes")
        assertTrue(
            nodes.any { it is Expression.Identifier && it.name == "a" },
            "Expected Expression.Identifier(name='a') among candidates"
        )
        assertTrue(
            nodes.any { it is Expression.Identifier && it.name == "x" },
            "Expected Expression.Identifier(name='x') among candidates"
        )
    }

    // -------------------------------------------------------------------------
    // singleReplacementSkeletons — constants cant replace an re-assignment
    // -------------------------------------------------------------------------

    @Test
    fun cannotOverride() {
        val src = """
            const a = 1;
            override b = 2;
            fn f(p: i32) -> bool {
              let c = 3;
              var d : i32;
              d = a;
              var e: i32 = a;
            }
        """.trimIndent()
        val tu = parseFromString(src, LoggingParseErrorListener())
        println(tu)
        val env = resolve(tu)
        val skeletons = getVariableSkeletons(tu, env, random=false).toList()
        val (decls, usages) = collectSkeletalCandidates(tu, env)


        for ((skeleton, charVect) in skeletons) {
            val assign : String = (((skeleton.globalDecls[2]  as GlobalDecl.Function).body.statements[2] as Statement.Assignment).lhsExpression as LhsExpression.Identifier).name
            assertEquals("d", assign , "expect 'd' to be the only lhs")
        }
    }

    // -------------------------------------------------------------------------
    // singleReplacementSkeletons — no matching variable → no skeleton
    // -------------------------------------------------------------------------

    @Test
    fun noUsageYieldsNoSkeletons() {
        val src = """
            fn f() -> bool {
              const a = true;
              const b = true;
              return (1i == 2i);
            }
        """.trimIndent()
        val tu = parseFromString(src, LoggingParseErrorListener())
        val env = resolve(tu)
        val skeletons = getVariableSkeletons(tu, env, random=false).toList()
        assertTrue(skeletons.isEmpty(), "Expected no skeletons when there is no variable usage (only declarations)")
    }

    // -------------------------------------------------------------------------
    // singleReplacementSkeletons — one variable per type → one skeleton per candidate
    // -------------------------------------------------------------------------

    @Test
    fun noMatchingTypeYieldsNoSkeletons() {
        val src = """
            fn f(a: i32) -> i32 {
              const b = 1u;
              return (a + 1i);
            }
        """.trimIndent()
        val tu = parseFromString(src, LoggingParseErrorListener())
        val env = resolve(tu)
        val candidates = collectSkeletalCandidates(tu, env)
        val skeletons = getVariableSkeletons(tu, env, random=false).toList()
        assertEquals(1, skeletons.size, "Expected only the original source Tu")
        assertTrue { equalTu(skeletons[0].first, listOf(tu)) == 0 }
    }

    // -------------------------------------------------------------------------
    // singleReplacementSkeletons — each skeleton is a valid parseable program
    // -------------------------------------------------------------------------

    @Test
    fun skeletonsRoundtripThroughParser() {
        val src = """
            fn f(a: f32, b: f32) -> f32 {
              var x : f32 = a * b;
              return x + a;
            }
        """.trimIndent()
        val tu = parseFromString(src, LoggingParseErrorListener())
        val env = resolve(tu)
        for ((skeleton, charVect) in getVariableSkeletons(tu, env, random=false)) {
            val baos = ByteArrayOutputStream()
            AstWriter(out = PrintStream(baos)).emit(skeleton)
            val text = baos.toString()
            val errorListener = LoggingParseErrorListener()
            parseFromString(text, errorListener)
            assertTrue(errorListener.loggedMessages.isEmpty(), "Skeleton failed to reparse:\n$text\nErrors: ${errorListener.loggedMessages}")
        }
    }

    // -------------------------------------------------------------------------
    // singleReplacementSkeletons — replacement uses correct variable name
    // -------------------------------------------------------------------------

    @Test
    fun cannotReplaceWithAlreadyInScope() {
        // Variable declaration may not introduce an identifier that is already declared in the same or enclosing scope within the function
        val src = """
            fn f(a: i32) {
              var x : f16 = 0.2;
              var y : i32;
            }
        """.trimIndent()
        val tu = parseFromString(src, LoggingParseErrorListener())
        val env = resolve(tu)
        val skeletons = getVariableSkeletons(tu, env, random=false).toList()
        assertEquals(0, skeletons.size)
    }

    fun notInScopeUntilAfterDeclaration() {
        // var x = x + 1; is invalid
        val src = """
            fn f(a: i32) -> Unit {
              var x = a + 1;
            }
        """.trimIndent()
        val tu = parseFromString(src, LoggingParseErrorListener())
        val env = resolve(tu)
        val skeletons = getVariableSkeletons(tu, env, random=false).toList()
        assertEquals(0, skeletons.size)
    }

    @Test
    fun replacementUsesVariableFromScope() {
        // With only `a` in scope, every replacement must be `a`.
        val src = """
            fn f(a: i32) -> i32 {
              var x : i32 = a + 1i;
              return x;
            }
        """.trimIndent()
        val tu = parseFromString(src, LoggingParseErrorListener())
        val env = resolve(tu)
        val skeletons = getVariableSkeletons(tu, env, random=false).toList()
        // Replacement -- return a;
        val identifierExpression =
            ((tu.globalDecls[0] as GlobalDecl.Function).body.statements[1] as Statement.Return).expression
                    as Expression.Identifier
        val replacement =
            mapOf(
                identifierExpression to Expression.Identifier("a"),
            )
        val expect_tu = tu.clone({ replacement[it] })

        var accept = listOf(tu, expect_tu)
        val contains = MutableList(accept.size) {-1}
        for ((skeleton, charVect) in skeletons) {
            var corr = equalTu(skeleton, accept)
            assertFalse{corr == -1}
            contains[corr] = corr
        }
        assertTrue{contains == (0 until accept.size).toList()}
    }

    @Test
    fun replacementCapturesGlobalScope() {
        // With only `a` in scope, every replacement must be `a`.
        val src = """
            const global_constant : i32 = 1;
            fn f(a: i32) -> i32 {
              var x : i32 = a + 1i;
              return x;
            }
        """.trimIndent()
        val tu = parseFromString(src, LoggingParseErrorListener())
        val env = resolve(tu)
        val skeletons = getVariableSkeletons(tu, env, random=false).toList()
        // Replacements
        // -- global_constant + 1;
        // -- return global_constant;
        // -- return a;
        val binaryLhs =
            (((tu.globalDecls[1] as GlobalDecl.Function).body.statements[0] as Statement.Variable).initializer as Expression.Binary).lhs
                    as Expression.Identifier
        val returnExpr =
            ((tu.globalDecls[1] as GlobalDecl.Function).body.statements[1] as Statement.Return).expression
                    as Expression.Identifier

        val replacements = listOf(
                binaryLhs to Expression.Identifier("global_constant"),
                returnExpr to Expression.Identifier("a"),
                returnExpr to Expression.Identifier("global_constant")
                )
        val expect1 = tu.clone({mapOf(replacements[0])[it]})
        val expect2 = tu.clone({mapOf(replacements[1])[it]})
        val expect3 = tu.clone({mapOf(replacements[2])[it]})
        val expect4 = tu.clone({mapOf(replacements[0], replacements[1])[it]})
        val expect5 = tu.clone({mapOf(replacements[0], replacements[2])[it]})

        var accept = listOf(tu, expect1, expect2, expect3, expect4, expect5)
        val contains = MutableList(accept.size) {-1}
        for ((skeleton, charVect) in skeletons) {
            var corr = equalTu(skeleton, accept)
            assertFalse{corr == -1}
            contains[corr] = corr
        }
        assertTrue{contains == (0 until accept.size).toList()}
    }

    @Test
    fun replacementCapturesAccessMode() {
        // With only `a` in scope, every replacement must be `a`.
        val src = """
            @group(0) @binding(0) var<storage, read_write> result: vec2<f32>;
            @group(0) @binding(1) var<storage, read_write> a: vec2<f32>;
            @group(0) @binding(2) var<storage, read> b: vec2<f32>;
            fn f() {
              result = a + b;
            }
        """.trimIndent()
        val tu = parseFromString(src, LoggingParseErrorListener())
        val env = resolve(tu)
        val skeletons = getVariableSkeletons(tu, env, random=false).toList()
        // Replacements
        // Lhs of Assignment can only be "result" or "a", not "b"
        for ((skeleton, charVect) in skeletons) {
            assertNotEquals("b", ((((skeleton.globalDecls[3] as GlobalDecl.Function).body.statements[0] as Statement.Assignment).lhsExpression) as LhsExpression.Identifier).name)
        }
    }

    // -------------------------------------------------------------------------
    // singleReplacementSkeletons — vec type: identifier replacement
    // -------------------------------------------------------------------------

    @Test
    fun vectorExpressionReplacedByVecConstructor() {
        val src = """
            fn f(a: vec2<f32>, b: vec2<f32>) -> vec2<f32> {
              return a + b;
            }
        """.trimIndent()
        val tu = parseFromString(src, LoggingParseErrorListener())
        val env = resolve(tu)
        val skeletons = getVariableSkeletons(tu, env, random=false).toList()
        assertTrue(skeletons.isNotEmpty())

        // Replacements
        // -- a + a;
        // -- b + b;
        // -- b + a;
        val returnExpr =
            ((tu.globalDecls[0] as GlobalDecl.Function).body.statements[0] as Statement.Return).expression
                    as Expression.Binary

        val replacements = listOf(
            returnExpr.lhs to Expression.Identifier("b"),
            returnExpr.rhs to Expression.Identifier("a"),
        )
        val expect1 = tu.clone({mapOf(replacements[0])[it]})
        val expect2 = tu.clone({mapOf(replacements[1])[it]})
        val expect3 = tu.clone({mapOf(replacements[0], replacements[1])[it]})

        var accept = listOf(tu, expect1, expect2, expect3)
        val contains = MutableList(accept.size) {-1}
        for ((skeleton, charVect) in skeletons) {
            var corr = equalTu(skeleton, accept)
            assertFalse{corr == -1}
            contains[corr] = corr
        }
        assertTrue{contains == (0 until accept.size).toList()}
        assertNotEquals(-1, equalTu(getVariableSkeletons(tu, env, n=1, random=true).toList()[0].first, accept), "random skeleton is not accepted" )
    }

    @Test
    fun test1() {
        val src = """
//            const GLOBAL_0 = 0i;
//            @compute
//            @workgroup_size(1)
//            fn computeMain() {
//                let c : i32 = 1;
//                let d : i32 = 2 + GLOBAL_0;
//                var e : i32 = d;
//            }
            fn computeMain() {
              var a : i32 = 1;
              var b : i32 = 2;
              if (a == 1) {
                var c: i32 = 3;
                var d: i32 = 5;
                b = c + d;
              }
              var x = 1 + a;
              var y = 1 + b;
            }
        """.trimIndent()
        val tu = parseFromString(src, LoggingParseErrorListener())
        val env = resolve(tu)
        val skeletons = getVariableSkeletons(tu, env, random=false).toList()
    }

}