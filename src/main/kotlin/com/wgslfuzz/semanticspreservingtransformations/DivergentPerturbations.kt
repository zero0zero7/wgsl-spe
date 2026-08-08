package com.wgslfuzz.semanticspreservingtransformations

import com.wgslfuzz.core.BinaryOperator
import com.wgslfuzz.core.Expression
import com.wgslfuzz.core.LhsExpression
import com.wgslfuzz.core.Type
import com.wgslfuzz.core.UnaryOperator

// How an injected pair modifies its target, and how it puts it back.
//
// Every template here is TOTAL: restore(perturb(v)) == v for every value of the target's type,
// with no value-range side condition. Integer wrapping (mod 2^32) may be relied on, since WGSL
// defines it; rounding and saturation may not.

/**
 * A perturbation paired with its exact algebraic inverse.
 *
 * Contract, for EVERY value v of the target's type:
 *  1. restore(perturb(v)) == v exactly.
 *  2. Both are bijections -- perturb(restore(v)) == v -- the injector is
 *     free to emit restore at the earlier index.
 *  3. Side-effect free, and reads nothing but the target itself.
 */
internal interface Perturbation {
    fun perturb(target: LhsExpression): Expression

    fun restore(target: LhsExpression): Expression

    val commentary: String
}

/**
 * A suffixed literal for [type]. 
 * 
 * Errors if [type] is not i32, u32, f32, or f16
 */
internal fun scalarLiteral(
    value: Int,
    type: Type.Scalar,
): Expression =
    when (type) {
        Type.I32 -> Expression.IntLiteral("${value}i")
        Type.U32 -> Expression.IntLiteral("${value}u")
        Type.F32 -> Expression.FloatLiteral("$value.0f")
        Type.F16 -> Expression.FloatLiteral("$value.0h")
        Type.Bool, Type.AbstractInteger, Type.AbstractFloat ->
            throw IllegalArgumentException("no perturbation operand for $type")
    }

/**
 * `x + D` / `x - D`.
 *
 * WGSL defines wraparound in 2s complement `(x + d) - d == x` mod 2^32, 
 * thus no bound on the operand required.
 */
private class AddSub(
    private val delta: Int,
    private val type: Type.Scalar,
) : Perturbation {
    override fun perturb(target: LhsExpression): Expression =
        Expression.Binary(BinaryOperator.PLUS, lhsExprToExpr(target), scalarLiteral(delta, type))

    override fun restore(target: LhsExpression): Expression =
        Expression.Binary(BinaryOperator.MINUS, lhsExprToExpr(target), scalarLiteral(delta, type))

    override val commentary: String = "+$delta then -$delta"
}

/**
 * `-x` / `-x`.
 *
 * Exact for every value, including i32's INT_MIN: `-INT_MIN` wraps back to INT_MIN, so negating
 * twice is still the identity. 
 * For floats it flips the sign bit and touches nothing else, so it is
 * exact for normals, subnormals, +/-0 and +/-inf alike.
 *
 * WGSL has no `operator - (u32)`, so u32 negates by subtracting from a hidden zero instead --
 * two's complement negation, `z - (z - x) == x` mod 2^32 for any z.
 *
 * [distinctSpellings] emits the two halves differently where the type allows it, so the pair is
 * not a syntactic copy of itself. Off by default: `-x` / `-x` is the plain form.
 */
private class Negate(
    private val type: Type.Scalar,
    private val hiddenZero: (() -> Expression)?,
    private val distinctSpellings: Boolean,
) : Perturbation {
    private fun negate(target: LhsExpression): Expression =
        if (type == Type.U32) {
            // u32 has no unary minus; subtract from the hidden zero.
            Expression.Binary(BinaryOperator.MINUS, hiddenZero!!(), lhsExprToExpr(target))
        } else {
            Expression.Unary(UnaryOperator.MINUS, lhsExprToExpr(target))
        }

    override fun perturb(target: LhsExpression): Expression = negate(target)

    override fun restore(target: LhsExpression): Expression =
        if (distinctSpellings && type != Type.U32 && hiddenZero != null) {
            // Same operation, different spelling: `zero - x` rather than `-x`.
            Expression.Binary(BinaryOperator.MINUS, hiddenZero(), lhsExprToExpr(target))
        } else {
            negate(target)
        }

    override val commentary: String = if (distinctSpellings) "negate (distinct spellings)" else "negate"
}

/**
 * Picks a perturbation applicable to [type], or null when nothing applies -- the caller must then
 * skip the site.
 *
 * Only i32 and u32 are supported while the float and bitwise templates are deferred, so v2 fires
 * only on integer locals. Note the explicit `Type.I32 || Type.U32` rather than `is Type.Integer`:
 * the latter also admits AbstractInteger, which firstScalarLeaf's Matrix branch can still let
 * through.
 * TODO
 *
 * Unlike every other `choose` call site in this package, the candidate list here can legitimately
 * be empty, so `choose` must not be called blindly.
 */
internal fun choosePerturbation(
    fuzzerSettings: FuzzerSettings,
    context: EntryPointContext,
    type: Type.Scalar,
): Perturbation? {
    val weights = fuzzerSettings.divergentPerturbationWeights
    // Non-null only when this entry point has an injected input buffer (v1/v2, not v0).
    val hiddenZero: (() -> Expression)? =
        if (context.hidden("zero", type) != null) {
            { context.hidden("zero", type)!! }
        } else {
            null
        }
    val choices: List<Pair<Int, () -> Perturbation>> =
        listOfNotNull(
            if (type == Type.I32 || type == Type.U32) {
                weights.addSub to { AddSub(fuzzerSettings.randomInt(1, 1001), type) }
            } else {
                null
            },
            // u32 negation needs the hidden zero; i32/f32 can negate without it.
            if ((type == Type.I32 || type == Type.F32) || (type == Type.U32 && hiddenZero != null)) {
                weights.negate to
                    {
                        Negate(
                            type = type,
                            hiddenZero = hiddenZero,
                            distinctSpellings = fuzzerSettings.negateWithDistinctSpellings(),
                        )
                    }
            } else {
                null
            },
        ).filter { it.first > 0 }
    return if (choices.isEmpty()) null else choose(fuzzerSettings, choices)
}
