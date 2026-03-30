@file:JvmName("BufferFactoryJvm")

package com.ditchoom.buffer

import java.nio.ByteBuffer

// =============================================================================
// v2 BufferFactory implementations
// =============================================================================

internal actual val defaultBufferFactory: BufferFactory =
    object : BufferFactory {
        override fun allocate(
            size: Int,
            byteOrder: ByteOrder,
        ): PlatformBuffer = DirectJvmBuffer(ByteBuffer.allocateDirect(size).order(byteOrder.toJava()))

        override fun wrap(
            array: ByteArray,
            byteOrder: ByteOrder,
        ): PlatformBuffer = HeapJvmBuffer(ByteBuffer.wrap(array).order(byteOrder.toJava()))
    }

internal actual val managedBufferFactory: BufferFactory =
    object : BufferFactory {
        override fun allocate(
            size: Int,
            byteOrder: ByteOrder,
        ): PlatformBuffer = HeapJvmBuffer(ByteBuffer.allocate(size).order(byteOrder.toJava()))

        override fun wrap(
            array: ByteArray,
            byteOrder: ByteOrder,
        ): PlatformBuffer = HeapJvmBuffer(ByteBuffer.wrap(array).order(byteOrder.toJava()))
    }

internal actual val sharedBufferFactory: BufferFactory =
    object : BufferFactory {
        override fun allocate(
            size: Int,
            byteOrder: ByteOrder,
        ): PlatformBuffer = DirectJvmBuffer(ByteBuffer.allocateDirect(size).order(byteOrder.toJava()))

        override fun wrap(
            array: ByteArray,
            byteOrder: ByteOrder,
        ): PlatformBuffer = HeapJvmBuffer(ByteBuffer.wrap(array).order(byteOrder.toJava()))
    }

private val deterministicFactoryInstance: BufferFactory =
    object : BufferFactory {
        override fun allocate(
            size: Int,
            byteOrder: ByteOrder,
        ): PlatformBuffer {
            // JVM 9+: use invokeCleaner on a direct ByteBuffer
            if (invokeCleanerFn != null) {
                return JvmDeterministicDirectJvmBuffer(
                    ByteBuffer.allocateDirect(size).order(byteOrder.toJava()),
                )
            }
            // JVM 8: fall back to Unsafe.allocateMemory
            return JvmUnsafePlatformBuffer.allocate(size, byteOrder)
        }

        override fun wrap(
            array: ByteArray,
            byteOrder: ByteOrder,
        ): PlatformBuffer = HeapJvmBuffer(ByteBuffer.wrap(array).order(byteOrder.toJava()))
    }

internal actual fun deterministicBufferFactory(threadConfined: Boolean): BufferFactory = deterministicFactoryInstance

/**
 * Allocates a buffer with guaranteed native memory access (DirectJvmBuffer).
 * Uses a direct ByteBuffer with accessible native memory address.
 */
actual fun PlatformBuffer.Companion.allocateNative(
    size: Int,
    byteOrder: ByteOrder,
): PlatformBuffer = DirectJvmBuffer(ByteBuffer.allocateDirect(size).order(byteOrder.toJava()))

/**
 * Allocates a buffer with shared memory support.
 * On JVM, falls back to direct allocation (no cross-process shared memory).
 */
actual fun PlatformBuffer.Companion.allocateShared(
    size: Int,
    byteOrder: ByteOrder,
): PlatformBuffer = BufferFactory.Default.allocate(size, byteOrder)

actual fun PlatformBuffer.Companion.wrapNativeAddress(
    address: Long,
    size: Int,
    byteOrder: ByteOrder,
): PlatformBuffer {
    // Try FFM first (JVM 21+ at runtime, even if compiled against jvmMain)
    tryWrapViaFfm(address, size, byteOrder)?.let { return it }

    // Fall back to Unsafe DirectByteBuffer reflection
    val byteBuffer =
        UnsafeMemory.tryWrapAsDirectByteBuffer(address, size)
            ?: throw UnsupportedOperationException(
                "Cannot wrap native address: neither FFM (JVM 21+) nor DirectByteBuffer reflection is available.",
            )
    byteBuffer.order(byteOrder.toJava())
    return DirectJvmBuffer(byteBuffer)
}

/** Attempt to wrap via FFM (available on JVM 21+ at runtime). Returns null if FFM is not available. */
private fun tryWrapViaFfm(
    address: Long,
    size: Int,
    byteOrder: ByteOrder,
): PlatformBuffer? =
    try {
        val memorySegmentClass = Class.forName("java.lang.foreign.MemorySegment")
        val ofAddress = memorySegmentClass.getMethod("ofAddress", Long::class.javaPrimitiveType)
        val reinterpret = memorySegmentClass.getMethod("reinterpret", Long::class.javaPrimitiveType)
        val asByteBuffer = memorySegmentClass.getMethod("asByteBuffer")

        val zeroSegment = ofAddress.invoke(null, address)
        val segment = reinterpret.invoke(zeroSegment, size.toLong())
        val byteBuffer = asByteBuffer.invoke(segment) as ByteBuffer
        byteBuffer.order(byteOrder.toJava())
        DirectJvmBuffer(byteBuffer)
    } catch (_: Exception) {
        null
    }
