package com.ditchoom.buffer.codec.test.protocols.http3fc

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.ViewCodec
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor

/**
 * Opaque-payload codec for `@RemainingBytes @UseCodec(...) val: ReadBuffer`
 * fields: decode yields a zero-copy **view** over the bounded region
 * (`readBytes(remaining)` — lifetime tied to the source buffer, the
 * HTTP/3-style contract where the payload is consumed before the frame
 * buffer is recycled); encode is **non-destructive** (the payload's
 * position is restored after copying, so a frame can be size-checked and
 * re-encoded without its payload draining).
 *
 * Contrast with [com.ditchoom.buffer.codec.test.protocols.payload.BinaryDataCodec],
 * which copies into a consumer-owned buffer on decode — the right contract
 * when the decoded value outlives the frame buffer.
 */
object RawBytesCodec : ViewCodec<ReadBuffer> {
    override fun decode(
        buffer: ReadBuffer,
        context: DecodeContext,
    ): ReadBuffer = buffer.readBytes(buffer.remaining())

    override fun encode(
        buffer: WriteBuffer,
        value: ReadBuffer,
        context: EncodeContext,
    ) {
        val savedPosition = value.position()
        buffer.write(value)
        value.position(savedPosition)
    }

    override fun wireSize(
        value: ReadBuffer,
        context: EncodeContext,
    ): WireSize = WireSize.Exact(value.remaining())

    override fun peekFrameSize(
        stream: StreamProcessor,
        baseOffset: Int,
    ): PeekResult = PeekResult.NoFraming
}
