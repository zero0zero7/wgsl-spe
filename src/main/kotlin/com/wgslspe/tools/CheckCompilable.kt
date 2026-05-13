package com.wgslspe.tools

import com.wgslfuzz.core.AstWriter
import com.wgslfuzz.core.TranslationUnit
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.required
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.PrintStream


fun isCompilable(tu: TranslationUnit): String {
    val wgslString = ByteArrayOutputStream().also { AstWriter(PrintStream(it)).emit(tu) }
        .toString() // AstWriter with output to PrintStream which writes to ByteArrayOutputStream. The output is from emit(tu) which emits the source code strings
    val tempFile = File.createTempFile("check_", ".wgsl").also {
        it.writeText(wgslString)
        it.deleteOnExit()
    }
    val output = isCompilable(tempFile.absolutePath)
    tempFile.delete()
    return output
}

fun isCompilable(filePath: String, tintPath: String = System.getenv("TINT_PATH") ?: "tint"): String {
    println(tintPath)
    val f = File(filePath)
    if (f.isFile() && f.extension=="wgsl") {
        return try {
            val process = ProcessBuilder(tintPath, filePath)
                .redirectErrorStream(true) // Merges stderr into stdout so both come out of a single stream. This prevents the process from blocking if its stderr buffer fills up.
                .start()
            val output = process.inputStream.bufferedReader().readText()
            if (process.waitFor() == 0) "Success" else "fail with $output"
            /*
            Correct implementation:
              1. readText() blocks, draining the output buffer as tint writes to it
              2. tint finishes and closes its end of the pipe
              3. readText() sees EOF and returns with everything
              4. waitFor() returns immediately (process is already done)
            Wrong -- The deadlock scenario with the reversed order would be:
              1. waitFor() blocks waiting for tint to exit
              2. tint tries to write output but its buffer is full (nobody is reading)
              3. tint blocks waiting for the buffer to drain
              4. Both processes wait on each other forever
             */
        }
        finally {}
    }
    else {
        throw IllegalArgumentException("$filePath is not a .wgsl file")
    }

}


fun main(args: Array<String>) {
    val parser = ArgParser("wgsl-fuzz pretty printer")

    val path by parser
        .option(
            ArgType.String,
            fullName = "path",
            description = "Path to the shader(s) to be compiled",
        ).required()

    val outputFile by parser
        .option(
            ArgType.String,
            fullName = "output",
            description = "File to which the results will be written",
        )

    parser.parse(args)

    val f = File(path)
    if (f.isFile) {
        val output = isCompilable(f.absolutePath)
        if (outputFile != null) {
            FileOutputStream(outputFile!!).bufferedWriter().use {it.write(output)}
        }
    }
    else if (f.isDirectory) {
        f.listFiles()?.filter{ it.isFile && it.extension=="wgsl" }?.forEach { f ->
            val output = isCompilable(f.absolutePath)
            if (outputFile != null) {
                FileOutputStream(outputFile!!).bufferedWriter().use { it.write(output) }
            }
        }
    }
}
