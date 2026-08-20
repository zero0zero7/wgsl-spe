package com.wgslspe.core

import com.wgslfuzz.core.*

/**
 * Composes the two per-axis enumerators: each combined skeleton applies one variable combination
 * (from [VariableEnumerator]) and one function-callee combination (from [FunctionEnumerator])
 * together. This file owns no enumeration machinery of its own — candidate collection, legality
 * of replacements, and per-axis sampling all live in the per-axis files.
 */

/**
 * Shared driver: yields pairs of <one variable combination (with its characteristic vector), one
 * function combination>.
 *
 * random: pair-level rejection sampling — each draw independently picks a uniformly random
 * combination on each axis, and duplicates are rejected on the *concatenated* characteristic
 * vector. A repeated variable combination is therefore kept only when paired with a function
 * combination not yet seen alongside it (and vice versa), giving a uniform sample without
 * replacement over the product space. As on each axis, the draw count is capped at the number of
 * distinct combined skeletons that exist so the generator cannot spin forever.
 *
 * sequential: lazy lexicographic product (variable axis outer, function axis inner).
 *
 * An axis with no candidates contributes the identity (empty combination), so the composition
 * degrades to the other axis alone; only when both axes are empty is the sequence empty.
 */
private fun combinedCombinations(
    tu: TranslationUnit,
    env: ResolvedEnvironment,
    n: Int,
    random: Boolean,
): Sequence<Pair<Pair<List<Pair<AstNode, AstNode>>, List<String>>, List<Pair<AstNode, String>>>> {
    val varChoices = variableChoices(tu, env)
    val funcChoices = functionCallChoices(tu, env)
    if (varChoices.isEmpty() && funcChoices.isEmpty()) return emptySequence()

    val maxVar = varChoices.fold(1L) { acc, c -> minOf(acc * c.size, Int.MAX_VALUE.toLong()) }
    val maxFunc = funcChoices.fold(1L) { acc, c -> minOf(acc * c.size, Int.MAX_VALUE.toLong()) }
    val maxDistinct = minOf(maxVar * maxFunc, Int.MAX_VALUE.toLong()).toInt()
    println("Total combined variable x function combinations (capped at Int.MAX_VALUE): $maxDistinct")

    return if (random) {
        generateSequence { Pair(randomVarCombination(varChoices), randomCallCombination(funcChoices)) }
            .distinctBy { (v, f) -> v.second + f.map { it.second } }
            .take(minOf(n, maxDistinct))
    } else {
        enumerateCombinations(varChoices)
            .flatMap { v -> enumerateCallCombinations(funcChoices).map { f -> Pair(v, f) } }
            .take(n)
    }
}

/**
 * Counterpart of [getVariableSkeletons]/[getFunctionSkeletons] for combined replacements: each
 * result applies one variable combination and one function combination in a single clone pass.
 * The characteristic vector is the variable vector followed by the function vector.
 */
fun getCombinedSkeletons(
    tu: TranslationUnit,
    env: ResolvedEnvironment,
    n: Int = Int.MAX_VALUE,
    random: Boolean = true,
): Sequence<Pair<TranslationUnit, List<String>>> =
    combinedCombinations(tu, env, n, random).map { (v, f) ->
        val (varCombination, varVect) = v
        Pair(
            // Function call replacements
            applyCalleeReplacements(tu, f.toMap(), leafReplacements = varCombination.toMap()), 
            varVect + f.map { it.second },
        )
    }

/**
 * Like [getCombinedSkeletons], but yields the list of *edits* (original node, replacement name)
 * instead of a re-serialized [TranslationUnit], mirroring [getVariableSkeletonEdits] and
 * [getFunctionSkeletonEdits]. Variable-usage tokens and callee tokens are disjoint spans in the
 * original text, so the two edit lists concatenate safely for splicing.
 */
fun getCombinedSkeletonEdits(
    tu: TranslationUnit,
    env: ResolvedEnvironment,
    n: Int = Int.MAX_VALUE,
    random: Boolean = true,
): Sequence<Pair<List<Pair<AstNode, String>>, List<String>>> =
    combinedCombinations(tu, env, n, random).map { (v, f) ->
        val (varCombination, varVect) = v
        val varEdits = varCombination.mapIndexed { i, pair -> pair.first to varVect[i] }
        Pair(varEdits + f, varVect + f.map { it.second })
    }
