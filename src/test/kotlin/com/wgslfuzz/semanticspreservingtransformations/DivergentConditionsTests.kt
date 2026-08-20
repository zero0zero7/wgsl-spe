package com.wgslfuzz.semanticspreservingtransformations

import com.wgslfuzz.core.AstWriter
import com.wgslfuzz.core.BinaryOperator
import com.wgslfuzz.core.Expression
import com.wgslfuzz.core.UnaryOperator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.Random

/**
 * Unit tests for [DivergentConditionTemplate] -- the guards wrapped around an injected
 * perturb/restore pair (see DivergentConditions.kt).
 *
 * The invariants being pinned here:
 *
 *  1. **fires** BOTH guards evaluate to TRUE at `lid.x == threadToRun()`. A guard that is false
 *     there makes the whole pair dead: nothing is perturbed, so nothing is tested. Under v3 the
 *     only invocation that exists is lid.x == 0, so a guard that cannot hold at 0 never fires at all.
 *  2. **agree** Both spellings evaluate the SAME at that thread -- covered by (1), since both are
 *     asserted true rather than merely asserted equal.
 *  3. **distinct** The two spellings are not textually identical, so a compiler cannot cancel the
 *     pair by syntactic matching.
 */
class DivergentConditionsTests {
    // ---------- helpers ----------

    private val lidName = "lid"
    private val selectorName = "thread_to_run"

    /** Used to force exactly 1 condition template for a given test. */
    private fun conditionWeights(
        negatedModulus: Int = 0,
        moduloVersusMask: Int = 0,
        bitTest: Int = 0,
        threshold: Int = 0,
        selectorEquality: Int = 0,
        divisorPair: Int = 0,
    ) = FuzzerSettings.DivergentConditionWeights(
        negatedModulus = negatedModulus,
        moduloVersusMask = moduloVersusMask,
        bitTest = bitTest,
        threshold = threshold,
        selectorEquality = selectorEquality,
        divisorPair = divisorPair,
    )

    /** Overrides [DefaultFuzzerSettings]' condition weights and the thread the gate admits. */
    private fun settings(
        weights: FuzzerSettings.DivergentConditionWeights,
        threadToRun: Int,
        seed: Long,
    ): FuzzerSettings {
        val base = DefaultFuzzerSettings(Random(seed))
        return object : FuzzerSettings by base {
            override val divergentConditionWeights = weights

            override fun threadToRun(): Int = threadToRun
        }
    }

    /** A v1/v2/v3-shaped context: a lid, plus the injected buffer's runtime-opaque selector. */
    private fun gatedContext(): EntryPointContext =
        EntryPointContext(
            lidExpr = Expression.Identifier(lidName),
            parameters = emptyList(),
            opaqueThreadExpr = { Expression.MemberLookup(Expression.Identifier(selectorName), "data") },
        )

    /** A v0-shaped context: a lid, but no injected input buffer and so no selector. */
    private fun bareContext(): EntryPointContext =
        EntryPointContext(
            lidExpr = Expression.Identifier(lidName),
            parameters = emptyList(),
        )

    /**
     * Evaluates a guard for one invocation. Covers exactly the expression subset the templates
     * build; anything else is a test bug rather than a silent pass, so it throws.
     *
     * Returns a [Long] for the arithmetic subexpressions and a [Boolean] for the comparisons.
     * Values are non-negative u32s, so [Long] arithmetic never disagrees with WGSL's.
     */
    private fun eval(
        expr: Expression,
        lidX: Long,
        selector: Long,
    ): Any =
        when (expr) {
            is Expression.IntLiteral -> expr.text.trimEnd('u', 'i').toLong()
            is Expression.Paren -> eval(expr.target, lidX, selector)
            is Expression.MemberLookup ->
                when (val receiver = (expr.receiver as? Expression.Identifier)?.name) {
                    lidName -> lidX
                    selectorName -> selector
                    else -> error("unexpected member lookup receiver: $receiver")
                }
            is Expression.Unary -> {
                check(expr.operator == UnaryOperator.LOGICAL_NOT) { "unexpected unary ${expr.operator}" }
                !(eval(expr.target, lidX, selector) as Boolean)
            }
            is Expression.Binary -> {
                val lhs = eval(expr.lhs, lidX, selector)
                val rhs = eval(expr.rhs, lidX, selector)
                when (expr.operator) {
                    BinaryOperator.MODULO -> (lhs as Long) % (rhs as Long)
                    BinaryOperator.BINARY_AND -> (lhs as Long) and (rhs as Long)
                    BinaryOperator.BINARY_OR -> (lhs as Long) or (rhs as Long)
                    BinaryOperator.SHIFT_LEFT -> (lhs as Long) shl (rhs as Long).toInt()
                    BinaryOperator.SHIFT_RIGHT -> (lhs as Long) shr (rhs as Long).toInt()
                    BinaryOperator.LESS_THAN -> (lhs as Long) < (rhs as Long)
                    BinaryOperator.LESS_THAN_EQUAL -> (lhs as Long) <= (rhs as Long)
                    BinaryOperator.EQUAL_EQUAL -> lhs == rhs
                    BinaryOperator.NOT_EQUAL -> lhs != rhs
                    else -> error("unexpected binary ${expr.operator}")
                }
            }
            else -> error("unexpected expression ${expr::class.simpleName}")
        }

