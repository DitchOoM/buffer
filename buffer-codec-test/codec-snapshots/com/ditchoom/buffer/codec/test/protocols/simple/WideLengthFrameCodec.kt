package com.ditchoom.buffer.codec.test.protocols.simple

import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.buffer.swapBytes
import kotlin.Int

public object WideLengthFrameCodec : Codec<WideLengthFrame> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): WideLengthFrame {
    val lengthRaw = buffer.readInt()
    val length = (if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) lengthRaw else swapBytes(lengthRaw)).toUInt()
    val flags = buffer.readUByte()
    if (length > Int.MAX_VALUE.toUInt()) {
      throw DecodeException(fieldPath = "WideLengthFrame.payload", bufferPosition = -1, expected = "@LengthFrom source <= ${'$'}{Int.MAX_VALUE}", actual = length.toString())
    }
    val payload = buffer.readString(length.toInt(), Charset.UTF8)
    return WideLengthFrame(length = length, flags = flags, payload = payload)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: WideLengthFrame,
    context: EncodeContext,
  ) {
    val lengthRaw = value.length.toInt()
    buffer.writeInt(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) lengthRaw else swapBytes(lengthRaw))
    buffer.writeUByte(value.flags)
    buffer.writeString(value.payload, Charset.UTF8)
  }

  override fun wireSize(`value`: WideLengthFrame, context: EncodeContext): WireSize = WireSize.Exact(5 + value.length.toInt())

  override fun sizeHint(`value`: WideLengthFrame, context: EncodeContext): Int = 5 + value.payload.length

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    var __offset = 0
    if (stream.available() - baseOffset < __offset + 4) return PeekResult.NeedsMoreData
    val lengthB0 = stream.peekByte(baseOffset + __offset).toInt() and 0xFF
    val lengthB1 = stream.peekByte(baseOffset + __offset + 1).toInt() and 0xFF
    val lengthB2 = stream.peekByte(baseOffset + __offset + 2).toInt() and 0xFF
    val lengthB3 = stream.peekByte(baseOffset + __offset + 3).toInt() and 0xFF
    val length = ((lengthB0 shl 24) or (lengthB1 shl 16) or (lengthB2 shl 8) or lengthB3).toUInt()
    __offset += 4
    if (stream.available() - baseOffset < __offset + 1) return PeekResult.NeedsMoreData
    __offset += 1
    if (length > Int.MAX_VALUE.toUInt()) {
      throw DecodeException(fieldPath = "WideLengthFrame.payload", bufferPosition = -1, expected = "@LengthFrom source <= ${'$'}{Int.MAX_VALUE}", actual = length.toString())
    }
    val payloadBytes = length.toInt()
    if (payloadBytes < 0 || payloadBytes > Int.MAX_VALUE - __offset) {
      throw DecodeException(fieldPath = "WideLengthFrame.payload", bufferPosition = baseOffset + __offset, expected = "__offset + @LengthFrom source in 0..${'$'}{Int.MAX_VALUE}", actual = """${__offset.toLong() + payloadBytes.toLong()}""")
    }
    if (stream.available() - baseOffset < __offset + payloadBytes) return PeekResult.NeedsMoreData
    __offset += payloadBytes
    return if (stream.available() - baseOffset >= __offset) PeekResult.Complete(__offset) else PeekResult.NeedsMoreData
  }
}
