package com.ditchoom.buffer.codec.test.protocols.count

import com.ditchoom.buffer.ByteOrder
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
import com.ditchoom.buffer.swapBytes
import kotlin.Deprecated
import kotlin.Int
import kotlin.ReplaceWith
import kotlin.Suppress

public object CountInnerFixedCodec : Codec<CountInnerFixed> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): CountInnerFixed {
    val aRaw = buffer.readShort()
    val a = if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) aRaw else swapBytes(aRaw)
    return CountInnerFixed(a = a)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: CountInnerFixed,
    context: EncodeContext,
  ) {
    val aRaw = value.a
    buffer.writeShort(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) aRaw else swapBytes(aRaw))
  }

  override fun wireBytes(`value`: CountInnerFixed, context: EncodeContext): WireByteCount = WireByteCount.exact(2L)

  @Deprecated(
    message = "Call wireBytes.",
    replaceWith = ReplaceWith("wireBytes(value, context)"),
  )
  @Suppress("DEPRECATION")
  override fun wireSize(`value`: CountInnerFixed, context: EncodeContext): WireSize = wireBytes(value, context).toWireSize()

  override fun sizeHint(`value`: CountInnerFixed, context: EncodeContext): Int = 2

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.declared(2L)

  @Deprecated(
    message = "Call frameSize.",
    replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
  )
  @Suppress("DEPRECATION")
  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)
}
