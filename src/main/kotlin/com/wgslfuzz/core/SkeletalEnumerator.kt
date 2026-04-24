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
 * Returns all non-trivial [Expression] nodes in [tu] as [SkeletalCandidate]s, each capturing the
 * expression, its concrete type, and the scope visible at that expression. Literals are excluded
 * (they are already maximally simple).
 */
fun collectSkeletalCandidates(
    tu: TranslationUnit,
    env: ResolvedEnvironment,
): Pair<List<SkeletalCandidate>, List<SkeletalCandidate>> {
    val declarations = mutableListOf<SkeletalCandidate>()
    val usages = mutableListOf<SkeletalCandidate>()
    collectCandidatesFromNode(tu, null, env, declarations, usages)
    return Pair(declarations, usages)
}

private fun collectCandidatesFromNode(
    node: AstNode,
    enclosingStatement: Statement?,
    env: ResolvedEnvironment,
    declarations: MutableList<SkeletalCandidate>, // WGSL allows use of declared but uninitialized variables
    usages: MutableList<SkeletalCandidate>
) {

    val currentStatement: Statement? = if (node is Statement) node else enclosingStatement

    fun helper(node: AstNode, rawType: Type, decl: Boolean) {
        val scope: Scope = currentStatement?.let { env.scopeAvailableBefore(it) } ?: env.globalScope
        val concreteType = defaultConcretizationOf(rawType)
        if (decl) {
            declarations.add(SkeletalCandidate(node, concreteType, scope))
        }
        else {
            usages.add(SkeletalCandidate(node, concreteType, scope))
        }
    }

    fun helper(node: AstNode, rawType: Type, scope: Scope, decl: Boolean) {
        val concreteType = defaultConcretizationOf(rawType)
        if (decl) {
            declarations.add(SkeletalCandidate(node, concreteType, scope))
        }
        else {
            usages.add(SkeletalCandidate(node, concreteType, scope))
        }
    }

    when (node) {
        is Expression.Identifier -> {
            val rawType: Type = env.typeOf(node)
            helper(node, rawType, false)
        }
        is LhsExpression.Identifier -> {
            val rawType: Type = env.typeOf(node)
            helper(node, rawType, false)
        }
        is GlobalDecl.Variable -> {
            val init: Expression? = node.initializer
            if (init != null) {
                helper(node, env.typeOf(init), true)
            }
            else {
                val scope: Scope = currentStatement?.let { env.scopeAvailableBefore(it) } ?: env.globalScope
                helper(node, node.typeDecl!!.toType(scope, env), scope, true)
            }
        }
        is Statement.Variable -> {
            val init: Expression? = node.initializer
            if (init != null) {
                helper(node, env.typeOf(init), true)
            }
            else {
                val scope: Scope = currentStatement?.let { env.scopeAvailableBefore(it) } ?: env.globalScope
                helper(node, node.typeDecl!!.toType(scope, env), scope, true)
            }
        }
        else -> {}
    }

    traverse(
        { child, _ -> collectCandidatesFromNode(child, currentStatement, env, declarations, usages) },
        node,
        Unit,
    )
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
    for ((id, concreteType, scope) in usage) {
        for (varName in variablesOfType(scope, concreteType)) {
//            val replacement = Expression.Identifier(varName)
            val replacement = id.cloneWithName(varName)
            yield(tu.clone { node -> if (node === id) replacement else null })
        }
    }
}