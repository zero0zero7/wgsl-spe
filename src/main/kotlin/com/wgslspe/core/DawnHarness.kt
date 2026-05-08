package com.wgslspe.core

import com.wgslfuzz.core.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.system.exitProcess

// ── WGSL buffer layout helpers ────────────────────────────────────────────────

private fun roundUp(value: Int, align: Int): Int = ((value + align - 1) / align) * align

private fun arrayStride(elementType: Type): Int =
    roundUp(bufferSizeInBytes(elementType), elementType.alignOf())

fun bufferSizeInBytes(type: Type): Int =
    when (type) {
        Type.Bool, Type.I32, Type.U32, Type.F32 -> 4
        Type.F16 -> 2
        is Type.Vector -> type.width * bufferSizeInBytes(type.elementType)
        is Type.Matrix -> {
            val colType = Type.Vector(type.numRows, type.elementType)
            type.numCols * roundUp(bufferSizeInBytes(colType), colType.alignOf())
        }
        is Type.Array -> {
            require(type.elementCount != null) { "Runtime-sized array needs explicit size hint" }
            type.elementCount * arrayStride(type.elementType)
        }
        is Type.Struct -> {
            var offset = 0
            for ((_, memberType) in type.members) {
                offset = roundUp(offset, memberType.alignOf())
                offset += bufferSizeInBytes(memberType)
            }
            roundUp(offset, type.alignOf())
        }
        is Type.AtomicI32, is Type.AtomicU32 -> 4
        else -> throw UnsupportedOperationException("bufferSizeInBytes not supported for $type")
    }

// ── Transfer types ────────────────────────────────────────────────────────────

// Sent to C++: describes each buffer the shader needs, including its type and initial bytes.
@Serializable
data class BufferDescriptor(
    val group: Int,
    val binding: Int,
    val bufferType: String, // "uniform", "storage_r", "storage_rw"
    val data: List<Int>,    // raw bytes; size already correct per WGSL layout rules
)

// Returned from C++: the readback contents of each storage_rw buffer.
@Serializable
data class BufferResult(
    val group: Int,
    val binding: Int,
    val data: List<Int>,
)

// ── JNI bridge ────────────────────────────────────────────────────────────────

object DawnHarness {
    init {
        System.loadLibrary("dawn_harness_jni")
    }

    external fun executeShader(
        shaderSource: String,
        entryPoint: String,
        buffersJson: String,
    ): String

    fun execute(shaderJob: ShaderJob): List<BufferResult> {
        val source = shaderJob.toWgslString()
        val entry = shaderJob.tu.getComputeEntryPoint()
        val buffers = shaderJob.getBufferDescriptors()
        val resultJson = executeShader(source, entry, Json.encodeToString(buffers))
        return Json.decodeFromString(resultJson)
    }
}

// ── Extensions ────────────────────────────────────────────────────────────────

fun ShaderJob.toWgslString(): String =
    ByteArrayOutputStream()
        .also { AstWriter(PrintStream(it)).emit(tu) }
        .toString("UTF-8")

// TODO: selects the first @compute function; a module may declare multiple entry points
fun TranslationUnit.getComputeEntryPoint(): String =
    globalDecls
        .filterIsInstance<GlobalDecl.Function>()
        .first { fn -> fn.attributes.any { it is Attribute.Compute } }
        .name

fun ShaderJob.getBufferDescriptors(): List<BufferDescriptor> {
    // buffers extracted from AST
    val uniformData = getByteLevelContentsForUniformBuffers()
        .associateBy { it.group to it.binding }
    // buffers defined in .wgsl -- map to buffer in uniforms.json if group and binding matches, else add new entry
    return tu.globalDecls
        .filterIsInstance<GlobalDecl.Variable>()
        .filter { v ->
            v.attributes.any { it is Attribute.Group } &&
            v.attributes.any { it is Attribute.Binding }
        }
        .mapNotNull { v ->
            val group = (v.attributes.filterIsInstance<Attribute.Group>().first().expression as Expression.IntLiteral).text.toInt()
            val binding = (v.attributes.filterIsInstance<Attribute.Binding>().first().expression as Expression.IntLiteral).text.toInt()
            val bufferType = when {
                v.addressSpace == AddressSpace.STORAGE && v.accessMode == AccessMode.READ_WRITE -> "storage_rw"
                v.addressSpace == AddressSpace.STORAGE -> "storage_r"
                v.addressSpace == AddressSpace.UNIFORM -> "uniform"
                else -> return@mapNotNull null // textures, samplers — not data buffers
            }
            val data = uniformData[group to binding]?.data ?: run {
                // Buffer not in pipelineState: derive byte size from the resolved store type
                // so C++ allocates the correct number of bytes for any nested type.
                val storeType = (environment.globalScope.getEntry(v.name) as ScopeEntry.TypedDecl)
                    .type.asStoreTypeIfReference()
                List(bufferSizeInBytes(storeType)) { 0 }
            }
            BufferDescriptor(group, binding, bufferType, data)
        }
}

//fun extractBuffer(shaderPath: String): List<BufferDescriptor> {
//    val shaderFile = File(shaderPath)
//    if (!shaderFile.exists()) {
//        System.err.println("Shader file $shaderPath does not exist")
//        exitProcess(1)
//    }
//
//    val uniformsFile = File(shaderPath.removeSuffix(".wgsl") + ".uniforms.json")
//    val uniformBuffers: List<UniformBufferInfoByteLevel> =
//        if (uniformsFile.exists()) {
//            Json.decodeFromString(uniformsFile.readText())
//        } else {
//            emptyList()
//        }
//
//    val shaderJob = createShaderJob(shaderFile.readText(), uniformBuffers)
//    return shaderJob.getBufferDescriptors()
//}