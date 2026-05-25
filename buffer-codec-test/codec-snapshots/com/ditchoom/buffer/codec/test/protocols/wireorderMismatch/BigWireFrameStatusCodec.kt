package com.ditchoom.buffer.codec.test.protocols.wireorderMismatch

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object BigWireFrameStatusCodec : Codec<BigWireFrame.Status> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): BigWireFrame.Status {
    val flagsB0 = buffer.readUByte().toUInt()
    val flagsB1 = buffer.readUByte().toUInt()
    val flagsB2 = buffer.readUByte().toUInt()
    val flagsB3 = buffer.readUByte().toUInt()
    val flags = ((flagsB0 shl 24) or (flagsB1 shl 16) or (flagsB2 shl 8) or flagsB3)
    val ratioB0 = buffer.readUByte().toULong()
    val ratioB1 = buffer.readUByte().toULong()
    val ratioB2 = buffer.readUByte().toULong()
    val ratioB3 = buffer.readUByte().toULong()
    val ratioB4 = buffer.readUByte().toULong()
    val ratioB5 = buffer.readUByte().toULong()
    val ratioB6 = buffer.readUByte().toULong()
    val ratioB7 = buffer.readUByte().toULong()
    val ratio = Double.fromBits(((ratioB0 shl 56) or (ratioB1 shl 48) or (ratioB2 shl 40) or (ratioB3 shl 32) or (ratioB4 shl 24) or (ratioB5 shl 16) or (ratioB6 shl 8) or ratioB7).toLong())
    return BigWireFrame.Status(flags = flags, ratio = ratio)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: BigWireFrame.Status,
    context: EncodeContext,
  ) {
    buffer.writeUByte(((value.flags shr 24) and 0xFFu).toUByte())
    buffer.writeUByte(((value.flags shr 16) and 0xFFu).toUByte())
    buffer.writeUByte(((value.flags shr 8) and 0xFFu).toUByte())
    buffer.writeUByte((value.flags and 0xFFu).toUByte())
    buffer.writeUByte(((value.ratio.toRawBits().toULong() shr 56) and 0xFFuL).toUByte())
    buffer.writeUByte(((value.ratio.toRawBits().toULong() shr 48) and 0xFFuL).toUByte())
    buffer.writeUByte(((value.ratio.toRawBits().toULong() shr 40) and 0xFFuL).toUByte())
    buffer.writeUByte(((value.ratio.toRawBits().toULong() shr 32) and 0xFFuL).toUByte())
    buffer.writeUByte(((value.ratio.toRawBits().toULong() shr 24) and 0xFFuL).toUByte())
    buffer.writeUByte(((value.ratio.toRawBits().toULong() shr 16) and 0xFFuL).toUByte())
    buffer.writeUByte(((value.ratio.toRawBits().toULong() shr 8) and 0xFFuL).toUByte())
    buffer.writeUByte((value.ratio.toRawBits().toULong() and 0xFFuL).toUByte())
  }

  override fun wireSize(`value`: BigWireFrame.Status, context: EncodeContext): WireSize = WireSize.Exact(12)

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = if (stream.available() - baseOffset >= 12) PeekResult.Complete(12) else PeekResult.NeedsMoreData
}