    private fun render(expr: Expression): String {
        val bytes = ByteArrayOutputStream()
        PrintStream(bytes).use { AstWriter(out = it).emit(expr) }
        return bytes.toString()
    }

    /**
     * Draws [seeds] templates under the given weights and asserts both guards fire at [threadToRun].
     *
     * Many seeds, because the constants inside a template are drawn randomly: one seed only exercises
     * one remainder / bit / threshold.
     */
    private fun assertFiresAtSelectedThread(
        weights: FuzzerSettings.DivergentConditionWeights,
        threadToRun: Int,
        context: EntryPointContext = gatedContext(),
        seeds: LongRange = 1L..200L,
    ) {
        val thread = threadToRun.toLong()
        for (seed in seeds) {
            val template = chooseConditionTemplate(settings(weights, threadToRun, seed), context)
            val where = "${template.commentary} (threadToRun=$threadToRun, seed=$seed)"
            assertEquals(true, eval(template.perturbGuard(context), thread, thread), "perturb guard false: $where")
            assertEquals(true, eval(template.restoreGuard(context), thread, thread), "restore guard false: $where")
        }
    }

    /** Threads a gate may admit: zero (v3's only invocation), odd, prime, and past Threshold's old ceiling. */
    private val threads = listOf(0, 1, 13, 64, 255)

    /** Threads for which [DivisorPair] is available: 0, plus values with two or more divisors above 1. */
    private val compositeThreads = listOf(0, 12, 60)

    // ---------- every template fires at the selected thread ----------

    @Test
    fun `negated modulus fires at the selected thread`() {
        threads.forEach { assertFiresAtSelectedThread(conditionWeights(negatedModulus = 1), it) }
    }

    @Test
    fun `modulo versus mask fires at the selected thread`() {
        threads.forEach { assertFiresAtSelectedThread(conditionWeights(moduloVersusMask = 1), it) }
    }

    @Test
    fun `bit test fires at the selected thread`() {
        threads.forEach { assertFiresAtSelectedThread(conditionWeights(bitTest = 1), it) }
    }

    @Test
    fun `threshold fires at the selected thread`() {
        threads.forEach { assertFiresAtSelectedThread(conditionWeights(threshold = 1), it) }
    }

    @Test
    fun `selector equality fires at the selected thread`() {
        threads.forEach { assertFiresAtSelectedThread(conditionWeights(selectorEquality = 1), it) }
    }

    @Test
    fun `divisor pair fires at the selected thread`() {
        compositeThreads.forEach { assertFiresAtSelectedThread(conditionWeights(divisorPair = 1), it) }
    }

    @Test
    fun `templates available without an input buffer fire at the selected thread`() {
        val v0Weights = conditionWeights(negatedModulus = 1, moduloVersusMask = 1, bitTest = 1, threshold = 1)
        threads.forEach { assertFiresAtSelectedThread(v0Weights, it, context = bareContext()) }
    }

    // ---------- the two spellings stay textually distinct ----------

    @Test
    fun `the two spellings of a guard are never textually identical`() {
        val context = gatedContext()
        val allWeights =
            conditionWeights(
                negatedModulus = 1,
                moduloVersusMask = 1,
                bitTest = 1,
                threshold = 1,
                selectorEquality = 1,
                divisorPair = 1,
            )
        for (threadToRun in compositeThreads) {
            for (seed in 1L..200L) {
                val template = chooseConditionTemplate(settings(allWeights, threadToRun, seed), context)
                assertNotEquals(
                    render(template.perturbGuard(context)),
                    render(template.restoreGuard(context)),
                    "spellings identical: ${template.commentary} (threadToRun=$threadToRun, seed=$seed)",
                )
            }
        }
    }

    // ---------- divisorsAboveOne ----------

    @Test
    fun `every integer above one divides zero, so zero offers a pool of divisors`() {
        val divisors = divisorsAboveOne(0)
        assertTrue(divisors.size >= 2, "zero must offer at least two divisors, got $divisors")
        assertTrue(divisors.all { it >= 2 }, "every divisor must be above one, got $divisors")
    }

    @Test
    fun `one has no divisor above itself`() {
        assertEquals(emptyList<Int>(), divisorsAboveOne(1))
    }
}
