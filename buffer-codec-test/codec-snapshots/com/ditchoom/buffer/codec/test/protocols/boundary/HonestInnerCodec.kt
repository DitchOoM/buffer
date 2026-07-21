package com.ditchoom.buffer.codec.test.protocols.boundary

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object HonestInnerCodec : Codec<HonestInner> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): HonestInner {
    val v = HonestSizeCodec.decode(buffer, context)
    return HonestInner(v = v)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: HonestInner,
    context: EncodeContext,
  ) {
    HonestSizeCodec.encode(buffer, value.v, context)
  }

  override fun wireSize(`value`: HonestInner, context: EncodeContext): WireSize {
    val __vSize = when (val __s = HonestSizeCodec.wireSize(value.v, context)) {
      is WireSize.Exact -> __s.bytes
      WireSize.BackPatch -> return WireSize.BackPatch
    }
    return WireSize.Exact(0 + __vSize)
  }

  override fun sizeHint(`value`: HonestInner, context: EncodeContext): Int = 0

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = PeekResult.NoFraming
}
