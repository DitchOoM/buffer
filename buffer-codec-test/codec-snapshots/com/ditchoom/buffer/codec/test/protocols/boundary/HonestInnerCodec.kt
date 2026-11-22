package com.ditchoom.buffer.codec.test.protocols.boundary

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.WireByteCount
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

  override fun wireBytes(`value`: HonestInner, context: EncodeContext): WireByteCount {
    val __vSize = HonestSizeCodec.wireBytes(value.v, context).fold(exact = { it }, backPatch = { return WireByteCount.BackPatch })
    return WireByteCount.exact(0L + __vSize)
  }

  override fun sizeHint(`value`: HonestInner, context: EncodeContext): Int = 0

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.Unframed
}
