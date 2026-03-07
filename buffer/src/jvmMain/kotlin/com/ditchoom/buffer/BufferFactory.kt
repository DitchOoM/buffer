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

internal actual val deterministicBufferFactory: BufferFactory =
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
