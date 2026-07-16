package com.wgslspe.core

import com.wgslfuzz.core.*
import io.ktor.util.reflect.instanceOf

/**
 * A candidate expression for skeletal replacement, paired with its concrete type and the scope
 * available at the point where the expression appears.
 */
data class SkeletalCandidate(val identifier: AstNode, val type: Type, val scope: Scope, val overrides: Boolean = false)

/**
 * Returns all variables declaration nodes and usage nodes in [tu] as [SkeletalCandidate]s, each capturing the
 * expression, its concrete type, and the scope visible at that expression.
 */
fun collectSkeletalCandidates(
    tu: TranslationUnit,
    env: ResolvedEnvironment,
): Pair<List<SkeletalCandidate>, List<SkeletalCandidate>> {
    val declarations = mutableListOf<SkeletalCandidate>()
    val usages = mutableListOf<SkeletalCandidate>()
    collectCandidatesFromNode(tu, NodeRole.None(), null, env, declarations, usages)
    return Pair(declarations, usages)
}

sealed class NodeRole {
    class Decl(val overridable: Boolean) : NodeRole() // whether declared variable's value can be re-writen
    class Usage(val overrides: Boolean) : NodeRole() // whether the usage rewrites the variable's value
    class None : NodeRole()
}

private fun collectCandidatesFromNode(
    node: AstNode,
    role: NodeRole,
    enclosingStatement: Statement?,
    env: ResolvedEnvironment,
    declarations: MutableList<SkeletalCandidate>,
    usages: MutableList<SkeletalCandidate>
) {
    val currentStatement: Statement? = if (node is Statement) node else enclosingStatement
    val scope: Scope = currentStatement?.let { env.scopeAvailableBefore(it) } ?: env.globalScope

    fun recurse(child: AstNode, childRole: NodeRole) =
        collectCandidatesFromNode(child, childRole, currentStatement, env, declarations, usages)

    fun addDecl(n: AstNode, rawType: Type, overridable: Boolean = true) =
        declarations.add(SkeletalCandidate(n, defaultConcretizationOf(rawType), scope))

    fun addUsage(n: AstNode, rawType: Type, overrides: Boolean = false) =
        usages.add(SkeletalCandidate(n, defaultConcretizationOf(rawType), scope, overrides))

    when (node) {
        // Identifier leaves: Expression.Identifier is always a usage; LhsExpression.Identifier
        // is a decl when written to (assignment/increment/decrement) and a usage otherwise.
        is Expression.Identifier ->
            addUsage(node, env.typeOf(node), (role as NodeRole.Usage).overrides)
        is LhsExpression.Identifier -> when (role) {
            is NodeRole.Decl -> addDecl(node, env.typeOf(node))
            is NodeRole.Usage -> addUsage(node, env.typeOf(node), role.overrides)
            is NodeRole.None -> {}
        }

        // Variable/value declarations: the declaration node is a Decl(true) candidate; its
        // initializer subtree is traversed for usages. When there is no initializer the type
        // is read directly from the type annotation.
        is Statement.Value -> {
            addDecl(node, env.typeOf(node.initializer))
            recurse(node.initializer, NodeRole.Usage(false))
        }
        is Statement.Variable -> {
            val init = node.initializer
            if (init != null) {
                addDecl(node, env.typeOf(init))
                recurse(init, NodeRole.Usage(false))
            } else {
                addDecl(node, node.typeDecl!!.toType(scope, env))
            }
        }
        is GlobalDecl.Variable -> {
            val init = node.initializer
            if (init != null) {
                addDecl(node, env.typeOf(init))
                recurse(init, NodeRole.Usage(false))
            } else {
                addDecl(node, node.typeDecl!!.toType(scope, env))
            }
        }
        is GlobalDecl.Constant -> {
            val init = node.initializer
            addDecl(node, env.typeOf(init), false)
            recurse(init, NodeRole.Usage(false))
        }

        // Assignment statements: the write target is a Usage(true) candidate; the rhs is Usage(false).
        is Statement.Assignment -> {
            node.lhsExpression?.let { recurse(it, NodeRole.Usage(true)) }
            recurse(node.rhs, NodeRole.Usage(false))
        }
        is Statement.Increment -> recurse(node.target, NodeRole.Usage(true))
        is Statement.Decrement -> recurse(node.target, NodeRole.Usage(true))

        // LhsExpression wrappers: propagate the incoming role to the inner target so that
        // the leaf LhsExpression.Identifier ends up with the correct Decl(true)/Usage(false) classification.
        is LhsExpression.Paren -> recurse(node.target, role)
        is LhsExpression.MemberLookup -> recurse(node.receiver, role)
        is LhsExpression.Dereference -> recurse(node.target, role)
        is LhsExpression.AddressOf -> recurse(node.target, role)
        is LhsExpression.IndexLookup -> {
            recurse(node.target, role)
            recurse(node.index, NodeRole.Usage(false))
        }

        // Expression nodes: all sub-expressions are in a usage context.
        is Expression.Binary -> {
            recurse(node.lhs, NodeRole.Usage(false))
            recurse(node.rhs, NodeRole.Usage(false))
        }
        is Expression.Unary -> recurse(node.target, NodeRole.Usage(false))
        is Expression.Paren -> recurse(node.target, NodeRole.Usage(false))
        is Expression.MemberLookup -> recurse(node.receiver, NodeRole.Usage(false))
        is Expression.IndexLookup -> {
            recurse(node.target, NodeRole.Usage(false))
            recurse(node.index, NodeRole.Usage(false))
        }
        is Expression.FunctionCall -> node.args.forEach { recurse(it, NodeRole.Usage(false)) }
        is Expression.ValueConstructor -> {
            if (node is Expression.ArrayValueConstructor) {
                node.elementCount?.let { recurse(it, NodeRole.Usage(false)) }
            }
            node.args.forEach { recurse(it, NodeRole.Usage(false)) }
        }
        is Expression.BoolLiteral, is Expression.FloatLiteral, is Expression.IntLiteral -> {}

        // Control-flow statements: expressions in conditions are Usage(false); compound bodies
        // are None so that the statements inside self-classify.
        is Statement.If -> {
            recurse(node.condition, NodeRole.Usage(false))
            recurse(node.thenBranch, NodeRole.None())
            node.elseBranch?.let { recurse(it, NodeRole.None()) }
        }
        is Statement.While -> {
            recurse(node.condition, NodeRole.Usage(false))
            recurse(node.body, NodeRole.None())
        }
        is Statement.For -> {
            node.init?.let { recurse(it, NodeRole.None()) }
            node.condition?.let { recurse(it, NodeRole.Usage(false)) }
            node.update?.let { recurse(it, NodeRole.None()) }
            recurse(node.body, NodeRole.None())
        }
        is Statement.Loop -> {
            recurse(node.body, NodeRole.None())
            node.continuingStatement?.let { recurse(it, NodeRole.None()) }
        }
        is Statement.Switch -> {
            recurse(node.expression, NodeRole.Usage(false))
            node.clauses.forEach { recurse(it, NodeRole.None()) }
        }
        is Statement.Return -> node.expression?.let { recurse(it, NodeRole.Usage(false)) }
        is Statement.FunctionCall -> node.args.forEach { recurse(it, NodeRole.Usage(false)) }
        is Statement.ConstAssert -> recurse(node.expression, NodeRole.Usage(false))
        is Statement.Compound -> node.statements.forEach { recurse(it, NodeRole.None()) }
        is Statement.Break, is Statement.Continue, is Statement.Discard, is Statement.Empty -> {}

        is ContinuingStatement -> {
            node.statements.statements.forEach { recurse(it, NodeRole.None()) }
            node.breakIfExpr?.let { recurse(it, NodeRole.Usage(false)) }
        }
        is SwitchClause -> {
            node.caseSelectors.forEach { it?.let { expr -> recurse(expr, NodeRole.Usage(false)) } }
            recurse(node.compoundStatement, NodeRole.None())
        }

        // Global declarations: functions and non-variable globals recurse but are not
        // themselves Decl(true) candidates.
        is GlobalDecl.Function -> recurse(node.body, NodeRole.None())
        is GlobalDecl.Override -> {
            val init = node.initializer
            if (init != null) {
                addDecl(node, env.typeOf(init), false)
                recurse(init, NodeRole.Usage(false))
            } else {
                addDecl(node, node.typeDecl!!.toType(scope, env), false)
            }
        }
        is GlobalDecl.ConstAssert -> recurse(node.expression, NodeRole.Usage(false))
        is GlobalDecl.Struct, is GlobalDecl.TypeAlias, is GlobalDecl.Empty -> {}

        is TranslationUnit -> node.globalDecls.forEach { recurse(it, NodeRole.None()) }

        // All Attribute, All TypeDecl, Directive, ParameterDecl, StructMember
        else -> {}
    }
}

