package com.wgslspe.core

import com.wgslfuzz.core.*

/**
 * A candidate expression for skeletal replacement, paired with its concrete type and the scope
 * available at the point where the expression appears.
 */
data class SkeletalCandidate(val identifier: AstNode, val type: Type, val scope: Scope)

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
    collectCandidatesFromNode(tu, NodeRole.NONE, null, env, declarations, usages)
    return Pair(declarations, usages)
}

private enum class NodeRole { DECL, USAGE, NONE }

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

    fun addDecl(n: AstNode, rawType: Type) =
        declarations.add(SkeletalCandidate(n, defaultConcretizationOf(rawType), scope))

    fun addUsage(n: AstNode, rawType: Type) =
        usages.add(SkeletalCandidate(n, defaultConcretizationOf(rawType), scope))

    when (node) {
        // Identifier leaves: Expression.Identifier is always a usage; LhsExpression.Identifier
        // is a decl when written to (assignment/increment/decrement) and a usage otherwise.
        is Expression.Identifier ->
            addUsage(node, env.typeOf(node))
        is LhsExpression.Identifier -> when (role) {
            NodeRole.DECL -> addDecl(node, env.typeOf(node))
            NodeRole.USAGE -> addUsage(node, env.typeOf(node))
            NodeRole.NONE -> {}
        }

        // Variable/value declarations: the declaration node is a DECL candidate; its
        // initializer subtree is traversed for usages. When there is no initializer the type
        // is read directly from the type annotation.
        is Statement.Value -> {
            addDecl(node, env.typeOf(node.initializer))
            recurse(node.initializer, NodeRole.USAGE)
        }
        is Statement.Variable -> {
            val init = node.initializer
            if (init != null) {
                addDecl(node, env.typeOf(init))
                recurse(init, NodeRole.USAGE)
            } else {
                addDecl(node, node.typeDecl!!.toType(scope, env))
            }
        }
        is GlobalDecl.Variable -> {
            val init = node.initializer
            if (init != null) {
                addDecl(node, env.typeOf(init))
                recurse(init, NodeRole.USAGE)
            } else {
                addDecl(node, node.typeDecl!!.toType(scope, env))
            }
        }
        is GlobalDecl.Constant -> {
            val init = node.initializer
            addDecl(node, env.typeOf(init))
            recurse(init, NodeRole.USAGE)
        }

        // Assignment statements: the write target is a DECL candidate; the rhs is USAGE.
        is Statement.Assignment -> {
            node.lhsExpression?.let { recurse(it, NodeRole.USAGE) }
            recurse(node.rhs, NodeRole.USAGE)
        }
        is Statement.Increment -> recurse(node.target, NodeRole.USAGE)
        is Statement.Decrement -> recurse(node.target, NodeRole.USAGE)

        // LhsExpression wrappers: propagate the incoming role to the inner target so that
        // the leaf LhsExpression.Identifier ends up with the correct DECL/USAGE classification.
        is LhsExpression.Paren -> recurse(node.target, role)
        is LhsExpression.MemberLookup -> recurse(node.receiver, role)
        is LhsExpression.Dereference -> recurse(node.target, role)
        is LhsExpression.AddressOf -> recurse(node.target, role)
        is LhsExpression.IndexLookup -> {
            recurse(node.target, role)
            recurse(node.index, NodeRole.USAGE)
        }

        // Expression nodes: all sub-expressions are in a usage context.
        is Expression.Binary -> {
            recurse(node.lhs, NodeRole.USAGE)
            recurse(node.rhs, NodeRole.USAGE)
        }
        is Expression.Unary -> recurse(node.target, NodeRole.USAGE)
        is Expression.Paren -> recurse(node.target, NodeRole.USAGE)
        is Expression.MemberLookup -> recurse(node.receiver, NodeRole.USAGE)
        is Expression.IndexLookup -> {
            recurse(node.target, NodeRole.USAGE)
            recurse(node.index, NodeRole.USAGE)
        }
        is Expression.FunctionCall -> node.args.forEach { recurse(it, NodeRole.USAGE) }
        is Expression.ValueConstructor -> {
            if (node is Expression.ArrayValueConstructor) {
                node.elementCount?.let { recurse(it, NodeRole.USAGE) }
            }
            node.args.forEach { recurse(it, NodeRole.USAGE) }
        }
        is Expression.BoolLiteral, is Expression.FloatLiteral, is Expression.IntLiteral -> {}

        // Control-flow statements: expressions in conditions are USAGE; compound bodies
        // are NONE so that the statements inside self-classify.
        is Statement.If -> {
            recurse(node.condition, NodeRole.USAGE)
            recurse(node.thenBranch, NodeRole.NONE)
            node.elseBranch?.let { recurse(it, NodeRole.NONE) }
        }
        is Statement.While -> {
            recurse(node.condition, NodeRole.USAGE)
            recurse(node.body, NodeRole.NONE)
        }
        is Statement.For -> {
            node.init?.let { recurse(it, NodeRole.NONE) }
            node.condition?.let { recurse(it, NodeRole.USAGE) }
            node.update?.let { recurse(it, NodeRole.NONE) }
            recurse(node.body, NodeRole.NONE)
        }
        is Statement.Loop -> {
            recurse(node.body, NodeRole.NONE)
            node.continuingStatement?.let { recurse(it, NodeRole.NONE) }
        }
        is Statement.Switch -> {
            recurse(node.expression, NodeRole.USAGE)
            node.clauses.forEach { recurse(it, NodeRole.NONE) }
        }
        is Statement.Return -> node.expression?.let { recurse(it, NodeRole.USAGE) }
        is Statement.FunctionCall -> node.args.forEach { recurse(it, NodeRole.USAGE) }
        is Statement.ConstAssert -> recurse(node.expression, NodeRole.USAGE)
        is Statement.Compound -> node.statements.forEach { recurse(it, NodeRole.NONE) }
        is Statement.Break, is Statement.Continue, is Statement.Discard, is Statement.Empty -> {}

        is ContinuingStatement -> {
            node.statements.statements.forEach { recurse(it, NodeRole.NONE) }
            node.breakIfExpr?.let { recurse(it, NodeRole.USAGE) }
        }
        is SwitchClause -> {
            node.caseSelectors.forEach { it?.let { expr -> recurse(expr, NodeRole.USAGE) } }
            recurse(node.compoundStatement, NodeRole.NONE)
        }

        // Global declarations: functions and non-variable globals recurse but are not
        // themselves DECL candidates.
        is GlobalDecl.Function -> recurse(node.body, NodeRole.NONE)
        is GlobalDecl.Override -> node.initializer?.let { recurse(it, NodeRole.USAGE) }
        is GlobalDecl.ConstAssert -> recurse(node.expression, NodeRole.USAGE)
        is GlobalDecl.Struct, is GlobalDecl.TypeAlias, is GlobalDecl.Empty -> {}

        is TranslationUnit -> node.globalDecls.forEach { recurse(it, NodeRole.NONE) }

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

// Returns names of all value declarations in [scope] whose store type matches [targetType].
private fun variablesOfType(
    scope: Scope,
    targetType: Type,
): List<String> {
    val targetValueType = defaultConcretizationOf(valueTypeOf(targetType))
    return scope
        .getAllEntries()
        .filterIsInstance<ScopeEntry.TypedDecl>()
        .filter { it !is ScopeEntry.Struct && it !is ScopeEntry.TypeAlias }
        .filter { entry -> defaultConcretizationOf(valueTypeOf(entry.type)) == targetValueType }
        .map { it.declName }
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
    maxReplacements: Int = Int.MAX_VALUE,
): Sequence<Pair<TranslationUnit, List<String>>> {
    val (_, usages) = collectSkeletalCandidates(tu, env)
    if (usages.isEmpty()) return emptySequence()

    val choices: List<List<Pair<AstNode, AstNode>>> = usages
        .map { (node, concreteType, scope) ->
            variablesOfType(scope, concreteType).map { varName -> node to node.cloneWithName(varName) }
        }
        .filter { it.isNotEmpty() } // [(usage1, cloned11), (usage1, cloned12), ...] repeat for each usage
    val tmp = choices.map { it.size }.reduce(Int::times)
    return enumerateCombinations(choices).take(minOf(maxReplacements, tmp)).map { (combination, charVect) ->
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
private fun enumerateCombinations(
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
        for (option in choices[usageIdx]) { // option is the Pair<usageNode, replacementNode>
            val replacement = option.second
            when (replacement) {
                is LhsExpression.Identifier -> charVect[usageIdx] = replacement.name
                is Expression.Identifier -> charVect[usageIdx] = replacement.name
                else -> throw IllegalStateException("Unexpected. Replacement should be LhsExpr.Id or Expr.Id, not $replacement")
            }

            yieldAll(enumerateCombinationsFrom(usageIdx + 1, combi + option, charVect))
        }
    }

    return enumerateCombinationsFrom(0, emptyList(), MutableList(choices.size){""})
}