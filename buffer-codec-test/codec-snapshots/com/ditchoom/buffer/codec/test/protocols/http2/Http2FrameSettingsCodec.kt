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

public object Http2FrameSettingsCodec : Codec<Http2Frame.Settings> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): Http2Frame.Settings {
    val headerRaw = buffer.readInt()
    val header = Http2LengthAndType((if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) headerRaw else swapBytes(headerRaw)).toUInt())
    val flags = buffer.readUByte()
    val streamId = Http2StreamId(buffer.readUInt())
    val entriesBytes = header.length
    val entriesOuterLimit = buffer.limit()
    buffer.setLimit(buffer.position() + entriesBytes)
    val entries = mutableListOf<Http2Setting>()
    try {
      while (buffer.position() < buffer.limit()) {
        entries += Http2SettingCodec.decode(buffer, context)
      }
    } finally {
      buffer.setLimit(entriesOuterLimit)
    }
    return Http2Frame.Settings(header = header, flags = flags, streamId = streamId, entries = entries)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: Http2Frame.Settings,
    context: EncodeContext,
  ) {
    val headerRaw = value.header.raw.toInt()
    buffer.writeInt(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) headerRaw else swapBytes(headerRaw))
    buffer.writeUByte(value.flags)
    buffer.writeUInt(value.streamId.raw)
    for (__elem in value.entries) {
      Http2SettingCodec.encode(buffer, __elem, context)
    }
  }

  override fun wireSize(`value`: Http2Frame.Settings, context: EncodeContext): WireSize = WireSize.Exact(9 + value.header.length)

  override fun sizeHint(`value`: Http2Frame.Settings, context: EncodeContext): Int = 9

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    var __offset = 0
    if (stream.available() - baseOffset < __offset + 4) return PeekResult.NeedsMoreData
    val headerRawB0 = stream.peekByte(baseOffset + __offset).toInt() and 0xFF
    val headerRawB1 = stream.peekByte(baseOffset + __offset + 1).toInt() and 0xFF
    val headerRawB2 = stream.peekByte(baseOffset + __offset + 2).toInt() and 0xFF
    val headerRawB3 = stream.peekByte(baseOffset + __offset + 3).toInt() and 0xFF
    val headerRaw = ((headerRawB0 shl 24) or (headerRawB1 shl 16) or (headerRawB2 shl 8) or headerRawB3).toUInt()
    val header = Http2LengthAndType(headerRaw)
    __offset += 4
    if (stream.available() - baseOffset < __offset + 1) return PeekResult.NeedsMoreData
    __offset += 1
    if (stream.available() - baseOffset < __offset + 4) return PeekResult.NeedsMoreData
    __offset += 4
    val entriesBytes = header.length
    if (stream.available() - baseOffset < __offset + entriesBytes) return PeekResult.NeedsMoreData
    __offset += entriesBytes
    return if (stream.available() - baseOffset >= __offset) PeekResult.Complete(__offset) else PeekResult.NeedsMoreData
  }
}