// Strips a Reference wrapper to get the underlying store type for equality comparisons.
private fun valueTypeOf(type: Type): Type =
    when (type) {
        is Type.Reference -> type.storeType
        else -> type
    }


private fun addrAccessFilter(candidate: Type, original: Type): Boolean {
    fun spaceAndMode(type: Type) = when (type) {
        is Type.Reference -> type.addressSpace to type.accessMode
        is Type.Pointer   -> type.addressSpace to type.accessMode
        else              -> null
    }
    val (cSpace, cMode) = spaceAndMode(candidate) ?: return true
    val (oSpace, oMode) = spaceAndMode(original) ?: return true
    return cSpace >= oSpace && cMode >= oMode
}
    // Needs to trace both candidate and original back to their declaration to identify their respective addressSpace and accessMode
//    fun helper(node: AstNode): Pair<AddressSpace?, AccessMode?> {
//        return when (node) {
//            is TypeDecl.Pointer -> Pair(node.addressSpace, node.accessMode)
//            is GlobalDecl.Variable -> Pair(node.addressSpace, node.accessMode)
//            is Statement.Variable -> Pair(node.addressSpace, node.accessMode)
//            is TypeDecl.TextureStorage1D -> Pair(null, node.accessMode)
//            is TypeDecl.TextureStorage2D -> Pair(null, node.accessMode)
//            is TypeDecl.TextureStorage2DArray -> Pair(null, node.accessMode)
//            is TypeDecl.TextureStorage3D -> Pair(null, node.accessMode)
//            else -> Pair(null, null)
//        }
//    }
//    fun accessHelper(originalAccess: AccessMode?, candidateAccess: AccessMode?) =
//        if (originalAccess == null) candidateAccess == null
//        else candidateAccess != null && candidateAccess >= originalAccess
//
//    val (candAddr, canAcc) = helper(candidate)
//    val (originalAddr, originalAcc) = helper(original)
//    println("$candidate, $candAddr, $canAcc")
//    println("$original, $originalAddr, $originalAcc")
//    return candAddr == originalAddr
//            && accessHelper(originalAcc, canAcc)


