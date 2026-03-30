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
        ): PlatformBuffer = allocateAutoFfmBuffer(size, byteOrder)

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
        ): PlatformBuffer = allocateAutoFfmBuffer(size, byteOrder)

        override fun wrap(
            array: ByteArray,
            byteOrder: ByteOrder,
        ): PlatformBuffer = HeapJvmBuffer(ByteBuffer.wrap(array).order(byteOrder.toJavaByteOrder()))
    }

private val deterministicSharedFactory: BufferFactory =
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

private val deterministicConfinedFactory: BufferFactory =
    object : BufferFactory {
        override fun allocate(
            size: Int,
            byteOrder: ByteOrder,
        ): PlatformBuffer = allocateConfinedFfmBuffer(size, byteOrder)

        override fun wrap(
            array: ByteArray,
            byteOrder: ByteOrder,
        ): PlatformBuffer = HeapJvmBuffer(ByteBuffer.wrap(array).order(byteOrder.toJavaByteOrder()))
    }

internal fun deterministicBufferFactory(threadConfined: Boolean): BufferFactory =
    if (threadConfined) deterministicConfinedFactory else deterministicSharedFactory

fun PlatformBuffer.Companion.allocateNative(
    size: Int,
    byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN,
): PlatformBuffer = allocateFfmBuffer(size, byteOrder)

fun PlatformBuffer.Companion.allocateShared(
    size: Int,
    byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN,
): PlatformBuffer = BufferFactory.Default.allocate(size, byteOrder)

fun PlatformBuffer.Companion.wrapNativeAddress(
    address: Long,
    size: Int,
    byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN,
): PlatformBuffer {
    // MemorySegment.ofAddress creates a global-scope segment (no Arena = no ownership).
    // reinterpret extends it to the given size. The buffer does NOT own this memory.
    val segment = MemorySegment.ofAddress(address).reinterpret(size.toLong())
    val byteBuffer = segment.asByteBuffer().order(byteOrder.toJavaByteOrder())
    return FfmAutoBuffer(segment, byteBuffer)
}

/**
 * Creates a GC-managed FFM buffer using [Arena.ofAuto].
 *
 * The returned [FfmAutoBuffer] does not implement [CloseableBuffer] — memory is reclaimed
 * by the garbage collector. This is used by [defaultBufferFactory] and [sharedBufferFactory].
 */
internal fun allocateAutoFfmBuffer(
    size: Int,
    byteOrder: ByteOrder,
): FfmAutoBuffer {
    val arena = Arena.ofAuto()
    val segment = arena.allocate(size.toLong())
    val byteOrderNative = byteOrder.toJavaByteOrder()
    val globalView =
        MemorySegment
            .ofAddress(segment.address())
            .reinterpret(size.toLong())
    val byteBuffer = globalView.asByteBuffer().order(byteOrderNative)
    return FfmAutoBuffer(segment, byteBuffer)
}

/**
 * Creates a deterministic FFM buffer using [Arena.ofShared].
 *
 * The returned [FfmBuffer] implements [CloseableBuffer] — memory is freed immediately
 * when [FfmBuffer.freeNativeMemory] is called from any thread.
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

/**
 * Creates a deterministic FFM buffer using [Arena.ofConfined].
 *
 * The returned [FfmBuffer] implements [CloseableBuffer] — memory must be freed on the
 * same thread that created it. This is more efficient than [Arena.ofShared] when
 * cross-thread access is not needed.
 */
internal fun allocateConfinedFfmBuffer(
    size: Int,
    byteOrder: ByteOrder,
): FfmBuffer {
    val arena = Arena.ofConfined()
    val segment = arena.allocate(size.toLong())
    val byteOrderNative = byteOrder.toJavaByteOrder()
    val globalView =
        MemorySegment
            .ofAddress(segment.address())
            .reinterpret(size.toLong())
    val byteBuffer = globalView.asByteBuffer().order(byteOrderNative)
    return FfmBuffer(arena, segment, byteBuffer)
}
