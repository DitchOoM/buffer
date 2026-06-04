package com.ditchoom.buffer.codec.test.protocols.visibility

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

internal object InternalCommandCodec : Codec<InternalCommand> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): InternalCommand {
    val discriminatorPosition = buffer.position()
    val discriminator = buffer.readUByte().toInt()
    return when (discriminator) {
      0x01 -> InternalCommandPingCodec.decode(buffer, context)
      0x02 -> InternalCommandEchoCodec.decode(buffer, context)
      else -> {
        throw DecodeException(fieldPath = "InternalCommand.discriminator", bufferPosition = discriminatorPosition, expected = "one of {0x01, 0x02}", actual = """0x${discriminator.toString(16).padStart(2, '0').uppercase()}""")
      }
    }
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: InternalCommand,
    context: EncodeContext,
  ) {
    when (value) {
      is InternalCommand.Ping -> {
        buffer.writeUByte(0x01.toUByte())
        InternalCommandPingCodec.encode(buffer, value, context)
      }
      is InternalCommand.Echo -> {
        buffer.writeUByte(0x02.toUByte())
        InternalCommandEchoCodec.encode(buffer, value, context)
      }
    }
  }

  override fun wireSize(`value`: InternalCommand, context: EncodeContext): WireSize = when (value) {
    is InternalCommand.Ping -> WireSize.Exact(9)
    is InternalCommand.Echo -> WireSize.BackPatch
  }

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    if (stream.available() - baseOffset < 1) return PeekResult.NeedsMoreData
    val discriminator = stream.peekByte(baseOffset).toInt() and 0xFF
    return when (discriminator) {
      0x01 -> {
        when (val inner = InternalCommandPingCodec.peekFrameSize(stream, baseOffset + 1)) {
          is PeekResult.Complete -> PeekResult.Complete(1 + inner.bytes)
          else -> inner
        }
      }
      0x02 -> {
        when (val inner = InternalCommandEchoCodec.peekFrameSize(stream, baseOffset + 1)) {
          is PeekResult.Complete -> PeekResult.Complete(1 + inner.bytes)
          else -> inner
        }
      }
      else -> {
        throw DecodeException(fieldPath = "InternalCommand.discriminator", bufferPosition = baseOffset, expected = "one of {0x01, 0x02}", actual = """0x${discriminator.toString(16).padStart(2, '0').uppercase()}""")
      }
    }
  }
}
