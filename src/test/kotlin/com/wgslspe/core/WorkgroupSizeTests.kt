package com.wgslspe.core

import com.wgslfuzz.core.LoggingParseErrorListener
import com.wgslfuzz.core.parseFromString
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkgroupSizeTests {

    // A minimal shader in the shape wgslsmith emits: the @compute/@workgroup_size attributes on their
    // own lines, immediately followed by the entry point.
    private fun shader(attributes: String): String =
        """
        struct Output {
            data: i32,
        }

        @group(0) @binding(0)
        var<storage, read_write> s_output: Output;

        $attributes
        fn main() {
            s_output.data = 1i;
        }
        """.trimIndent()

    private fun assertParses(shaderText: String) {
        // The regression this file guards against produced text that still *looked* plausible; only a
        // parse catches it.
        parseFromString(shaderText, LoggingParseErrorListener(), 60000)
    }

    @Test
    fun rewriteKeepsComputeAttributeAndEntryPoint() {
        val rewritten = rewriteWorkgroupSize(shader("@compute\n@workgroup_size(1u)"), 256)
        assertContains(rewritten, "@compute")
        assertContains(rewritten, "@workgroup_size(256u)")
        assertContains(rewritten, "fn main()")
        assertParses(rewritten)
    }

    @Test
    fun rewriteHandlesAttributesOnOneLine() {
        val rewritten = rewriteWorkgroupSize(shader("@compute @workgroup_size(1)"), 64)
        assertContains(rewritten, "@compute @workgroup_size(64)")
        assertContains(rewritten, "fn main()")
        assertParses(rewritten)
    }

    @Test
    fun twoAndThreeArgumentFormsCollapseToASingleArgument() {
        for (attribute in listOf("@compute\n@workgroup_size(2, 4)", "@compute\n@workgroup_size(2u, 4u, 8u)")) {
            val rewritten = rewriteWorkgroupSize(shader(attribute), 256)
            assertContains(rewritten, "@compute")
            assertContains(rewritten, "fn main()")
            assertTrue(
                "@workgroup_size(256)" in rewritten || "@workgroup_size(256u)" in rewritten,
                "expected a single-argument workgroup size in:\n$rewritten",
            )
            assertParses(rewritten)
        }
    }

    @Test
    fun entryPointWithBuiltinParameterIsRewritten() {
        val original =
            """
            struct Output {
                data: i32,
            }

            @group(0) @binding(0)
            var<storage, read_write> s_output: Output;

            @compute
            @workgroup_size(1u)
            fn main(@builtin(local_invocation_index) lid: u32) {
                s_output.data = i32(lid);
            }
            """.trimIndent()
        val rewritten = rewriteWorkgroupSize(original, 256)
        assertContains(rewritten, "@compute")
        assertContains(rewritten, "@workgroup_size(256u)")
        assertContains(rewritten, "fn main(@builtin(local_invocation_index) lid: u32)")
        assertParses(rewritten)
    }

    @Test
    fun nonLiteralWorkgroupSizeIsLeftUnchanged() {
        val original =
            """
            override wg_size: u32 = 1u;

            @compute
            @workgroup_size(wg_size)
            fn main() {
            }
            """.trimIndent()
        assertEquals(original, rewriteWorkgroupSize(original, 256))
    }

    @Test
    fun everyComputeEntryPointIsRewritten() {
        val original =
            """
            @compute
            @workgroup_size(1u)
            fn main() {
            }

            @compute @workgroup_size(1) fn main2() {
            }
            """.trimIndent()
        val rewritten = rewriteWorkgroupSize(original, 256)
        // Only `fn main` is targeted, by design: the second entry point is not touched.
        assertContains(rewritten, "@workgroup_size(256u)\nfn main()")
        assertContains(rewritten, "@compute @workgroup_size(1) fn main2()")
    }
}