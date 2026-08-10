package com.wgslspe.core

import com.wgslfuzz.core.BufferInfo
import com.wgslfuzz.core.ShaderJob
import com.wgslfuzz.core.createShaderJob
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess

/**
 * Wraps `createShaderJob` to exit a JVM process when a parse is stuck.
 *
 * core's in-parser guard (the parse-tree listener) is the primary defence, but it cannot help if the JVM is too busy eg. garbage collecting. 
 * Every tool parses exactly one shader per invocation, so exiting loses no other work.
 *
 * The motivating case: some wgslsmith seeds produce deeply nested call arguments containing comparisons, which the
 * ambiguous `<` in the WGSL grammar turns into an Antlr prediction blowup. Before this guard existed, seed 63 ran for
 * 3m21s at --parse-timeout 120000, 5m30s at 300000, and had still not finished when killed at 30 minutes with 1800000.
 */
fun parseWithHardDeadline(
    shaderText: String,
    uniformBuffers: List<BufferInfo>,
    parseTimeoutMillis: Int,
): ShaderJob {
    val result = AtomicReference<Result<ShaderJob>>() // AtomicReference to allow the worker thread to set the result, and the main thread to read it after join
    val worker =
        Thread {
            result.set(
                runCatching {
                    createShaderJob(shaderText, uniformBuffers, timeoutMilliseconds = parseTimeoutMillis)
                },
            )
        }
    worker.isDaemon = true
    worker.start() // excutes createShaderJob on a separate thread, so that we can abandon it if it takes too long
    worker.join(2L * parseTimeoutMillis) // blocks calling thread for 2 (fast SLL and slow LL)*parseTimeoutMillis ms, or until worker thread finishes, whichever comes first
    val outcome: Result<ShaderJob> =
        result.get() ?: run { // result null when worker exits due to timeout
            System.err.println(
                "Parsing timed out. Exceeded hard deadline of ${2L * parseTimeoutMillis} ms; abandoning.",
            )
            exitProcess(1)
        }
    return outcome.getOrThrow()
}
