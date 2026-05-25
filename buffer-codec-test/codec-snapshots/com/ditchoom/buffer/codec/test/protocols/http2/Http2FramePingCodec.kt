package com.ditchoom.buffer.codec.test.protocols.http2

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object Http2FramePingCodec : Codec<Http2Frame.Ping> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): Http2Frame.Ping {
    val header = Http2LengthAndType(buffer.readUInt())
    val flags = buffer.readUByte()
    val streamId = Http2StreamId(buffer.readUInt())
    val opaqueDataB0 = buffer.readUByte().toULong()
    val opaqueDataB1 = buffer.readUByte().toULong()
    val opaqueDataB2 = buffer.readUByte().toULong()
    val opaqueDataB3 = buffer.readUByte().toULong()
    val opaqueDataB4 = buffer.readUByte().toULong()
    val opaqueDataB5 = buffer.readUByte().toULong()
    val opaqueDataB6 = buffer.readUByte().toULong()
    val opaqueDataB7 = buffer.readUByte().toULong()
    val opaqueData = ((opaqueDataB0 shl 56) or (opaqueDataB1 shl 48) or (opaqueDataB2 shl 40) or (opaqueDataB3 shl 32) or (opaqueDataB4 shl 24) or (opaqueDataB5 shl 16) or (opaqueDataB6 shl 8) or opaqueDataB7)
    return Http2Frame.Ping(header = header, flags = flags, streamId = streamId, opaqueData = opaqueData)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: Http2Frame.Ping,
    context: EncodeContext,
  ) {
    buffer.writeUInt(value.header.raw)
    buffer.writeUByte(value.flags)
    buffer.writeUInt(value.streamId.raw)
    buffer.writeUByte(((value.opaqueData shr 56) and 0xFFuL).toUByte())
    buffer.writeUByte(((value.opaqueData shr 48) and 0xFFuL).toUByte())
    buffer.writeUByte(((value.opaqueData shr 40) and 0xFFuL).toUByte())
    buffer.writeUByte(((value.opaqueData shr 32) and 0xFFuL).toUByte())
    buffer.writeUByte(((value.opaqueData shr 24) and 0xFFuL).toUByte())
    buffer.writeUByte(((value.opaqueData shr 16) and 0xFFuL).toUByte())
    buffer.writeUByte(((value.opaqueData shr 8) and 0xFFuL).toUByte())
    buffer.writeUByte((value.opaqueData and 0xFFuL).toUByte())
  }

  override fun wireSize(`value`: Http2Frame.Ping, context: EncodeContext): WireSize = WireSize.Exact(17)

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = if (stream.available() - baseOffset >= 17) PeekResult.Complete(17) else PeekResult.NeedsMoreData
}
