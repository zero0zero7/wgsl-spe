package com.wgslspe.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        val metrics = tempDir.resolve("no-candidate-metrics.jsonl")

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
                "--metrics-file",
                metrics.toString(),
            ).redirectErrorStream(true).start()
        val processOutput = process.inputStream.bufferedReader().readText()

        assertEquals(3, process.waitFor(), processOutput)
        assertContains(processOutput, "no-skeletal-candidate")
        val generatedFiles = outputDir.toFile().walkTopDown().filter { it.isFile }.toList()
        assertTrue(generatedFiles.isEmpty(), "no skeleton files should be emitted: $generatedFiles")
        val stages = metrics.toFile().readLines().map { Json.parseToJsonElement(it).jsonObject.getValue("stage").jsonPrimitive.content }
        assertEquals(1, stages.count { it == "total" })
    }

    @Test
    fun `metrics describe setup and every emitted variant for all modes`() {
        val shader = tempDir.resolve("input.wgsl")
        shader.writeText(
            """
            fn a(x: f32) -> f32 { return x; }
            fn b(x: f32) -> f32 { return x; }
            @compute @workgroup_size(1)
            fn main() {
              let p: f32 = 1.0;
              let q: f32 = 2.0;
              let result = a(p);
            }
            """.trimIndent(),
        )

        for (mode in listOf("variables", "functions", "both")) {
            val outputDir = tempDir.resolve("output-$mode")
            val metrics = tempDir.resolve("metrics-$mode.jsonl")
            val process = ProcessBuilder(
                javaExecutable(),
                "-cp",
                System.getProperty("java.class.path"),
                "com.wgslspe.tools.PrintSkeletalProgramsKt",
                "--shader", shader.toString(),
                "--mode", mode,
                "--limit", "2",
                "--output-dir", outputDir.toString(),
                "--metrics-file", metrics.toString(),
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.waitFor(), output)

            val records = metrics.toFile().readLines().map { Json.parseToJsonElement(it).jsonObject }
            val stages = records.map { it.getValue("stage").jsonPrimitive.content }
            assertEquals(1, stages.count { it == "parse_resolve" })
            assertEquals(1, stages.count { it == "setup" })
            assertEquals(1, stages.count { it == "total" })
            val emitted = outputDir.toFile().walkTopDown().count { it.isFile && it.extension == "wgsl" }
            assertEquals(emitted, stages.count { it == "variant" })
            records.forEach { record ->
                assertTrue(record.getValue("elapsedNs").jsonPrimitive.content.toLong() >= 0)
                assertEquals(mode, record.getValue("mode").jsonPrimitive.content)
            }
        }
    }

    private fun javaExecutable(): String =
        Path.of(System.getProperty("java.home"), "bin", "java").toString()
}
