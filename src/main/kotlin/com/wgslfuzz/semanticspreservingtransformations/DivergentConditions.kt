package com.wgslfuzz.semanticspreservingtransformations

import com.wgslfuzz.core.BinaryOperator
import com.wgslfuzz.core.Expression
import com.wgslfuzz.core.clone

// Boolean guards wrapped around injected perturbation statements.
// Conditions are meant to create divergence across invocations, to trigger different compiler optimisations and arrangements.
//
// A perturb/restore pair is only semantics preserving if BOTH guards evaluate the same way for
// every invocation. The catalogue in this file will make that true by construction: one template
// instance yields both spellings, so two mismatched predicates become unrepresentable rather than
// merely discouraged. (The v0/v1 %2-vs-%3 bug is exactly that mistake.)

/**
 * `local_invocation_id.x % Nu == 0u`.
 *
 * u32 (not i32) since local_invocation_id is vec3<u32>, so `.x` is u32, and WGSL's `%` requires
 * both operands to share that type.
 */
internal fun modNCondition(
    lidExpr: Expression,
    n: Int = 2,
): Expression.Binary =
    Expression.Binary(
        operator = BinaryOperator.EQUAL_EQUAL,
        lhs =
            Expression.Binary(
                operator = BinaryOperator.MODULO,
                lhs = Expression.MemberLookup(lidExpr.clone(), "x"),
                rhs = Expression.IntLiteral("${n}u"),
            ),
        rhs = Expression.IntLiteral("0u"),
    )

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
