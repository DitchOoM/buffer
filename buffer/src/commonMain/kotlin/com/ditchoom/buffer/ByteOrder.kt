package com.ditchoom.buffer

enum class ByteOrder {
    BIG_ENDIAN,
    LITTLE_ENDIAN,
    ;

    companion object {
        /**
         * Native byte order of the platform. All supported platforms (x86, ARM, WASM) are little-endian,
         * so this is an alias for [LITTLE_ENDIAN]. Using NATIVE avoids byte-swap overhead.
         */
        val NATIVE: ByteOrder = LITTLE_ENDIAN
    }
}

// Endian-swap helpers — used by generated codec code when a batched read/write
// must canonicalize bytes between the wire order and the buffer's runtime byte
// order. Kotlin stdlib's `Long.reverseBytes()` is JVM-only; these top-level
// functions are common-source and resolve unambiguously from generated imports.

fun swapBytes(value: Short): Short {
    val v = value.toInt() and 0xFFFF
    return (((v and 0xFF) shl 8) or ((v ushr 8) and 0xFF)).toShort()
}

fun swapBytes(value: Int): Int =
    ((value and 0xFF) shl 24) or
        (((value ushr 8) and 0xFF) shl 16) or
        (((value ushr 16) and 0xFF) shl 8) or
        ((value ushr 24) and 0xFF)

fun swapBytes(value: Long): Long =
    ((value and 0xFFL) shl 56) or
        (((value ushr 8) and 0xFFL) shl 48) or
        (((value ushr 16) and 0xFFL) shl 40) or
        (((value ushr 24) and 0xFFL) shl 32) or
        (((value ushr 32) and 0xFFL) shl 24) or
        (((value ushr 40) and 0xFFL) shl 16) or
        (((value ushr 48) and 0xFFL) shl 8) or
        ((value ushr 56) and 0xFFL)
