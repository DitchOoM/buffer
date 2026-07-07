package com.ditchoom.buffer.okio

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer

/**
 * Copies [length] bytes from this buffer's current position into [dst] at
 * [dstOffset] and advances position. Semantically identical to
 * [ReadBuffer.readInto]; platform implementations may pick a faster copy
 * primitive for the segment-array transfer (see the JVM actual).
 *
 * Preserves the bridge's fail-fast contract: a freed pooled buffer throws its
 * use-after-free `IllegalStateException`, an under-length buffer throws the
 * library's underflow exception.
 */
internal expect fun ReadBuffer.readIntoSegment(
    dst: ByteArray,
    dstOffset: Int,
    length: Int,
)

/**
 * Writes [length] bytes from [src] starting at [srcOffset] into this buffer
 * at its current position and advances position. Semantically identical to
 * [WriteBuffer.writeBytes]; platform implementations may pick a faster copy
 * primitive for the segment-array transfer (see the JVM actual).
 *
 * Preserves the bridge's fail-fast contract: a freed pooled buffer throws its
 * use-after-free `IllegalStateException`, an over-capacity write throws the
 * library's overflow exception.
 */
internal expect fun WriteBuffer.writeSegmentBytes(
    src: ByteArray,
    srcOffset: Int,
    length: Int,
)
