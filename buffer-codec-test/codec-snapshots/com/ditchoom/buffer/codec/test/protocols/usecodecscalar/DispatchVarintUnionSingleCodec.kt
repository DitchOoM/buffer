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
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Deprecated
import kotlin.Int
import kotlin.ReplaceWith
import kotlin.Suppress

public object DispatchVarintUnionSingleCodec : Codec<DispatchVarintUnion.Single> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): DispatchVarintUnion.Single {
    val v = QuicVarintCodec.decode(buffer, context)
    return DispatchVarintUnion.Single(v = v)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: DispatchVarintUnion.Single,
    context: EncodeContext,
  ) {
    QuicVarintCodec.encode(buffer, value.v, context)
  }

  override fun wireBytes(`value`: DispatchVarintUnion.Single, context: EncodeContext): WireByteCount {
    val __vSize = QuicVarintCodec.encodedLength(value.v).toLong()
    return WireByteCount.exact(0L + __vSize)
  }

  @Deprecated(
    message = "Call wireBytes.",
    replaceWith = ReplaceWith("wireBytes(value, context)"),
  )
  @Suppress("DEPRECATION")
  override fun wireSize(`value`: DispatchVarintUnion.Single, context: EncodeContext): WireSize = wireBytes(value, context).toWireSize()

  override fun sizeHint(`value`: DispatchVarintUnion.Single, context: EncodeContext): Int = 0

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize {
    val __vWidth = QuicVarintCodec.frameSize(stream, baseOffset + 0).fold(declared = { it }, needsMoreData = { return FrameSize.NeedsMoreData }, unframed = { return FrameSize.Unframed })
    return FrameSize.declared(0, __vWidth)
  }

  @Deprecated(
    message = "Call frameSize.",
    replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
  )
  @Suppress("DEPRECATION")
  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)
}
