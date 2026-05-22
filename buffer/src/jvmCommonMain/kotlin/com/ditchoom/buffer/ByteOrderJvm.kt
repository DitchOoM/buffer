package com.ditchoom.buffer

/**
 * Converts [ByteOrder] to [java.nio.ByteOrder]. Public so the FFM
 * classes in `jvm21Main` (which compile against `jvmMain` only via
 * classpath, not source-set inheritance) can reuse the helper.
 */
fun ByteOrder.toJava(): java.nio.ByteOrder =
    when (this) {
        ByteOrder.BIG_ENDIAN -> java.nio.ByteOrder.BIG_ENDIAN
        ByteOrder.LITTLE_ENDIAN -> java.nio.ByteOrder.LITTLE_ENDIAN
    }