// Returns names of all value declarations in [scope] whose store type matches [targetType].
// If [overrides] is true, only mutable var declarations are returned.
private fun suitableVariables(
    scope: Scope,
    targetType: Type,
    overrides: Boolean,
): List<String> {
    val targetValueType = defaultConcretizationOf(valueTypeOf(targetType))
    return scope
        .getAllEntries()
        .filterIsInstance<ScopeEntry.TypedDecl>()
        .filter { it !is ScopeEntry.Struct && it !is ScopeEntry.TypeAlias }
        .filter { entry -> defaultConcretizationOf(valueTypeOf(entry.type)) == targetValueType }
        // When usage's overrides=true (write context), only mutable var declarations are valid replacements. Parameters (non-pointer), let bindings, const, and override declarations are all immutable in WGSL.
        .filter { entry -> !overrides || entry is ScopeEntry.LocalVariable || entry is ScopeEntry.GlobalVariable }
        .filter{ entry -> addrAccessFilter(entry.type, targetType) }
        .map { it.declName }
}


// Per-usage-site replacement options: each site maps to pairs of <original usage node, replacement
// identifier node>. Sites with no suitable variable are dropped.
// internal: also consumed by CombinedEnumerator.
internal fun variableChoices(
    tu: TranslationUnit,
    env: ResolvedEnvironment,
): List<List<Pair<AstNode, AstNode>>> {
    val (_, usages) = collectSkeletalCandidates(tu, env)
    return usages
        .map { (node, concreteType, scope, overrides) ->
            suitableVariables(scope, concreteType, overrides).map { varName -> node to node.cloneWithName(varName) }
        }
        .filter { it.isNotEmpty() } // [(usage1, cloned11), (usage1, cloned12), ...] repeat for each usage
}

