package com.wgslfuzz.semanticspreservingtransformations

import com.wgslfuzz.core.BinaryOperator
import com.wgslfuzz.core.Expression
import com.wgslfuzz.core.LhsExpression
import com.wgslfuzz.core.Statement
import com.wgslfuzz.core.Type
import com.wgslfuzz.core.TypeDecl
import com.wgslfuzz.core.UnaryOperator

// How an injected pair modifies its target, how it restores it -- as if no modification was performed.
//
// Every template is TOTAL: restore(perturb(v)) == v for every value of the target's type,
// with no value-range side condition. Integer wrapping (mod 2^32) may be relied on, since WGSL
// defines it; rounding and saturation may not.
//
// algebraic templates rely on restorable transformations which have an exact inverse.
// tempCopy template relies on restorable transformations to perturb the target, but the restore is routed through an intermediary variable.
// snapshot template can use any perturbation (need not be restorable), since the target restore is routed through a saved copy of the original value.

/**
 * A modification of the target.
 *
 * Side-effect free, reads nothing but the target itself, and total -- defined for every value of
 * the target's type, with no divide and no shift, so reconditioning has nothing to rewrite.
 *
 * A transformation that is NOT a [RestorablePerturbation] may only be used by a template that restores from
 * a saved copy, i.e. [SnapshotTemplate].
 */
internal interface Perturbation {
    fun perturb(target: LhsExpression): Expression

    val commentary: String
}

/**
 * A perturbation paired with its exact algebraic inverse.
 *
 * Contract, for EVERY value v of the target's type:
 *  1. restore(perturb(v)) == v exactly.
 *  2. Both are bijections -- perturb(restore(v)) == v -- the injector is
 *     free to emit restore at the earlier index.
 *  3. Side-effect free, and reads nothing but the target itself.
 */
internal interface RestorablePerturbation : Perturbation {
    fun restore(target: LhsExpression): Expression
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
 * thus no bound on the operand required. Reconditioned right before execution anyway.
 */
private class AddSub(
    private val delta: Int,
    private val type: Type.Scalar,
) : RestorablePerturbation {
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
) : RestorablePerturbation {
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
            // Same transformation, different spelling: `zero - x` rather than `-x`.
            Expression.Binary(BinaryOperator.MINUS, hiddenZero(), lhsExprToExpr(target))
        } else {
            negate(target)
        }

    override val commentary: String = if (distinctSpellings) "negate (distinct spellings)" else "negate"
}

/**
 * `x = <literal>`. 
 * No inverse: the original value is simply gone.
 * Used in snapshot template only.
 */
private class Constant(
    private val value: Int,
    private val type: Type.Scalar,
) : Perturbation {
    override fun perturb(target: LhsExpression): Expression = scalarLiteral(value, type)

    override val commentary: String = "overwrite with $value"
}

/**
 * `x = x * x`. 
 * Not restorable: ioverflow and lost of sign for integers, overflow to inf for floats.
 * Used in snapshot template only.
 */
private class SelfProduct : Perturbation {
    override fun perturb(target: LhsExpression): Expression =
        Expression.Binary(BinaryOperator.TIMES, lhsExprToExpr(target), lhsExprToExpr(target))

    override val commentary: String = "overwrite with x * x"
}

/**
 * `x = <input>`
 * Not restorable.
 * Read from the injected buffer at runtime, so the store cannot be constant-folded.
 */
private class HiddenConstant(
    private val kind: String,
    private val read: () -> Expression,
) : Perturbation {
    override fun perturb(target: LhsExpression): Expression = read()

    override val commentary: String = "overwrite with hidden $kind"
}

/**
 * Picks a `RestorablePerturbation` applicable to [type], or null when nothing applies -- the caller
 * must then skip the injection site, or fall back to a template that needs no inverse.
 * Note:f32 IS supported via Negate; only f16 comes back null.
 *
 * Unlike every other `choose` call site in this package, the candidate list here can legitimately
 * be empty, so `choose` must not be called blindly.
 */
