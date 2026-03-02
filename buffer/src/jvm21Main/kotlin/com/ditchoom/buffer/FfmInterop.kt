@file:Suppress("unused") // Loaded at runtime via multi-release JAR

package com.ditchoom.buffer

import java.lang.foreign.MemorySegment

/**
 * Returns a [MemorySegment] view of this buffer's remaining bytes for use with
 * Java FFM (Foreign Function & Memory) API Panama downcalls.
 *
 * The returned segment covers [ReadBuffer.position] to [ReadBuffer.limit].
 *
 * **Zero-copy path:**
 * - [FfmBuffer]: Returns a slice of the owned MemorySegment (scoped to the Arena's lifetime).
 * - [DirectJvmBuffer] / other direct [BaseJvmBuffer]: Creates a MemorySegment from the
 *   direct ByteBuffer's native address.
 *
 * **Returns null:**
 * - Heap-backed buffers (no native address to wrap).
 * - Non-JVM buffer types.
 *
 * Example usage with FFM Panama downcall:
 * ```kotlin
 * val segment = buffer.asMemorySegment() ?: error("Buffer has no native memory")
 * val result = nativeFunctionHandle.invokeExact(segment, buffer.remaining()) as Int
 * ```
 */
fun ReadBuffer.asMemorySegment(): MemorySegment? {
    val unwrapped = unwrapFully()
    return when (unwrapped) {
        is FfmBuffer -> unwrapped.segment.asSlice(unwrapped.position().toLong(), unwrapped.remaining().toLong())
        is FfmSliceBuffer -> unwrapped.segment.asSlice(unwrapped.position().toLong(), unwrapped.remaining().toLong())
        is BaseJvmBuffer ->
            if (unwrapped.byteBuffer.isDirect) {
                MemorySegment
                    .ofBuffer(unwrapped.byteBuffer)
                    .asSlice(unwrapped.position().toLong(), unwrapped.remaining().toLong())
            } else {
                null
            }
        else -> null
    }
}
