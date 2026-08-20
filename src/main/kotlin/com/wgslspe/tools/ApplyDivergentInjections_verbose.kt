package com.wgslspe.tools

import com.wgslfuzz.core.AccessMode
import com.wgslfuzz.core.AddressSpace
import com.wgslfuzz.core.Attribute
import com.wgslfuzz.core.AstWriter
import com.wgslfuzz.core.BufferInfo
import com.wgslfuzz.core.Expression
import com.wgslfuzz.core.GlobalDecl
import com.wgslfuzz.core.ShaderJob
import com.wgslfuzz.semanticspreservingtransformations.DEFAULT_FUZZER_SEED
import com.wgslfuzz.semanticspreservingtransformations.DEFAULT_THREAD_TO_RUN
import com.wgslfuzz.semanticspreservingtransformations.DefaultFuzzerSettings
import com.wgslfuzz.semanticspreservingtransformations.FuzzerSettings
import com.wgslfuzz.semanticspreservingtransformations.addDivergentInjectionsV0
import com.wgslfuzz.semanticspreservingtransformations.addDivergentInjectionsV1
import com.wgslfuzz.semanticspreservingtransformations.addDivergentInjectionsV2
import com.wgslfuzz.semanticspreservingtransformations.addDivergentInjectionsV3
import com.wgslspe.core.parseWithHardDeadline
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
import java.lang.management.ManagementFactory
import kotlin.random.Random
import kotlin.random.asJavaRandom
import kotlin.system.exitProcess

// Timing-instrumented duplicate of ApplyDivergentInjections.kt (com.wgslspe.tools.ApplyDivergentInjectionsKt).
//
// Identical behaviour and CLI -- see ApplyDivergentInjections.kt for what the flags mean, why
// --workgroupSize is required for v0/v1/v2 but ignored for v3, and how the augmented inputs file is
// built. The only difference here is a "TIMING ..." line written to stderr just before exit,
// breaking the process's wall time into its phases:
//   jvm_startup   process start -> first statement of main (JVM boot + class loading)
//   read          reading the .wgsl (+ uniforms.json) and the @workgroup_size text substitution
//   parse         parseWithHardDeadline: ANTLR parse + ShaderJob construction (resolver etc.)
//   transform     addDivergentInjectionsV<n>: the variant generation proper
//   serialize     AstWriter emit + stripAstWriterTrailingCommas
//   write         writing the variant .wgsl and the augmented inputs.json
// The fuzz scripts otherwise only see the total, which folds all six together.
//
// Run it the same way the launcher runs the production tool, with the same classpath:
//   java -Xmx1g -cp "build/install/tools/lib/*" \
//        com.wgslspe.tools.ApplyDivergentInjections_verboseKt --shader ... --output ...
//
// Kept in step with ApplyDivergentInjections.kt by hand: nothing else references it.
// The no-local-candidate exit status is shared with that file (same package) so the two cannot drift.

private fun ms(fromNanos: Long, toNanos: Long): String = "%.1f".format((toNanos - fromNanos) / 1_000_000.0)

