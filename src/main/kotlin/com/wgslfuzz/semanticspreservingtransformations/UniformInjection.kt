package com.wgslfuzz.semanticspreservingtransformations

import com.wgslfuzz.core.Statement

// Injects `if (<workgroup-uniform guard>) { workgroupBarrier(); }` around v2/v3's existing
// perturb/restore pairs (see the call sites in DivergentLocalInjection.kt).
//
// Unlike the perturb/restore pair itself, a single guarded workgroupBarrier() call needs no "undo",
// since it does not introduce any memory reads or writes.
// As long as the guard is genuinely uniform across the workgroup, which is what EntryPointContext.uniformBuiltin() guarantees,
// it has no observable effect on shader output beyond synchronization. 
// The differential-testing oracle that compares a variant's output against the original is unaffected, 
// and there is no target, no restore.

/**
 * `if (<uniform guard>) { workgroupBarrier(); }`, with a fresh guard and a fresh id.
 */
internal fun guardedUniformBarrierStatement(
    context: EntryPointContext,
    fuzzerSettings: FuzzerSettings,
): Statement.If {
    val template = chooseUniformConditionTemplate(fuzzerSettings)
    val id = fuzzerSettings.getUniqueId()
    return guardedStatement(
        guard = template.guard(context.uniformBuiltin()),
        body = listOf(Statement.FunctionCall(callee = "workgroupBarrier", args = emptyList())),
        id = id,
        commentary = template.commentary,
    )
}

/**
 * 50% chance of [guardedUniformBarrierStatement], else nothing. Called independently at each
 * candidate slot around a perturb/restore pair -- see DivergentLocalInjection.kt -- so a single
 * pair may end up with anywhere from zero to four barrier injections around it.
 */
internal fun maybeUniformBarrierStatements(
    context: EntryPointContext,
    fuzzerSettings: FuzzerSettings,
): List<Statement> =
    if (fuzzerSettings.injectUniformBarrier()) {
        listOf(guardedUniformBarrierStatement(context, fuzzerSettings))
    } else {
        emptyList()
    }
