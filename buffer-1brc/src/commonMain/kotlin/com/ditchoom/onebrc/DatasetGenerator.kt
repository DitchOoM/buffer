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

/** Shared numeric constants for the platform-specific [generateDataset] actuals. */
internal object DatasetGen {
    /** Largest representable temperature in tenths (1BRC range: at most two integer digits). */
    const val MAX_TENTHS = 999

    /** Smallest representable temperature in tenths. */
    const val MIN_TENTHS = -999

    /** Scale factor between whole degrees and tenths-of-a-degree fixed point. */
    const val TENTHS_PER_UNIT = 10

    /** Lower clamp on the first Box–Muller uniform to avoid `ln(0)`. */
    const val GAUSSIAN_EPSILON = 1e-12

    /** Coefficient inside the Box–Muller radius term `sqrt(-2 * ln(u1))`. */
    const val BOX_MULLER_COEFF = -2.0
}
