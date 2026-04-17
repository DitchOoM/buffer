package com.ditchoom.buffer

import java.nio.ByteBuffer

/**
 * Cached method reference for `Unsafe.invokeCleaner(ByteBuffer)` — available on JVM 9+.
 * Returns null on JVM 8 and Android (ART does not expose invokeCleaner).
 *
 * Resolved once on first access, then it's just a direct method call with zero reflection overhead.
 */
internal val invokeCleanerFn: ((ByteBuffer) -> Unit)? by lazy(LazyThreadSafetyMode.PUBLICATION) {
    try {
        val field = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
        field.isAccessible = true
        val unsafe = field.get(null) as sun.misc.Unsafe
        val method = unsafe.javaClass.getMethod("invokeCleaner", ByteBuffer::class.java)
        return@lazy { buffer: ByteBuffer -> method.invoke(unsafe, buffer) }
    } catch (_: Throwable) {
        null
    }
}

/**
 * Shared deterministic allocation strategy for JVM and Android.
 *
 * Tries invokeCleaner (JVM 9+ / Android host-JVM tests) first.
 * Falls back to Unsafe.allocateMemory on JVM 8 and Android ART.
 */
internal inline fun allocateDeterministicBuffer(
    size: Int,
    byteOrder: ByteOrder,
    directCreator: (ByteBuffer) -> PlatformBuffer,
    unsafeCreator: (Int, ByteOrder) -> PlatformBuffer,
): PlatformBuffer {
    if (invokeCleanerFn != null) {
        return directCreator(ByteBuffer.allocateDirect(size).order(byteOrder.toJava()))
    }
    return unsafeCreator(size, byteOrder)
}
