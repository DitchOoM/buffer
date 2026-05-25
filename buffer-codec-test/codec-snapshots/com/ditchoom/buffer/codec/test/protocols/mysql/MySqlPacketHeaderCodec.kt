package com.ditchoom.buffer.codec.test.protocols.mysql

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object MySqlPacketHeaderCodec : Codec<MySqlPacketHeader> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): MySqlPacketHeader {
    val rawB0 = buffer.readUByte().toUInt()
    val rawB1 = buffer.readUByte().toUInt()
    val rawB2 = buffer.readUByte().toUInt()
    val rawB3 = buffer.readUByte().toUInt()
    val raw = (rawB0 or (rawB1 shl 8) or (rawB2 shl 16) or (rawB3 shl 24))
    return MySqlPacketHeader(raw = raw)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: MySqlPacketHeader,
    context: EncodeContext,
  ) {
    buffer.writeUByte((value.raw and 0xFFu).toUByte())
    buffer.writeUByte(((value.raw shr 8) and 0xFFu).toUByte())
    buffer.writeUByte(((value.raw shr 16) and 0xFFu).toUByte())
    buffer.writeUByte(((value.raw shr 24) and 0xFFu).toUByte())
  }

  override fun wireSize(`value`: MySqlPacketHeader, context: EncodeContext): WireSize = WireSize.Exact(4)

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = if (stream.available() - baseOffset >= 4) PeekResult.Complete(4) else PeekResult.NeedsMoreData
}
