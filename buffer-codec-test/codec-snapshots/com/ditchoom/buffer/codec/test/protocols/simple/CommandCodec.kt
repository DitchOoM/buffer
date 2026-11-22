package com.ditchoom.buffer.codec.test.protocols.simple

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.WireByteCount
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

  override fun wireBytes(`value`: Command, context: EncodeContext): WireByteCount = when (value) {
    is Command.Ping -> WireByteCount.exact(9L)
    is Command.Echo -> WireByteCount.BackPatch
  }

  override fun sizeHint(`value`: Command, context: EncodeContext): Int = 1 + when (value) {
    is Command.Ping -> CommandPingCodec.sizeHint(value, context)
    is Command.Echo -> CommandEchoCodec.sizeHint(value, context)
  }

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize {
    if (stream.available() - baseOffset < 1) return FrameSize.NeedsMoreData
    val discriminator = stream.peekByte(baseOffset).toInt() and 0xFF
    return when (discriminator) {
      0x01 -> CommandPingCodec.frameSize(stream, baseOffset + 1).fold(declared = { __h, __b -> FrameSize.declared(1 + __h, __b) }, needsMoreData = { FrameSize.NeedsMoreData }, unframed = { FrameSize.Unframed })
      0x02 -> CommandEchoCodec.frameSize(stream, baseOffset + 1).fold(declared = { __h, __b -> FrameSize.declared(1 + __h, __b) }, needsMoreData = { FrameSize.NeedsMoreData }, unframed = { FrameSize.Unframed })
      else -> {
        throw DecodeException(fieldPath = "Command.discriminator", bufferPosition = baseOffset, expected = "one of {0x01, 0x02}", actual = """0x${discriminator.toString(16).padStart(2, '0').uppercase()}""")
      }
    }
  }
}
