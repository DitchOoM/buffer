package com.ditchoom.buffer.codec.test.protocols.http3

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireByteCount
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.codec.test.protocols.quic.QuicVarintCodec
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Deprecated
import kotlin.Int
import kotlin.ReplaceWith
import kotlin.Suppress

public object Http3FrameTypeCodec : Codec<Http3FrameType> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): Http3FrameType {
    val raw = QuicVarintCodec.decode(buffer, context)
    return Http3FrameType(raw = raw)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: Http3FrameType,
    context: EncodeContext,
  ) {
    QuicVarintCodec.encode(buffer, value.raw, context)
  }

  override fun wireBytes(`value`: Http3FrameType, context: EncodeContext): WireByteCount {
    val __rawSize = QuicVarintCodec.encodedLength(value.raw).toLong()
    return WireByteCount.exact(0L + __rawSize)
  }

  @Deprecated(
    message = "Call wireBytes.",
    replaceWith = ReplaceWith("wireBytes(value, context)"),
  )
  @Suppress("DEPRECATION")
  override fun wireSize(`value`: Http3FrameType, context: EncodeContext): WireSize = wireBytes(value, context).toWireSize()

  override fun sizeHint(`value`: Http3FrameType, context: EncodeContext): Int = 0

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize {
    val __rawWidth = QuicVarintCodec.frameSize(stream, baseOffset + 0).fold(declared = { it }, needsMoreData = { return FrameSize.NeedsMoreData }, unframed = { return FrameSize.Unframed })
    return FrameSize.declared(0, __rawWidth)
  }

  @Deprecated(
    message = "Call frameSize.",
    replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
  )
  @Suppress("DEPRECATION")
  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)
}
