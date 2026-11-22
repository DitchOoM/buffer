package com.ditchoom.buffer.codec.test.protocols.count

import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.WireByteCount
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.buffer.swapBytes
import kotlin.Int

public object CountPointCodec : Codec<CountPoint> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): CountPoint {
    val xRaw = buffer.readShort()
    val x = (if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) xRaw else swapBytes(xRaw)).toUShort()
    val y = buffer.readUByte()
    return CountPoint(x = x, y = y)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: CountPoint,
    context: EncodeContext,
  ) {
    val xRaw = value.x.toShort()
    buffer.writeShort(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) xRaw else swapBytes(xRaw))
    buffer.writeUByte(value.y)
  }

  override fun wireBytes(`value`: CountPoint, context: EncodeContext): WireByteCount = WireByteCount.exact(3L)

  override fun sizeHint(`value`: CountPoint, context: EncodeContext): Int = 3

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.declared(0, 3L)
}
