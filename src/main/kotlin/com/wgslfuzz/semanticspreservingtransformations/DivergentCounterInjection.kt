package com.wgslfuzz.semanticspreservingtransformations

import com.wgslfuzz.core.AccessMode
import com.wgslfuzz.core.AddressSpace
import com.wgslfuzz.core.AssignmentOperator
import com.wgslfuzz.core.AstNode
import com.wgslfuzz.core.AugmentedMetadata
import com.wgslfuzz.core.BinaryOperator
import com.wgslfuzz.core.Expression
import com.wgslfuzz.core.GlobalDecl
import com.wgslfuzz.core.LhsExpression
import com.wgslfuzz.core.ShaderJob
import com.wgslfuzz.core.Statement
import com.wgslfuzz.core.Type
import com.wgslfuzz.core.clone
import com.wgslfuzz.core.toType
import com.wgslfuzz.core.traverse

// v0 and v1: inject a SYNTHESISED i32 counter into each @compute entry point, perturb it at
// randomly chosen sites, and write it out so an oracle can check it came back to
// COUNTER_INITIAL_VALUE. v2 (DivergentLocalInjection.kt) instead hijacks a variable the shader
// already has.

internal typealias DivergentCounterInjections = MutableMap<Statement.Compound, Set<Int>>

/**
 * For v0 and v1: one perturb/restore pair applied to the synthesised counter.
 * Both statements share a single id, so the reducer deletes them together or not at all.
 */
private fun createDivergentCounterPair(
    fuzzerSettings: FuzzerSettings,
    context: EntryPointContext,
    incrementValue: Expression,
    decrementValue: Expression,
): List<Statement> {
    val id = fuzzerSettings.getUniqueId() // both conditions share a single id, so that both deleted together
    val incrementIf =
        Statement.If(
            condition = modNCondition(context.lid(), n = 2),
            thenBranch =
                Statement.Compound(
                    listOf(
                        Statement.Assignment(
                            lhsExpression = context.counter(),
                            assignmentOperator = AssignmentOperator.PLUS_EQUAL,
                            rhs = incrementValue.clone(),
                        ),
                    ),
                ),
            metadata = setOf(AugmentedMetadata.DeletableStatement(id, "divergent counter increment")),
        )
    val decrementIf =
        Statement.If(
            condition = modNCondition(context.lid(), n = 3),
            thenBranch =
                Statement.Compound(
                    listOf(
                        Statement.Assignment(
                            lhsExpression = context.counter(),
                            assignmentOperator = AssignmentOperator.MINUS_EQUAL,
                            rhs = decrementValue.clone(),
                        ),
                    ),
                ),
            metadata = setOf(AugmentedMetadata.DeletableStatement(id, "divergent counter decrement")),
        )
    return listOf(incrementIf, decrementIf)
}

/**
 * The shader's output buffer: the module-scope `var<storage, read_write>`.
 * wgslsmith emits exactly one (`s_output`); if a shader somehow has several, the first is used,
 * since that is also the one the harness dumps first.
 */
private fun findExistingOutputBuffer(shaderJob: ShaderJob): GlobalDecl.Variable? =
    shaderJob.tu.globalDecls
        .filterIsInstance<GlobalDecl.Variable>()
        .firstOrNull { it.addressSpace == AddressSpace.STORAGE && it.accessMode == AccessMode.READ_WRITE }

/**
 * `<existing_output>.<..first scalar..> = <scalarType>(<counter>);`
 * Returns null when the shader has no output buffer. For v0.
 */
private fun createCounterOverwrite(
    shaderJob: ShaderJob,
    context: EntryPointContext,
): Statement? {
    val outputBuffer = findExistingOutputBuffer(shaderJob) ?: return null
    val outputType =
        outputBuffer.typeDecl?.toType(shaderJob.environment.globalScope, shaderJob.environment)
            ?: return null
    val (lhs, scalarType) =
        firstScalarLeaf(LhsExpression.Identifier(outputBuffer.name), outputType, 0)
            ?: return null
    // The counter is i32; convert rather than assume, so that an output buffer starting with
    // eg. mat3x3<f32> or u32 gets a well-typed store.
    val counter = context.counterExpr()
    val rhs: Expression =
        when (scalarType) {
            Type.I32 -> Expression.I32ValueConstructor(listOf(counter))
            Type.U32 -> Expression.U32ValueConstructor(listOf(counter))
            Type.F32 -> Expression.F32ValueConstructor(listOf(counter))
            Type.F16 -> Expression.F16ValueConstructor(listOf(counter))
            Type.Bool, Type.AbstractInteger, Type.AbstractFloat -> return null
        }
    return Statement.Assignment(
        lhsExpression = lhs,
        assignmentOperator = AssignmentOperator.EQUAL,
        rhs = rhs,
    )
}

