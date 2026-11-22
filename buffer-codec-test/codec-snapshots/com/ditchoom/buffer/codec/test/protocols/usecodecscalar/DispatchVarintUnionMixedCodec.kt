package com.ditchoom.buffer.codec.test.protocols.usecodecscalar

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

public object DispatchVarintUnionMixedCodec : Codec<DispatchVarintUnion.Mixed> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): DispatchVarintUnion.Mixed {
    val v = QuicVarintCodec.decode(buffer, context)
    val flag = buffer.readByte() != 0.toByte()
    return DispatchVarintUnion.Mixed(v = v, flag = flag)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: DispatchVarintUnion.Mixed,
    context: EncodeContext,
  ) {
    QuicVarintCodec.encode(buffer, value.v, context)
    buffer.writeByte(if (value.flag) 1.toByte() else 0.toByte())
  }

  override fun wireBytes(`value`: DispatchVarintUnion.Mixed, context: EncodeContext): WireByteCount {
    val __vSize = QuicVarintCodec.encodedLength(value.v).toLong()
    return WireByteCount.exact(1L + __vSize)
  }

  override fun sizeHint(`value`: DispatchVarintUnion.Mixed, context: EncodeContext): Int = 1

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize {
    val __vWidth = QuicVarintCodec.frameSize(stream, baseOffset + 0).fold(declared = { __h, __b -> __h + __b }, needsMoreData = { return FrameSize.NeedsMoreData }, unframed = { return FrameSize.Unframed })
    return FrameSize.declared(0, 1L + __vWidth)
  }
}
