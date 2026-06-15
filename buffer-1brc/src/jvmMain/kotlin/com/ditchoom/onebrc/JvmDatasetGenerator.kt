package com.ditchoom.onebrc

import java.io.BufferedWriter
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.Random

actual fun generateDataset(
    path: String,
    rows: Long,
    seed: Int,
) {
    val rng = Random(seed.toLong())
    val stations = StationData.stations
    val count = stations.size
    BufferedWriter(OutputStreamWriter(FileOutputStream(path), Charsets.UTF_8), 1 shl 20).use { writer ->
        var i = 0L
        while (i < rows) {
            val station = stations[rng.nextInt(count)]
            val temperature = station.mean + rng.nextGaussian() * 10.0
            // Clamp to the 1BRC range (one or two integer digits, one fractional digit).
            var tenths = Math.round(temperature * 10.0).toInt()
            if (tenths > 999) tenths = 999
            if (tenths < -999) tenths = -999

            writer.write(station.name)
            writer.write(';'.code)
            writeTenths(writer, tenths)
            writer.write('\n'.code)
            i++
        }
    }
}

private fun writeTenths(
    writer: BufferedWriter,
    tenths: Int,
) {
    if (tenths < 0) writer.write('-'.code)
    val abs = if (tenths < 0) -tenths else tenths
    writer.write((abs / 10).toString())
    writer.write('.'.code)
    writer.write('0'.code + (abs % 10))
}
