package com.wgslfuzz.semanticspreservingtransformations

import com.wgslfuzz.core.Expression
import com.wgslfuzz.core.Statement
import com.wgslfuzz.core.Type

// v4 only (see applyV4 in DivergentLocalInjection.kt). Injects
// `if (<workgroup-uniform guard>) { <target> = <value>; }` around v4's existing LID-guarded
// perturb/restore pair.
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
// own v4: see the coverage-15m campaign notes for why a guarded workgroupBarrier() call there was
// rejected by Tint's uniformity analysis almost everywhere it landed.
//
// [target]'s VALUE: [uniformBuiltin] is always vec3<u32>, so its .x component is used directly
// when [target] is itself u32. For every other target type, a runtime u32-to-<type> conversion
// call (e.g. `i32(...)`) would be needed instead -- but the core resolver's function-call type
// inference (ToType.kt) has no case for those scalar-conversion builtins yet (nothing in this
// toolkit had ever needed to construct one before this feature). Rather than extend that shared,
// widely-used resolver for a v4-only need, the non-u32 case instead reads an ALREADY-typed value
// from the injected input buffer's hidden-constant block (EntryPointContext.hidden), exactly the
// mechanism choosePerturbation already uses elsewhere in this package -- no conversion call, no
// resolver gap. There's no hidden-constant member for f16 (see hiddenMemberFor in
// DivergentInjections.kt), so an f16 target is simply skipped (no injection at that slot).

/**
 * `if (<uniform guard>) { <target> = <value>; }`, with a fresh guard and a fresh id (independent
 * of the enclosing perturb/restore pair's id, so the reducer can delete this injection on its
 * own). Null when no value is available for [target]'s type (f16 -- see file header).
 */
internal fun guardedUniformStatement(
    context: EntryPointContext,
    fuzzerSettings: FuzzerSettings,
    target: LocalVariableTarget,
): Statement.If? {
    val value: Expression =
        if (target.targetType == Type.U32) {
            Expression.MemberLookup(context.uniformBuiltin(), "x")
        } else {
            context.hidden("max", target.targetType) ?: return null
        }
    val template = chooseUniformConditionTemplate(fuzzerSettings)
    val id = fuzzerSettings.getUniqueId()
    return guardedStatement(
        guard = template.guard(context.uniformBuiltin()),
        body = listOf(assign(target.target, value)),
        id = id,
        commentary = template.commentary,
    )
}

/**
 * 50% chance of [guardedUniformStatement], else nothing (also nothing when [guardedUniformStatement]
 * itself returns null). Called independently at each candidate slot around a perturb/restore pair
 * -- see DivergentLocalInjection.kt -- so a single pair may end up with anywhere from zero to four
 * of these injections around it.
 */
internal fun maybeUniformStatements(
    context: EntryPointContext,
    fuzzerSettings: FuzzerSettings,
    target: LocalVariableTarget,
): List<Statement> =
    if (fuzzerSettings.injectUniformStatement()) {
        listOfNotNull(guardedUniformStatement(context, fuzzerSettings, target))
    } else {
        emptyList()
    }
