@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.ditchoom.buffer

import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.convert
import platform.Foundation.NSData
import platform.Foundation.NSMutableData
import platform.Foundation.create

/**
 * Allocates a new buffer with the specified size and allocation zone.
 *
 * - [AllocationZone.Heap]: Returns [ByteArrayBuffer] (Kotlin managed memory)
 * - [AllocationZone.Direct]: Returns [MutableDataBuffer] (Apple native memory)
 * - [AllocationZone.SharedMemory]: Falls back to Direct (no shared memory on Apple)
 */
actual fun PlatformBuffer.Companion.allocate(
    size: Int,
    zone: AllocationZone,
    byteOrder: ByteOrder,
): PlatformBuffer =
    when (zone) {
        AllocationZone.Heap -> ByteArrayBuffer(ByteArray(size), byteOrder = byteOrder)
        AllocationZone.Direct, AllocationZone.SharedMemory -> {
            @OptIn(UnsafeNumber::class)
            MutableDataBuffer(NSMutableData.create(length = size.convert())!!, byteOrder = byteOrder)
        }
    }

/**
 * Wraps an existing ByteArray in a buffer.
 *
 * Returns a [ByteArrayBuffer] which shares memory with the original array.
 * Modifications to the buffer will be visible in the original array and vice versa.
 *
 * For wrapping Apple native data, use [wrap] with NSData or NSMutableData instead.
 */
actual fun PlatformBuffer.Companion.wrap(
    array: ByteArray,
    byteOrder: ByteOrder,
): PlatformBuffer = ByteArrayBuffer(array, byteOrder = byteOrder)

/**
 * Wraps an existing NSData in a read-only buffer (zero-copy).
 *
 * The buffer shares memory with the original NSData. This is efficient for
 * reading data from Apple APIs without copying.
 *
 * @param data The NSData to wrap
 * @param byteOrder The byte order for multi-byte operations
 * @return An [NSDataBuffer] providing read-only access to the data
 */
fun PlatformBuffer.Companion.wrapReadOnly(
    data: NSData,
    byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN,
): ReadBuffer = NSDataBuffer(data, byteOrder)

/**
 * Wraps an existing NSData in a mutable buffer by creating a copy.
 *
 * This creates a new NSMutableData with a copy of the data to provide write operations.
 * For zero-copy read-only access, use [wrapReadOnly] instead.
 *
 * @param data The NSData to wrap
 * @param byteOrder The byte order for multi-byte operations
 * @return A [MutableDataBuffer] wrapping a mutable copy of the data
 */
@OptIn(UnsafeNumber::class)
fun PlatformBuffer.Companion.wrap(
    data: NSData,
    byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN,
): MutableDataBuffer {
    val mutableData = NSMutableData.create(data)
    return MutableDataBuffer(mutableData, byteOrder)
}

/**
 * Wraps an existing NSMutableData in a buffer (zero-copy).
 *
 * The buffer shares memory with the original NSMutableData.
 * Modifications to the buffer will be visible in the original data and vice versa.
 *
 * @param data The NSMutableData to wrap
 * @param byteOrder The byte order for multi-byte operations
 * @return A [MutableDataBuffer] wrapping the data
 */
fun PlatformBuffer.Companion.wrap(
    data: NSMutableData,
    byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN,
): MutableDataBuffer = MutableDataBuffer(data, byteOrder)

/**
 * Allocates a buffer with guaranteed native memory access (MutableDataBuffer).
 * Uses NSMutableData which provides native pointer access via [NativeMemoryAccess].
 */
@OptIn(UnsafeNumber::class)
actual fun PlatformBuffer.Companion.allocateNative(
    size: Int,
    byteOrder: ByteOrder,
): PlatformBuffer = MutableDataBuffer(NSMutableData.create(length = size.convert())!!, byteOrder = byteOrder)

/**
 * Allocates a buffer with shared memory support.
 * On Apple, falls back to direct allocation (no cross-process shared memory API exposed).
 */
actual fun PlatformBuffer.Companion.allocateShared(
    size: Int,
    byteOrder: ByteOrder,
): PlatformBuffer = allocate(size, AllocationZone.Direct, byteOrder)
