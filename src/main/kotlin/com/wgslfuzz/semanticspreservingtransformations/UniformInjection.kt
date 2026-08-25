package com.wgslfuzz.semanticspreservingtransformations

import com.wgslfuzz.core.Expression
import com.wgslfuzz.core.Statement
import com.wgslfuzz.core.Type

// v4 only (see applyV4 in DivergentLocalInjection.kt). Injects
// `if (<workgroup-uniform guard>) { <target> = <cast>(<uniformBuiltin>.x); }` around v4's existing
// LID-guarded perturb/restore pair.
//
// Unlike that pair, this statement is NOT semantics preserving -- there is no restore, so [target]
// can end up holding a value the UNMODIFIED original shader would never have computed. That's
// deliberate: v4's oracle (_check_divergence_v4 in fuzz/lib/divergenceCheck.sh) does not compare
// the variant's output against the original, only that the variant itself runs cleanly. What DOES
// still hold is workgroup-safety: the guard is built from a genuinely workgroup-uniform builtin
// (EntryPointContext.uniformBuiltin()), so within one workgroup every invocation takes the branch
// or none do -- no intra-workgroup inconsistency -- and, unlike a restricted-callsite builtin such
// as workgroupBarrier(), an ordinary assignment carries no "must be called from uniform control
// flow" requirement, so it compiles wherever it's placed, including downstream of v2/v3's own
// LID-guarded (non-uniform) branches. That's the whole reason this moved out of v2/v3 and into its
// own v4: see the coverage-15m campaign notes for why a guarded workgroupBarier() call there was
// rejected by Tint's uniformity analysis almost everywhere it landed.

/** `<type>(<value>)`, or [value] unchanged when [type] is already u32. Errors for non-scalar types. */
private fun castTo(
    type: Type.Scalar,
    value: Expression,
): Expression =
    when (type) {
        Type.U32 -> value
        Type.I32 -> Expression.FunctionCall(callee = "i32", args = listOf(value))
        Type.F32 -> Expression.FunctionCall(callee = "f32", args = listOf(value))
        Type.F16 -> Expression.FunctionCall(callee = "f16", args = listOf(value))
        Type.Bool, Type.AbstractInteger, Type.AbstractFloat ->
            throw IllegalArgumentException("no scalar cast for $type")
    }

/**
 * `if (<uniform guard>) { <target> = <cast>(<uniformBuiltin>.x); }`, with a fresh guard and a
 * fresh id (independent of the enclosing perturb/restore pair's id, so the reducer can delete this
 * injection on its own).
 */
internal fun guardedUniformStatement(
    context: EntryPointContext,
    fuzzerSettings: FuzzerSettings,
    target: LocalVariableTarget,
): Statement.If {
    val template = chooseUniformConditionTemplate(fuzzerSettings)
    val id = fuzzerSettings.getUniqueId()
    val value = castTo(target.targetType, Expression.MemberLookup(context.uniformBuiltin(), "x"))
    return guardedStatement(
        guard = template.guard(context.uniformBuiltin()),
        body = listOf(assign(target.target, value)),
        id = id,
        commentary = template.commentary,
    )
}

/**
 * 50% chance of [guardedUniformStatement], else nothing. Called independently at each candidate
 * slot around a perturb/restore pair -- see DivergentLocalInjection.kt -- so a single pair may end
 * up with anywhere from zero to four of these injections around it.
 */
internal fun maybeUniformStatements(
    context: EntryPointContext,
    fuzzerSettings: FuzzerSettings,
    target: LocalVariableTarget,
): List<Statement> =
    if (fuzzerSettings.injectUniformStatement()) {
        listOf(guardedUniformStatement(context, fuzzerSettings, target))
    } else {
        emptyList()
    }
