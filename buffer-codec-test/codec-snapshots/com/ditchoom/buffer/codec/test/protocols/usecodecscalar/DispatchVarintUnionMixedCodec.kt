package com.ditchoom.buffer.codec.test.protocols.usecodecscalar

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
import com.ditchoom.buffer.codec.toPeekResult
import com.ditchoom.buffer.codec.toWireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Deprecated
import kotlin.Int
import kotlin.ReplaceWith
import kotlin.Suppress

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

  @Deprecated(
    message = "Call wireBytes, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("wireBytes(value, context)"),
  )
  @Suppress("DEPRECATION")
  override fun wireSize(`value`: DispatchVarintUnion.Mixed, context: EncodeContext): WireSize = wireBytes(value, context).toWireSize()

  override fun sizeHint(`value`: DispatchVarintUnion.Mixed, context: EncodeContext): Int = 1

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize {
    val __vWidth = QuicVarintCodec.frameSize(stream, baseOffset + 0).fold(declared = { __h, __b -> __h + __b }, needsMoreData = { return FrameSize.NeedsMoreData }, unframed = { return FrameSize.Unframed })
    return FrameSize.declared(0, 1L + __vWidth)
  }

  @Deprecated(
    message = "Call frameSize, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
  )
  @Suppress("DEPRECATION")
  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)
}
