package com.wgslspe.tools

import com.wgslfuzz.core.AccessMode
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
import kotlinx.cli.required
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.PrintStream
import kotlin.system.exitProcess
import java.io.FileOutputStream
import java.io.Serial


@Serializable
data class UniformsFile(
    val entryPoint: String,
    val buffers: List<BufferInfo>,
)

fun main(args: Array<String>) {
    val parser = ArgParser("Pipeline to 1. Enumerate 2. Check compilable by Tint 3. Execute shader in Dawn")

    val shaderPath by parser
        .option(
            ArgType.String,
            fullName = "shader",
            description = "Path to the input .wgsl shader file",
        ).required()

    val limit by parser
        .option(
            ArgType.Int,
            fullName = "limit",
            description = "Maximum number of skeletons to print (default: all)",
        )

    val maxReplacements by parser
        .option(
            ArgType.Int,
            fullName = "max-replacements",
            description = "Maximum number of simultaneous replacements per skeleton (default: 1)",
        ).default(Int.MAX_VALUE)

    val outputDir by parser
        .option(
            ArgType.String,
            fullName = "output-dir",
            description = "Directory to write each skeleton as a numbered .wgsl file (optional)",
        ).default("out")
    parser.parse(args)

    // Set up input, output files paths and directories
    val shaderName = File(shaderPath).nameWithoutExtension
    val outDir = File(outputDir, shaderName)
    outDir.mkdirs()

    val shaderFile = File(shaderPath)
    if (!shaderFile.exists()) {
        System.err.println("Shader file $shaderPath does not exist")
        exitProcess(1)
    }

    val uniformsFile = File(shaderPath.removeSuffix(".wgsl") + ".uniforms.json")
    val uniformBuffers: List<BufferInfo> =
        if (uniformsFile.exists()) {
            Json.decodeFromString<UniformsFile>(uniformsFile.readText()).buffers
        } else {
            emptyList()
        }


    val shaderJob = createShaderJob(shaderFile.readText(), uniformBuffers)
    val tu = shaderJob.tu
    val env = shaderJob.environment

    val (decls, usages) = collectSkeletalCandidates(tu, env)
    println("// Input: $shaderPath")
    println("// Found ${decls.size} declarations and ${usages.size} usages(s)\n")
    if (usages.isEmpty()) {
        println("No usages found")
    }

    // 1. Enumerate skeletons
    val skeletons = allReplacementSkeletons(tu, env, maxReplacements)
    val maxSkeletons = limit ?: Int.MAX_VALUE
    for ((idx, skeleton_charVect) in skeletons.take(maxSkeletons).withIndex()) {
        val (skeleton, charVect) = skeleton_charVect
        val skeletonName = "skeleton_%03d.wgsl".format(idx)
        var skeletonFile = File(outDir, skeletonName)
        println("$skeletonName, $charVect")
        AstWriter(out = PrintStream(FileOutputStream(skeletonFile))).emit(skeleton)
        // 2. Check compilable
        val tintCompilable = isCompilable(skeletonFile.absolutePath)
        println(tintCompilable)
        // 3. Execute in Dawn
        val skeletonJob = createShaderJob(skeletonFile.readText(), uniformBuffers)
        val results: List<BufferResult> = DawnHarness.execute(skeletonJob)
//        println(Json { prettyPrint = true }.encodeToString(results))
        println(results.joinToString("\n"))
        skeletonFile.delete()
    }
}