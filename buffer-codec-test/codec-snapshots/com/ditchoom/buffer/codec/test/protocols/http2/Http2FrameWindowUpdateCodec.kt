package com.ditchoom.buffer.codec.test.protocols.http2

import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireByteCount
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.buffer.swapBytes
import kotlin.Deprecated
import kotlin.Int
import kotlin.ReplaceWith
import kotlin.Suppress

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

  override fun wireBytes(`value`: Http2Frame.WindowUpdate, context: EncodeContext): WireByteCount = WireByteCount.exact(13L)

  @Deprecated(
    message = "Call wireBytes.",
    replaceWith = ReplaceWith("wireBytes(value, context)"),
  )
  @Suppress("DEPRECATION")
  override fun wireSize(`value`: Http2Frame.WindowUpdate, context: EncodeContext): WireSize = wireBytes(value, context).toWireSize()

  override fun sizeHint(`value`: Http2Frame.WindowUpdate, context: EncodeContext): Int = 13

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.declared(13L)

  @Deprecated(
    message = "Call frameSize.",
    replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
  )
  @Suppress("DEPRECATION")
  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)
}
