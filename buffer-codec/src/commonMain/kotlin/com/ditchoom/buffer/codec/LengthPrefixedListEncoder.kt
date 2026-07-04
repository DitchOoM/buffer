package com.ditchoom.buffer.codec

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.WriteBuffer

/**
 * Runtime entry point for the generated encode of a `@LengthPrefixed @UseCodec List<T>`
 * section whose elements are [WireSize.BackPatch] — variable-length, so the body byte
 * count isn't known until the elements are encoded.
 *
 * Encodes every element into a growable scratch buffer to measure the exact body size,
 * writes the length prefix into [target] via [lengthPrefixCodec], then bulk-copies the
 * measured body. The scratch grows on demand (via [GrowableWriteBufferPool]) so a list
 * section of any size encodes correctly.
 *
 * This replaces an earlier emit that staged the body into a **fixed 64-byte** buffer:
 * once the encoded section exceeded 64 bytes the scratch overflowed and — on platforms
 * whose `writeString`/`write` silently truncated instead of throwing — produced a
 * structurally-valid frame carrying truncated data (the truncated count is what got
 * length-prefixed, so nothing downstream could detect the corruption).
 */
public object LengthPrefixedListEncoder {
    public fun <T> encode(
        target: WriteBuffer,
        factory: BufferFactory,
        lengthPrefixCodec: Encoder<UInt>,
        elements: Iterable<T>,
        elementCodec: Encoder<T>,
        context: EncodeContext,
        initialBodyEstimate: Int = 256,
    ) {
        val growable = GrowableWriteBufferPool.acquire()
        try {
            growable.attach(factory, initialSize = initialBodyEstimate, byteOrder = target.byteOrder)
            for (element in elements) {
                elementCodec.encode(growable, element, context)
            }
            val bodyBytes = growable.position()
            lengthPrefixCodec.encode(target, bodyBytes.toUInt(), context)
            target.write(growable.toReadBuffer())
        } finally {
            growable.freeAndDetach()
            GrowableWriteBufferPool.release(growable)
        }
    }
}
