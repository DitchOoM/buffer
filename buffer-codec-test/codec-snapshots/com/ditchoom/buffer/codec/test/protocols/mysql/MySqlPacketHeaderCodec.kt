package com.ditchoom.buffer.codec.test.protocols.mysql

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

public object MySqlPacketHeaderCodec : Codec<MySqlPacketHeader> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): MySqlPacketHeader {
    val rawRaw = buffer.readInt()
    val raw = (if (buffer.byteOrder == ByteOrder.LITTLE_ENDIAN) rawRaw else swapBytes(rawRaw)).toUInt()
    return MySqlPacketHeader(raw = raw)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: MySqlPacketHeader,
    context: EncodeContext,
  ) {
    val rawRaw = value.raw.toInt()
    buffer.writeInt(if (buffer.byteOrder == ByteOrder.LITTLE_ENDIAN) rawRaw else swapBytes(rawRaw))
  }

  override fun wireBytes(`value`: MySqlPacketHeader, context: EncodeContext): WireByteCount = WireByteCount.exact(4L)

  override fun sizeHint(`value`: MySqlPacketHeader, context: EncodeContext): Int = 4

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.declared(0, 4L)
}
