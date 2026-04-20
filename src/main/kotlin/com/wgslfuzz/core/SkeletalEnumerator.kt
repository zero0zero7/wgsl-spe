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
 * Returns a zero-value placeholder Expression for [type], or null if the type
 * cannot be represented as a simple value constructor (e.g. pointers, atomics, textures).
 *
 * Abstract types (AbstractInteger, AbstractFloat) are treated as their concrete
 * defaults (I32, F32) so the returned expression is always well-typed.
 */
fun placeholderFor(type: Type): Expression? =
    when (type) {
        Type.Bool -> Expression.BoolLiteral("false")
        Type.AbstractInteger, Type.I32 -> Expression.IntLiteral("0i")
        Type.U32 -> Expression.IntLiteral("0u")
        Type.AbstractFloat, Type.F32 -> Expression.FloatLiteral("0.0f")
        Type.F16 -> Expression.FloatLiteral("0.0h")
        is Type.Vector -> {
            val elem = placeholderFor(type.elementType) ?: return null
            val args = List(type.width) { elem }
            when (type.width) {
                2 -> Expression.Vec2ValueConstructor(args = args)
                3 -> Expression.Vec3ValueConstructor(args = args)
                4 -> Expression.Vec4ValueConstructor(args = args)
                else -> null
            }
        }
        is Type.Matrix -> {
            val colVecType = Type.Vector(type.numRows, type.elementType)
            val col = placeholderFor(colVecType) ?: return null
            val cols = List(type.numCols) { col }
            when (type.numCols to type.numRows) {
                2 to 2 -> Expression.Mat2x2ValueConstructor(args = cols)
                2 to 3 -> Expression.Mat2x3ValueConstructor(args = cols)
                2 to 4 -> Expression.Mat2x4ValueConstructor(args = cols)
                3 to 2 -> Expression.Mat3x2ValueConstructor(args = cols)
                3 to 3 -> Expression.Mat3x3ValueConstructor(args = cols)
                3 to 4 -> Expression.Mat3x4ValueConstructor(args = cols)
                4 to 2 -> Expression.Mat4x2ValueConstructor(args = cols)
                4 to 3 -> Expression.Mat4x3ValueConstructor(args = cols)
                4 to 4 -> Expression.Mat4x4ValueConstructor(args = cols)
                else -> null
            }
        }
        is Type.Array ->
            if (type.elementCount == null) null
            else {
                val elem = placeholderFor(type.elementType) ?: return null
                Expression.ArrayValueConstructor(
                    args = List(type.elementCount) { elem },
                )
            }
        is Type.Struct -> {
            val memberExprs = type.members.map { (_, memberType) -> placeholderFor(memberType) ?: return null }
            Expression.StructValueConstructor(constructorName = type.name, args = memberExprs)
        }
        // A reference in an rvalue context is implicitly loaded; replace with a placeholder for the store type.
        is Type.Reference -> placeholderFor(type.storeType)
        // Pointer, Atomic, Texture, Sampler — no simple value constructor exists.
        else -> null
    }

/**
 * Returns all non-trivial [Expression] nodes in [tu] paired with their placeholder-eligible
 * concrete type. Literals are excluded (they are already maximally simple).
 * Expressions whose type has no valid placeholder (e.g. textures, pointers) are excluded.
 */
fun collectSkeletalCandidates(
    tu: TranslationUnit,
    env: ResolvedEnvironment,
): List<Pair<Expression, Type>> =
    nodesPreOrder(tu)
        .filterIsInstance<Expression>()
        .filter { it !is Expression.BoolLiteral && it !is Expression.IntLiteral && it !is Expression.FloatLiteral }
        .mapNotNull { expr ->
            val rawType = env.typeOf(expr)
            val concreteType = defaultConcretizationOf(rawType)
            if (placeholderFor(concreteType) != null) expr to concreteType else null
        }

/**
 * Lazily enumerates all single-expression skeletal variants of [tu].
 * Each emitted [TranslationUnit] is identical to [tu] except that exactly one
 * candidate expression has been replaced by its zero-value placeholder.
 */
fun singleReplacementSkeletons(
    tu: TranslationUnit,
    env: ResolvedEnvironment,
): Sequence<TranslationUnit> = sequence {
    for ((target, concreteType) in collectSkeletalCandidates(tu, env)) {
        val placeholder = placeholderFor(concreteType) ?: continue
        yield(tu.clone { node -> if (node === target) placeholder else null })
    }
}