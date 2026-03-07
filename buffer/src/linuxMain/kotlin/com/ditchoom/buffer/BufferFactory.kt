@file:Suppress("DEPRECATION") // AllocationZone is deprecated

package com.ditchoom.buffer

// =============================================================================
// v2 BufferFactory implementations
// =============================================================================

internal actual val defaultBufferFactory: BufferFactory =
    object : BufferFactory {
        override fun allocate(
            size: Int,
            byteOrder: ByteOrder,
        ): PlatformBuffer = NativeBuffer.allocate(size, byteOrder)

        override fun wrap(
            array: ByteArray,
            byteOrder: ByteOrder,
        ): PlatformBuffer = ByteArrayBuffer(array, byteOrder = byteOrder)
    }

internal actual val managedBufferFactory: BufferFactory =
    object : BufferFactory {
        override fun allocate(
            size: Int,
            byteOrder: ByteOrder,
        ): PlatformBuffer = ByteArrayBuffer(ByteArray(size), byteOrder = byteOrder)

        override fun wrap(
            array: ByteArray,
            byteOrder: ByteOrder,
        ): PlatformBuffer = ByteArrayBuffer(array, byteOrder = byteOrder)
    }

internal actual val sharedBufferFactory: BufferFactory = defaultBufferFactory

// =============================================================================
// Legacy factory functions (backward compat)
// =============================================================================

/**
 * Allocates a buffer with the specified allocation zone.
 *
 * - [AllocationZone.Heap]: Uses [ByteArrayBuffer] backed by Kotlin ByteArray (GC managed)
 * - [AllocationZone.Direct]: Uses [NativeBuffer] backed by malloc (zero-copy for io_uring)
 * - [AllocationZone.SharedMemory]: Falls back to Direct on Linux
 */
actual fun PlatformBuffer.Companion.allocate(
    size: Int,
    zone: AllocationZone,
    byteOrder: ByteOrder,
): PlatformBuffer =
    when (zone) {
        AllocationZone.Heap -> ByteArrayBuffer(ByteArray(size), byteOrder = byteOrder)
        AllocationZone.Direct, AllocationZone.SharedMemory -> NativeBuffer.allocate(size, byteOrder)
    }

actual fun PlatformBuffer.Companion.wrap(
    array: ByteArray,
    byteOrder: ByteOrder,
): PlatformBuffer = ByteArrayBuffer(array, byteOrder = byteOrder)

/**
 * Allocates a buffer with guaranteed native memory access using malloc.
 *
 * Returns a [NativeBuffer] that provides [NativeMemoryAccess.nativeAddress] for
 * zero-copy I/O with io_uring and other native APIs.
 *
 * IMPORTANT: The returned buffer must be explicitly closed to free native memory.
 */
actual fun PlatformBuffer.Companion.allocateNative(
    size: Int,
    byteOrder: ByteOrder,
): PlatformBuffer = NativeBuffer.allocate(size, byteOrder)

/**
 * Allocates a buffer with shared memory support.
 * On Linux, falls back to native allocation (no cross-process shared memory).
 */
actual fun PlatformBuffer.Companion.allocateShared(
    size: Int,
    byteOrder: ByteOrder,
): PlatformBuffer = NativeBuffer.allocate(size, byteOrder)