/**
 * `<counter_output>.data = i32(<counter>);`
 * Stores the counter into v1's dedicated output struct.
 */
private fun createCounterWrite(
    context: EntryPointContext,
    outputBufferName: String,
): Statement {
    val lhs = LhsExpression.MemberLookup(LhsExpression.Identifier(outputBufferName), V1_STRUCT_MEMBER)
    return Statement.Assignment(
        lhsExpression = lhs,
        assignmentOperator = AssignmentOperator.EQUAL,
        rhs = Expression.I32ValueConstructor(listOf(context.counterExpr())),
    )
}

/**
 * For v0 and v1: given an entry point's function body, randomly select a set of indices within
 * each Compound where a perturb/restore pair should be injected.
 */
private fun selectInjectionPoints(
    fuzzerSettings: FuzzerSettings,
    node: AstNode,
    injections: DivergentCounterInjections,
) {
    traverse({ n, acc -> selectInjectionPoints(fuzzerSettings, n, acc) }, node, injections)
    if (node is Statement.Compound) {
        val range = 0..node.statements.size
        val filtered = range.filter { fuzzerSettings.randomInt(100) < 30 }
        // Fall back to a single random index if all were filtered out, so at least one injection
        // occurs in this compound.
        injections[node] = filtered.ifEmpty { listOf(fuzzerSettings.randomElement(range.toList())) }.toSet()
    }
}

/**
 * For v0 and v1: injects the perturb/restore pair into every Compound at the indices selected by
 * [selectInjectionPoints].
 */
private fun injectDivergentInjections(
    fuzzerSettings: FuzzerSettings,
    context: EntryPointContext,
    node: AstNode,
    injections: DivergentCounterInjections,
    counterWrite: Statement?,
    incrementValue: Expression,
    decrementValue: Expression,
): AstNode? =
    injections[node]?.let { indices ->
        val compound = node as Statement.Compound
        val newBody = mutableListOf<Statement>()
        for (index in 0..compound.statements.size) {
            if (index in indices) {
                newBody.addAll(createDivergentCounterPair(fuzzerSettings, context, incrementValue, decrementValue))
            }
            if (index < compound.statements.size) {
                val statement = compound.statements[index]
                // Insert the counter write before each `return` that leaves the entry point
                if (counterWrite != null && statement is Statement.Return) {
                    newBody.add(counterWrite.clone())
                }
                // Add original statements. Recursive, to inject into nested (compound) statements.
                newBody.add(
                    statement.clone {
                        injectDivergentInjections(
                            fuzzerSettings, context, it, injections, counterWrite, incrementValue, decrementValue,
                        )
                    },
                )
            }
        }
        Statement.Compound(newBody, compound.metadata)
    }

/** Selects injection points for [body]; null means nothing was selected. */
private fun computeInjectionsOrNull(
    fuzzerSettings: FuzzerSettings,
    body: Statement.Compound,
): DivergentCounterInjections? {
    val injections: DivergentCounterInjections = mutableMapOf()
    selectInjectionPoints(fuzzerSettings, body, injections)
    return injections.takeIf { map -> map.values.any { it.isNotEmpty() } }
}

/**
 * Applies [injections], then wraps with the initial counter decl and (if not already followed by
 * a return) a trailing counter write. Shared by v0/v1.
 */
private fun buildInjectedBody(
    fuzzerSettings: FuzzerSettings,
    context: EntryPointContext,
    body: Statement.Compound,
    injections: DivergentCounterInjections,
    counterWrite: Statement?,
    incrementValue: Expression,
    decrementValue: Expression,
): Statement.Compound {
    val injectedBody =
        body.clone {
            injectDivergentInjections(
                fuzzerSettings, context, it, injections, counterWrite, incrementValue, decrementValue,
            )
        }
    val statements = mutableListOf<Statement>()
    statements.add(counterInstance(COUNTER_INITIAL_VALUE, context.counterName!!))
    statements.addAll(injectedBody.statements)
    if (counterWrite != null && injectedBody.statements.lastOrNull() !is Statement.Return) {
        statements.add(counterWrite.clone())
    }
    return Statement.Compound(statements, injectedBody.metadata)
}

