package com.wgslfuzz.semanticspreservingtransformations

import com.wgslfuzz.core.BinaryOperator
import com.wgslfuzz.core.Expression
import com.wgslfuzz.core.LhsExpression
import com.wgslfuzz.core.Type

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
    type: Type.Scalar,
): Perturbation? {
    val weights = fuzzerSettings.divergentPerturbationWeights
    val choices: List<Pair<Int, () -> Perturbation>> =
        listOfNotNull(
            if (type == Type.I32 || type == Type.U32) {
                weights.addSub to { AddSub(fuzzerSettings.randomInt(1, 1001), type) }
            } else {
                null
            },
        ).filter { it.first > 0 }
    return if (choices.isEmpty()) null else choose(fuzzerSettings, choices)
}
