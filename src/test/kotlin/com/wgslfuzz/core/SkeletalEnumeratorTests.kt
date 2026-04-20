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

import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkeletalEnumeratorTests {

    // -------------------------------------------------------------------------
    // placeholderFor — scalar types
    // -------------------------------------------------------------------------

    @Test
    fun placeholderForBool() {
        val p = placeholderFor(Type.Bool)
        assertIs<Expression.BoolLiteral>(p)
        assertEquals("false", p.text)
    }

    @Test
    fun placeholderForI32() {
        val p = placeholderFor(Type.I32)
        assertIs<Expression.IntLiteral>(p)
        assertEquals("0i", p.text)
    }

    @Test
    fun placeholderForU32() {
        val p = placeholderFor(Type.U32)
        assertIs<Expression.IntLiteral>(p)
        assertEquals("0u", p.text)
    }

    @Test
    fun placeholderForF32() {
        val p = placeholderFor(Type.F32)
        assertIs<Expression.FloatLiteral>(p)
        assertEquals("0.0f", p.text)
    }

    @Test
    fun placeholderForF16() {
        val p = placeholderFor(Type.F16)
        assertIs<Expression.FloatLiteral>(p)
        assertEquals("0.0h", p.text)
    }

    @Test
    fun placeholderForAbstractInteger() {
        val p = placeholderFor(Type.AbstractInteger)
        assertIs<Expression.IntLiteral>(p)
        assertEquals("0i", p.text)
    }

    @Test
    fun placeholderForAbstractFloat() {
        val p = placeholderFor(Type.AbstractFloat)
        assertIs<Expression.FloatLiteral>(p)
        assertEquals("0.0f", p.text)
    }

    // -------------------------------------------------------------------------
    // placeholderFor — vector types
    // -------------------------------------------------------------------------

    @Test
    fun placeholderForVec2F32() {
        val p = placeholderFor(Type.Vector(2, Type.F32))
        assertIs<Expression.Vec2ValueConstructor>(p)
        assertEquals(2, p.args.size)
        p.args.forEach { assertIs<Expression.FloatLiteral>(it) }
    }

    @Test
    fun placeholderForVec3Bool() {
        val p = placeholderFor(Type.Vector(3, Type.Bool))
        assertIs<Expression.Vec3ValueConstructor>(p)
        assertEquals(3, p.args.size)
        p.args.forEach { assertIs<Expression.BoolLiteral>(it) }
    }

    @Test
    fun placeholderForVec4I32() {
        val p = placeholderFor(Type.Vector(4, Type.I32))
        assertIs<Expression.Vec4ValueConstructor>(p)
        assertEquals(4, p.args.size)
    }

    // -------------------------------------------------------------------------
    // placeholderFor — matrix types
    // -------------------------------------------------------------------------

    @Test
    fun placeholderForMat2x2F32() {
        val p = placeholderFor(Type.Matrix(2, 2, Type.F32))
        assertIs<Expression.Mat2x2ValueConstructor>(p)
        assertEquals(2, p.args.size)
        p.args.forEach { assertIs<Expression.Vec2ValueConstructor>(it) }
    }

    @Test
    fun placeholderForMat4x3F32() {
        val p = placeholderFor(Type.Matrix(4, 3, Type.F32))
        assertIs<Expression.Mat4x3ValueConstructor>(p)
        // 4 columns of vec3
        assertEquals(4, p.args.size)
        p.args.forEach { col ->
            assertIs<Expression.Vec3ValueConstructor>(col)
            assertEquals(3, col.args.size)
        }
    }

    // -------------------------------------------------------------------------
    // placeholderFor — struct type
    // -------------------------------------------------------------------------

    @Test
    fun placeholderForStruct() {
        val structType = Type.Struct("MyStruct", listOf("x" to Type.F32, "y" to Type.I32))
        val p = placeholderFor(structType)
        assertIs<Expression.StructValueConstructor>(p)
        assertEquals("MyStruct", p.constructorName)
        assertEquals(2, p.args.size)
        assertIs<Expression.FloatLiteral>(p.args[0])
        assertIs<Expression.IntLiteral>(p.args[1])
    }

    // -------------------------------------------------------------------------
    // placeholderFor — reference type (implicit load)
    // -------------------------------------------------------------------------

    @Test
    fun placeholderForReference() {
        val refType = Type.Reference(storeType = Type.I32, addressSpace = AddressSpace.FUNCTION, accessMode = AccessMode.READ_WRITE)
        val p = placeholderFor(refType)
        assertIs<Expression.IntLiteral>(p)
        assertEquals("0i", p.text)
    }

    // -------------------------------------------------------------------------
    // placeholderFor — unsupported types return null
    // -------------------------------------------------------------------------

    @Test
    fun placeholderForPointerIsNull() {
        val ptrType = Type.Pointer(pointeeType = Type.I32, addressSpace = AddressSpace.FUNCTION, accessMode = AccessMode.READ_WRITE)
        assertNull(placeholderFor(ptrType))
    }

    @Test
    fun placeholderForTextureIsNull() {
        assertNull(placeholderFor(Type.Texture.Sampled2D(Type.F32)))
    }

    @Test
    fun placeholderForAtomicIsNull() {
        assertNull(placeholderFor(Type.AtomicI32))
    }

    // -------------------------------------------------------------------------
    // collectSkeletalCandidates — literals excluded
    // -------------------------------------------------------------------------

    @Test
    fun literalOnlyProgramHasNoCandidates() {
        // The return expression is a literal — nothing to replace.
        val src = """
            fn f() -> i32 {
              return 1i;
            }
        """.trimIndent()
        val tu = parseFromString(src, LoggingParseErrorListener())
        val env = resolve(tu)
        val candidates = collectSkeletalCandidates(tu, env)
        assertTrue(candidates.isEmpty(), "Expected no candidates but got ${candidates.size}")
    }

    // -------------------------------------------------------------------------
    // collectSkeletalCandidates — compound expression found
    // -------------------------------------------------------------------------

    @Test
    fun binaryExpressionIsCandidateNotItsLiteralOperands() {
        // `a + 1i` has one binary expression candidate; the literal `1i` is excluded.
        // `a` is an Identifier with Reference type -> included.
        val src = """
            fn f(a: i32) -> i32 {
              return a + 1i;
            }
        """.trimIndent()
        val tu = parseFromString(src, LoggingParseErrorListener())
        val env = resolve(tu)
        val candidates = collectSkeletalCandidates(tu, env)
        // Expect: Binary(a+1i), Identifier(a) — not IntLiteral(1i)
        val kinds = candidates.map { (expr, _) -> expr::class.simpleName }
        assertTrue(kinds.contains("Binary"), "Expected Binary among candidates: $kinds")
        assertTrue(kinds.contains("Identifier"), "Expected Identifier among candidates: $kinds")
        assertTrue(!kinds.contains("IntLiteral"), "IntLiteral should be excluded: $kinds")
    }

    // -------------------------------------------------------------------------
    // singleReplacementSkeletons — count equals number of candidates
    // -------------------------------------------------------------------------

    @Test
    fun skeletonCountMatchesCandidateCount() {
        val src = """
            fn f(a: i32, b: i32) -> i32 {
              return a + b;
            }
        """.trimIndent()
        val tu = parseFromString(src, LoggingParseErrorListener())
        val env = resolve(tu)
        val candidates = collectSkeletalCandidates(tu, env)
        val skeletons = singleReplacementSkeletons(tu, env).toList()
        assertEquals(candidates.size, skeletons.size)
    }

    // -------------------------------------------------------------------------
    // singleReplacementSkeletons — each skeleton is a valid parseable program
    // -------------------------------------------------------------------------

    @Test
    fun skeletonsRoundtripThroughParser() {
        val src = """
            fn f(a: f32, b: f32) -> f32 {
              var x : f32 = a * b;
              return x + a;
            }
        """.trimIndent()
        val tu = parseFromString(src, LoggingParseErrorListener())
        val env = resolve(tu)
        for (skeleton in singleReplacementSkeletons(tu, env)) {
            val baos = ByteArrayOutputStream()
            AstWriter(out = PrintStream(baos)).emit(skeleton)
            val text = baos.toString()
            val errorListener = LoggingParseErrorListener()
            parseFromString(text, errorListener)
            assertTrue(errorListener.loggedMessages.isEmpty(), "Skeleton failed to reparse:\n$text\nErrors: ${errorListener.loggedMessages}")
        }
    }

    // -------------------------------------------------------------------------
    // singleReplacementSkeletons — replaced expression becomes a placeholder
    // -------------------------------------------------------------------------

    @Test
    fun singleBinaryReplacedByZero() {
        // A function returning a single binary expression: the one skeleton
        // should return 0i directly.
        val src = """
            fn f(a: i32, b: i32) -> i32 {
              return (a + b);
            }
        """.trimIndent()
        val tu = parseFromString(src, LoggingParseErrorListener())
        val env = resolve(tu)
        // Find the skeleton that replaced the Binary node (a + b)
        val skeletons = singleReplacementSkeletons(tu, env).toList()
        val binarySkeletons = skeletons.filter { skeleton ->
            // In the skeleton the return expression should be a literal or constructor (not Binary)
            val fn = skeleton.globalDecls.filterIsInstance<GlobalDecl.Function>().first()
            val ret = fn.body.statements.filterIsInstance<Statement.Return>().first()
            ret.expression !is Expression.Binary
        }
        assertTrue(binarySkeletons.isNotEmpty(), "Expected at least one skeleton where binary was replaced")
    }

    // -------------------------------------------------------------------------
    // singleReplacementSkeletons — vec type placeholder
    // -------------------------------------------------------------------------

    @Test
    fun vectorExpressionReplacedByVecConstructor() {
        val src = """
            fn f(a: vec2<f32>, b: vec2<f32>) -> vec2<f32> {
              return a + b;
            }
        """.trimIndent()
        val tu = parseFromString(src, LoggingParseErrorListener())
        val env = resolve(tu)
        val candidates = collectSkeletalCandidates(tu, env)
        assertTrue(candidates.isNotEmpty())
        // All placeholders for vec2<f32> candidates should be Vec2ValueConstructor
        for ((_, type) in candidates.filter { (_, t) -> t is Type.Vector }) {
            val p = placeholderFor(type)
            assertIs<Expression.VectorValueConstructor>(p)
        }
    }
}
