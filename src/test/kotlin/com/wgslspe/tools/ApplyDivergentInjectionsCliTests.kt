package com.wgslspe.tools

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ApplyDivergentInjectionsCliTests {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `v1 and v2 reject thread ids outside the workgroup`() {
        listOf(
            Triple(1, -1, "0 <= threadToRun < workgroupSize"),
            Triple(2, 4, "0 <= threadToRun < workgroupSize"),
        ).forEachIndexed { index, (version, thread, expectedMessage) ->
            val output = tempDir.resolve("v$version-$index.wgsl")
            val result = runTool(version, thread, workgroupSize = 4, output = output)

            assertEquals(1, result.exitCode, result.output)
            assertContains(result.output, expectedMessage)
            assertFalse(output.toFile().exists(), "invalid configuration must not create $output")
        }
    }

    @Test
    fun `v3 rejects a nonzero thread id`() {
        val output = tempDir.resolve("v3.wgsl")
        val result = runTool(version = 3, thread = 1, workgroupSize = null, output = output)

        assertEquals(1, result.exitCode, result.output)
        assertContains(result.output, "--threadToRun must be 0 with --divergenceVersion 3")
        assertFalse(output.toFile().exists(), "invalid configuration must not create $output")
    }

    private fun runTool(version: Int, thread: Int, workgroupSize: Int?, output: Path): ToolResult {
        val inputs = tempDir.resolve("inputs.json")
        inputs.writeText("{}")

        val arguments = mutableListOf(
            javaExecutable(),
            "-cp",
            System.getProperty("java.class.path"),
            "com.wgslspe.tools.ApplyDivergentInjectionsKt",
            "--shader",
            Path.of("samples/divergence_with_lid.wgsl").toAbsolutePath().toString(),
            "--output",
            output.toString(),
            "--injectDivergence",
            "--divergenceVersion",
            version.toString(),
            "--threadToRun",
            thread.toString(),
            "--inputs",
            inputs.toString(),
            "--inputs-out",
            tempDir.resolve("augmented-$version-$thread.json").toString(),
        )
        if (workgroupSize != null) {
            arguments += listOf("--workgroupSize", workgroupSize.toString())
        }

        val process = ProcessBuilder(arguments).redirectErrorStream(true).start()
        val processOutput = process.inputStream.bufferedReader().readText()
        return ToolResult(process.waitFor(), processOutput)
    }

    private fun javaExecutable(): String =
        Path.of(System.getProperty("java.home"), "bin", "java").toString()

    private data class ToolResult(val exitCode: Int, val output: String)
}
