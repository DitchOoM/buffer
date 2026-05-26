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

public object Http2FrameWindowUpdateCodec : Codec<Http2Frame.WindowUpdate> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): Http2Frame.WindowUpdate {
    val headerRaw = buffer.readInt()
    val header = Http2LengthAndType((if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) headerRaw else swapBytes(headerRaw)).toUInt())
    val flags = buffer.readUByte()
    val streamId = Http2StreamId(buffer.readUInt())
    val windowSizeIncrementRaw = buffer.readInt()
    val windowSizeIncrement = (if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) windowSizeIncrementRaw else swapBytes(windowSizeIncrementRaw)).toUInt()
    return Http2Frame.WindowUpdate(header = header, flags = flags, streamId = streamId, windowSizeIncrement = windowSizeIncrement)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: Http2Frame.WindowUpdate,
    context: EncodeContext,
  ) {
    val headerRaw = value.header.raw.toInt()
    buffer.writeInt(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) headerRaw else swapBytes(headerRaw))
    buffer.writeUByte(value.flags)
    buffer.writeUInt(value.streamId.raw)
    val windowSizeIncrementRaw = value.windowSizeIncrement.toInt()
    buffer.writeInt(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) windowSizeIncrementRaw else swapBytes(windowSizeIncrementRaw))
  }

  override fun wireSize(`value`: Http2Frame.WindowUpdate, context: EncodeContext): WireSize = WireSize.Exact(13)

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = if (stream.available() - baseOffset >= 13) PeekResult.Complete(13) else PeekResult.NeedsMoreData
}
