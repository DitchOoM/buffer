package com.ditchoom.buffer.codec.test.protocols.http2

import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.EncodeException
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.FrameTooLargeException
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireByteCount
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.codec.toPeekResult
import com.ditchoom.buffer.codec.toWireSize
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.buffer.swapBytes
import kotlin.Deprecated
import kotlin.Int
import kotlin.ReplaceWith
import kotlin.Suppress

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
      throw FrameTooLargeException(declaredBytes = length.toLong(), maxBytes = Int.MAX_VALUE)
    }
    val entriesBytes = length.toInt()
    if (entriesBytes > buffer.remaining()) {
      throw DecodeException(
            fieldPath = "Http2SettingsFrame.entries",
            bufferPosition = buffer.position(),
            expected = "a " + entriesBytes + "-byte bounded region within the enclosing limit",
            actual = buffer.remaining().toString() + " bytes available",
          )
    }
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

  override fun wireBytes(`value`: Http2SettingsFrame, context: EncodeContext): WireByteCount = WireByteCount.exact(9L + value.length.toLong())

  @Deprecated(
    message = "Call wireBytes, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("wireBytes(value, context)"),
  )
  @Suppress("DEPRECATION")
  override fun wireSize(`value`: Http2SettingsFrame, context: EncodeContext): WireSize = wireBytes(value, context).toWireSize()

  override fun sizeHint(`value`: Http2SettingsFrame, context: EncodeContext): Int = 9

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize {
    var __offset = 0
    if (stream.available() - baseOffset < __offset + 3) return FrameSize.NeedsMoreData
    val lengthB0 = stream.peekByte(baseOffset + __offset).toInt() and 0xFF
    val lengthB1 = stream.peekByte(baseOffset + __offset + 1).toInt() and 0xFF
    val lengthB2 = stream.peekByte(baseOffset + __offset + 2).toInt() and 0xFF
    val length = ((lengthB0 shl 16) or (lengthB1 shl 8) or lengthB2).toUInt()
    __offset += 3
    if (stream.available() - baseOffset < __offset + 1) return FrameSize.NeedsMoreData
    __offset += 1
    if (stream.available() - baseOffset < __offset + 1) return FrameSize.NeedsMoreData
    __offset += 1
    if (stream.available() - baseOffset < __offset + 4) return FrameSize.NeedsMoreData
    __offset += 4
    if (length > Int.MAX_VALUE.toUInt()) {
      throw FrameTooLargeException(declaredBytes = length.toLong(), maxBytes = Int.MAX_VALUE)
    }
    val entriesBytes = length.toInt()
    if (entriesBytes < 0 || entriesBytes > Int.MAX_VALUE - __offset) {
      throw FrameTooLargeException(declaredBytes = if (entriesBytes < 0) entriesBytes.toLong() else __offset.toLong() + entriesBytes, maxBytes = Int.MAX_VALUE)
    }
    __offset += entriesBytes
    return FrameSize.declared(0, __offset.toLong())
  }

  @Deprecated(
    message = "Call frameSize, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
  )
  @Suppress("DEPRECATION")
  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)
}
