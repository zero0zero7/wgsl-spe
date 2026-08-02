package com.wgslspe.tools

import com.wgslfuzz.core.AccessMode
import com.wgslfuzz.core.AddressSpace
import com.wgslfuzz.core.Attribute
import com.wgslfuzz.core.AstWriter
import com.wgslfuzz.core.BufferInfo
import com.wgslfuzz.core.Expression
import com.wgslfuzz.core.GlobalDecl
import com.wgslfuzz.core.ShaderJob
import com.wgslfuzz.core.createShaderJob
import com.wgslfuzz.semanticspreservingtransformations.DefaultFuzzerSettings
import com.wgslfuzz.semanticspreservingtransformations.addDivergentCountersV0
import com.wgslfuzz.semanticspreservingtransformations.addDivergentCountersV1
import com.wgslfuzz.semanticspreservingtransformations.addDivergentCountersV2
import com.wgslspe.core.rewriteWorkgroupSize
import com.wgslspe.core.stripAstWriterTrailingCommas
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.default
import kotlinx.cli.required
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.random.Random
import kotlin.random.asJavaRandom
import kotlin.system.exitProcess

// Applies addDivergentCounters (DivergentCounters.kt) to a supplied shader, bypassing initMetamorphicTransformations' random pick over the full transformation list.
//
// --workgroupSize is REQUIRED whenever --injectDivergence is set.
fun main(args: Array<String>) {
    val parser = ArgParser("apply-divergent-counters")

    val shaderFilePath by parser
        .option(ArgType.String, fullName = "shader", description = "Path to the input .wgsl shader")
        .required()

    val outputFilePath by parser
        .option(ArgType.String, fullName = "output", description = "Path to write the transformed .wgsl")
        .required()

    val seed by parser
        .option(ArgType.Int, fullName = "seed", description = "PRNG seed controlling which candidate sites get a counter pair")
        .default(0)

    val injectDivergence by parser
        .option(ArgType.Boolean, fullName = "injectDivergence", description = "Apply addDivergentCounters")
        .default(false)

    val workgroupSize by parser
        .option(
            ArgType.Int,
            fullName = "workgroupSize",
            description = "New (x) @workgroup_size to splice in before parsing. Required when --injectDivergence is set.",
        )

    val divergenceVersion by parser
        .option(
            ArgType.Int,
            fullName = "divergenceVersion",
            shortName = "dv",
            description = "Which addDivergentCounters variant to apply: 0 or 1. Required when --injectDivergence is set.",
        )

    val inputsFilePath by parser
        .option(
            ArgType.String,
            fullName = "inputs",
            description = "Path to the base inputs.json. Required with --divergenceVersion 1: the v1 " +
                "transformation adds a storage input buffer (the single-thread selector), so its byte " +
                "value must be appended to the inputs fed to the variant.",
        ).default("inputs.json")

    val augmentedInputsFilePath by parser
        .option(
            ArgType.String,
            fullName = "inputs-out",
            description = "Where to write the augmented inputs.json for --divergenceVersion 1 (base " +
                "--inputs is left untouched, since the uninstrumented original still runs against it).",
        ).default("inputsWithThread.json")

    val threadToRun by parser
        .option(
            ArgType.Int,
            fullName = "threadToRun",
            description = "local_invocation_id.x value the v1 single-thread guard admits, written into the " +
                "injected input buffer. Must match the constant the v1 transformation compares against.",
        ).default(60)

    val parseTimeout by parser
        .option(
            ArgType.Int,
            fullName = "parse-timeout",
            description = "Timeout in milliseconds for parsing the input shader (default: 10000)",
        ).default(10000)

    parser.parse(args)

    if (injectDivergence && workgroupSize == null) {
        System.err.println(
            "--workgroupSize is required when --injectDivergence is set (local_invocation_id " +
                "is constant under the corpus's default @workgroup_size(1), so intra-workgroup " +
                "divergence needs an explicit size > 1).",
        )
        exitProcess(1)
    }

    if (injectDivergence && divergenceVersion == null) {
        System.err.println("--divergenceVersion is required when --injectDivergence is set (0 or 1).")
        exitProcess(1)
    }
    if (divergenceVersion != null && divergenceVersion !in 0..2) {
        System.err.println("--divergenceVersion must be 0 or 1 or 2, got $divergenceVersion")
        exitProcess(1)
    }
    if (injectDivergence && divergenceVersion == 1 && inputsFilePath == null) {
        System.err.println(
            "--inputs is required with --divergenceVersion 1: the v1 transformation adds a storage input " +
                "buffer, and its value (${threadToRun}i) must be appended to a copy of the inputs fed to " +
                "the variant (written to --inputs-out).",
        )
        exitProcess(1)
    }

    if (!shaderFilePath.endsWith(".wgsl")) {
        System.err.println("Shader file $shaderFilePath must have extension .wgsl")
        exitProcess(1)
    }
    val shaderFile = File(shaderFilePath)
    if (!shaderFile.exists()) {
        System.err.println("Shader file $shaderFilePath does not exist")
        exitProcess(1)
    }

    var shaderText = shaderFile.readText()
    if (workgroupSize != null) {
        shaderText = rewriteWorkgroupSize(shaderText, workgroupSize!!)
    }

    if (!injectDivergence) {
        // Pure text substitution only -- no need to parse/re-serialize, which would otherwise reformat the whole file for no reason.
        File(outputFilePath).writeText(shaderText)
        println("Wrote $outputFilePath")
        return
    }

    val uniformsFile = File(shaderFilePath.removeSuffix(".wgsl") + ".uniforms.json")
    val uniformBuffers: List<BufferInfo> =
        if (uniformsFile.exists()) {
            Json.decodeFromString(uniformsFile.readText())
        } else {
            emptyList()
        }

    val shaderJob = createShaderJob(shaderText, uniformBuffers, timeoutMilliseconds = parseTimeout)
    val fuzzerSettings = DefaultFuzzerSettings(Random(seed.toLong()).asJavaRandom())
    val transformedShaderJob =
        when (divergenceVersion) {
            0 -> addDivergentCountersV0(shaderJob, fuzzerSettings)
            1 -> addDivergentCountersV1(shaderJob, fuzzerSettings)
            else -> addDivergentCountersV2(shaderJob, fuzzerSettings)
        }

    val textOut = ByteArrayOutputStream()
    AstWriter(
        out = PrintStream(textOut),
        emitUniformCommentary = uniformBuffers.isNotEmpty(),
        shaderJob = transformedShaderJob,
    ).emit()
    // AstWriter emits trailing commas wgslsmith's own parser rejects (see stripAstWriterTrailingCommas).
    val transformedText = stripAstWriterTrailingCommas(textOut.toString("UTF-8"))

    if ("divergent_counter_" !in transformedText) {
        System.err.println(
            "No @compute entry point found, or no candidate site happened to be selected for " +
                "injection (each site is an independent 50% coin flip) -- output is unchanged " +
                "apart from the workgroup size. Try a different --seed.",
        )
    }

    File(outputFilePath).writeText(transformedText)
    println("Wrote $outputFilePath")

    if (divergenceVersion == 1) {
        writeAugmentedInputs(
            baseInputsPath = inputsFilePath!!,
            outInputsPath = augmentedInputsFilePath,
            original = shaderJob,
            transformed = transformedShaderJob,
            threadToRun = threadToRun,
        )
    }
}