internal fun chooseRestorablePerturbation(
    fuzzerSettings: FuzzerSettings,
    context: EntryPointContext,
    type: Type.Scalar,
): RestorablePerturbation? {
    val weights = fuzzerSettings.divergentPerturbationWeights
    // Non-null only when this entry point has an injected input buffer (v1/v2, not v0).
    val hiddenZero: (() -> Expression)? =
        if (context.hidden("zero", type) != null) {
            { context.hidden("zero", type)!! }
        } else {
            null
        }
    val choices: List<Pair<Int, () -> RestorablePerturbation>> =
        listOfNotNull(
            // Add then Subtract
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

/**
 * Every template applicable to [type], restorable or not: 70% non-restorable (equally likely among
 * themselves) and 30% restorable, per [FuzzerSettings.divergentPerturbationWeights].
 *
 * Never null -- [Constant] applies to every scalar type [firstScalarLeaf] can produce -- so only a
 * template that restores from a saved copy may call this.
 */
internal fun choosePerturbation(
    fuzzerSettings: FuzzerSettings,
    context: EntryPointContext,
    type: Type.Scalar,
): Perturbation {
    val weights = fuzzerSettings.divergentPerturbationWeights
    // Any of zero/one/min/max; max is the most distinctive in a dump. 
    // Null for f16 (no hidden-constant member) and for v0 (no injected input buffer).
    val hiddenKind = "max"
    val nonRestorable: List<() -> Perturbation> =
        listOfNotNull(
            { Constant(fuzzerSettings.randomInt(1, 1001), type) }, // random constant
            { SelfProduct() }, // target * target
            if (context.hidden(hiddenKind, type) != null) { // read from injected input buffer
                { HiddenConstant(hiddenKind) { context.hidden(hiddenKind, type)!! } }
            } else {
                null
            },
        )
    val restorable = chooseRestorablePerturbation(fuzzerSettings, context, type)
    val choices: List<Pair<Int, () -> Perturbation>> =
        listOfNotNull( 
            // Select a random non-restorable transformation
            // Select a random restorable transformation
            // Both are fine as we just need a template that doesnt require restorability
            // Select from the two, with the non-restorable transformations weighted 60% and the restorable 40% (from FuzzerSettings)
            weights.nonRestorable to { fuzzerSettings.randomElement(nonRestorable)() },
            restorable?.let { weights.restorable to { it } },
        ).filter { it.first > 0 }
    return choose(fuzzerSettings, choices)
}

/**
 * [TypeDecl] for [type]
 */
internal fun scalarTypeDecl(type: Type.Scalar): TypeDecl.ScalarTypeDecl =
    when (type) {
        Type.I32 -> TypeDecl.I32()
        Type.U32 -> TypeDecl.U32()
        Type.F32 -> TypeDecl.F32()
        Type.F16 -> TypeDecl.F16()
        Type.Bool, Type.AbstractInteger, Type.AbstractFloat ->
            throw IllegalArgumentException("no type declaration for $type")
    }

// ---------- templates: how a pair is laid out in the statement list ----------

/**
 * [atPerturbIndex] <= [atRestoreIndex].
 * A template that needs an intermediary variable puts its declaration at the head of [atPerturbIndex], 
 * so that the declaration lands ahead of and outside both guards, in the same enclosing compound.
 */
internal class InjectedPair(
    val atPerturbIndex: List<Statement>,
    val atRestoreIndex: List<Statement>,
) {
    /** For v0/v1, which place the two halves adjacent to each other at a single index. 
    * Returns a single list of statements (instead of 2 separate lists)
    */
    val adjacent: List<Statement> get() = atPerturbIndex + atRestoreIndex
}

/**
 * How a perturb/restore pair is laid out.
 *
 * Every template must end with the target holding its original value after BOTH halves have run, and be a
 * no-op when NEITHER runs.
 * The condition guards ensure a thread takes both or neither; the template owns everything in between.
 */
internal interface PerturbationTemplate {
    fun build(
        guards: DivergentConditionTemplate,
        context: EntryPointContext,
        target: LhsExpression,
        id: Int,
    ): InjectedPair

    val commentary: String
}

/**
 *     if (guardA) { t = t + 5i; }
 *     ...
 *     if (guardB) { t = t - 5i; }
 *
 * Takes a [RestorablePerturbation], so a transformation without an inverse cannot reach it.
 */
private class AlgebraicTemplate(
    private val perturbation: RestorablePerturbation,
) : PerturbationTemplate {
    override fun build(
        guards: DivergentConditionTemplate,
        context: EntryPointContext,
        target: LhsExpression,
        id: Int,
    ): InjectedPair =
        InjectedPair(
            atPerturbIndex =
                listOf(
                    perturbationStatement(
                        guard = guards.perturbGuard(context),
                        target = target,
                        newValue = perturbation.perturb(target), // Expression that applies perturbation to target
                        id = id,
                        commentary = "divergent perturbation: ${perturbation.commentary} under ${guards.commentary}",
                    ),
                ),
            atRestoreIndex =
                listOf(
                    perturbationStatement(
                        guard = guards.restoreGuard(context),
                        target = target,
                        newValue = perturbation.restore(target),
                        id = id,
                        commentary = "divergent restore",
                    ),
                ),
        )

    override val commentary: String = "algebraic"
}

/**
 * Save the target, perturb it in any way, then assign target back to saved copy.
 *
 *     let injected_7 = t;             // unguarded, immediately before the perturb
 *     if (guardA) { t = t * t; } // perturbation can be non-restorable
 *     ...
 *     if (guardB) { t = injected_7; }
 *
 * A compiler might be able to eliminate meaningless code in the two guards, and ignore the perturb outright.
 */
private class SnapshotTemplate(
    private val perturbation: Perturbation,
) : PerturbationTemplate {
    override fun build(
        guards: DivergentConditionTemplate,
        context: EntryPointContext,
        target: LhsExpression,
        id: Int,
    ): InjectedPair {
        val copyName = injectedTempName(id)
        return InjectedPair(
            atPerturbIndex =
                listOf(
                    snapshotDeclaration(
                        name = copyName,
                        target = target,
                        id = id,
                        commentary = "divergent snapshot: saves $name for the restore below",
                    ),
                    perturbationStatement(
                        guard = guards.perturbGuard(context),
                        target = target,
                        newValue = perturbation.perturb(target), // Any perturbation, restorable or not
                        id = id,
                        commentary = "divergent perturbation: ${perturbation.commentary} under ${guards.commentary}",
                    ),
                ),
            atRestoreIndex =
                listOf(
                    perturbationStatement(
                        guard = guards.restoreGuard(context),
                        target = target,
                        newValue = Expression.Identifier(copyName),
                        id = id,
                        commentary = "divergent restore from snapshot",
                    ),
                ),
        )
    }

    override val commentary: String = "snapshot"
}

/**
 * An algebraic restore routed through an intermediary variable.
 *
 *     var injected_7 : i32; // unguarded; WGSL zero-initialises
 *     if (guardA) { t = t + 5i; }
 *     ...
 *     if (guardB) { injected_7 = t - 5i; t = injected_7; } // restore through intermediary
 *
 * Motivation: to trigger copy propgation where for an assignment `x=y`, if `x` and `y` are both unmodified between the assingment and a use of `x`, the use is rewritten to `y`.
 */
private class TempCopyTemplate(
    private val perturbation: RestorablePerturbation,
    private val type: Type.Scalar,
) : PerturbationTemplate {
    override fun build(
        guards: DivergentConditionTemplate,
        context: EntryPointContext,
        target: LhsExpression,
        id: Int,
    ): InjectedPair {
        val intermediaryName = injectedTempName(id)
        val intermediary = LhsExpression.Identifier(intermediaryName)
        return InjectedPair(
            atPerturbIndex =
                listOf(
                    scratchDeclaration(
                        name = intermediaryName,
                        type = type,
                        id = id,
                        commentary = "divergent intermediary for the restore below",
                    ),
                    perturbationStatement(
                        guard = guards.perturbGuard(context),
                        target = target,
                        newValue = perturbation.perturb(target),
                        id = id,
                        commentary = "divergent perturbation: ${perturbation.commentary} under ${guards.commentary}",
                    ),
                ),
            atRestoreIndex =
                listOf(
                    guardedStatement(
                        guard = guards.restoreGuard(context),
                        body =
                            listOf(
                                assign(intermediary, perturbation.restore(target)),
                                assign(target, Expression.Identifier(intermediaryName)),
                            ),
                        id = id,
                        commentary = "divergent restore via intermediary",
                    ),
                ),
        )
    }

    override val commentary: String = "temp-copy"
}

/**
 * Picks a template for [type], or null when nothing applies -- the caller must then skip the site.
 *
 * [SnapshotTemplate] applies to every scalar type, so null comes back only when its weight is zero AND
 * no restorable transformation exists for [type]: today, f16 with the snapshot template disabled.
 *
 * v0/v1 place both halves adjacently, so any template works there. 
 * v2 places them apart (interleaved with other existing code), hence the hoisted declaration is necessary.
 */
internal fun chooseTemplate(
    fuzzerSettings: FuzzerSettings,
    context: EntryPointContext,
    type: Type.Scalar,
): PerturbationTemplate? {
    val weights = fuzzerSettings.divergentPerturbationWeights
    // Select a random restorable transformation, to be used if algebraic or tempCopy is selected later.
    val restorable = chooseRestorablePerturbation(fuzzerSettings, context, type)
    val choices: List<Pair<Int, () -> PerturbationTemplate> =
        listOfNotNull(
            restorable?.let { weights.algebraic to { AlgebraicTemplate(it) } },
            weights.snapshot to { SnapshotTemplate(choosePerturbation(fuzzerSettings, context, type)) },
            restorable?.let { weights.tempCopy to { TempCopyTemplate(it, type) } },
        ).filter { it.first > 0 }
    return if (choices.isEmpty()) null else choose(fuzzerSettings, choices)
}
