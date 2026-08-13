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

/**
 * Picks the inclusive index range within [compoundInfo]'s compound that the perturb/restore pair must both land in.
 *
 * Perturb is inserted before `statements[min]` and restore before `statements[max]`,
 * so statement `i` executes BETWEEN them exactly when `min <= i < max`. 
 * Nothing that would break the pair may sit there (a read of the target, or an escape from the compound)
 *
 * The boundaries cut `[lowestIndex, statements.size]` into segments; 
 * a pair drawn from one segment cannot straddle. 
 * An index EQUAL to a boundary is legal -- it places the statement immediately before the boundary,
 * so consecutive boundaries `b1 < b2` yield the segment `[b1 + 1, b2]`.
 *
 * A boundary that IS a bare jump -- `break;`, `continue;`, `return;`, `discard;` as the statement
 * itself rather than something containing one -- also ends the search: everything after it is
 * unreachable, and a pair injected there would be dead code that exercises nothing.
 *
 * There is always at least one segment: the first boundary is at or after [lowestIndex], 
 * so the segment ending at it is non-empty, and with no boundaries at all the whole range is one segment.
 */
private fun chooseInjectionSegment(
    fuzzerSettings: FuzzerSettings,
    compoundInfo: CompoundInfo,
    target: LocalVariableTarget,
    lowestIndex: Int,
): Pair<Int, Int> {
    val statements = compoundInfo.compound.statements
    val targetName = lhsBaseIdentifierName(target.target)
    // boundaries are indices of statements that either escape the compound or read/write the target.
    val boundaries =
        (lowestIndex until statements.size)
            .filter { index ->
                index in compoundInfo.escapeIndices ||
                    (targetName != null && mentionsIdentifier(statements[index], targetName))
            }

    val segments = mutableListOf<Pair<Int, Int>>()
    var segmentLow = lowestIndex
    var reachable = true
    for (boundary in boundaries) {
        if (segmentLow <= boundary) {
            segments.add(segmentLow to boundary)
        }
        segmentLow = boundary + 1
        // boundary is captured by escapeIndices. But it could be an exit given a particular condition in the subtree, so the statements after it are still reachable. Only a bare jump itself makes the rest unreachable, making it deadcode that is pointless to inject into.
        // TODO: if want to innject into deadcode, remove the if (isBareJump(...)) check, as well as the if(reachable) check after the loop.
        if (isBareJump(statements[boundary])) {
            reachable = false
            break
        }
    }
    // An unreachable segment is still a legal injection site, though technically it is dead code that exercises nothing.
    if (reachable) {
        segments.add(segmentLow to statements.size)
    }
    return fuzzerSettings.randomElement(segments)
}

internal fun applyV2(
    shaderJob: ShaderJob,
    fuzzerSettings: FuzzerSettings,
): ShaderJob? {
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
        val template = chooseTemplate(fuzzerSettings, context, target.targetType)
        if (template == null) {
            // No template applies to this scalar type -- f16, with the snapshot template disabled.
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
        val (segmentLow, segmentHigh) = chooseInjectionSegment(fuzzerSettings, compoundInfo, target, lowestIndex)
        // +1 to include segmentHigh as the max legal index for injection
        val index1: Int = fuzzerSettings.randomInt(segmentLow, segmentHigh + 1)
        val index2: Int = fuzzerSettings.randomInt(segmentLow, segmentHigh + 1)

        // Every statement carries the SAME id, so the reducer deletes them together or not at all,
        // and ONE condition template supplies both guards, so they cannot disagree.
        val guards = chooseConditionTemplate(fuzzerSettings, context)
        val pair = template.build(guards, context, target.target, id)

        // Inject the new statements, cloning the existing ones over around them.
        // A template needing a temporary contributes its declaration at the head of atPerturbIndex, so
        // it lands in THIS compound, ahead of and outside both guards -- a declaration inside a
        // guard's `then` block would not be in scope at the restore.
        for (i in 0..compound.statements.size) {
            if (i == min(index1, index2)) newStatements.addAll(pair.atPerturbIndex)
            if (i == max(index1, index2)) newStatements.addAll(pair.atRestoreIndex)
            if (i < compound.statements.size) {
                newStatements.add(
                    compound.statements[i].clone { node -> injectInto(node, i) },
                )
            }
        }
        return Statement.Compound(newStatements, compound.metadata)
    }

    var injected = false
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

            injected = true
            decl.withParametersAndBody(context.parameters, gatedBody)
        }
    if (!injected) return null
    return rebuildShaderJob(shaderJob, newGlobalDecls, listOf(inputStruct, inputBuffer))
}
