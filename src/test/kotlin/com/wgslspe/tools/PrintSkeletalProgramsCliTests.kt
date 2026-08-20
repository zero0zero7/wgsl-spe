package com.wgslspe.tools

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PrintSkeletalProgramsCliTests {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `no skeletal candidate exits as a skip`() {
        val shader = tempDir.resolve("no-candidate.wgsl")
        shader.writeText(
            """
            @compute @workgroup_size(1)
            fn main() {
            }
            """.trimIndent(),
        )
        val outputDir = tempDir.resolve("output")

        val process =
            ProcessBuilder(
                javaExecutable(),
                "-cp",
                System.getProperty("java.class.path"),
                "com.wgslspe.tools.PrintSkeletalProgramsKt",
                "--shader",
                shader.toString(),
                "--output-dir",
                outputDir.toString(),
            ).redirectErrorStream(true).start()
        val processOutput = process.inputStream.bufferedReader().readText()

        assertEquals(3, process.waitFor(), processOutput)
        assertContains(processOutput, "no-skeletal-candidate")
        val generatedFiles = outputDir.toFile().walkTopDown().filter { it.isFile }.toList()
        assertTrue(generatedFiles.isEmpty(), "no skeleton files should be emitted: $generatedFiles")
    }

    private fun javaExecutable(): String =
        Path.of(System.getProperty("java.home"), "bin", "java").toString()
}