fun main(args: Array<String>) {
    val tMainStart = System.nanoTime()
    // Wall time already burnt before main was entered: JVM boot, class loading, Kotlin runtime init.
    val jvmStartupMs = System.currentTimeMillis() - ManagementFactory.getRuntimeMXBean().startTime

    val parser = ArgParser("apply-divergent-counters")

    val shaderFilePath by parser
        .option(ArgType.String, fullName = "shader", description = "Path to the input .wgsl shader")
        .required()

    val outputFilePath by parser
        .option(ArgType.String, fullName = "output", description = "Path to write the transformed .wgsl")
        .required()

    val seed by parser
        .option(ArgType.Int, fullName = "seed", description = "PRNG seed controlling which candidate sites get a counter pair")
        .default(DEFAULT_FUZZER_SEED.toInt())

    val injectDivergence by parser
        .option(ArgType.Boolean, fullName = "injectDivergence", description = "Apply addDivergentInjections")
        .default(false)

    val workgroupSize by parser
        .option(
            ArgType.Int,
            fullName = "workgroupSize",
            description = "New (x) @workgroup_size to splice in before parsing. Required when --injectDivergence is set, " +
                "except with --divergenceVersion 3, which keeps the shader's own size and ignores this flag.",
        )

    val divergenceVersion by parser
        .option(
            ArgType.Int,
            fullName = "divergenceVersion",
            shortName = "dv",
            description = "Which addDivergentInjections variant to apply: 0, 1, 2 or 3 (3 = v2 without the " +
                "single-thread gate, at the shader's own workgroup size). Required when --injectDivergence is set.",
        )

    val inputsFilePath by parser
        .option(
            ArgType.String,
            fullName = "inputs",
            description = "Path to the base inputs.json.",
        ).default("inputs.json")

    val augmentedInputsFilePath by parser
        .option(
            ArgType.String,
            fullName = "inputs-out",
            description = "Where to write the augmented inputs.json for --divergenceVersion 1, 2 or 3.",
        ).default("inputsWithThread.json")

    val threadToRun by parser
        .option(
            ArgType.Int,
            fullName = "threadToRun",
            description = "local_invocation_id.x value the v1/v2 single-thread gate admits. v3 has no gate and " +
                "runs only invocation 0, so it wants 0 here.",
        ).default(DEFAULT_THREAD_TO_RUN)

    val parseTimeout by parser
        .option(
            ArgType.Int,
            fullName = "parse-timeout",
            description = "Timeout in milliseconds for parsing the input shader (default: 10000)",
        ).default(10000)

    parser.parse(args)

    if (injectDivergence && divergenceVersion != 3 && workgroupSize == null) {
        System.err.println(
            "--workgroupSize is required when --injectDivergence is set. Not so for " +
                "--divergenceVersion 3, which keeps the shader's own workgroup size and ignores the flag.",
        )
        exitProcess(1)
    }
    if (injectDivergence && divergenceVersion == null) {
        System.err.println("--divergenceVersion is required when --injectDivergence is set (0, 1, 2 or 3).")
        exitProcess(1)
    }
    if (divergenceVersion != null && divergenceVersion !in 0..3) {
        System.err.println("--divergenceVersion must be 0, 1, 2 or 3, got $divergenceVersion")
        exitProcess(1)
    }
    divergenceThreadValidationError(injectDivergence, divergenceVersion, workgroupSize, threadToRun)?.let {
        System.err.println(it)
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

    val tReadStart = System.nanoTime()
    var shaderText = shaderFile.readText()
    // v3 runs at the shader's OWN @workgroup_size: --workgroupSize is ignored rather than applied.
    if (workgroupSize != null && divergenceVersion != 3) {
        shaderText = rewriteWorkgroupSize(shaderText, workgroupSize!!)
    }

    if (!injectDivergence) {
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
    val tReadEnd = System.nanoTime()

    val shaderJob = parseWithHardDeadline(shaderText, uniformBuffers, parseTimeout)
    val tParseEnd = System.nanoTime()

    val fuzzerSettings: FuzzerSettings =
        ThreadToRunSettingsVerbose(DefaultFuzzerSettings(Random(seed.toLong()).asJavaRandom()), threadToRun)
    // Same distinct exit status as the production tool, with the phases measured so far still reported.
    fun noLocalCandidate(): Nothing {
        System.err.println(
            "no-local-candidate: no @compute entry point declares a function-scope " +
                "var for v2/v3 to hijack; variant skipped, no output written.",
        )
        val tAbort = System.nanoTime()
        System.err.println(
            "TIMING applyDivergentInjections jvm_startup_ms=$jvmStartupMs " +
                "read_ms=${ms(tReadStart, tReadEnd)} parse_ms=${ms(tReadEnd, tParseEnd)} " +
                "transform_ms=${ms(tParseEnd, tAbort)} serialize_ms=0 write_ms=0 " +
                "in_process_ms=${ms(tMainStart, tAbort)} outcome=no-local-candidate",
        )
        exitProcess(EXIT_NO_LOCAL_CANDIDATE)
    }

    val transformedShaderJob =
        when (divergenceVersion) {
            0 -> addDivergentInjectionsV0(shaderJob, fuzzerSettings)
            1 -> addDivergentInjectionsV1(shaderJob, fuzzerSettings)
            3 -> addDivergentInjectionsV3(shaderJob, fuzzerSettings) ?: noLocalCandidate()
            else -> addDivergentInjectionsV2(shaderJob, fuzzerSettings) ?: noLocalCandidate()
        }
    val tTransformEnd = System.nanoTime()

    val textOut = ByteArrayOutputStream()
    AstWriter(
        out = PrintStream(textOut),
        emitUniformCommentary = uniformBuffers.isNotEmpty(),
        shaderJob = transformedShaderJob,
    ).emit()
    val transformedText = stripAstWriterTrailingCommas(textOut.toString("UTF-8"))
    val tSerializeEnd = System.nanoTime()

    if (divergenceVersion == 1 && "divergent_counter_" !in transformedText) {
        System.err.println(
            "No @compute entry point found, or no candidate site happened to be selected for " +
                "injection -- output is unchanged apart from the workgroup size. Try a different --seed.",
        )
    }

    File(outputFilePath).writeText(transformedText)
    println("Wrote $outputFilePath")

    if ((divergenceVersion ?: 2) >= 1) {
        writeAugmentedInputsVerbose(
            baseInputsPath = inputsFilePath,
            outInputsPath = augmentedInputsFilePath,
            original = shaderJob,
            transformed = transformedShaderJob,
            threadToRun = threadToRun,
        )
    }
    val tWriteEnd = System.nanoTime()

    System.err.println(
        "TIMING applyDivergentInjections jvm_startup_ms=$jvmStartupMs " +
            "read_ms=${ms(tReadStart, tReadEnd)} parse_ms=${ms(tReadEnd, tParseEnd)} " +
            "transform_ms=${ms(tParseEnd, tTransformEnd)} serialize_ms=${ms(tTransformEnd, tSerializeEnd)} " +
            "write_ms=${ms(tSerializeEnd, tWriteEnd)} in_process_ms=${ms(tMainStart, tWriteEnd)} outcome=ok",
    )
}

// Mirror of writeAugmentedInputs in ApplyDivergentInjections.kt (duplicated because that one is
// file-private); see there for why the added buffer is located by diffing rather than by name.
private fun writeAugmentedInputsVerbose(
    baseInputsPath: String,
    outInputsPath: String,
    original: ShaderJob,
    transformed: ShaderJob,
    threadToRun: Int,
) {
    val added = storageReadBindingsVerbose(transformed) - storageReadBindingsVerbose(original)
    if (added.size != 1) {
        System.err.println(
            "augmentation-error: expected v1/v2/v3 to add exactly one storage-read input buffer, " +
                "found ${added.size} (${added.joinToString()}); $outInputsPath was not written.",
        )
        exitProcess(2)
    }
    val (group, binding) = added.first()

    val base = Json.parseToJsonElement(File(baseInputsPath).readText()).jsonObject
    val blob = divergentInputBytesVerbose(threadToRun)
    val bytes = JsonArray(blob.map { JsonPrimitive(it) })
    val augmented = JsonObject(base + ("$group:$binding" to bytes))
    File(outInputsPath).writeText(augmented.toString())
    println("Wrote $outInputsPath (thread ${threadToRun}u + hidden constants, ${blob.size} bytes, at $group:$binding)")
}

private fun storageReadBindingsVerbose(job: ShaderJob): Set<Pair<Int, Int>> =
    job.tu.globalDecls
        .filterIsInstance<GlobalDecl.Variable>()
        .filter { it.addressSpace == AddressSpace.STORAGE && it.accessMode == AccessMode.READ }
        .mapNotNull(::groupAndBindingVerbose)
        .toSet()

private fun groupAndBindingVerbose(v: GlobalDecl.Variable): Pair<Int, Int>? {
    var group: Int? = null
    var binding: Int? = null
    for (attr in v.attributes) {
        when (attr) {
            is Attribute.Group -> group = intLiteralOfVerbose(attr.expression)
            is Attribute.Binding -> binding = intLiteralOfVerbose(attr.expression)
            else -> {}
        }
    }
    return if (group != null && binding != null) group to binding else null
}

private fun intLiteralOfVerbose(expr: Expression): Int? {
    val lit = expr as? Expression.IntLiteral ?: return null
    return lit.text.trimEnd('i', 'u', 'U', 'I').trim().toIntOrNull()
}

private fun intToLittleEndianBytesVerbose(value: Int): List<Int> =
    listOf(
        value and 0xFF,
        (value ushr 8) and 0xFF,
        (value ushr 16) and 0xFF,
        (value ushr 24) and 0xFF,
    )

private class ThreadToRunSettingsVerbose(
    private val delegate: FuzzerSettings,
    private val threadToRun: Int,
) : FuzzerSettings by delegate {
    override fun threadToRun(): Int = threadToRun
}

private fun divergentInputBytesVerbose(threadToRun: Int): List<Int> =
    intToLittleEndianBytesVerbose(threadToRun) +
        intToLittleEndianBytesVerbose(0) +
        intToLittleEndianBytesVerbose(1) +
        intToLittleEndianBytesVerbose(Int.MIN_VALUE) +
        intToLittleEndianBytesVerbose(Int.MAX_VALUE) +
        intToLittleEndianBytesVerbose(0) +
        intToLittleEndianBytesVerbose(1) +
        intToLittleEndianBytesVerbose(0) +
        intToLittleEndianBytesVerbose(-1) +
        intToLittleEndianBytesVerbose(0x00000000) +
        intToLittleEndianBytesVerbose(0x3F800000) +
        intToLittleEndianBytesVerbose(0xFF800000.toInt()) +
        intToLittleEndianBytesVerbose(0x7F800000)
