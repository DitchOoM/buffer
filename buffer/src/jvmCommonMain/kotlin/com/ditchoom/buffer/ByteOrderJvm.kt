package com.ditchoom.buffer

/**
 * Converts [ByteOrder] to [java.nio.ByteOrder].
 */
internal fun ByteOrder.toJava(): java.nio.ByteOrder =
    when (this) {
        ByteOrder.BIG_ENDIAN -> java.nio.ByteOrder.BIG_ENDIAN
        ByteOrder.LITTLE_ENDIAN -> java.nio.ByteOrder.LITTLE_ENDIAN
    }
