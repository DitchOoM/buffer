package com.ditchoom.buffer.compression

import kotlin.jvm.JvmInline

/**
 * Log2 size of zlib's LZ77 sliding window. Pair with a [CompressionAlgorithm] to
 * select the format (raw vs zlib vs gzip); this type only carries the *size*.
 *
 * Construction is range-checked: only [Default] (algorithm default) or 9..15 are
 * representable. zlib's `deflateInit2` rejects 8 entirely (`Z_STREAM_ERROR`), so it
 * is excluded here. Negative / offset / out-of-range values are unrepresentable —
 * the platform applies the algorithm-appropriate sign for [CompressionAlgorithm.Raw]
 * (negate) or [CompressionAlgorithm.Gzip] (+16) when invoking zlib.
 *
 * Why a value class instead of `Int`: the previous `windowBits: Int` parameter
 * conflated four overlapping namespaces (`0` sentinel, positive log size, negated
 * raw form, gzip-offset form) with no compile-time prevention of invalid combinations.
 * Callers that intend a 9-bit raw window passed `windowBits = 9` and silently got a
 * zlib-format stream that the raw decompressor couldn't read. Modeling the size
 * separately from the format eliminates that whole class of bug.
 */
@JvmInline
value class WindowBits private constructor(
    val sizeLog2: Int,
) {
    companion object {
        /**
         * Sentinel meaning "use the algorithm's default window (15 bits / 32 KB)".
         * The internal [sizeLog2] of `Default` is `0` — do not read it directly;
         * always go through [resolveWindowBits].
         */
        val Default: WindowBits = WindowBits(0)

        /** Smallest window supported by zlib's `deflateInit2` (512 bytes). */
        val Min: WindowBits = WindowBits(9)

        /** Largest window (32 KB) — also the algorithm default. */
        val Max: WindowBits = WindowBits(15)

        /**
         * Constructs a [WindowBits] of the given log2 size. Only `9..15` is valid;
         * other values throw [IllegalArgumentException].
         */
        operator fun invoke(sizeLog2: Int): WindowBits {
            require(sizeLog2 in 9..15) {
                "windowBits must be in 9..15 (zlib's deflateInit2 rejects 8). " +
                    "Use WindowBits.Default for the algorithm's default. Got: $sizeLog2"
            }
            return WindowBits(sizeLog2)
        }
    }
}

/**
 * Resolves a [WindowBits] + [CompressionAlgorithm] pair to the signed/offset value
 * that zlib's `deflateInit2` / `inflateInit2` expects:
 *
 * | Algorithm | Default | Custom (`9..15`)     |
 * |-----------|---------|----------------------|
 * | Raw       | -15     | -size  (e.g. 9 → -9) |
 * | Deflate   | 15      | size                 |
 * | Gzip      | 31      | size + 16            |
 */
internal fun resolveWindowBits(
    algorithm: CompressionAlgorithm,
    windowBits: WindowBits,
): Int {
    if (windowBits == WindowBits.Default) {
        return when (algorithm) {
            CompressionAlgorithm.Deflate -> 15
            CompressionAlgorithm.Raw -> -15
            CompressionAlgorithm.Gzip -> 31
        }
    }
    return when (algorithm) {
        CompressionAlgorithm.Deflate -> windowBits.sizeLog2
        CompressionAlgorithm.Raw -> -windowBits.sizeLog2
        CompressionAlgorithm.Gzip -> windowBits.sizeLog2 + 16
    }
}
