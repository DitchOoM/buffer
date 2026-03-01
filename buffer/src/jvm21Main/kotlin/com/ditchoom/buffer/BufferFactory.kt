@file:JvmName("BufferFactoryJvm")
@file:Suppress("unused") // Loaded at runtime via multi-release JAR

package com.ditchoom.buffer

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.nio.ByteBuffer

/**
 * FFM-backed buffer factory for Java 21+.
 *
 * Shadows the jvmMain BufferFactory via multi-release JAR. Routes [AllocationZone.Direct]
 * and [AllocationZone.SharedMemory] to [FfmBuffer] for deterministic memory management.
 * Heap allocation still uses [HeapJvmBuffer].
 */
fun PlatformBuffer.Companion.allocate(
    size: Int,
    zone: AllocationZone = AllocationZone.Heap,
    byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN,
): PlatformBuffer {
    val byteOrderNative =
        when (byteOrder) {
            ByteOrder.BIG_ENDIAN -> java.nio.ByteOrder.BIG_ENDIAN
            ByteOrder.LITTLE_ENDIAN -> java.nio.ByteOrder.LITTLE_ENDIAN
        }
    return when (zone) {
        AllocationZone.Heap -> HeapJvmBuffer(ByteBuffer.allocate(size).order(byteOrderNative))
        AllocationZone.SharedMemory,
        AllocationZone.Direct,
        -> allocateFfmBuffer(size, byteOrder)
    }
}

fun PlatformBuffer.Companion.wrap(
    array: ByteArray,
    byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN,
): PlatformBuffer {
    val byteOrderNative =
        when (byteOrder) {
            ByteOrder.BIG_ENDIAN -> java.nio.ByteOrder.BIG_ENDIAN
            ByteOrder.LITTLE_ENDIAN -> java.nio.ByteOrder.LITTLE_ENDIAN
        }
    return HeapJvmBuffer(ByteBuffer.wrap(array).order(byteOrderNative))
}

/**
 * Allocates a buffer with guaranteed native memory access ([FfmBuffer]).
 * Uses FFM Arena for deterministic memory management.
 */
fun PlatformBuffer.Companion.allocateNative(
    size: Int,
    byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN,
): PlatformBuffer = allocateFfmBuffer(size, byteOrder)

/**
 * Allocates a buffer with shared memory support.
 * On JVM, falls back to FFM-backed direct allocation (no cross-process shared memory).
 */
fun PlatformBuffer.Companion.allocateShared(
    size: Int,
    byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN,
): PlatformBuffer = allocate(size, AllocationZone.Direct, byteOrder)

/**
 * Internal factory that creates an FFM-backed buffer.
 *
 * Uses [Arena.ofShared] for memory ownership — deterministic free from any thread via
 * [FfmBuffer.freeNativeMemory]. The ByteBuffer view is created from a global-scope
 * reinterpretation of the segment address so it is compatible with all JDK APIs
 * (Deflater, Inflater, CRC32, NIO channels). The JDK rejects ByteBuffers from closeable
 * shared sessions, but global-scope ByteBuffers are treated as regular direct ByteBuffers.
 *
 * Lifecycle safety is provided by the pool ([BufferPool] tracks references and only frees
 * when buffers are unreferenced) or by the caller for standalone allocations.
 *
 * @param size Buffer size in bytes.
 * @param byteOrder Byte order for multi-byte operations.
 */
internal fun allocateFfmBuffer(
    size: Int,
    byteOrder: ByteOrder,
): FfmBuffer {
    val arena = Arena.ofShared()
    val segment = arena.allocate(size.toLong())
    val byteOrderNative =
        when (byteOrder) {
            ByteOrder.BIG_ENDIAN -> java.nio.ByteOrder.BIG_ENDIAN
            ByteOrder.LITTLE_ENDIAN -> java.nio.ByteOrder.LITTLE_ENDIAN
        }
    // Create ByteBuffer from a global-scope view of the same native memory.
    // This ensures the ByteBuffer is NOT tied to the Arena's closeable shared session,
    // which JDK APIs (Deflater.deflate, Inflater.inflate, CRC32.update) reject.
    // Memory ownership remains with the shared Arena — freeNativeMemory() closes it.
    val globalView =
        MemorySegment
            .ofAddress(segment.address())
            .reinterpret(size.toLong())
    val byteBuffer = globalView.asByteBuffer().order(byteOrderNative)
    return FfmBuffer(arena, segment, byteBuffer)
}
