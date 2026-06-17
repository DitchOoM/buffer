@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.onebrc

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fputs
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.round
import kotlin.math.sqrt
import kotlin.random.Random

actual fun generateDataset(
    path: String,
    rows: Long,
    seed: Int,
) {
    val file = fopen(path, "w") ?: error("fopen() failed for $path")
    try {
        val random = Random(seed)
        val stations = StationData.stations
        val count = stations.size
        val line = StringBuilder(48)
        var i = 0L
        while (i < rows) {
            val station = stations[random.nextInt(count)]
            val temperature = station.mean + nextGaussian(random) * 10.0
            var tenths = round(temperature * 10.0).toInt()
            if (tenths > 999) tenths = 999
            if (tenths < -999) tenths = -999

            line.setLength(0)
            line.append(station.name).append(';')
            if (tenths < 0) line.append('-')
            val abs = if (tenths < 0) -tenths else tenths
            line
                .append(abs / 10)
                .append('.')
                .append(abs % 10)
                .append('\n')
            fputs(line.toString(), file)
            i++
        }
    } finally {
        fclose(file)
    }
}

/** Box–Muller transform — Kotlin's Random has no nextGaussian on Native. */
private fun nextGaussian(random: Random): Double {
    var u1 = random.nextDouble()
    if (u1 < 1e-12) u1 = 1e-12
    val u2 = random.nextDouble()
    return sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)
}
