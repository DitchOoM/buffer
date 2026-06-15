package com.ditchoom.onebrc

import kotlin.math.floor
import kotlin.math.round
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * End-to-end tests that run identically on every platform (JVM, Native, …). They use the common
 * [openMappedFile]/[generateDataset] seams plus small expect/actual file helpers, so there is a
 * single source of truth for correctness rather than a per-platform copy.
 */
class OneBrcTest {
    @Test
    fun solvesTinyDatasetExactly() {
        val path = onebrcTempFile("tiny")
        onebrcWriteText(path, "Hamburg;12.0\nHamburg;14.0\nBulawayo;8.9\nHamburg;10.0\n")
        try {
            assertEquals(
                "{Bulawayo=8.9/8.9/8.9, Hamburg=10.0/12.0/14.0}",
                OneBrc.solveFile(path, workers = 1),
            )
        } finally {
            onebrcDeleteFile(path)
        }
    }

    @Test
    fun matchesIndependentReference() {
        val path = onebrcTempFile("ref")
        generateDataset(path, rows = 100_000, seed = 7)
        try {
            assertEquals(reference(path), OneBrc.solveFile(path, workers = 8))
        } finally {
            onebrcDeleteFile(path)
        }
    }

    @Test
    fun singleAndMultiThreadedAgree() {
        val path = onebrcTempFile("agree")
        generateDataset(path, rows = 100_000, seed = 42)
        try {
            assertEquals(
                OneBrc.solveFile(path, workers = 1),
                OneBrc.solveFile(path, workers = 12),
            )
        } finally {
            onebrcDeleteFile(path)
        }
    }

    // Independent reference: reads the whole file via the common mmap seam and aggregates with a
    // plain map — exercising none of OneBrc's hashing/StationTable internals.
    private fun reference(path: String): String {
        val file = openMappedFile(path)
        val text =
            try {
                file.region(0, file.size.toInt()).readString(file.size.toInt())
            } finally {
                file.close()
            }

        val mins = HashMap<String, Int>()
        val maxs = HashMap<String, Int>()
        val sums = HashMap<String, Long>()
        val counts = HashMap<String, Long>()
        for (line in text.split('\n')) {
            if (line.isEmpty()) continue
            val semi = line.indexOf(';')
            val name = line.substring(0, semi)
            val tenths = round(line.substring(semi + 1).toDouble() * 10.0).toInt()
            mins[name] = minOf(mins[name] ?: Int.MAX_VALUE, tenths)
            maxs[name] = maxOf(maxs[name] ?: Int.MIN_VALUE, tenths)
            sums[name] = (sums[name] ?: 0L) + tenths
            counts[name] = (counts[name] ?: 0L) + 1
        }

        val sb = StringBuilder("{")
        mins.keys.sorted().forEachIndexed { i, name ->
            if (i > 0) sb.append(", ")
            val mean = floor(sums.getValue(name).toDouble() / counts.getValue(name).toDouble() + 0.5).toLong()
            sb.append(name).append('=')
            appendTenths(sb, mins.getValue(name).toLong())
            sb.append('/')
            appendTenths(sb, mean)
            sb.append('/')
            appendTenths(sb, maxs.getValue(name).toLong())
        }
        sb.append('}')
        return sb.toString()
    }

    private fun appendTenths(
        sb: StringBuilder,
        tenths: Long,
    ) {
        if (tenths < 0) sb.append('-')
        val abs = if (tenths < 0) -tenths else tenths
        sb.append(abs / 10).append('.').append(abs % 10)
    }
}
