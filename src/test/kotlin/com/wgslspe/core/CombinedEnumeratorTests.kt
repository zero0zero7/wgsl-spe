package com.wgslspe.core

import com.wgslfuzz.core.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CombinedEnumeratorTests {

    private fun parseAndResolve(src: String): Pair<TranslationUnit, ResolvedEnvironment> {
        val tu = parseFromString(src, LoggingParseErrorListener())
        return tu to resolve(tu)
    }

    // One swappable user call site (a <-> b, identical signature) and one variable usage site with
    // two options (p, q); other usage sites, if collected, have only their identity option.
    private val src = """
        fn a(x: f32) -> f32 {
          return x;
        }
        fn b(x: f32) -> f32 {
          return x;
        }
        fn caller() -> f32 {
          let p: f32 = 1.0;
          let q: f32 = 2.0;
          return a(p);
        }
    """.trimIndent()

    @Test
    fun sequentialEnumerationCoversTheProduct() {
        val (tu, env) = parseAndResolve(src)
        val varVects = getVariableSkeletons(tu, env, random = false).map { it.second }.toList()
        val funcVects = getFunctionSkeletons(tu, env, random = false).map { it.second }.toList()
        val combinedVects = getCombinedSkeletons(tu, env, random = false).map { it.second }.toList()
        assertEquals(varVects.size * funcVects.size, combinedVects.size)
        assertEquals(
            varVects.flatMap { v -> funcVects.map { f -> v + f } }.toSet(),
            combinedVects.toSet(),
            "Combined vectors must be exactly the product of the per-axis vectors",
        )
    }

    @Test
    fun combinedReplacementIsApplied() {
        val (tu, env) = parseAndResolve(src)
        val varVects = getVariableSkeletons(tu, env, random = false).map { it.second }.toList()
        // The p-usage site is the last variable site in traversal order; pick the combination
        // where it is replaced by q, composed with the callee swap a -> b.
        val targetVect = varVects.single { it.last() == "q" } + listOf("b")
        val combined = getCombinedSkeletons(tu, env, random = false)
            .single { it.second == targetVect }
            .first
        val expected = parseFromString(src.replace("a(p)", "b(q)"), LoggingParseErrorListener())
        assertTrue(equalTu(combined, listOf(expected)) == 0, "Combined replacement was misapplied")
    }

    @Test
    fun randomSamplingYieldsDistinctCombinedVectors() {
        val (tu, env) = parseAndResolve(src)
        val vectors = getCombinedSkeletons(tu, env, n = 3, random = true).map { it.second }.toList()
        assertEquals(3, vectors.size)
        assertEquals(3, vectors.toSet().size, "Combined characteristic vectors must be distinct")
    }

    @Test
    fun axisWithoutCandidatesDegradesToTheOther() {
        // No replaceable variable usages (literal argument): combined == function-only.
        val fnOnly = """
            fn f() -> f32 {
              return sin(0.5);
            }
        """.trimIndent()
        val (tu, env) = parseAndResolve(fnOnly)
        val combinedVects = getCombinedSkeletons(tu, env, random = false).map { it.second }.toSet()
        val functionVects = getFunctionSkeletons(tu, env, random = false).map { it.second }.toSet()
        assertTrue(functionVects.isNotEmpty())
        assertEquals(functionVects, combinedVects)
    }

    @Test
    fun editsAgreeWithSkeletons() {
        val (tu, env) = parseAndResolve(src)
        val editVects = getCombinedSkeletonEdits(tu, env, random = false).map { it.second }.toList()
        val skeletonVects = getCombinedSkeletons(tu, env, random = false).map { it.second }.toList()
        assertEquals(skeletonVects, editVects)
    }
}
