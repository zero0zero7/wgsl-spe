package com.wgslspe.core

import com.wgslfuzz.core.AccessMode
import com.wgslfuzz.core.BufferInfo
import com.wgslfuzz.core.createShaderJob
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.assertEquals

// Encode a list of Int32 values into a flat byte list (little-endian).
private fun intsToBytes(vararg values: Int): List<Int> {
    val buf = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
    values.forEach { buf.putInt(it) }
    return buf.array().map { it.toInt() and 0xFF } // convert byte from unsigned to signed (C++ interprets as signed)
}

// Decode the flat byte list from a BufferResult back into Int32 values (little-endian).
private fun bytesToInts(bytes: List<Int>): List<Int> {
    val buf = ByteBuffer.wrap(bytes.map { it.toByte() }.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
    return (0 until bytes.size / 4).map { buf.getInt() }
}

// Encode a list of Float32 values into a flat byte list (little-endian).
private fun floatsToBytes(vararg values: Float): List<Int> {
    val buf = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
    values.forEach { buf.putFloat(it) }
    return buf.array().map { it.toInt() and 0xFF }
}

// Decode the flat byte list from a BufferResult back into Float32 values (little-endian).
private fun bytesToFloats(bytes: List<Int>): List<Float> {
    val buf = ByteBuffer.wrap(bytes.map { it.toByte() }.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
    return (0 until bytes.size / 4).map { buf.getFloat() }
}

private fun findBuffer(results: List<BufferResult>, group: Int, binding: Int): BufferResult =
    results.first { it.group == group && it.binding == binding }

class DawnExecutionTests {

    // -------------------------------------------------------------------------
    // Basic scalar write
    // -------------------------------------------------------------------------

    @Test
    fun scalarWriteToOutputBuffer() {
        // Writes the constant 42 into output[0].
        val src = """
            @group(0) @binding(0) var<storage, read_write> output: array<i32, 1>;

            @compute @workgroup_size(1)
            fn main() {
                output[0] = 42;
            }
        """.trimIndent()
        val buffers = listOf(
            BufferInfo(group = 0, binding = 0, accessMode = AccessMode.READ_WRITE, data = intsToBytes(0)),
        )
        val job = createShaderJob(src, buffers)
        val results = DawnHarness.execute(job)

        val outputBuf = findBuffer(results, group = 0, binding = 0)
        assertEquals(listOf(42), bytesToInts(outputBuf.data))
    }

    // -------------------------------------------------------------------------
    // Integer addition from two read buffers into a write buffer
    // -------------------------------------------------------------------------

    @Test
    fun addTwoInputBuffers() {
        // result[i] = a[i] + b[i] for i in 0..3
        val src = """
            @group(0) @binding(0) var<storage, read_write> result: array<i32, 4>;
            @group(0) @binding(1) var<storage, read>       a:      array<i32, 4>;
            @group(0) @binding(2) var<storage, read>       b:      array<i32, 4>;

            @compute @workgroup_size(4)
            fn main(@builtin(local_invocation_index) lid: u32) {
                result[lid] = a[lid] + b[lid];
            }
        """.trimIndent()

        val aData = intsToBytes(1, 2, 3, 4)
        val bData = intsToBytes(10, 20, 30, 40)
        val buffers = listOf(
            BufferInfo(group = 0, binding = 0, accessMode = AccessMode.READ_WRITE, data = intsToBytes(0, 0, 0, 0)),
            BufferInfo(group = 0, binding = 1, accessMode = AccessMode.READ, data = aData),
            BufferInfo(group = 0, binding = 2, accessMode = AccessMode.READ, data = bData),
        )
        val job = createShaderJob(src, buffers)
        val results = DawnHarness.execute(job)

        val outputBuf = findBuffer(results, group = 0, binding = 0)
        assertEquals(listOf(11, 22, 33, 44), bytesToInts(outputBuf.data))
    }

    // -------------------------------------------------------------------------
    // Float addition
    // -------------------------------------------------------------------------

    @Test
    fun addTwoFloatBuffers() {
        val src = """
            @group(0) @binding(0) var<storage, read_write> result: array<f32, 2>;
            @group(0) @binding(1) var<storage, read>       a:      array<f32, 2>;
            @group(0) @binding(2) var<storage, read>       b:      array<f32, 2>;

            @compute @workgroup_size(2)
            fn main(@builtin(local_invocation_index) lid: u32) {
                result[lid] = a[lid] + b[lid];
            }
        """.trimIndent()

        val buffers = listOf(
            BufferInfo(group = 0, binding = 0, accessMode = AccessMode.READ_WRITE, data = floatsToBytes(0f, 0f)),
            BufferInfo(group = 0, binding = 1, accessMode = AccessMode.READ, data = floatsToBytes(1.5f, 2.5f)),
            BufferInfo(group = 0, binding = 2, accessMode = AccessMode.READ, data = floatsToBytes(3.0f, 4.0f)),
        )
        val job = createShaderJob(src, buffers)
        val results = DawnHarness.execute(job)

        val outputBuf = findBuffer(results, group = 0, binding = 0)
        val floats = bytesToFloats(outputBuf.data)
        assertEquals(2, floats.size)
        assertEquals(4.5f, floats[0], absoluteTolerance = 1e-6f)
        assertEquals(6.5f, floats[1], absoluteTolerance = 1e-6f)
    }

    // -------------------------------------------------------------------------
    // vec2<f32> storage buffer (matches ex2.wgsl pattern)
    // -------------------------------------------------------------------------

    @Test
    fun vec2AdditionMatchesEx2() {
        // Reads shader from file; supplies buffers programmatically with correct float bytes.
        // a = (3.0, 4.0), b = (1.0, 2.0) → result = (4.0, 6.0)
        val buffers = listOf(
            BufferInfo(group = 0, binding = 0, accessMode = AccessMode.READ_WRITE, data = floatsToBytes(0f, 0f)),
            BufferInfo(group = 0, binding = 1, accessMode = AccessMode.READ_WRITE, data = floatsToBytes(3f, 4f)),
            BufferInfo(group = 0, binding = 2, accessMode = AccessMode.READ_WRITE, data = floatsToBytes(1f, 2f)),
        )
        val job = createShaderJob(File("samples/ex2.wgsl").readText(), buffers)
        val results = DawnHarness.execute(job)

        val outputBuf = findBuffer(results, group = 0, binding = 0)
        val floats = bytesToFloats(outputBuf.data)
        assertEquals(4.0f, floats[0], absoluteTolerance = 1e-6f)
        assertEquals(6.0f, floats[1], absoluteTolerance = 1e-6f)
    }

    // -------------------------------------------------------------------------
    // Conditional / branch path
    // -------------------------------------------------------------------------

    @Test
    fun conditionalWriteDependingOnInput() {
        // output[0] = input[0] > 5 ? 1 : 0
        val src = """
            @group(0) @binding(0) var<storage, read_write> output: array<i32, 1>;
            @group(0) @binding(1) var<storage, read>       input:  array<i32, 1>;

            @compute @workgroup_size(1)
            fn main() {
                if (input[0] > 5) {
                    output[0] = 1;
                } else {
                    output[0] = 0;
                }
            }
        """.trimIndent()

        fun run(inputVal: Int): Int {
            val buffers = listOf(
                BufferInfo(group = 0, binding = 0, accessMode = AccessMode.READ_WRITE, data = intsToBytes(0)),
                BufferInfo(group = 0, binding = 1, accessMode = AccessMode.READ, data = intsToBytes(inputVal)),
            )
            val job = createShaderJob(src, buffers)
            val results = DawnHarness.execute(job)
            return bytesToInts(findBuffer(results, 0, 0).data)[0]
        }

        assertEquals(0, run(3))
        assertEquals(0, run(5))
        assertEquals(1, run(6))
        assertEquals(1, run(100))
    }

    // -------------------------------------------------------------------------
    // Multiple workgroups — parallel writes
    // -------------------------------------------------------------------------

    @Test
    fun eachInvocationWritesItsIndex() {
        // output[i] = i for i in 0..7
        val src = """
            @group(0) @binding(0) var<storage, read_write> output: array<u32, 8>;

            @compute @workgroup_size(8)
            fn main(@builtin(local_invocation_index) lid: u32) {
                output[lid] = lid;
            }
        """.trimIndent()

        val buffers = listOf(
            BufferInfo(group = 0, binding = 0, accessMode = AccessMode.READ_WRITE,
                data = intsToBytes(0, 0, 0, 0, 0, 0, 0, 0)),
        )
        val job = createShaderJob(src, buffers)
        val results = DawnHarness.execute(job)

        val outputBuf = findBuffer(results, group = 0, binding = 0)
        // Reinterpret as unsigned by masking — they fit in Int for 0-7.
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7), bytesToInts(outputBuf.data))
    }

    // -------------------------------------------------------------------------
    // Unchanged read-only buffer is not modified
    // -------------------------------------------------------------------------

    @Test
    fun readOnlyBufferIsUnchanged() {
        val src = """
            @group(0) @binding(0) var<storage, read_write> output: array<i32, 1>;
            @group(0) @binding(1) var<storage, read>       input:  array<i32, 1>;

            @compute @workgroup_size(1)
            fn main() {
                output[0] = input[0] * 2;
            }
        """.trimIndent()

        val buffers = listOf(
            BufferInfo(group = 0, binding = 0, accessMode = AccessMode.READ_WRITE, data = intsToBytes(0)),
            BufferInfo(group = 0, binding = 1, accessMode = AccessMode.READ, data = intsToBytes(7)),
        )
        val job = createShaderJob(src, buffers)
        val results = DawnHarness.execute(job)

        val outputBuf = findBuffer(results, group = 0, binding = 0)
        println(results)
        println(outputBuf)
        assertEquals(listOf(14), bytesToInts(outputBuf.data))
    }
}