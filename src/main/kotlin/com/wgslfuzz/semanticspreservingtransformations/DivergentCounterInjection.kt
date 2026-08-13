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

// v0 and v1: inject a SYNTHESISED i32 counter into each @compute entry point,
// perturb it at randomly chosen sites, and write it out 
// so an oracle can check it came back to COUNTER_INITIAL_VALUE. 

internal typealias DivergentCounterInjections = MutableMap<Statement.Compound, Set<Int>>

/**
 * v0, v1.
 * One perturb/restore pair applied to the synthesised counter.
 * Both statements share a single id, so the reducer deletes them together or not at all.
 */
private fun createDivergentCounterPair(
    fuzzerSettings: FuzzerSettings,
    context: EntryPointContext,
): List<Statement> {
    // The counter is declared u32 by counterInstance, so the perturbation operand must be u32 too.
    val shape = chooseShape(fuzzerSettings, context, Type.U32) ?: return emptyList()
    val guards = chooseConditionTemplate(fuzzerSettings, context)
    val id = fuzzerSettings.getUniqueId() // one id per pair, so the reducer deletes both or neither
    // v0/v1 place the two halves adjacently, so a shape's temporary declaration already lands in
    // the same compound as its uses -- no hoisting problem to solve.
    return shape.build(guards, context, context.counter(), id).adjacent
}

/**
 * v0.
 * The shader's output buffer: the module-scope `var<storage, read_write>`.
 * wgslsmith emits exactly one (`s_output`); if a shader somehow has several, the first is used,
 * since that is also the one the harness dumps first.
 */
private fun findExistingOutputBuffer(shaderJob: ShaderJob): GlobalDecl.Variable? =
    shaderJob.tu.globalDecls
        .filterIsInstance<GlobalDecl.Variable>()
        .firstOrNull { it.addressSpace == AddressSpace.STORAGE && it.accessMode == AccessMode.READ_WRITE }

/**
 * v0.
 * `<existing_output>.<..first scalar..> = <scalarType>(<counter>);`
 * Returns null when the shader has no output buffer.
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
    // The counter is u32; convert rather than assume, so that an output buffer starting with
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
 * v1.
 * `<counter_output>.data = u32(<counter>);`
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
        rhs = Expression.U32ValueConstructor(listOf(context.counterExpr())),
    )
}

/**
 * v0, v1.
 * Given an entry point's function body, randomly select a set of indices within
 * each Compound where a perturb/restore pair should be injected.
 * Can be anywhere since the perturb/restore pair are adjacent and the counter is a synthesized variable, so no scoping issues.
 */
private fun selectInjectionPoints(
    fuzzerSettings: FuzzerSettings,
    node: AstNode,
    injections: DivergentCounterInjections,
) {
    traverse({ n, acc -> selectInjectionPoints(fuzzerSettings, n, acc) }, node, injections)
    if (node is Statement.Compound) {
        val range = 0..node.statements.size
        val filtered = range.filter { fuzzerSettings.randomInt(100) < 30 } // 30% chance to select each index
        // Fall back to a single random index if all were filtered out, so at least one injection occurs in each compound.
        injections[node] = filtered.ifEmpty { listOf(fuzzerSettings.randomElement(range.toList())) }.toSet()
    }
}

/**
 * v0, v1
 * Injects the perturb/restore pair into every Compound at the indices selected by [selectInjectionPoints].
 */
private fun injectDivergentInjections(
    fuzzerSettings: FuzzerSettings,
    context: EntryPointContext,
    node: AstNode,
    injections: DivergentCounterInjections,
    counterWrite: Statement?,
): AstNode? =
    injections[node]?.let { indices ->
        val compound = node as Statement.Compound
        val newBody = mutableListOf<Statement>()
        for (index in 0..compound.statements.size) {
            if (index in indices) { // inject adjacent perturb/restore pair at this index
                newBody.addAll(createDivergentCounterPair(fuzzerSettings, context))
            }
            if (index < compound.statements.size) {
                val statement = compound.statements[index]
                // Insert the counter write before each `return` that leaves the entry point
                if (counterWrite != null && statement is Statement.Return) {
                    newBody.add(counterWrite.clone())
                }
                // Add original statements. Recursive, to inject into nested (compound) statements.
                newBody.add(
                    statement.clone { injectDivergentInjections(fuzzerSettings, context, it, injections, counterWrite) },
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
 * v0, v1.
 * Applies [injections], then wraps with the initial counter declaration and (if not already followed by
 * a return) a trailing counter write.
 */
private fun buildInjectedBody(
    fuzzerSettings: FuzzerSettings,
    context: EntryPointContext,
    body: Statement.Compound,
    injections: DivergentCounterInjections,
    counterWrite: Statement?,
): Statement.Compound {
    val injectedBody = body.clone { injectDivergentInjections(fuzzerSettings, context, it, injections, counterWrite) }
    val statements = mutableListOf<Statement>()
    // Declare the counter at the top of the entry point.
    statements.add(counterInstance(COUNTER_INITIAL_VALUE, context.counterName!!))
    statements.addAll(injectedBody.statements)
    // If the last statement is a return, the counter write has already been inserted before it, so don't add it again.
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
                    opaqueThreadExpr = null, // v0 injects no input buffer
                )
            val counterWrite = createCounterOverwrite(shaderJob, context)
            val body = buildInjectedBody(fuzzerSettings, context, decl.body, injections, counterWrite)
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
                    opaqueThreadExpr = { Expression.MemberLookup(Expression.Identifier(inputInstance.name), V1_STRUCT_MEMBER) }, // single thread selected to run
                    hiddenConstant = { member -> Expression.MemberLookup(Expression.Identifier(inputInstance.name), member) },
                )

            val counterWrite = createCounterWrite(context, outputBufferName = outputInstance.name)
            val body = buildInjectedBody(fuzzerSettings, context, decl.body, injections, counterWrite)
            // Only a single thread runs, all other threads return immediately
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
