package com.ditchoom.buffer.codec

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer

/**
 * Phase J.M.5 slice 14b — runtime entry point for the `@FramedBy` slicing-scheme
 * encode emit. The KSP-generated `@FramedBy` codec calls [encode], which:
 *
 *   1. Allocates a [GrowableWriteBuffer] with a slack region at the front sized
 *      to fit the worst-case header bytes plus the worst-case framing prefix.
 *   2. Positions the growable past the slack region and invokes [writeBody]
 *      to write the body fields into the buffer.
 *   3. Computes `bodyBytes = position - maxSlack` and asks [framingCodec] for
 *      the actual prefix width via `wireSize(bodyBytes, context).asExact`.
 *   4. Right-flushes the prefix (and optional header) into the slack region
 *      so the wire bytes lie contiguously: `[header?][prefix][body]`.
 *   5. Returns a [ReadBuffer] slice spanning exactly those wire bytes.
 *
 * Body bytes are never moved — the slicing scheme leaves them at offset
 * `maxSlack` and writes the prefix backwards into the slack instead. This is
 * what makes the emit zero-memcpy for the body content (the design's headline
 * property — see slice 14b handoff Q5).
 *
 * For a class with `@FramedBy(codec, after = "")`, [headerWireWidth] is `0`
 * and [writeHeader] is `null`. For `after = "X"`, [headerWireWidth] is the
 * Exact wire width of `X` and [writeHeader] writes that field into the
 * supplied buffer (the framework owns the header write so the gap between
 * header and prefix is zero — eliminating the 1-byte memmove that an
 * "in-variant header write" would otherwise require).
 */
public object FramedEncoder {
    public fun encode(
        factory: BufferFactory,
        framingCodec: BoundingLengthCodec<UInt>,
        context: EncodeContext,
        headerWireWidth: Int = 0,
        writeHeader: ((PlatformBuffer) -> Unit)? = null,
        initialBodyEstimate: Int = 256,
        writeBody: (WriteBuffer) -> Unit,
    ): ReadBuffer {
        val maxSlack = headerWireWidth + framingCodec.maxWireSize
        val growable = GrowableWriteBuffer(factory, initialSize = maxSlack + initialBodyEstimate)
        growable.position(maxSlack)
        writeBody(growable)
        val bodyBytes = growable.position() - maxSlack
        val prefixWireSize = framingCodec.wireSize(bodyBytes.toUInt(), context)
        require(prefixWireSize is WireSize.Exact) {
            "framing codec ${framingCodec::class.simpleName} returned non-Exact wire size for prefix"
        }
        val actualPrefixWidth = prefixWireSize.bytes
        val sliceStart = maxSlack - actualPrefixWidth - headerWireWidth
        val buffer: PlatformBuffer = growable.innerBuffer()
        buffer.position(sliceStart)
        writeHeader?.invoke(buffer)
        framingCodec.encode(buffer, bodyBytes.toUInt(), context)
        buffer.position(sliceStart)
        buffer.setLimit(maxSlack + bodyBytes)
        return buffer.slice()
    }
}
