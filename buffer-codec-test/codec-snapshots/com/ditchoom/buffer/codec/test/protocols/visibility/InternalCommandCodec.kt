package com.ditchoom.buffer.codec.test.protocols.visibility

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

  override fun wireBytes(`value`: InternalCommand, context: EncodeContext): WireByteCount = when (value) {
    is InternalCommand.Ping -> WireByteCount.exact(9L)
    is InternalCommand.Echo -> WireByteCount.BackPatch
  }

  override fun sizeHint(`value`: InternalCommand, context: EncodeContext): Int = 1 + when (value) {
    is InternalCommand.Ping -> InternalCommandPingCodec.sizeHint(value, context)
    is InternalCommand.Echo -> InternalCommandEchoCodec.sizeHint(value, context)
  }

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize {
    if (stream.available() - baseOffset < 1) return FrameSize.NeedsMoreData
    val discriminator = stream.peekByte(baseOffset).toInt() and 0xFF
    return when (discriminator) {
      0x01 -> InternalCommandPingCodec.frameSize(stream, baseOffset + 1).fold(declared = { __h, __b -> FrameSize.declared(1 + __h, __b) }, needsMoreData = { FrameSize.NeedsMoreData }, unframed = { FrameSize.Unframed })
      0x02 -> InternalCommandEchoCodec.frameSize(stream, baseOffset + 1).fold(declared = { __h, __b -> FrameSize.declared(1 + __h, __b) }, needsMoreData = { FrameSize.NeedsMoreData }, unframed = { FrameSize.Unframed })
      else -> {
        throw DecodeException(fieldPath = "InternalCommand.discriminator", bufferPosition = baseOffset, expected = "one of {0x01, 0x02}", actual = """0x${discriminator.toString(16).padStart(2, '0').uppercase()}""")
      }
    }
  }
}
