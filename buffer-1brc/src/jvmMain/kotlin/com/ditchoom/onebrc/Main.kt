package com.ditchoom.onebrc

import java.io.File

/**
 * Convenience timing runner for ad-hoc local measurement (the @Benchmark in jvmBenchmark is the
 * formal harness). Generates a dataset if one isn't supplied, then prints wall-clock and throughput.
 *
 * Usage: `./gradlew :buffer-1brc:onebrcRun -Ponebrc.rows=1000000000`
 *   or `... -Ponebrc.file=/path/to/measurements.txt -Ponebrc.workers=24`
 */
fun main(args: Array<String>) {
    var rows = 10_000_000L
    var workers = defaultParallelism()
    var file: String? = null
    var out: String? = null
    var repeat = 1
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--rows" -> rows = args[++i].toLong()
            "--workers" -> workers = args[++i].toInt()
            "--file" -> file = args[++i]
            "--out" -> out = args[++i] // generate here and keep (don't delete)
            "--repeat" -> repeat = args[++i].toInt() // re-solve N times in one JVM (warm timing / profiling)
        }
        i++
    }

    val path =
        file ?: run {
            val generated = out?.let { File(it) } ?: File.createTempFile("onebrc-run-", ".txt").also { it.deleteOnExit() }
            print("Generating $rows rows -> ${generated.absolutePath} ... ")
            val started = System.nanoTime()
            generateDataset(generated.absolutePath, rows, seed = 1)
            println("done in ${fmt((System.nanoTime() - started) / 1e9)}s (${generated.length() / 1_000_000} MB)")
            generated.absolutePath
        }

    val sizeMb = File(path).length() / 1_000_000.0
    var result = ""
    for (run in 1..repeat) {
        val started = System.nanoTime()
        result = OneBrc.solveFile(path, workers)
        val elapsed = (System.nanoTime() - started) / 1e9
        val label = if (repeat > 1) "[${run}/$repeat] " else ""
        println("${label}Solved ${fmt(sizeMb)} MB with $workers workers in ${fmt(elapsed)}s  (${fmt(sizeMb / elapsed)} MB/s)")
    }
    val preview = if (result.length > 140) result.substring(0, 140) + "…}" else result
    println("Output: $preview")
}

private fun fmt(value: Double): String {
    val scaled = kotlin.math.round(value * 1000) / 1000.0
    return scaled.toString()
}
