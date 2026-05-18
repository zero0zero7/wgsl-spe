package com.wgslspe.core

import com.wgslfuzz.core.AccessMode
import com.wgslfuzz.core.BufferInfo
import com.wgslfuzz.core.createShaderJob
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
        assertEquals(listOf(14), bytesToInts(outputBuf.data))
    }
}

// Tests that exercise the cached Dawn device introduced to fix the OOM regression.
// Running several sample files in sequence verifies that ensureDawnReady() correctly
// reuses a single native device rather than re-initialising Dawn on every call.
class DawnCachedDeviceTests {

    // -------------------------------------------------------------------------
    // Three different sample shaders executed back-to-back
    // -------------------------------------------------------------------------

    @Test
    fun sequentialSampleFilesExerciseCachedDevice() {
        // 1. ex2.wgsl — vec2<f32> addition: (3,4) + (1,2) = (4,6)
        val ex2Job = createShaderJob(
            File("samples/ex2.wgsl").readText(),
            listOf(
                BufferInfo(0, 0, AccessMode.READ_WRITE, floatsToBytes(0f, 0f)),
                BufferInfo(0, 1, AccessMode.READ_WRITE, floatsToBytes(3f, 4f)),
                BufferInfo(0, 2, AccessMode.READ_WRITE, floatsToBytes(1f, 2f)),
            ),
        )
        val ex2Out = bytesToFloats(findBuffer(DawnHarness.execute(ex2Job), 0, 0).data)
        assertEquals(4.0f, ex2Out[0], absoluteTolerance = 1e-6f)
        assertEquals(6.0f, ex2Out[1], absoluteTolerance = 1e-6f)

        // 2. ex4.wgsl — conditional i32 logic:
        //    a=1, b=2; since a==1 → b=3+5=8; output=[1+a, 1+b]=[2, 9]
        val ex4Job = createShaderJob(
            File("samples/ex4.wgsl").readText(),
            listOf(BufferInfo(0, 0, AccessMode.READ_WRITE, intsToBytes(0, 0))),
        )
        assertEquals(listOf(2, 9), bytesToInts(findBuffer(DawnHarness.execute(ex4Job), 0, 0).data))

        // 3. ex5.wgsl — writes 4 into output[1]; output[0] stays 0
        val ex5Job = createShaderJob(
            File("samples/ex5.wgsl").readText(),
            listOf(BufferInfo(0, 0, AccessMode.READ_WRITE, intsToBytes(0, 0))),
        )
        assertEquals(listOf(0, 4), bytesToInts(findBuffer(DawnHarness.execute(ex5Job), 0, 0).data))
    }

    // -------------------------------------------------------------------------
    // Same shader executed twice — cached device must give identical results
    // -------------------------------------------------------------------------

    @Test
    fun repeatedExecutionOfSameShaderGivesIdenticalResults() {
        fun makeJob() = createShaderJob(
            File("samples/ex4.wgsl").readText(),
            listOf(BufferInfo(0, 0, AccessMode.READ_WRITE, intsToBytes(0, 0))),
        )
        val r1 = bytesToInts(findBuffer(DawnHarness.execute(makeJob()), 0, 0).data)
        val r2 = bytesToInts(findBuffer(DawnHarness.execute(makeJob()), 0, 0).data)
        assertEquals(r1, r2)
    }

    // -------------------------------------------------------------------------
    // Shader with no storage buffers — ensureDawnReady must not crash
    // -------------------------------------------------------------------------

    @Test
    fun shaderWithNoStorageBuffersReturnsEmptyResults() {
        // smith.wgsl declares no @group/@binding variables; the compute shader
        // only manipulates local variables.  Dawn should run it cleanly and
        // return an empty result list.
        val job = createShaderJob(File("samples/smith.wgsl").readText(), emptyList())
        val results = DawnHarness.execute(job)
        assertTrue(results.isEmpty())
    }
}