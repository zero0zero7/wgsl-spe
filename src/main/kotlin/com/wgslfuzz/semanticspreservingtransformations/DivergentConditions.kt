package com.wgslfuzz.semanticspreservingtransformations

import com.wgslfuzz.core.BinaryOperator
import com.wgslfuzz.core.Expression
import com.wgslfuzz.core.UnaryOperator
import com.wgslfuzz.core.clone

// Boolean guards wrapped around injected perturbation statements.
//
// A perturb/restore pair is only semantics preserving if BOTH guards evaluate the same way for
// every invocation. That is enforced here by construction: ONE template instance yields both
// spellings.

/** `<lid>.x`, a u32. The shared free variable of every template below. */
private fun lidX(context: EntryPointContext): Expression = Expression.MemberLookup(context.lid(), "x")

private fun u(value: Int): Expression = Expression.IntLiteral("${value}u")

private fun not(expr: Expression): Expression =
    Expression.Unary(UnaryOperator.LOGICAL_NOT, Expression.Paren(expr))

private fun binary(
    op: BinaryOperator,
    lhs: Expression,
    rhs: Expression,
): Expression = Expression.Binary(op, lhs, rhs)

/**
 * One template yielding TWO spellings of a single predicate.
 *
 * Contract:
 *  1. Both guards evaluate identically for every invocation -- by construction, from parameters
 *     drawn once when the instance is created, not by a caller happening to pass matching arguments.
 *  2. The two spellings are NOT textually identical, so a compiler cannot cancel the pair by
 *     syntactic matching.
 *  3. Neither guard reads the perturbed target, nor anything a perturbation writes: the restore
 *     guard runs while the target is perturbed and must still agree with the perturb guard.
 *  4. Total and side-effect free: no divide-by-zero, no shift >= 32.
 */
internal interface DivergentConditionTemplate {
    fun perturbGuard(context: EntryPointContext): Expression

    fun restoreGuard(context: EntryPointContext): Expression

    val commentary: String
}

/**
 * `t % Nu == Ru` / `!(t % Nu != Ru)`.
 * The divisor is at least 2, so that the guard is not trivially uniform (always true) for all invocations.
 */
private class NegatedModulus(
    private val n: Int,
    private val r: Int,
) : DivergentConditionTemplate {
    override fun perturbGuard(context: EntryPointContext): Expression =
        binary(BinaryOperator.EQUAL_EQUAL, binary(BinaryOperator.MODULO, lidX(context), u(n)), u(r))

    override fun restoreGuard(context: EntryPointContext): Expression =
        not(binary(BinaryOperator.NOT_EQUAL, binary(BinaryOperator.MODULO, lidX(context), u(n)), u(r)))

    override val commentary: String = "lid.x % $n == $r"
}

/**
 * `t % Nu == Ru` / `(t & (N-1)u) == Ru`, with N a power of two.
 * For unsigned t and N = 2^k, `t % N` is the low k bits of t, which is exactly `t & (N-1)`.
 */
private class ModuloVersusMask(
    private val n: Int,
    private val r: Int,
) : DivergentConditionTemplate {
    override fun perturbGuard(context: EntryPointContext): Expression =
        binary(BinaryOperator.EQUAL_EQUAL, binary(BinaryOperator.MODULO, lidX(context), u(n)), u(r))

    override fun restoreGuard(context: EntryPointContext): Expression =
        binary(
            BinaryOperator.EQUAL_EQUAL,
            Expression.Paren(binary(BinaryOperator.BINARY_AND, lidX(context), Expression.Paren(u(n - 1)))),
            u(r),
        )

    override val commentary: String = "lid.x % $n == $r (mask form on restore)"
}

/**
 * `((t >> Bu) & 1u) == 1u` / `(t & (1u << Bu)) != 0u`.
 * Both test bit B of t. Shift amounts are below 32, so both shifts are fully defined.
 */
private class BitTest(
    private val bit: Int,
) : DivergentConditionTemplate {
    override fun perturbGuard(context: EntryPointContext): Expression =
        binary(
            BinaryOperator.EQUAL_EQUAL,
            Expression.Paren(
                binary(BinaryOperator.BINARY_AND, Expression.Paren(binary(BinaryOperator.SHIFT_RIGHT, lidX(context), u(bit))), u(1)),
            ),
            u(1),
        )

    override fun restoreGuard(context: EntryPointContext): Expression =
        binary(
            BinaryOperator.NOT_EQUAL,
            Expression.Paren(
                binary(BinaryOperator.BINARY_AND, lidX(context), Expression.Paren(binary(BinaryOperator.SHIFT_LEFT, u(1), u(bit)))),
            ),
            u(0),
        )

    override val commentary: String = "bit $bit of lid.x set"
}

/**
 * `t < Ku` / `!(Ku <= t)`.
 */
private class Threshold(
    private val k: Int,
) : DivergentConditionTemplate {
    override fun perturbGuard(context: EntryPointContext): Expression =
        binary(BinaryOperator.LESS_THAN, lidX(context), u(k))

    override fun restoreGuard(context: EntryPointContext): Expression =
        not(binary(BinaryOperator.LESS_THAN_EQUAL, u(k), lidX(context)))

    override val commentary: String = "lid.x < $k"
}

