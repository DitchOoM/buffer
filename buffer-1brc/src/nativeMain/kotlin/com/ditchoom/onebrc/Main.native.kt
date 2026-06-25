package com.ditchoom.onebrc

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.managed
import kotlin.time.TimeSource

/**
 * Native (Linux) timing runner — the native counterpart of the JVM [main]. Build the release
 * executable and run it directly, e.g.
 *   buffer-1brc/build/bin/linuxX64/releaseExecutable/buffer-1brc.kexe --file /tmp/measurements.txt
 */
fun main(args: Array<String>) {
    var rows = 10_000_000L
    var workers = defaultParallelism()
    var file: String? = null
    var repeat = 1
    var scanBackend = "default" // default = scan the mmap region in place (zero-copy NativeBuffer)
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--rows" -> rows = args[++i].toLong()
            "--workers" -> workers = args[++i].toInt()
            "--file" -> file = args[++i]
            "--repeat" -> repeat = args[++i].toInt() // re-solve N times (warm timing / profiling)
            "--managed" -> scanBackend = "managed" // scan a heap ByteArrayBuffer copy
            "--deterministic" -> scanBackend = "deterministic" // scan an off-heap NativeBuffer copy
        }
        i++
    }
    // default = null (scan mmap in place); deterministic/managed copy the chunk into that backend first.
    val scanFactory =
        when (scanBackend) {
            "managed" -> BufferFactory.managed()
            "deterministic" -> BufferFactory.deterministic()
            else -> null
        }

    val path =
        file ?: run {
            val generated = "/tmp/onebrc-native-run.txt"
            print("Generating $rows rows -> $generated ... ")
            val mark = TimeSource.Monotonic.markNow()
            generateDataset(generated, rows, seed = 1)
            println("done in ${secondsSince(mark)}s")
            generated
        }

    var result = ""
    for (run in 1..repeat) {
        val mark = TimeSource.Monotonic.markNow()
        result = OneBrc.solveFile(path, workers, scanFactory = scanFactory)
        val label = if (repeat > 1) "[$run/$repeat] " else ""
        println("${label}Solved with $workers worker(s) [$scanBackend] in ${secondsSince(mark)}s")
    }
    val preview = if (result.length > 140) result.substring(0, 140) + "…}" else result
    println("Output: $preview")
}

private const val MILLIS_PER_SECOND = 1000.0

private fun secondsSince(mark: TimeSource.Monotonic.ValueTimeMark): String {
    val millis = mark.elapsedNow().inWholeMilliseconds
    return (millis / MILLIS_PER_SECOND).toString()
}
