package com.ditchoom.buffer.codec.test.protocols.http2

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

public object Http2LengthAndTypeCodec : Codec<Http2LengthAndType> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): Http2LengthAndType {
    val rawRaw = buffer.readInt()
    val raw = (if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) rawRaw else swapBytes(rawRaw)).toUInt()
    return Http2LengthAndType(raw = raw)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: Http2LengthAndType,
    context: EncodeContext,
  ) {
    val rawRaw = value.raw.toInt()
    buffer.writeInt(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) rawRaw else swapBytes(rawRaw))
  }

  override fun wireBytes(`value`: Http2LengthAndType, context: EncodeContext): WireByteCount = WireByteCount.exact(4L)

  override fun sizeHint(`value`: Http2LengthAndType, context: EncodeContext): Int = 4

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.declared(0, 4L)
}
