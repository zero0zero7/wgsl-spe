package com.wgslfuzz.semanticspreservingtransformations

import com.wgslfuzz.core.BinaryOperator
import com.wgslfuzz.core.Expression

// Boolean guards wrapped around an injected workgroupBarrier() call (see UniformInjection.kt).
// Unlike DivergentConditionTemplate in DivergentConditions.kt, a guard here needs only ONE `if (uniform-condition-across-workgroup) {workgroupBarrier()}`


/** `<uniformExpr>.x`, a u32. The shared free variable of every template below. */
private fun uniformX(uniformExpr: Expression): Expression = Expression.MemberLookup(uniformExpr, "x")

private fun u(value: Int): Expression = Expression.IntLiteral("${value}u")

private fun binary(
    op: BinaryOperator,
    lhs: Expression,
    rhs: Expression,
): Expression = Expression.Binary(op, lhs, rhs)

/**
 * One workgroup-uniform guard expression
 */
internal interface UniformConditionTemplate {
    fun guard(uniformExpr: Expression): Expression

    val commentary: String
}

/**
 * `<uniform>.x % Nu == Ru`
 * Regardless of whther <uniform> is workgroup_id or num_workgroups (value determined by JS harness), 
 * this template is guaranteed to be workgroup-uniform.
 */
internal class UniformModulus(
    private val n: Int,
    private val r: Int,
) : UniformConditionTemplate {
    override fun guard(uniformExpr: Expression): Expression =
        binary(BinaryOperator.EQUAL_EQUAL, binary(BinaryOperator.MODULO, uniformX(uniformExpr), u(n)), u(r))

    override val commentary: String = "<uniform builtin>.x % $n == $r"
}

/**
 * `<uniform>.x < Ku`.
 */
internal class UniformThreshold(
    private val k: Int,
) : UniformConditionTemplate {
    override fun guard(uniformExpr: Expression): Expression = binary(BinaryOperator.LESS_THAN, uniformX(uniformExpr), u(k))

    override val commentary: String = "<uniform builtin>.x < $k"
}

/**
 * `((<uniform>.x >> Bu) & 1u) == Eu`, testing bit B of the uniform's x component.
 */
internal class UniformBitTest(
    private val bit: Int,
    private val expected: Int,
) : UniformConditionTemplate {
    override fun guard(uniformExpr: Expression): Expression =
        binary(
            BinaryOperator.EQUAL_EQUAL,
            Expression.Paren(
                binary(BinaryOperator.BINARY_AND, Expression.Paren(binary(BinaryOperator.SHIFT_RIGHT, uniformX(uniformExpr), u(bit))), u(1)),
            ),
            u(expected),
        )

    override val commentary: String = "bit $bit of <uniform builtin>.x ${if (expected == 1) "set" else "clear"}"
}

/**
 * Picks a guard template, evenly weighted across the three templates above.
 */
internal fun chooseUniformConditionTemplate(fuzzerSettings: FuzzerSettings): UniformConditionTemplate =
    choose(
        fuzzerSettings,
        listOf(
            1 to {
                val n = fuzzerSettings.randomInt(2, 9)
                UniformModulus(n, fuzzerSettings.randomInt(0, n))
            },
            1 to { UniformThreshold(fuzzerSettings.randomInt(1, 1025)) },
            1 to {
                val bit = fuzzerSettings.randomInt(0, 8)
                UniformBitTest(bit, fuzzerSettings.randomInt(0, 2))
            },
        ),
    )
