package com.ditchoom.buffer.codec.test.protocols.boundary

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireByteCount
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Deprecated
import kotlin.Int
import kotlin.ReplaceWith
import kotlin.Suppress

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

  @Deprecated(
    message = "Call wireBytes.",
    replaceWith = ReplaceWith("wireBytes(value, context)"),
  )
  @Suppress("DEPRECATION")
  override fun wireSize(`value`: HonestInner, context: EncodeContext): WireSize = wireBytes(value, context).toWireSize()

  override fun sizeHint(`value`: HonestInner, context: EncodeContext): Int = 0

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.Unframed

  @Deprecated(
    message = "Call frameSize.",
    replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
  )
  @Suppress("DEPRECATION")
  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)
}
