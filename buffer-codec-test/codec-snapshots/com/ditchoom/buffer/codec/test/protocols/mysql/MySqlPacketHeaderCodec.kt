package com.ditchoom.buffer.codec.test.protocols.mysql

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

  override fun wireSize(`value`: MySqlPacketHeader, context: EncodeContext): WireSize = WireSize.Exact(4)

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = if (stream.available() - baseOffset >= 4) PeekResult.Complete(4) else PeekResult.NeedsMoreData
}
