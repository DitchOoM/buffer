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

public object Http2FrameWindowUpdateCodec : Codec<Http2Frame.WindowUpdate> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): Http2Frame.WindowUpdate {
    val header = Http2LengthAndType(buffer.readUInt())
    val flags = buffer.readUByte()
    val streamId = Http2StreamId(buffer.readUInt())
    val windowSizeIncrementB0 = buffer.readUByte().toUInt()
    val windowSizeIncrementB1 = buffer.readUByte().toUInt()
    val windowSizeIncrementB2 = buffer.readUByte().toUInt()
    val windowSizeIncrementB3 = buffer.readUByte().toUInt()
    val windowSizeIncrement = ((windowSizeIncrementB0 shl 24) or (windowSizeIncrementB1 shl 16) or (windowSizeIncrementB2 shl 8) or windowSizeIncrementB3)
    return Http2Frame.WindowUpdate(header = header, flags = flags, streamId = streamId, windowSizeIncrement = windowSizeIncrement)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: Http2Frame.WindowUpdate,
    context: EncodeContext,
  ) {
    buffer.writeUInt(value.header.raw)
    buffer.writeUByte(value.flags)
    buffer.writeUInt(value.streamId.raw)
    buffer.writeUByte(((value.windowSizeIncrement shr 24) and 0xFFu).toUByte())
    buffer.writeUByte(((value.windowSizeIncrement shr 16) and 0xFFu).toUByte())
    buffer.writeUByte(((value.windowSizeIncrement shr 8) and 0xFFu).toUByte())
    buffer.writeUByte((value.windowSizeIncrement and 0xFFu).toUByte())
  }

  override fun wireSize(`value`: Http2Frame.WindowUpdate, context: EncodeContext): WireSize = WireSize.Exact(13)

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = if (stream.available() - baseOffset >= 13) PeekResult.Complete(13) else PeekResult.NeedsMoreData
}
