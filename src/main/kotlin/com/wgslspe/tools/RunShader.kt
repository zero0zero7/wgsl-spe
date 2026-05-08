package com.wgslspe.tools

import com.wgslfuzz.core.UniformBufferInfoByteLevel
import com.wgslspe.core.BufferResult
import com.wgslspe.core.DawnHarness
import com.wgslfuzz.core.createShaderJob
import com.wgslspe.core.getBufferDescriptors
import com.wgslspe.core.toWgslString
import com.wgslspe.core.getComputeEntryPoint
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.required
import kotlinx.serialization.json.Json
import java.io.File

fun main(args: Array<String>) {
    val parser = ArgParser("run-shader")

    val shaderPath by parser.option(
        ArgType.String,
        fullName = "path",
        description = "Path to the .wgsl shader file",
    ).required()

    val uniformsPath by parser.option(
        ArgType.String,
        fullName = "uniforms",
        description = "Path to uniforms.json ([{group,binding,data}]); defaults to <shader>.uniforms.json if it exists",
    )

    parser.parse(args)

    val shaderFile = File(shaderPath)
    require(shaderFile.exists()) { "Shader file not found: $shaderPath" }

    val resolvedUniformsPath = uniformsPath
        ?: shaderPath.removeSuffix(".wgsl") + ".uniforms.json"

    val uniformBuffers: List<UniformBufferInfoByteLevel> =
        File(resolvedUniformsPath).takeIf { it.exists() }
            ?.let { Json.decodeFromString(it.readText()) }
            ?: emptyList()

    val shaderJob = createShaderJob(shaderFile.readText(), uniformBuffers)
    val results: List<BufferResult> = DawnHarness.execute(shaderJob)

    println(Json { prettyPrint = true }.encodeToString(results))
}