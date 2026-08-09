package com.wgslfuzz.semanticspreservingtransformations

import com.wgslfuzz.core.AstNode
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
 * 50% chance of selecting each existing local var as target, but at least one is always selected.
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

    fun recursiveInjectTargetModifiers(
        context: EntryPointContext,
        compoundInfo: CompoundInfo,
        scopes: Map<Statement.Compound, CompoundInfo>,
        injections: Map<Statement.Compound, List<LocalVariableTarget>>,
        ancestorTargets: List<LocalVariableTarget> = emptyList(),
    ): Statement.Compound {
        val compound = compoundInfo.compound
        val currentTargets = injections[compound] ?: emptyList()
        val qualifiedTargets = ancestorTargets + currentTargets

        // Suitable targets = targets in an outer scope + targets in this scope declared before
        // the statement being descended into.
        fun targetsVisible(statementIndex: Int): List<LocalVariableTarget> =
            ancestorTargets +
                currentTargets.filter { target ->
                    target.declIndex != null && target.declIndex < statementIndex
                }
        
        // Recursively inject into a node, if it is a compound statement, using the targets visible at the given statement index.
        fun injectInto(
            node: AstNode,
            statementIndex: Int,
        ): Statement.Compound? =
            (node as? Statement.Compound)?.let { scopes[it] }?.let { nestedInfo ->
                recursiveInjectTargetModifiers(context, nestedInfo, scopes, injections, targetsVisible(statementIndex))
            }

        val newStatements = mutableListOf<Statement>()

        // ----- No suitable declaration in this scope -----
        // Traversal must continue: a nested scope may have targets. Return a cloned scope.
        if (qualifiedTargets.isEmpty()) {
            compound.statements.forEachIndexed { statementIndex, statement ->
                newStatements.add(
                    // Clone the entire compound statement, but with a transformation that recurses into nested compounds and adds injections to them if previously computed.
                    statement.clone { node -> injectInto(node, statementIndex) },
                )
            }
            return Statement.Compound(newStatements, compound.metadata)
        }

        // ----- There are suitable modification targets -----
        // Pick a random target and two random indices,
        // - keep both after the declaration when the target is declared in this scope
        // - keep both before the read if target is read in this scope
        val target = fuzzerSettings.randomElement(qualifiedTargets)
        val perturbation = choosePerturbation(fuzzerSettings, context, target.targetType)
        if (perturbation == null) {
            // No template applies to this scalar type (floats, while they are deferred). 
            // Recurse into nested scopes but inject nothing here.
            compound.statements.forEachIndexed { statementIndex, statement ->
                newStatements.add(
                    statement.clone { node -> injectInto(node, statementIndex) },
                )
            }
            return Statement.Compound(newStatements, compound.metadata)
        }
        val id = fuzzerSettings.getUniqueId()
        val lowestIndex =
            if (target.declCompound == compound) { // target is declared in this scope, so the perturbation must be after it
                assert(target.declIndex != null)
                (target.declIndex ?: -1) + 1
            } else {
                0
            }
        // The target is read in this scope, so both statements must land before the first such read.
        // Reads before lowestIndex cannot refer to this declaration (refer to outer variable), so not of concern here.
        val firstReadIndex =
            compoundInfo.reads
                .filter{ (name, index) -> name == lhsBaseIdentifierName(target.target) && index >= lowestIndex }
                .minOfOrNull { (_, index) -> index }
        // Nothing may be injected at or after a disqualifying exit
        val highestIndex =
            minOf(
                firstReadIndex ?: compound.statements.size,
                compoundInfo.exitIndex ?: compound.statements.size,
            )
        // +1 to include highestIndex as the max legal index for injection
        val index1: Int = fuzzerSettings.randomInt(lowestIndex, highestIndex + 1)
        val index2: Int = fuzzerSettings.randomInt(lowestIndex, highestIndex + 1)

        // Both statements carry the SAME id, so the reducer deletes them together or not at all,
        // and ONE condition template supplies both guards, so they cannot disagree.
        val guards = chooseConditionTemplate(fuzzerSettings, context)
        val perturbStatement =
            perturbationStatement(
                guard = guards.perturbGuard(context),
                target = target.target,
                newValue = perturbation.perturb(target.target),
                id = id,
                commentary = "divergent perturbation: ${perturbation.commentary} under ${guards.commentary}",
            )
        val restoreStatement =
            perturbationStatement(
                guard = guards.restoreGuard(context),
                target = target.target,
                newValue = perturbation.restore(target.target),
                id = id,
                commentary = "divergent restore",
            )

        // Inject the new statements, cloning the existing ones over around them.
        for (i in 0..compound.statements.size) {
            if (i == min(index1, index2)) newStatements.add(perturbStatement)
            if (i == max(index1, index2)) newStatements.add(restoreStatement)
            if (i < compound.statements.size) {
                newStatements.add(
                    compound.statements[i].clone { node -> injectInto(node, i) },
                )
            }
        }
        return Statement.Compound(newStatements, compound.metadata)
    }

    val newGlobalDecls =
        mapComputeFunctions(shaderJob.tu.globalDecls) { decl ->
            val (compoundInfo, candidates) = findLocalVariableCandidates(shaderJob, decl.body)
            if (candidates.isEmpty()) return@mapComputeFunctions decl
            val scopes = compoundInfo.scopesByCompound()
            val selected = selectLocalVariableTargets(fuzzerSettings, candidates)
            // Group the selected targets by their declaring compound.
            val injectionsByCompound = selected.groupBy { target -> target.declCompound }

            val (lidExpr, parameters) = getLidExpr(shaderJob, fuzzerSettings, decl)
            val context =
                EntryPointContext(
                    lidExpr = lidExpr,
                    parameters = parameters,
                    counterName = null, // v2 hijacks an existing var
                    opaqueThreadExpr = { Expression.MemberLookup(Expression.Identifier(inputBuffer.name), V2_STRUCT_MEMBER) },
                    hiddenConstant = { member -> Expression.MemberLookup(Expression.Identifier(inputBuffer.name), member) },
                )

            // Recursively, starting from the outermost scope ie. the entry point body.
            // - For each compound, amongst all targets selected for injection, obtain the subset that are declared in that compound -> `currentTargets`
            // - If there are suitable targets (current + ancestor, filtered by index), pick randomly and perform injection in that compound, else recurse into nested compounds.
            val newBody = recursiveInjectTargetModifiers(context, compoundInfo, scopes, injectionsByCompound)
            // Gate entry point to a single thread. 
            val gatedBody =
                Statement.Compound(
                    listOf(
                        Statement.If(
                            condition = singleThreadCondition(context.lid(), context.opaque()!!, equals = false),
                            thenBranch = Statement.Compound(listOf(Statement.Return(null))),
                        ),
                    ) + newBody.statements,
                    newBody.metadata,
                )

            decl.withParametersAndBody(context.parameters, gatedBody)
        }
    return rebuildShaderJob(shaderJob, newGlobalDecls, listOf(inputStruct, inputBuffer))
}
