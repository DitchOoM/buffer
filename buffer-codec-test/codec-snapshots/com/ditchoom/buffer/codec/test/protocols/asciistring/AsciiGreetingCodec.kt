package com.ditchoom.buffer.codec.test.protocols.asciistring

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.AsciiStringCodec
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.EncodeException
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object AsciiGreetingCodec : Codec<AsciiGreeting> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): AsciiGreeting {
    val commandPrefixB0 = buffer.readUByte().toUInt()
    val commandPrefixB1 = buffer.readUByte().toUInt()
    val commandPrefix = ((commandPrefixB0 shl 8) or commandPrefixB1)
    if (commandPrefix > Int.MAX_VALUE.toUInt()) {
      throw DecodeException(fieldPath = "AsciiGreeting.command", bufferPosition = -1, expected = "length prefix <= ${'$'}{Int.MAX_VALUE}", actual = commandPrefix.toString())
    }
    val commandLength = commandPrefix.toInt()
    val __commandOuterLimit = buffer.limit()
    buffer.setLimit(buffer.position() + commandLength)
    val command = try {
      AsciiStringCodec.decode(buffer, context)
    } finally {
      buffer.setLimit(__commandOuterLimit)
    }
    return AsciiGreeting(command = command)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: AsciiGreeting,
    context: EncodeContext,
  ) {
    val commandSizePosition = buffer.position()
    repeat(2) { buffer.writeUByte(0u) }
    val commandBodyStart = buffer.position()
    AsciiStringCodec.encode(buffer, value.command, context)
    val commandEndPosition = buffer.position()
    val commandByteCount = commandEndPosition - commandBodyStart
    if (commandByteCount > 65_535) {
      throw EncodeException(fieldPath = "AsciiGreeting.command", reason = """encoded payload byte length ${commandByteCount} exceeds @LengthPrefixed(LengthPrefix.Short) max 65535""")
    }
    buffer.position(commandSizePosition)
    val commandPrefix = commandByteCount.toUInt()
    buffer.writeUByte(((commandPrefix shr 8) and 0xFFu).toUByte())
    buffer.writeUByte((commandPrefix and 0xFFu).toUByte())
    buffer.position(commandEndPosition)
  }

  override fun wireSize(`value`: AsciiGreeting, context: EncodeContext): WireSize {
    val __commandSize = when (val __s = AsciiStringCodec.wireSize(value.command, context)) {
      is WireSize.Exact -> 2 + __s.bytes
      WireSize.BackPatch -> return WireSize.BackPatch
    }
    return WireSize.Exact(0 + __commandSize)
  }

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    var __offset = 0
    if (stream.available() - baseOffset < __offset + 2) return PeekResult.NeedsMoreData
    val commandPrefixB0 = stream.peekByte(baseOffset + __offset).toInt() and 0xFF
    val commandPrefixB1 = stream.peekByte(baseOffset + __offset + 1).toInt() and 0xFF
    val commandPrefix = ((commandPrefixB0 shl 8) or commandPrefixB1).toUInt()
    if (commandPrefix > (Int.MAX_VALUE - __offset - 2).toUInt()) {
      throw DecodeException(fieldPath = "AsciiGreeting.command", bufferPosition = baseOffset + __offset, expected = "__offset + 2 + length prefix <= ${'$'}{Int.MAX_VALUE}", actual = """${__offset + 2 + commandPrefix.toInt()}""")
    }
    __offset += 2 + commandPrefix.toInt()
    return if (stream.available() - baseOffset >= __offset) PeekResult.Complete(__offset) else PeekResult.NeedsMoreData
  }
}
