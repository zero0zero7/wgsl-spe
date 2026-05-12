package com.wgslspe.tools

import com.wgslfuzz.core.AstWriter
import com.wgslfuzz.core.BufferInfo
import com.wgslfuzz.core.createShaderJob
import com.wgslspe.core.BufferResult
import com.wgslspe.core.DawnHarness
import com.wgslspe.core.collectSkeletalCandidates
import com.wgslspe.core.allReplacementSkeletons
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.default
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.PrintStream
import kotlin.system.exitProcess


@Serializable
data class UniformsFile(
    val entryPoint: String,
    val buffers: List<BufferInfo>,
)

fun printUsage() {
    println("""
Usage: Pipeline [options]

Options:
  --shader <path>           Path to the input .wgsl shader file (required)
  --limit <n>               Maximum number of skeletons to enumerate (default: all)
  --max-replacements <n>    Maximum simultaneous replacements per skeleton (default: unlimited)
  --output-dir <dir>        Directory to write skeleton .wgsl files (default: out)
  -h, --help                Show this help message

Description:
  1. Enumerates semantics-preserving skeletal variants of a WGSL shader
  2. Checks each variant is compilable via Tint
  3. Executes each variant in Dawn and reports buffer results

  Uniforms are loaded from <shader-basename>.uniforms.json if present.
    """.trimIndent())
}

fun main(args: Array<String>) {
    if (args.isEmpty() || args.contains("-h") || args.contains("--help")) {
        printUsage()
        exitProcess(0)
    }

    val parser = ArgParser("Pipeline")

    val shaderPath by parser
        .option(
            ArgType.String,
            fullName = "shader",
            description = "Path to the input .wgsl shader file",
        )

    val limit by parser
        .option(
            ArgType.Int,
            fullName = "limit",
            description = "Maximum number of skeletons to print (default: all)",
        )

    val outputDir by parser
        .option(
            ArgType.String,
            fullName = "output-dir",
            description = "Directory to write each skeleton as a numbered .wgsl file (optional)",
        ).default("out")

    parser.parse(args)

    val resolvedShaderPath = shaderPath ?: run {
        System.err.println("Error: --shader is required")
        println()
        printUsage()
        exitProcess(1)
    }

    // Set up input, output files paths and directories
    val shaderName = File(resolvedShaderPath).nameWithoutExtension
    val outDir = File(outputDir, shaderName)
    outDir.mkdirs()

    val shaderFile = File(resolvedShaderPath)
    if (!shaderFile.exists()) {
        System.err.println("Shader file $resolvedShaderPath does not exist")
        exitProcess(1)
    }

    val originalCompilable = isCompilable(shaderFile.absolutePath)
    if (originalCompilable != "Success") {
        System.err.println("Original shader is not compilable: $originalCompilable")
        exitProcess(1)
    }

    val uniformsFile = File(resolvedShaderPath.removeSuffix(".wgsl") + ".uniforms.json")
    val uniformBuffers: List<BufferInfo> =
        if (uniformsFile.exists()) {
            Json.decodeFromString<UniformsFile>(uniformsFile.readText()).buffers
        } else {
            emptyList()
        }

    val shaderJob = createShaderJob(shaderFile.readText(), uniformBuffers, timeoutMilliseconds = Int.MAX_VALUE)
    val tu = shaderJob.tu
    val env = shaderJob.environment

    val (decls, usages) = collectSkeletalCandidates(tu, env)
    println("Found ${decls.size} declarations and ${usages.size} usages(s)\n")
    if (usages.isEmpty()) {
        println("No usages found")
    }

    // 1. Enumerate skeletons
    val maxSkeletons = limit ?: Int.MAX_VALUE
    val skeletons = allReplacementSkeletons(tu, env, maxSkeletons)
    for ((idx, skeletonCharVect) in skeletons.withIndex()) {
        val (skeleton, charVect) = skeletonCharVect
        val skeletonName = "skeleton_%03d.wgsl".format(idx)
        val skeletonFile = File(outDir, skeletonName)
//        println("$skeletonName, $charVect")
        print("Skeleton $idx: $skeletonName. ")
        AstWriter(out = PrintStream(FileOutputStream(skeletonFile))).emit(skeleton)
        // 2. Check compilable
        val tintCompilable = isCompilable(skeletonFile.absolutePath)
        print("Tint compile: $tintCompilable. ")
        // 3. Execute in Dawn
        val skeletonJob = createShaderJob(skeletonFile.readText(), uniformBuffers, timeoutMilliseconds = 60_000)
        try {
            val results: List<BufferResult> = DawnHarness.execute(skeletonJob)
            println("Deleting.")
            skeletonFile.delete()
        } catch (e: Exception) {
            println("BUG: Dawn crashed/timed out — keeping $skeletonName. ${e.message}")
        }
    }
}