fun getVariableSkeletons(
    tu: TranslationUnit,
    env: ResolvedEnvironment,
    n: Int = Int.MAX_VALUE,
    random: Boolean = true,
): Sequence<Pair<TranslationUnit, List<String>>> {
    val choices = variableChoices(tu, env)
    if (choices.isEmpty()) return emptySequence()

    val tmp = choices.fold(1L) { acc, list -> minOf(acc * list.size, Int.MAX_VALUE.toLong()) }
    println("Total combinations (capped at Int.MAX_VALUE): $tmp")

    return if (random) {
        nRandomSkeletons(tu, env, n, choices)
    }
    else {
        allReplacementSkeletons(tu, env, n, choices)
    }
}

/**
 * Like [getVariableSkeletons], but instead of producing a re-serialized [TranslationUnit]
 * per skeleton, yields the list of *edits* to apply to the original source text.
 * Each edit is (the original usage node, the replacement variable name). The
 * usage node carries a [com.wgslfuzz.core.SourceSpan] (attached at parse time),
 * so a caller can splice the new name over that span in the original text and
 * leave every other byte — and therefore the input's exact dialect/formatting —
 * intact. This avoids the [com.wgslfuzz.core.AstWriter] round-trip, which would
 * re-emit the whole program in a dialect wgslsmith's parser rejects.
 *
 * The original [getVariableSkeletons] is unchanged; this is an additional entry point.
 */
fun getVariableSkeletonEdits(
    tu: TranslationUnit,
    env: ResolvedEnvironment,
    n: Int = Int.MAX_VALUE,
    random: Boolean = true,
): Sequence<Pair<List<Pair<AstNode, String>>, List<String>>> {
    val choices = variableChoices(tu, env)
    if (choices.isEmpty()) return emptySequence()

    val maxDistinct = choices.fold(1L) { acc, c -> minOf(acc * c.size, Int.MAX_VALUE.toLong()) }.toInt()
    val combinations =
        if (random) {
            generateSequence { getRandomCombination(choices) }
                .distinctBy { it.second }
                .take(minOf(n, maxDistinct))      // <- can't ask for more than exist
        } else {
            enumerateCombinations(choices).take(n)
        }

    // val combinations =
    //     if (random) {
    //         generateSequence { getRandomCombination(choices) }.distinctBy { it.second }.take(n)
    //     } else {
    //         enumerateCombinations(choices).take(n)
    //     }

    // combination[i].first is the original usage node for usage i; charVect[i] is
    // its chosen replacement name — both indexed in the same usage order.
    return combinations.map { (combination, charVect) ->
        combination.mapIndexed { i, pair -> pair.first to charVect[i] } to charVect
    }
}

/**
 * Lazily enumerates (hence Sequence over List) all skeletal variants of [tu] produced by simultaneously replacing between
 * 1 and [maxReplacements] candidate expressions with in-scope variables of matching type.
 *
 * For every non-empty subset of candidates (up to [maxReplacements] elements), and every
 * combination of valid variable choices for each selected candidate, one [TranslationUnit] is
 * emitted with all chosen replacements applied at once.
 */
