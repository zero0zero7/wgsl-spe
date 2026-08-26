package com.wgslfuzz.semanticspreservingtransformations

import com.wgslfuzz.core.Statement

// v4 only (see applyV4 in DivergentLocalInjection.kt). Injects
// `if (<workgroup-uniform guard>) { workgroupBarrier(); }` around v4's existing LID-guarded
// perturb/restore pair. The barrier is represented by Statement.FunctionCall: it has no result
// type and therefore cannot be used as an Expression.FunctionCall or assignment value.

/**
 * `if (<uniform guard>) { workgroupBarrier(); }`
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
 * `if (<uniform guard>) {  }`
 */
internal fun guardedUniformEmptyStatement(
    context: EntryPointContext,
    fuzzerSettings: FuzzerSettings,
): Statement.If {
    val template = chooseUniformConditionTemplate(fuzzerSettings)
    val id = fuzzerSettings.getUniqueId()
    return guardedStatement(
        guard = template.guard(context.uniformBuiltin()),
        body = emptyList(),
        id = id,
        commentary = template.commentary,
    )
}

/**
 * 50% chance of [guardedUniformEmptyStatement], else nothing. Called independently at each candidate
 * slot around a perturb/restore pair, so a single pair may receive zero to four barrier injections.
 */
internal fun maybeUniformStatements(
    context: EntryPointContext,
    fuzzerSettings: FuzzerSettings,
): List<Statement> =
    if (fuzzerSettings.injectUniformStatement()) {
        listOf(guardedUniformEmptyStatement(context, fuzzerSettings))
    } else {
        emptyList()
    }
