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
import com.ditchoom.buffer.codec.toPeekResult
import com.ditchoom.buffer.codec.toWireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Deprecated
import kotlin.Int
import kotlin.ReplaceWith
import kotlin.Suppress

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

  override fun wireBytes(`value`: MisdeclaredInner, context: EncodeContext): WireByteCount {
    val __vSize = MisdeclaredSizeCodec.wireBytes(value.v, context).fold(exact = { it }, backPatch = { return WireByteCount.BackPatch })
    return WireByteCount.exact(0L + __vSize)
  }

  @Deprecated(
    message = "Call wireBytes, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("wireBytes(value, context)"),
  )
  @Suppress("DEPRECATION")
  override fun wireSize(`value`: MisdeclaredInner, context: EncodeContext): WireSize = wireBytes(value, context).toWireSize()

  override fun sizeHint(`value`: MisdeclaredInner, context: EncodeContext): Int = 0

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.Unframed

  @Deprecated(
    message = "Call frameSize, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
  )
  @Suppress("DEPRECATION")
  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)
}
