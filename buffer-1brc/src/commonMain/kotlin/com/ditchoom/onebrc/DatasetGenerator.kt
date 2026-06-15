package com.ditchoom.onebrc

/**
 * Generates a 1BRC-format measurements file at [path] with [rows] lines, sampling stations from
 * [StationData] and drawing temperatures as `mean + gaussian*stddev` rounded to one decimal.
 * Deterministic for a given [seed]. Platform-specific because it performs file I/O.
 */
expect fun generateDataset(
    path: String,
    rows: Long,
    seed: Int,
)
