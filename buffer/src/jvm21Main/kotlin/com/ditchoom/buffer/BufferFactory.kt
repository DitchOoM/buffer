@file:JvmName("BufferFactoryJvm")
@file:Suppress("unused") // Loaded at runtime via multi-release JAR

package com.ditchoom.buffer

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.nio.ByteBuffer

// =============================================================================
// v2 BufferFactory implementations — JVM 21+ (shadows jvmMain via multi-release JAR)
//
// This file has the same JVM class name as jvmMain's BufferFactory.kt.
// At runtime on JVM 21+, the multi-release JAR loads this version instead.
// =============================================================================

private fun ByteOrder.toJavaByteOrder(): java.nio.ByteOrder =
    when (this) {
        ByteOrder.BIG_ENDIAN -> java.nio.ByteOrder.BIG_ENDIAN
        ByteOrder.LITTLE_ENDIAN -> java.nio.ByteOrder.LITTLE_ENDIAN
    }

internal val defaultBufferFactory: BufferFactory =
    object : BufferFactory {
        override fun allocate(
            size: Int,
            byteOrder: ByteOrder,
        ): PlatformBuffer = allocateFfmBuffer(size, byteOrder)

        override fun wrap(
            array: ByteArray,
            byteOrder: ByteOrder,
        ): PlatformBuffer = HeapJvmBuffer(ByteBuffer.wrap(array).order(byteOrder.toJavaByteOrder()))
    }

internal val managedBufferFactory: BufferFactory =
    object : BufferFactory {
        override fun allocate(
            size: Int,
            byteOrder: ByteOrder,
        ): PlatformBuffer = HeapJvmBuffer(ByteBuffer.allocate(size).order(byteOrder.toJavaByteOrder()))

        override fun wrap(
            array: ByteArray,
            byteOrder: ByteOrder,
        ): PlatformBuffer = HeapJvmBuffer(ByteBuffer.wrap(array).order(byteOrder.toJavaByteOrder()))
    }

internal val sharedBufferFactory: BufferFactory =
    object : BufferFactory {
        override fun allocate(
            size: Int,
            byteOrder: ByteOrder,
        ): PlatformBuffer = allocateFfmBuffer(size, byteOrder)

        override fun wrap(
            array: ByteArray,
            byteOrder: ByteOrder,
        ): PlatformBuffer = HeapJvmBuffer(ByteBuffer.wrap(array).order(byteOrder.toJavaByteOrder()))
    }

internal val deterministicBufferFactory: BufferFactory =
    object : BufferFactory {
        override fun allocate(
            size: Int,
            byteOrder: ByteOrder,
        ): PlatformBuffer = allocateFfmBuffer(size, byteOrder)

        override fun wrap(
            array: ByteArray,
            byteOrder: ByteOrder,
        ): PlatformBuffer = HeapJvmBuffer(ByteBuffer.wrap(array).order(byteOrder.toJavaByteOrder()))
    }

fun PlatformBuffer.Companion.allocateNative(
    size: Int,
    byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN,
): PlatformBuffer = allocateFfmBuffer(size, byteOrder)

fun PlatformBuffer.Companion.allocateShared(
    size: Int,
    byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN,
): PlatformBuffer = BufferFactory.Default.allocate(size, byteOrder)

/**
 * Internal factory that creates an FFM-backed buffer.
 *
 * Uses [Arena.ofShared] for memory ownership — deterministic free from any thread via
 * [FfmBuffer.freeNativeMemory]. The ByteBuffer view is created from a global-scope
 * reinterpretation of the segment address so it is compatible with all JDK APIs
 * (Deflater, Inflater, CRC32, NIO channels).
 */
internal fun allocateFfmBuffer(
    size: Int,
    byteOrder: ByteOrder,
): FfmBuffer {
    val arena = Arena.ofShared()
    val segment = arena.allocate(size.toLong())
    val byteOrderNative = byteOrder.toJavaByteOrder()
    val globalView =
        MemorySegment
            .ofAddress(segment.address())
            .reinterpret(size.toLong())
    val byteBuffer = globalView.asByteBuffer().order(byteOrderNative)
    return FfmBuffer(arena, segment, byteBuffer)
}