/**
 * v0: inject a counter, perturb it, and overwrite the first element of the shader's own output
 * buffer with its final value. The oracle compares only that first element, because the rest of
 * the buffer is non-deterministic under a racing multi-thread dispatch.
 */
internal fun applyV0(
    shaderJob: ShaderJob,
    fuzzerSettings: FuzzerSettings,
): ShaderJob {
    val newGlobalDecls =
        mapComputeFunctions(shaderJob.tu.globalDecls) { decl ->
            val injections = computeInjectionsOrNull(fuzzerSettings, decl.body) ?: return@mapComputeFunctions decl
            val (lidExpr, parameters) = getLidExpr(shaderJob, fuzzerSettings, decl)
            val context =
                EntryPointContext(
                    lidExpr = lidExpr,
                    parameters = parameters,
                    counterName = "divergent_counter_${fuzzerSettings.getUniqueId()}",
                    opaqueI32 = null, // v0 injects no input buffer
                )
            val counterWrite = createCounterOverwrite(shaderJob, context)
            val randVal = fuzzerSettings.randomInt(1000)
            val magnitude = Expression.IntLiteral("${randVal}i")
            val body =
                buildInjectedBody(
                    fuzzerSettings, context, decl.body, injections, counterWrite,
                    incrementValue = magnitude,
                    decrementValue = magnitude,
                )
            decl.withParametersAndBody(context.parameters, body)
        }
    return rebuildShaderJob(shaderJob, newGlobalDecls)
}

/**
 * v1: inject a counter, perturb it, and write it to a dedicated output buffer.
 * Only one thread actually runs, chosen by the injected input buffer, so the whole output is
 * deterministic and the oracle can compare all of it.
 */
internal fun applyV1(
    shaderJob: ShaderJob,
    fuzzerSettings: FuzzerSettings,
): ShaderJob {
    val (inputBinding, outputBinding) = nextTwoBindings(shaderJob)
    val structDecl = dataStruct(fuzzerSettings.getUniqueId())
    val inputInstance = threadToRunInputInstance(inputBinding, structDecl.name)
    val outputInstance = scalarOutputInstance(outputBinding, structDecl.name, "counter_output")

    val newGlobalDecls =
        mapComputeFunctions(shaderJob.tu.globalDecls) { decl ->
            val injections = computeInjectionsOrNull(fuzzerSettings, decl.body) ?: return@mapComputeFunctions decl
            val (lidExpr, parameters) = getLidExpr(shaderJob, fuzzerSettings, decl)
            val context =
                EntryPointContext(
                    lidExpr = lidExpr,
                    parameters = parameters,
                    counterName = "divergent_counter_${fuzzerSettings.getUniqueId()}",
                    opaqueI32 = { Expression.MemberLookup(Expression.Identifier(inputInstance.name), V1_STRUCT_MEMBER) },
                )

            val incrementValue = context.opaque()!!
            val decrementValue =
                Expression.Binary(
                    operator = BinaryOperator.MODULO,
                    lhs = context.opaque()!!,
                    rhs =
                        Expression.Paren(
                            target =
                                Expression.Binary(
                                    BinaryOperator.MINUS,
                                    Expression.IntLiteral("2147483645i"),
                                    context.opaque()!!,
                                ),
                        ),
                )

            val counterWrite = createCounterWrite(context, outputBufferName = outputInstance.name)
            val body =
                buildInjectedBody(
                    fuzzerSettings, context, decl.body, injections, counterWrite, incrementValue, decrementValue,
                )

            val gatedBody =
                Statement.Compound(
                    listOf(
                        Statement.If(
                            condition = singleThreadCondition(context.lid(), context.opaque()!!, equals = false),
                            thenBranch = Statement.Compound(listOf(Statement.Return(null))),
                        ),
                    ) + body.statements,
                    body.metadata,
                )

            decl.withParametersAndBody(context.parameters, gatedBody)
        }
    return rebuildShaderJob(shaderJob, newGlobalDecls, listOf(structDecl, inputInstance, outputInstance))
}
