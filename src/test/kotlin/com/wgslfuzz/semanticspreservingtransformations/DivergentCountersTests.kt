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

package com.wgslfuzz.semanticspreservingtransformations

import com.wgslfuzz.core.Attribute
import com.wgslfuzz.core.AstWriter
import com.wgslfuzz.core.BufferInfo
import com.wgslfuzz.core.Expression
import com.wgslfuzz.core.GlobalDecl
import com.wgslfuzz.core.ShaderJob
import com.wgslfuzz.core.createShaderJob
import com.wgslfuzz.core.nodesPreOrder
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.util.Random

/**
 * Covers the two branches of AddDivergentCounters.findExistingLocalInvocationId /
 * synthesizeLocalInvocationIdParameter: an entry point that must have a local_invocation_id
 * parameter added (samples/divergence_no_lid.wgsl), and one that already exposes
 * local_invocation_id directly and must reuse it without adding anything to the signature
 * (samples/divergence_with_lid.wgsl).
 *
 * Unlike TransformReduceTests's subclasses (all fragment/vertex shaders, so addDivergentCounters
 * is a no-op on every one of them), these two samples are the only @compute shaders in samples/
 * with the specific parameter shapes this transformation branches on -- kept as their own test
 * class rather than folded into the shared suite for that reason.
 */
class DivergentCountersTests {
    private fun loadShaderJob(filenameNoExtension: String): ShaderJob {
        val uniformBuffers: List<BufferInfo> =
            Json.decodeFromString(File("samples", "$filenameNoExtension.uniforms.json").readText())
        return createShaderJob(
            File("samples", "$filenameNoExtension.wgsl").readText(),
            uniformBuffers,
        )
    }

    private fun emit(shaderJob: ShaderJob): String {
        val out = ByteArrayOutputStream()
        AstWriter(out = PrintStream(out)).emit(shaderJob.tu)
        return out.toString("UTF-8")
    }

    private fun computeEntryPoint(shaderJob: ShaderJob): GlobalDecl.Function =
        shaderJob.tu.globalDecls
            .filterIsInstance<GlobalDecl.Function>()
            .first { fn -> fn.attributes.any { it is Attribute.Compute } }

    private fun structCount(shaderJob: ShaderJob): Int = shaderJob.tu.globalDecls.filterIsInstance<GlobalDecl.Struct>().size

    @Test
    fun `entry point without local_invocation_id gets a synthesized parameter`() {
        val shaderJob = loadShaderJob("divergence_no_lid")
        val originalStructCount = structCount(shaderJob)
        val originalParamNames = computeEntryPoint(shaderJob).parameters.map { it.name }
        assertEquals(1, computeEntryPoint(shaderJob).parameters.size, "sample should start with exactly one parameter (gid)")

        val transformed = applyV0(shaderJob, DefaultFuzzerSettings(Random(1)))
        val transformedEntryPoint = computeEntryPoint(transformed)
        val text = emit(transformed)

        // local_invocation_id arrives as a plain parameter, so no module-scope declaration is added.
        assertEquals(originalStructCount, structCount(transformed), "no struct should be synthesized")

        // The entry point gained exactly one parameter, and the original one is untouched.
        assertEquals(originalParamNames.size + 1, transformedEntryPoint.parameters.size)
        assertTrue(
            transformedEntryPoint.parameters.map { it.name }.containsAll(originalParamNames),
            "original parameter(s) must still be present unchanged",
        )
        val newParam = transformedEntryPoint.parameters.first { it.name !in originalParamNames }

        // The new parameter carries @builtin(local_invocation_id) and has that builtin's type.
        assertTrue(
            newParam.attributes
                .filterIsInstance<Attribute.Builtin>()
                .any { it.name == com.wgslfuzz.core.BuiltinValue.LOCAL_INVOCATION_ID },
            "synthesized parameter must carry @builtin(local_invocation_id)",
        )
        val newParamType = newParam.typeDecl as com.wgslfuzz.core.TypeDecl.Vec3
        assertTrue(
            newParamType.elementType is com.wgslfuzz.core.TypeDecl.U32,
            "synthesized parameter must be vec3<u32>",
        )

        // The transformation actually fired (not just eligible-but-unused).
        assertTrue("divergent_counter_" in text, "expected at least one injected counter pair")
        assertTrue(newParam.name in text, "synthesized parameter name should appear in the emitted text")

        // Every expression still resolves to a type after the rewrite.
        for (node in nodesPreOrder(transformed.tu)) {
            if (node is Expression) {
                transformed.environment.typeOf(node)
            }
        }
    }

    @Test
    fun `entry point with existing local_invocation_id parameter is reused, not duplicated`() {
        val shaderJob = loadShaderJob("divergence_with_lid")
        val originalParams = computeEntryPoint(shaderJob).parameters
        assertEquals(1, originalParams.size, "sample should start with exactly one parameter (lid)")
        assertTrue(
            originalParams.single().attributes.filterIsInstance<Attribute.Builtin>().any {
                it.name == com.wgslfuzz.core.BuiltinValue.LOCAL_INVOCATION_ID
            },
        )

        val transformed = applyV0(shaderJob, DefaultFuzzerSettings(Random(1)))
        val transformedEntryPoint = computeEntryPoint(transformed)
        val text = emit(transformed)

        // No struct was synthesized -- the existing parameter was reused directly.
        assertEquals(0, structCount(transformed), "no struct should be added when local_invocation_id already exists")

        // The parameter list is completely unchanged.
        assertEquals(originalParams.size, transformedEntryPoint.parameters.size)
        assertEquals(originalParams.single().name, transformedEntryPoint.parameters.single().name)

        // The transformation actually fired, using the EXISTING parameter name directly.
        assertTrue("divergent_counter_" in text, "expected at least one injected counter pair")
        assertTrue("${originalParams.single().name}.x % 2u" in text, "condition should read the existing parameter directly")
        assertFalse("DivergentCountersLid_" in text, "no synthesized struct name should appear")
        assertFalse("divergent_counters_lid_" in text, "no synthesized parameter name should appear")

        for (node in nodesPreOrder(transformed.tu)) {
            if (node is Expression) {
                transformed.environment.typeOf(node)
            }
        }
    }
}
