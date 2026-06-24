package com.ditchoom.onebrc

import java.io.BufferedWriter
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.Random

/** 1 MiB output buffer for the dataset writer. */
private const val WRITE_BUFFER_BYTES = 1 shl 20

actual fun generateDataset(
    path: String,
    rows: Long,
    seed: Int,
) {
    val rng = Random(seed.toLong())
    val stations = StationData.stations
    val count = stations.size
    BufferedWriter(OutputStreamWriter(FileOutputStream(path), Charsets.UTF_8), WRITE_BUFFER_BYTES).use { writer ->
        var i = 0L
        while (i < rows) {
            val station = stations[rng.nextInt(count)]
            val temperature = station.mean + rng.nextGaussian() * 10.0
            // Clamp to the 1BRC range (one or two integer digits, one fractional digit).
            var tenths = Math.round(temperature * 10.0).toInt()
            if (tenths > DatasetGen.MAX_TENTHS) tenths = DatasetGen.MAX_TENTHS
            if (tenths < DatasetGen.MIN_TENTHS) tenths = DatasetGen.MIN_TENTHS

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
    writer.write((abs / DatasetGen.TENTHS_PER_UNIT).toString())
    writer.write('.'.code)
    writer.write('0'.code + (abs % DatasetGen.TENTHS_PER_UNIT))
}
