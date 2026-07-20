package com.ditchoom.buffer.codec.test.protocols.wireorderMismatch

import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.buffer.swapBytes
import kotlin.Int

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

  override fun wireSize(`value`: BigWireFrame.Status, context: EncodeContext): WireSize = WireSize.Exact(12)

  override fun sizeHint(`value`: BigWireFrame.Status, context: EncodeContext): Int = 12

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = if (stream.available() - baseOffset >= 12) PeekResult.Complete(12) else PeekResult.NeedsMoreData
}
