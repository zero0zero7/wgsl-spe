package com.wgslspe.tools

import com.wgslfuzz.core.AstNode
import com.wgslfuzz.core.AstWriter
import com.wgslfuzz.core.BufferInfo
import com.wgslfuzz.core.SourceSpan
import com.wgslspe.core.parseWithHardDeadline
import com.wgslspe.core.collectSkeletalCandidates
import com.wgslspe.core.stripAstWriterTrailingCommas
import com.wgslspe.core.allReplacementSkeletons
import com.wgslspe.core.getCombinedSkeletonEdits
import com.wgslspe.core.getCombinedSkeletons
import com.wgslspe.core.getFunctionSkeletonEdits
import com.wgslspe.core.getFunctionSkeletons
import com.wgslspe.core.getVariableSkeletons
import com.wgslspe.core.getVariableSkeletonEdits
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

private fun emitSkeleton(skeleton: com.wgslfuzz.core.TranslationUnit, file: File) {
    val buffer = ByteArrayOutputStream()
    AstWriter(out = PrintStream(buffer)).emit(skeleton)
    file.writeText(stripAstWriterTrailingCommas(buffer.toString(Charsets.UTF_8.name())))
}

/**
 * Format-preserving emitter (used by --preserve-format). Produces a skeleton by
 * splicing each replacement name over its usage's [SourceSpan] in the *original*
 * source text, leaving every other byte untouched. The output is therefore in
 * the input's exact dialect (wgslsmith's), which `recondition`/`run` accept.
 *
 * Edits are applied in descending start offset so earlier splices don't shift
 * the offsets of later ones; usage tokens are non-overlapping. Assumes the
 * source is ASCII (so ANTLR char offsets == String indices), which holds for
 * wgslsmith output. A usage lacking a [SourceSpan] is skipped with a warning.
 */
private fun spliceSkeleton(originalText: String, edits: List<Pair<AstNode, String>>, file: File) {
    val spans = edits.mapNotNull { (node, newName) ->
        val span = node.metadata.filterIsInstance<SourceSpan>().firstOrNull()
        if (span == null) {
            System.err.println("WARNING: usage node has no SourceSpan; skipping a replacement in ${file.name}")
            null
        } else {
            Triple(span.start, span.stopInclusive, newName)
        }
    }.sortedByDescending { it.first }

    val sb = StringBuilder(originalText)
    for ((start, stopInclusive, newName) in spans) {
        sb.replace(start, stopInclusive + 1, newName) // end index is exclusive
    }
    file.writeText(sb.toString())
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

    val mode by parser
        .option(
            ArgType.Choice(listOf("variables", "functions", "both"), { it }),
            fullName = "mode",
            description = "Which replacement axis to enumerate: variable usages, function callees, or both combined " +
                "(each skeleton applies one variable combination and one function combination together) (default: variables)",
        ).default("variables")

    // val maxReplacements by parser
    //     .option(
    //         ArgType.Int,
    //         fullName = "max-replacements",
    //         description = "Maximum number of simultaneous replacements per skeleton (default: 1)",
    //     ).default(Int.MAX_VALUE)

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

    val preserveFormat by parser
        .option(
            ArgType.Boolean,
            fullName = "preserve-format",
            description = "Emit skeletons by splicing replacements into the original text " +
                "(preserves the input's exact formatting/dialect) instead of re-serializing " +
                "via AstWriter (default: false)",
        ).default(false)
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

    val shaderText = shaderFile.readText()
    val shaderJob = parseWithHardDeadline(shaderText, uniformBuffers, parseTimeout)
    val tu = shaderJob.tu
    val env = shaderJob.environment

    val (decls, usages) = collectSkeletalCandidates(tu, env)
    println("// Input: $shaderPath")
    println("// Found ${decls.size} declarations and ${usages.size} usages(s)\n")

    val maxSkeletons = limit ?: Int.MAX_VALUE
    var emittedSkeletons = 0

    if (preserveFormat) {
        // Format-preserving mode: splice replacements into the original text.
        val edits = when (mode) {
            "variables" -> getVariableSkeletonEdits(tu, env, n = maxSkeletons, random = random)
            "functions" -> getFunctionSkeletonEdits(tu, env, n = maxSkeletons, random = random)
            else -> getCombinedSkeletonEdits(tu, env, n = maxSkeletons, random = random)
        }
        for ((idx, editCharVect) in edits.take(maxSkeletons).withIndex()) {
            val (editList, charVect) = editCharVect
            val fileName = "skeleton_%03d.wgsl".format(idx)
            println("$fileName, $charVect")
            spliceSkeleton(shaderText, editList, File(outDir, fileName))
            emittedSkeletons++
        }
    } else {
        // Default mode: re-serialize each skeleton via AstWriter.
        val skeletons = when (mode) {
            "variables" -> getVariableSkeletons(tu, env, n = maxSkeletons, random = random)
            "functions" -> getFunctionSkeletons(tu, env, n = maxSkeletons, random = random)
            else -> getCombinedSkeletons(tu, env, n = maxSkeletons, random = random)
        }
        for ((idx, skeletonCharVect) in skeletons.take(maxSkeletons).withIndex()) {
            val (skeleton, charVect) = skeletonCharVect
            val fileName = "skeleton_%03d.wgsl".format(idx)
            println("$fileName, $charVect")
            emitSkeleton(skeleton, File(outDir, fileName))
            emittedSkeletons++
        }
    }

    if (emittedSkeletons == 0) {
        System.err.println("no-skeletal-candidate: no skeletal variants were emitted")
        exitProcess(3)
    }
}
