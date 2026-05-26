package com.ditchoom.buffer.codec.test.protocols.http2

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

public object Http2FramePingCodec : Codec<Http2Frame.Ping> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): Http2Frame.Ping {
    val header = Http2LengthAndType(buffer.readUInt())
    val flags = buffer.readUByte()
    val streamId = Http2StreamId(buffer.readUInt())
    val opaqueDataRaw = buffer.readLong()
    val opaqueData = (if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) opaqueDataRaw else swapBytes(opaqueDataRaw)).toULong()
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
    val opaqueDataRaw = value.opaqueData.toLong()
    buffer.writeLong(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) opaqueDataRaw else swapBytes(opaqueDataRaw))
  }

  override fun wireSize(`value`: Http2Frame.Ping, context: EncodeContext): WireSize = WireSize.Exact(17)

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = if (stream.available() - baseOffset >= 17) PeekResult.Complete(17) else PeekResult.NeedsMoreData
}
