package com.ditchoom.onebrc

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
    val random = Random(seed)
    val stations = StationData.stations
    val count = stations.size
    val sb = StringBuilder()
    var i = 0L
    while (i < rows) {
        val station = stations[random.nextInt(count)]
        val temperature = station.mean + nextGaussian(random) * 10.0
        var tenths = round(temperature * 10.0).toInt()
        if (tenths > 999) tenths = 999
        if (tenths < -999) tenths = -999

        sb.append(station.name).append(';')
        if (tenths < 0) sb.append('-')
        val abs = if (tenths < 0) -tenths else tenths
        sb
            .append(abs / 10)
            .append('.')
            .append(abs % 10)
            .append('\n')
        i++
    }
    nodeWriteFileUtf8(path, sb.toString())
}

/** Box–Muller transform — Kotlin's Random has no nextGaussian on JS/WASM. */
private fun nextGaussian(random: Random): Double {
    var u1 = random.nextDouble()
    if (u1 < 1e-12) u1 = 1e-12
    val u2 = random.nextDouble()
    return sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)
}
