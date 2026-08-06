package com.wgslfuzz.semanticspreservingtransformations

import com.wgslfuzz.core.BinaryOperator
import com.wgslfuzz.core.Expression
import com.wgslfuzz.core.ShaderJob
import com.wgslfuzz.core.Statement
import com.wgslfuzz.core.clone
import kotlin.math.max
import kotlin.math.min

// v2: instead of synthesising a counter, hijack a local `var` the entry point already declares.
// The perturb and restore statements are no longer adjacent -- real code sits between them -- so
// the variable is genuinely live program state rather than a throwaway diagnostic.

/**
 * 50% chance of selecting each candidate, but at least one is always selected.
 */
private fun selectLocalVariableTargets(
    fuzzerSettings: FuzzerSettings,
    candidates: List<LocalVariableTarget>,
): List<LocalVariableTarget> {
    require(candidates.isNotEmpty()) { "List of local variable targets must not be empty" }
    val filtered = candidates.filter { fuzzerSettings.injectDivergentCounter() }
    return filtered.ifEmpty {
        listOf(fuzzerSettings.randomElement(candidates))
    }
}

internal fun applyV2(
    shaderJob: ShaderJob,
    fuzzerSettings: FuzzerSettings,
): ShaderJob {
    val (inputBinding, _) = nextTwoBindings(shaderJob)
    val inputStruct = dataStruct(fuzzerSettings.getUniqueId())
    val inputBuffer = threadToRunInputInstance(inputBinding, inputStruct.name)
    var requireGate = true

    fun recursiveInjectLocalVariableModifiers(
        context: EntryPointContext,
        compound: Statement.Compound,
        injections: Map<Statement.Compound, List<Pair<LocalVariableTarget, Int>>>,
        ancestorTargets: List<Pair<LocalVariableTarget, Int>> = emptyList(),
    ): Statement.Compound {
        val currentTargets = injections[compound] ?: emptyList()
        val qualifiedTargets = ancestorTargets + currentTargets

        // Suitable targets = targets in an outer scope + targets in this scope declared before
        // the statement being descended into.
        fun targetsVisibleInside(statementIndex: Int): List<Pair<LocalVariableTarget, Int>> =
            ancestorTargets +
                currentTargets.filter { (target, _) ->
                    target.declIndex != null && target.declIndex < statementIndex
                }

        val newStatements = mutableListOf<Statement>()
        if (requireGate) {
            newStatements.add(
                0,
                Statement.If(
                    condition = singleThreadCondition(context.lid(), context.opaque()!!, equals = false),
                    thenBranch = Statement.Compound(listOf(Statement.Return(null))),
                ),
            )
            requireGate = false
        }

        // ----- No suitable declaration in this scope -----
        // Traversal must continue: a nested scope may have targets. Return a cloned scope.
        if (qualifiedTargets.isEmpty()) {
            compound.statements.forEachIndexed { statementIndex, statement ->
                newStatements.add(
                    statement.clone { node ->
                        if (node is Statement.Compound) {
                            recursiveInjectLocalVariableModifiers(
                                context,
                                node,
                                injections,
                                targetsVisibleInside(statementIndex),
                            )
                        } else {
                            null
                        }
                    },
                )
            }
            return Statement.Compound(newStatements, compound.metadata)
        }

        // ----- There are suitable modification targets -----
        // Pick a random target and two random indices, keeping both after the declaration when
        // the target is declared in this scope.
        val (target, id) = fuzzerSettings.randomElement(qualifiedTargets)
        val lowestIndex =
            if (target.declCompound == compound) {
                assert(target.declIndex != null)
                (target.declIndex ?: -1) + 1
            } else {
                0
            }
        val index1: Int = fuzzerSettings.randomInt(lowestIndex, compound.statements.size + 1)
        val index2: Int = fuzzerSettings.randomInt(lowestIndex, compound.statements.size + 1)

        val fixCondition = singleThreadCondition(context.lid(), context.opaque()!!, equals = true)
        val unfixCondition = singleThreadCondition(context.lid(), context.opaque()!!, equals = true)
        val unfixStatement =
            modificationStatement(
                unfixCondition,
                target.target,
                Expression.Binary(BinaryOperator.PLUS, lhsExprToExpr(target.target), Expression.IntLiteral("10")),
                id,
            )
        val fixStatement =
            modificationStatement(
                fixCondition,
                target.target,
                Expression.Binary(BinaryOperator.MINUS, lhsExprToExpr(target.target), Expression.IntLiteral("10")),
                id,
            )

        // Inject the new statements, cloning the existing ones over around them.
        for (i in 0..compound.statements.size) {
            if (i == min(index1, index2)) newStatements.add(fixStatement)
            if (i == max(index1, index2)) newStatements.add(unfixStatement)
            if (i < compound.statements.size) {
                newStatements.add(
                    compound.statements[i].clone { node ->
                        if (node is Statement.Compound) {
                            recursiveInjectLocalVariableModifiers(
                                context,
                                node,
                                injections,
                                targetsVisibleInside(i),
                            )
                        } else {
                            null
                        }
                    },
                )
            }
        }
        return Statement.Compound(newStatements, compound.metadata)
    }

    val newGlobalDecls =
        mapComputeFunctions(shaderJob.tu.globalDecls) { decl ->
            val candidates = findLocalVariableCandidates(shaderJob, decl.body)
            if (candidates.isEmpty()) return@mapComputeFunctions decl
            val selected = selectLocalVariableTargets(fuzzerSettings, candidates)
            // Group the selected targets by their declaring compound.
            val injectionsByCompound =
                selected
                    .map { it to fuzzerSettings.getUniqueId() }
                    .groupBy({ (target, _) -> target.declCompound }, { it })

            val (lidExpr, parameters) = getLidExpr(shaderJob, fuzzerSettings, decl)
            val context =
                EntryPointContext(
                    lidExpr = lidExpr,
                    parameters = parameters,
                    counterName = null, // v2 hijacks an existing var
                    opaqueI32 = { Expression.MemberLookup(Expression.Identifier(inputBuffer.name), V2_STRUCT_MEMBER) },
                )

            // Recursively, starting from the outermost scope ie. the entry point body.
            val newBody = recursiveInjectLocalVariableModifiers(context, decl.body, injectionsByCompound)

            decl.withParametersAndBody(context.parameters, newBody)
        }
    return rebuildShaderJob(shaderJob, newGlobalDecls, listOf(inputStruct, inputBuffer))
}
