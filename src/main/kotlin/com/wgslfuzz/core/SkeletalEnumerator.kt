/*
 * Copyright 2025 The wgsl-fuzz Project Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.wgslfuzz.core

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
            node.lhsExpression?.let { recurse(it, NodeRole.DECL) }
            recurse(node.rhs, NodeRole.USAGE)
        }
//        is Statement.Increment -> recurse(node.target, NodeRole.DECL)
//        is Statement.Decrement -> recurse(node.target, NodeRole.DECL)

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
//        is GlobalDecl.ConstAssert -> recurse(node.expression, NodeRole.USAGE)
        is GlobalDecl.Struct, is GlobalDecl.TypeAlias, is GlobalDecl.Empty -> {}

        is TranslationUnit -> node.globalDecls.forEach { recurse(it, NodeRole.NONE) }

        // TypeDecl nodes and anything else: no candidates to collect.
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
 * Lazily enumerates all single-expression skeletal variants of [tu].
 * Each emitted [TranslationUnit] is identical to [tu] except that exactly one candidate
 * expression has been replaced by a reference to an in-scope variable of the same type.
 * Candidates for which no matching variable exists in scope are skipped.
 */
fun singleReplacementSkeletons(
    tu: TranslationUnit,
    env: ResolvedEnvironment,
): Sequence<TranslationUnit> = sequence {
    val (decl, usage) = collectSkeletalCandidates(tu, env)
    print(decl.size)
    print(usage.size)
    for ((id, concreteType, scope) in usage) {
        for (varName in variablesOfType(scope, concreteType)) {
            val replacement = id.cloneWithName(varName)
            yield(tu.clone { node -> if (node === id) replacement else null })
        }
    }
}

/**
 * Lazily enumerates all skeletal variants of [tu] produced by simultaneously replacing between
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
): Sequence<TranslationUnit> {
    val (_, usages) = collectSkeletalCandidates(tu, env)
    val choices: List<List<Pair<AstNode, AstNode>>> = usages
        .map { (id, concreteType, scope) ->
            variablesOfType(scope, concreteType).map { varName -> id to id.cloneWithName(varName) }
        }
        .filter { it.isNotEmpty() }
    return enumerateCombinations(choices, minOf(maxReplacements, choices.size)).map { combination ->
        val replacementMap = combination.toMap()
        tu.clone { node -> replacementMap[node] }
    }
}

private fun enumerateCombinations(
    choices: List<List<Pair<AstNode, AstNode>>>,
    maxSize: Int,
): Sequence<List<Pair<AstNode, AstNode>>> =
    enumerateCombinationsFrom(choices, 0, maxSize, emptyList())

private fun enumerateCombinationsFrom(
    choices: List<List<Pair<AstNode, AstNode>>>,
    fromIndex: Int,
    remainingSlots: Int,
    current: List<Pair<AstNode, AstNode>>,
): Sequence<List<Pair<AstNode, AstNode>>> = sequence {
    if (current.isNotEmpty()) yield(current)
    if (remainingSlots == 0) return@sequence
    for (i in fromIndex until choices.size) {
        for (option in choices[i]) {
            yieldAll(enumerateCombinationsFrom(choices, i + 1, remainingSlots - 1, current + option))
        }
    }
}