package com.ditchoom.buffer.codec.test.protocols.wireorderMismatch

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
import com.ditchoom.buffer.codec.toPeekResult
import com.ditchoom.buffer.codec.toWireSize
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.buffer.swapBytes
import kotlin.Deprecated
import kotlin.Int
import kotlin.ReplaceWith
import kotlin.Suppress

public object BigWireFrameStatusCodec : Codec<BigWireFrame.Status> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): BigWireFrame.Status {
    val flagsRaw = buffer.readInt()
    val flags = (if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) flagsRaw else swapBytes(flagsRaw)).toUInt()
    val ratioRaw = buffer.readLong()
    val ratio = Double.fromBits(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) ratioRaw else swapBytes(ratioRaw))
    return BigWireFrame.Status(flags = flags, ratio = ratio)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: BigWireFrame.Status,
    context: EncodeContext,
  ) {
    val flagsRaw = value.flags.toInt()
    buffer.writeInt(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) flagsRaw else swapBytes(flagsRaw))
    val ratioRaw = value.ratio.toRawBits()
    buffer.writeLong(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) ratioRaw else swapBytes(ratioRaw))
  }

  override fun wireBytes(`value`: BigWireFrame.Status, context: EncodeContext): WireByteCount = WireByteCount.exact(12L)

  @Deprecated(
    message = "Call wireBytes, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("wireBytes(value, context)"),
  )
  @Suppress("DEPRECATION")
  override fun wireSize(`value`: BigWireFrame.Status, context: EncodeContext): WireSize = wireBytes(value, context).toWireSize()

  override fun sizeHint(`value`: BigWireFrame.Status, context: EncodeContext): Int = 12

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.declared(0, 12L)

  @Deprecated(
    message = "Call frameSize, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
  )
  @Suppress("DEPRECATION")
  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)
}
