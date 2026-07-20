package com.ditchoom.buffer.codec.test.protocols.simple

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object CommandCodec : Codec<Command> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): Command {
    val discriminatorPosition = buffer.position()
    val discriminator = buffer.readUByte().toInt()
    return when (discriminator) {
      0x01 -> CommandPingCodec.decode(buffer, context)
      0x02 -> CommandEchoCodec.decode(buffer, context)
      else -> {
        throw DecodeException(fieldPath = "Command.discriminator", bufferPosition = discriminatorPosition, expected = "one of {0x01, 0x02}", actual = """0x${discriminator.toString(16).padStart(2, '0').uppercase()}""")
      }
    }
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: Command,
    context: EncodeContext,
  ) {
    when (value) {
      is Command.Ping -> {
        buffer.writeUByte(0x01.toUByte())
        CommandPingCodec.encode(buffer, value, context)
      }
      is Command.Echo -> {
        buffer.writeUByte(0x02.toUByte())
        CommandEchoCodec.encode(buffer, value, context)
      }
    }
  }

  override fun wireSize(`value`: Command, context: EncodeContext): WireSize = when (value) {
    is Command.Ping -> WireSize.Exact(9)
    is Command.Echo -> WireSize.BackPatch
  }

  override fun sizeHint(`value`: Command, context: EncodeContext): Int = 1 + when (value) {
    is Command.Ping -> CommandPingCodec.sizeHint(value, context)
    is Command.Echo -> CommandEchoCodec.sizeHint(value, context)
  }

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    if (stream.available() - baseOffset < 1) return PeekResult.NeedsMoreData
    val discriminator = stream.peekByte(baseOffset).toInt() and 0xFF
    return when (discriminator) {
      0x01 -> {
        when (val inner = CommandPingCodec.peekFrameSize(stream, baseOffset + 1)) {
          is PeekResult.Complete -> PeekResult.Complete(1 + inner.bytes)
          else -> inner
        }
      }
      0x02 -> {
        when (val inner = CommandEchoCodec.peekFrameSize(stream, baseOffset + 1)) {
          is PeekResult.Complete -> PeekResult.Complete(1 + inner.bytes)
          else -> inner
        }
      }
      else -> {
        throw DecodeException(fieldPath = "Command.discriminator", bufferPosition = baseOffset, expected = "one of {0x01, 0x02}", actual = """0x${discriminator.toString(16).padStart(2, '0').uppercase()}""")
      }
    }
  }
}