fun allReplacementSkeletons(
    tu: TranslationUnit,
    env: ResolvedEnvironment,
    maxSkeletons: Int = Int.MAX_VALUE,
    choices: List<List<Pair<AstNode, AstNode>>>,
): Sequence<Pair<TranslationUnit, List<String>>>  =
    enumerateCombinations(choices).take(maxSkeletons).map { (combination, charVect) ->
        val replacementMap = combination.toMap()
        Pair(tu.clone { node -> replacementMap[node] }, charVect)
    }


fun nRandomSkeletons(
    tu: TranslationUnit,
    env: ResolvedEnvironment,
    n: Int,
    choices: List<List<Pair<AstNode, AstNode>>>
): Sequence<Pair<TranslationUnit, List<String>>> {
    // Cap at the number of distinct combinations that exist; otherwise, when the
    // shader admits fewer than n distinct skeletons, distinctBy().take(n) can never
    // reach n and spins on the infinite generateSequence forever.
    val maxDistinct = choices.fold(1L) { acc, c -> minOf(acc * c.size, Int.MAX_VALUE.toLong()) }.toInt()
    return generateSequence { getRandomCombination(choices) }
    .distinctBy { it.second } // prevents duplicates if n is large
    .take(minOf(n, maxDistinct))
    .map { (combination, charVect) ->
        val replacementMap = combination.toMap()
        Pair(tu.clone { node -> replacementMap[node] }, charVect)
    }
}


/**
 * Yields one replacement per usage across all combinations.
 *
 * @return a sequence of combinations, where each combination is a pair of:
 *   - a list of (original, replacement) node pairs for one skeleton
 *   - a characteristic vector of usage nodes represented as a list of Strings for the skeleton given the chosen replacements
 */
internal fun enumerateCombinations(
    choices: List<List<Pair<AstNode, AstNode>>>,
): Sequence<Pair<List<Pair<AstNode, AstNode>>, List<String>>> {

    fun enumerateCombinationsFrom(
        usageIdx: Int,
        combi: List<Pair<AstNode, AstNode>>,
        charVect : MutableList<String>
    ): Sequence<Pair<List<Pair<AstNode, AstNode>>, List<String>>> = sequence {
        // Yield full combi
        if (usageIdx == choices.size) {
            yield(Pair(combi, charVect.toList())) // .toList() is required so that a copy of the current state of charVect is returned, else, it would be overwritten in future iterations (combinations). If .toList() instead of .take() is used to extract the pairs returned by enumerateCombinations(), charVect for all combinations would be the same despite the TU being diverse
            return@sequence
        }
        for (choice in choices[usageIdx]) { // choice is the Pair<usageNode, replacementNode>
            val replacement = choice.second
            when (replacement) {
                is LhsExpression.Identifier -> charVect[usageIdx] = replacement.name
                is Expression.Identifier -> charVect[usageIdx] = replacement.name
                else -> throw IllegalStateException("Unexpected. Replacement should be LhsExpr.Id or Expr.Id, not $replacement")
            }

            yieldAll(enumerateCombinationsFrom(usageIdx + 1, combi + choice, charVect))
        }
    }
    return enumerateCombinationsFrom(0, emptyList(), MutableList(choices.size){""})
}


/**
 * Yields one random replacement per usage across all combinations.
 */
internal fun getRandomCombination(
    choices: List<List<Pair<AstNode, AstNode>>>,
): Pair<List<Pair<AstNode, AstNode>>, List<String>> {
    val combination = mutableListOf<Pair<AstNode, AstNode>>()
    val charVect = mutableListOf<String>()

    var usageIdx = 0
    while (usageIdx < choices.size) {
        val picked = choices[usageIdx].random()
        combination.add(picked)
        val name = when (val replacement = picked.second) {
            is LhsExpression.Identifier -> replacement.name
            is Expression.Identifier -> replacement.name
            else -> throw IllegalStateException("Unexpected replacement: $replacement")
        }
        charVect.add(name)
        usageIdx++
    }
    return Pair(combination, charVect)
}