/**
 * `t == <sel>` / `!(<sel> != t)`, where `<sel>` reads the injected `var<storage, read>` selector.
 * No shader code can write that buffer, so both loads yield the same value within an invocation.
 * Only available when the entry point has such a selector (v1/v2, not v0).
 */
private object SelectorEquality : DivergentConditionTemplate {
    override fun perturbGuard(context: EntryPointContext): Expression =
        binary(BinaryOperator.EQUAL_EQUAL, lidX(context), context.opaque()!!)

    override fun restoreGuard(context: EntryPointContext): Expression =
        not(binary(BinaryOperator.NOT_EQUAL, context.opaque()!!, lidX(context)))

    override val commentary: String = "lid.x == thread selector"
}

/**
 * Every divisor of [value] that is greater than 1, ascending.
 *
 * 1 is excluded deliberately: `x % 1u == 0u` holds for every invocation, so a compiler folds it to
 * `true` and the guard stops guarding anything.
 */
internal fun divisorsAboveOne(value: Int): List<Int> {
    if (value < 2) return emptyList()
    val divisors = sortedSetOf<Int>()
    var d = 2
    while (d * d <= value) {
        if (value % d == 0) {
            divisors.add(d)
            val co = value / d
            if (co > 1) divisors.add(co)
        }
        d++
    }
    divisors.add(value) // value >= 2 always divides itself
    return divisors.toList()
}

/**
 * Two DISTINCT divisors of [value], both greater than 1, or null when [value] has fewer than two
 * such divisors.
 */
internal fun twoDistinctDivisors(
    value: Int,
    fuzzerSettings: FuzzerSettings,
): Pair<Int, Int>? {
    val divisors = divisorsAboveOne(value)
    if (divisors.size < 2) return null
    val first = fuzzerSettings.randomElement(divisors)
    val second = fuzzerSettings.randomElement(divisors.filter { it != first })
    return first to second
}

/**
 * `t % D1u == 0u` / `t % D2u == 0u`, where D1 and D2 are DISTINCT divisors of the thread the
 * single-thread gate admits.
 *
 * Harder to fold than the negation-based templates above: the two spellings share no constant and
 * neither is a syntactic transform of the other.
 */
private class DivisorPair(
    private val perturbDivisor: Int,
    private val restoreDivisor: Int,
) : DivergentConditionTemplate {
    override fun perturbGuard(context: EntryPointContext): Expression =
        binary(BinaryOperator.EQUAL_EQUAL, binary(BinaryOperator.MODULO, lidX(context), u(perturbDivisor)), u(0))

    override fun restoreGuard(context: EntryPointContext): Expression =
        binary(BinaryOperator.EQUAL_EQUAL, binary(BinaryOperator.MODULO, lidX(context), u(restoreDivisor)), u(0))

    override val commentary: String = "lid.x % $perturbDivisor == 0 / lid.x % $restoreDivisor == 0"
}

/**
 * Picks a condition template. Never null: [NegatedModulus] applies unconditionally.
 */
internal fun chooseConditionTemplate(
    fuzzerSettings: FuzzerSettings,
    context: EntryPointContext,
): DivergentConditionTemplate {
    val weights = fuzzerSettings.divergentConditionWeights
    val choices: List<Pair<Int, () -> DivergentConditionTemplate>> =
        listOfNotNull(
            weights.negatedModulus to
                {
                    val n = fuzzerSettings.randomInt(2, 9)
                    NegatedModulus(n, fuzzerSettings.randomInt(0, n)) // [0, n) is the valid range of remainders
                },
            weights.moduloVersusMask to
                {
                    val n = 1 shl fuzzerSettings.randomInt(1, 5) // 2, 4, 8 or 16
                    ModuloVersusMask(n, fuzzerSettings.randomInt(0, n))
                },
            weights.bitTest to { BitTest(fuzzerSettings.randomInt(0, 8)) },
            weights.threshold to { Threshold(fuzzerSettings.randomInt(1, 65)) },
            // v0 injects no input buffer, so it has no runtime-opaque selector to compare against.
            if (context.opaque() != null) weights.selectorEquality to { SelectorEquality } else null,
            if (context.opaque() != null && divisorsAboveOne(fuzzerSettings.threadToRun()).size >= 2) {
                weights.divisorPair to
                    {
                        val (perturbDivisor, restoreDivisor) =
                            twoDistinctDivisors(fuzzerSettings.threadToRun(), fuzzerSettings)!!
                        DivisorPair(perturbDivisor, restoreDivisor)
                    }
            } else {
                null
            },
        ).filter { it.first > 0 }
    return choose(fuzzerSettings, choices)
}

/**
 * Selects a single thread to run, with all others taking an early return.
 *
 * [threadSelector] reads the value from the injected input buffer (`thread_to_run.data`) rather
 * than being a literal, so the comparison cannot be constant-folded away at compile time.
 */
internal fun singleThreadCondition(
    lidExpr: Expression,
    threadSelector: Expression,
    equals: Boolean,
): Expression =
    Expression.Binary(
        operator = if (equals) BinaryOperator.EQUAL_EQUAL else BinaryOperator.NOT_EQUAL,
        lhs = Expression.MemberLookup(lidExpr, "x"),
        rhs = threadSelector.clone(),
    )
