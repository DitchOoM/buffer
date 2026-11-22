package com.ditchoom.buffer.codec.test.protocols.http3fc

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.WireByteCount
import com.ditchoom.buffer.codec.test.protocols.quic.QuicVarintCodec
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object Http3FcFrameTypeCodec : Codec<Http3FcFrameType> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): Http3FcFrameType {
    val raw = QuicVarintCodec.decode(buffer, context)
    return Http3FcFrameType(raw = raw)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: Http3FcFrameType,
    context: EncodeContext,
  ) {
    QuicVarintCodec.encode(buffer, value.raw, context)
  }

  override fun wireBytes(`value`: Http3FcFrameType, context: EncodeContext): WireByteCount {
    val __rawSize = QuicVarintCodec.encodedLength(value.raw).toLong()
    return WireByteCount.exact(0L + __rawSize)
  }

  override fun sizeHint(`value`: Http3FcFrameType, context: EncodeContext): Int = 0

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize {
    val __rawWidth = QuicVarintCodec.frameSize(stream, baseOffset + 0).fold(declared = { __h, __b -> __h + __b }, needsMoreData = { return FrameSize.NeedsMoreData }, unframed = { return FrameSize.Unframed })
    return FrameSize.declared(0, 0L + __rawWidth)
  }
}
