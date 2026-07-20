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

public object MisdeclaredInnerCodec : Codec<MisdeclaredInner> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): MisdeclaredInner {
    val v = MisdeclaredSizeCodec.decode(buffer, context)
    return MisdeclaredInner(v = v)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: MisdeclaredInner,
    context: EncodeContext,
  ) {
    MisdeclaredSizeCodec.encode(buffer, value.v, context)
  }

  override fun wireSize(`value`: MisdeclaredInner, context: EncodeContext): WireSize {
    val __vSize = when (val __s = MisdeclaredSizeCodec.wireSize(value.v, context)) {
      is WireSize.Exact -> __s.bytes
      WireSize.BackPatch -> return WireSize.BackPatch
    }
    return WireSize.Exact(0 + __vSize)
  }

  override fun sizeHint(`value`: MisdeclaredInner, context: EncodeContext): Int = 0

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = PeekResult.NoFraming
}