// The v1 transformation appends one storage `read` input buffer (the single-thread selector). 
// Copy the base inputs and adds one entry -- "group:binding": <threadToRun as
// little-endian i32 bytes> -- for the buffer applyV1 introduced. 
// The buffer is located by diffing the storage-read buffers before and after the transform rather than by name, so this stays decoupled from applyV1's internal naming.
private fun writeAugmentedInputs(
    baseInputsPath: String,
    outInputsPath: String,
    original: ShaderJob,
    transformed: ShaderJob,
    threadToRun: Int,
) {
    val added = storageReadBindings(transformed) - storageReadBindings(original)
    if (added.size != 1) {
        // No additional input read buffer was injected.
        // Still emit a copy so the caller has a single inputs file to feed every variant.
        System.err.println(
            "Expected v1 to add exactly one storage-read input buffer, found ${added.size} " +
                "(${added.joinToString()}). Leaving $baseInputsPath un-augmented at $outInputsPath.",
        )
        File(outInputsPath).writeText(File(baseInputsPath).readText())
        return
    }
    val (group, binding) = added.first()

    val base = Json.parseToJsonElement(File(baseInputsPath).readText()).jsonObject
    val bytes = JsonArray(intToLittleEndianBytes(threadToRun).map { JsonPrimitive(it) })
    val augmented = JsonObject(base + ("$group:$binding" to bytes))
    File(outInputsPath).writeText(augmented.toString())
    println("Wrote $outInputsPath (added ${threadToRun}i at $group:$binding)")
}

private fun storageReadBindings(job: ShaderJob): Set<Pair<Int, Int>> =
    job.tu.globalDecls
        .filterIsInstance<GlobalDecl.Variable>()
        .filter { it.addressSpace == AddressSpace.STORAGE && it.accessMode == AccessMode.READ }
        .mapNotNull(::groupAndBinding)
        .toSet()

private fun groupAndBinding(v: GlobalDecl.Variable): Pair<Int, Int>? {
    var group: Int? = null
    var binding: Int? = null
    for (attr in v.attributes) {
        when (attr) {
            is Attribute.Group -> group = intLiteralOf(attr.expression)
            is Attribute.Binding -> binding = intLiteralOf(attr.expression)
            else -> {}
        }
    }
    return if (group != null && binding != null) group to binding else null
}

private fun intLiteralOf(expr: Expression): Int? {
    val lit = expr as? Expression.IntLiteral ?: return null
    // IntLiteral text may carry an i/u type suffix (e.g. "60i"); strip it before parsing.
    return lit.text.trimEnd('i', 'u', 'U', 'I').trim().toIntOrNull()
}

// Uses unisgned right shift
private fun intToLittleEndianBytes(value: Int): List<Int> =
    listOf(
        value and 0xFF,
        (value ushr 8) and 0xFF,
        (value ushr 16) and 0xFF,
        (value ushr 24) and 0xFF,
    )
