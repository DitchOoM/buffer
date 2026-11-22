package com.ditchoom.buffer.codec.test.protocols.asciistring

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.AsciiStringCodec
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
import kotlin.Deprecated
import kotlin.Int
import kotlin.ReplaceWith
import kotlin.Suppress

public object AsciiGreetingCodec : Codec<AsciiGreeting> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): AsciiGreeting {
    val commandPrefixB0 = buffer.readUByte().toUInt()
    val commandPrefixB1 = buffer.readUByte().toUInt()
    val commandPrefix = ((commandPrefixB0 shl 8) or commandPrefixB1)
    if (commandPrefix > Int.MAX_VALUE.toUInt()) {
      throw FrameTooLargeException(declaredBytes = commandPrefix.toLong(), maxBytes = Int.MAX_VALUE)
    }
    val commandLength = commandPrefix.toInt()
    if (commandLength > buffer.remaining()) {
      throw DecodeException(
            fieldPath = "AsciiGreeting.command",
            bufferPosition = buffer.position(),
            expected = "a " + commandLength + "-byte bounded region within the enclosing limit",
            actual = buffer.remaining().toString() + " bytes available",
          )
    }
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

  override fun wireBytes(`value`: AsciiGreeting, context: EncodeContext): WireByteCount {
    val __commandBodyBytes = AsciiStringCodec.wireBytes(value.command, context).fold(exact = { it }, backPatch = { return WireByteCount.BackPatch })
    val __commandSize = 2 + __commandBodyBytes
    return WireByteCount.exact(0L + __commandSize)
  }

  @Deprecated(
    message = "Call wireBytes, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("wireBytes(value, context)"),
  )
  @Suppress("DEPRECATION")
  override fun wireSize(`value`: AsciiGreeting, context: EncodeContext): WireSize = wireBytes(value, context).toWireSize()

  override fun sizeHint(`value`: AsciiGreeting, context: EncodeContext): Int = 2

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize {
    var __offset = 0
    if (stream.available() - baseOffset < __offset + 2) return FrameSize.NeedsMoreData
    val commandPrefixB0 = stream.peekByte(baseOffset + __offset).toInt() and 0xFF
    val commandPrefixB1 = stream.peekByte(baseOffset + __offset + 1).toInt() and 0xFF
    val commandPrefix = ((commandPrefixB0 shl 8) or commandPrefixB1).toUInt()
    if (commandPrefix > (Int.MAX_VALUE - __offset - 2).toUInt()) {
      throw FrameTooLargeException(declaredBytes = __offset.toLong() + 2 + commandPrefix.toLong(), maxBytes = Int.MAX_VALUE)
    }
    __offset += 2 + commandPrefix.toInt()
    return FrameSize.declared(0, __offset.toLong())
  }

  @Deprecated(
    message = "Call frameSize, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
  )
  @Suppress("DEPRECATION")
  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)
}
