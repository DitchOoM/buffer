package com.ditchoom.buffer.codec.test.protocols.simple

import com.ditchoom.buffer.Charset
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
import kotlin.Int

public object CommandEchoCodec : Codec<Command.Echo> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): Command.Echo {
    val msgPrefixB0 = buffer.readUByte().toUInt()
    val msgPrefixB1 = buffer.readUByte().toUInt()
    val msgPrefix = ((msgPrefixB0 shl 8) or msgPrefixB1)
    if (msgPrefix > Int.MAX_VALUE.toUInt()) {
      throw DecodeException(fieldPath = "Echo.msg", bufferPosition = -1, expected = "length prefix <= ${'$'}{Int.MAX_VALUE}", actual = msgPrefix.toString())
    }
    val msgLength = msgPrefix.toInt()
    val msg = buffer.readString(msgLength, Charset.UTF8)
    return Command.Echo(msg = msg)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: Command.Echo,
    context: EncodeContext,
  ) {
    val msgSizePosition = buffer.position()
    buffer.position(msgSizePosition + 2)
    val msgBodyStart = buffer.position()
    buffer.writeString(value.msg, Charset.UTF8)
    val msgEndPosition = buffer.position()
    val msgByteCount = msgEndPosition - msgBodyStart
    if (msgByteCount > 65_535) {
      throw EncodeException(fieldPath = "Echo.msg", reason = """UTF-8 byte length ${msgByteCount} exceeds @LengthPrefixed(LengthPrefix.Short) max 65535""")
    }
    buffer.position(msgSizePosition)
    val msgPrefix = msgByteCount.toUInt()
    buffer.writeUByte(((msgPrefix shr 8) and 0xFFu).toUByte())
    buffer.writeUByte((msgPrefix and 0xFFu).toUByte())
    buffer.position(msgEndPosition)
  }

  override fun wireSize(`value`: Command.Echo, context: EncodeContext): WireSize = WireSize.BackPatch

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    var __offset = 0
    if (stream.available() - baseOffset < __offset + 2) return PeekResult.NeedsMoreData
    val msgPrefixB0 = stream.peekByte(baseOffset + __offset).toInt() and 0xFF
    val msgPrefixB1 = stream.peekByte(baseOffset + __offset + 1).toInt() and 0xFF
    val msgPrefix = ((msgPrefixB0 shl 8) or msgPrefixB1).toUInt()
    if (msgPrefix > (Int.MAX_VALUE - __offset - 2).toUInt()) {
      throw DecodeException(fieldPath = "Echo.msg", bufferPosition = baseOffset + __offset, expected = "__offset + 2 + length prefix <= ${'$'}{Int.MAX_VALUE}", actual = """${__offset + 2 + msgPrefix.toInt()}""")
    }
    __offset += 2 + msgPrefix.toInt()
    return if (stream.available() - baseOffset >= __offset) PeekResult.Complete(__offset) else PeekResult.NeedsMoreData
  }
}
