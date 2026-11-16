package com.ditchoom.buffer.codec.test.protocols.usecodecscalar

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FramePeek
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.codec.test.protocols.quic.QuicVarintCodec
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

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

  override fun wireSize(`value`: DispatchVarintUnion.Single, context: EncodeContext): WireSize {
    val __vSize = when (val __s = QuicVarintCodec.wireSize(value.v, context)) {
      is WireSize.Exact -> __s.bytes
      WireSize.BackPatch -> return WireSize.BackPatch
    }
    return WireSize.Exact(0 + __vSize)
  }

  override fun sizeHint(`value`: DispatchVarintUnion.Single, context: EncodeContext): Int = 0

  override fun peekFrame(stream: StreamProcessor, baseOffset: Int): FramePeek {
    val __vFrame = QuicVarintCodec.peekFrame(stream, baseOffset + 0)
    if (!__vFrame.isComplete) {
      return __vFrame.withoutDeclaredSize()
    }
    val __total = 0 + __vFrame.size + 0
    return FramePeek.forDeclaredSize(__total, stream.available() - baseOffset)
  }

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = peekFrame(stream, baseOffset).toPeekResult()
}
