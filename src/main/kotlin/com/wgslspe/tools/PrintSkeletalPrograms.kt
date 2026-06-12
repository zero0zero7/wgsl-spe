package com.wgslspe.tools

import com.wgslfuzz.core.AstWriter
import com.wgslfuzz.core.BufferInfo
import com.wgslfuzz.core.createShaderJob
import com.wgslspe.core.collectSkeletalCandidates
import com.wgslspe.core.allReplacementSkeletons
import com.wgslspe.core.getSkeletons
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.default
import kotlinx.cli.required
import kotlinx.serialization.json.Json
import java.io.File
import java.io.PrintStream
import kotlin.system.exitProcess
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream

/**
 * AstWriter always emits a trailing comma after the last element of argument
 * lists, vec/array constructors, switch-case selector lists, etc. That is valid
 * WGSL, but wgslsmith's (stricter) parser rejects a comma immediately before
 * `)`, `]`, `>` or a case `:`. Since skeletons produced here are fed back into
 * wgslsmith (recondition/run), strip those trailing commas so the two tools
 * interoperate. WGSL has no string/char literals, so a textual pass is safe.
 *
 * NB: `}` is deliberately excluded -- wgslsmith both emits and requires the
 * trailing comma in struct bodies (`d: f32,\n}`), so it must be kept.
 */
private val TRAILING_COMMA = Regex(",\\s*(?=[)\\]>:])")

private fun emitSkeleton(skeleton: com.wgslfuzz.core.TranslationUnit, file: File) {
    val buffer = ByteArrayOutputStream()
    AstWriter(out = PrintStream(buffer)).emit(skeleton)
    file.writeText(TRAILING_COMMA.replace(buffer.toString(Charsets.UTF_8.name()), ""))
}

fun main(args: Array<String>) {
    val parser = ArgParser("wgsl skeletal program enumerator")

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

    val random by parser
        .option(
            ArgType.Boolean,
            fullName = "random",
            description = "Randomly sample --limit skeletons instead of enumerating in order (default: false)",
        ).default(false)

    val outputDir by parser
        .option(
            ArgType.String,
            fullName = "output-dir",
            description = "Directory to write each skeleton as a numbered .wgsl file (optional)",
        ).default("out")

    val parseTimeout by parser
        .option(
            ArgType.Int,
            fullName = "parse-timeout",
            description = "Timeout in milliseconds for parsing the input shader (default: 10000)",
        ).default(10000)
    parser.parse(args)

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
            Json.decodeFromString(uniformsFile.readText())
        } else {
            emptyList()
        }

    val shaderJob = createShaderJob(shaderFile.readText(), uniformBuffers, timeoutMilliseconds = parseTimeout)
    val tu = shaderJob.tu
    val env = shaderJob.environment

    val (decls, usages) = collectSkeletalCandidates(tu, env)
    println("// Input: $shaderPath")
    println("// Found ${decls.size} declarations and ${usages.size} usages(s)\n")

    if (usages.isNotEmpty()) {
        val maxSkeletons = limit ?: Int.MAX_VALUE
        val skeletons = getSkeletons(tu, env, n=maxSkeletons, random=random)
        for ((idx, skeleton_charVect) in skeletons.take(maxSkeletons).withIndex()) {
            val (skeleton, charVect) = skeleton_charVect
            val fileName = "skeleton_%03d.wgsl".format(idx)
            println("$fileName, $charVect")
            emitSkeleton(skeleton, File(outDir, fileName))
        }
    }
    else {
        println("No usages found")
    }
}