package com.ditchoom.buffer.codec.test.protocols.http2

import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.EncodeException
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.buffer.swapBytes
import kotlin.Int

public object Http2SettingsFrameCodec : Codec<Http2SettingsFrame> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): Http2SettingsFrame {
    val lengthB0 = buffer.readUByte().toUInt()
    val lengthB1 = buffer.readUByte().toUInt()
    val lengthB2 = buffer.readUByte().toUInt()
    val length = ((lengthB0 shl 16) or (lengthB1 shl 8) or lengthB2)
    val __batch1Raw = buffer.readShort().toInt() and 0xFFFF
    val __batch1 = if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) __batch1Raw else swapBytes(__batch1Raw.toShort()).toInt() and 0xFFFF
    val type = (__batch1 ushr 8 and 0xFF).toUByte()
    val flags = (__batch1 and 0xFF).toUByte()
    val streamId = Http2StreamId(buffer.readUInt())
    if (length > Int.MAX_VALUE.toUInt()) {
      throw DecodeException(fieldPath = "Http2SettingsFrame.entries", bufferPosition = -1, expected = "@LengthFrom source <= ${'$'}{Int.MAX_VALUE}", actual = length.toString())
    }
    val entriesBytes = length.toInt()
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
    return Http2SettingsFrame(length = length, type = type, flags = flags, streamId = streamId, entries = entries)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: Http2SettingsFrame,
    context: EncodeContext,
  ) {
    if (value.length > ((1u shl 24) - 1u)) {
      throw EncodeException(fieldPath = "Http2SettingsFrame.length", reason = "value exceeds @WireBytes(3) range (max 16777215)")
    }
    buffer.writeUByte(((value.length shr 16) and 0xFFu).toUByte())
    buffer.writeUByte(((value.length shr 8) and 0xFFu).toUByte())
    buffer.writeUByte((value.length and 0xFFu).toUByte())
    val __batch2 = (((value.type.toInt() and 0xFF) shl 8) or (value.flags.toInt() and 0xFF)).toShort()
    buffer.writeShort(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) __batch2 else swapBytes(__batch2))
    buffer.writeUInt(value.streamId.raw)
    for (__elem in value.entries) {
      Http2SettingCodec.encode(buffer, __elem, context)
    }
  }

  override fun wireSize(`value`: Http2SettingsFrame, context: EncodeContext): WireSize = WireSize.Exact(9 + value.length.toInt())

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    var __offset = 0
    if (stream.available() - baseOffset < __offset + 3) return PeekResult.NeedsMoreData
    val lengthB0 = stream.peekByte(baseOffset + __offset).toInt() and 0xFF
    val lengthB1 = stream.peekByte(baseOffset + __offset + 1).toInt() and 0xFF
    val lengthB2 = stream.peekByte(baseOffset + __offset + 2).toInt() and 0xFF
    val length = ((lengthB0 shl 16) or (lengthB1 shl 8) or lengthB2).toUInt()
    __offset += 3
    if (stream.available() - baseOffset < __offset + 1) return PeekResult.NeedsMoreData
    __offset += 1
    if (stream.available() - baseOffset < __offset + 1) return PeekResult.NeedsMoreData
    __offset += 1
    if (stream.available() - baseOffset < __offset + 4) return PeekResult.NeedsMoreData
    __offset += 4
    if (length > Int.MAX_VALUE.toUInt()) {
      throw DecodeException(fieldPath = "Http2SettingsFrame.entries", bufferPosition = -1, expected = "@LengthFrom source <= ${'$'}{Int.MAX_VALUE}", actual = length.toString())
    }
    val entriesBytes = length.toInt()
    if (stream.available() - baseOffset < __offset + entriesBytes) return PeekResult.NeedsMoreData
    __offset += entriesBytes
    return if (stream.available() - baseOffset >= __offset) PeekResult.Complete(__offset) else PeekResult.NeedsMoreData
  }
}
