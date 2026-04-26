/*
 * Copyright 2025 The wgsl-fuzz Project Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.wgslfuzz.tools

import com.wgslfuzz.core.AstWriter
import com.wgslfuzz.core.UniformBufferInfoByteLevel
import com.wgslfuzz.core.createShaderJob
import com.wgslfuzz.core.collectSkeletalCandidates
import com.wgslfuzz.core.allReplacementSkeletons
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.default
import kotlinx.cli.required
import kotlinx.serialization.json.Json
import java.io.File
import java.io.PrintStream
import kotlin.system.exitProcess
import java.io.FileOutputStream

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
        ).default(1)

    val outputDir by parser
        .option(
            ArgType.String,
            fullName = "output-dir",
            description = "Directory to write each skeleton as a numbered .wgsl file (optional)",
        ).default("out")
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
    val uniformBuffers: List<UniformBufferInfoByteLevel> =
        if (uniformsFile.exists()) {
            Json.decodeFromString(uniformsFile.readText())
        } else {
            emptyList()
        }

    val shaderJob = createShaderJob(shaderFile.readText(), uniformBuffers)
    val tu = shaderJob.tu
    val env = shaderJob.environment

    val (decls, usages) = collectSkeletalCandidates(tu, env)
    println("// Input: $shaderPath")
    println("// Found ${decls.size} declarations and ${usages.size} usages(s)\n")

    val skeletons = allReplacementSkeletons(tu, env, maxReplacements)
    val maxSkeletons = limit ?: Int.MAX_VALUE
    for ((idx, skeleton) in skeletons.take(maxSkeletons).withIndex()) {
        val fileName = "skeleton_%03d.wgsl".format(idx)
        AstWriter(out = PrintStream(FileOutputStream(File(outDir, fileName)))).emit(skeleton)
    }
}