package com.ditchoom.buffer.codec.test.protocols

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

public object CommandPayloadCodec : Codec<CommandPayload> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): CommandPayload {
    val discriminatorPosition = buffer.position()
    val discriminator = buffer.readUByte().toInt()
    return when (discriminator) {
      0x22 -> CommandPayloadSetRgbStateCodec.decode(buffer, context)
      0x23 -> CommandPayloadGetRgbStateCodec.decode(buffer, context)
      0x24 -> CommandPayloadResetDeviceCodec.decode(buffer, context)
      else -> {
        throw DecodeException(fieldPath = "CommandPayload.discriminator", bufferPosition = discriminatorPosition, expected = "one of {0x22, 0x23, 0x24}", actual = """0x${discriminator.toString(16).padStart(2, '0').uppercase()}""")
      }
    }
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: CommandPayload,
    context: EncodeContext,
  ) {
    when (value) {
      is CommandPayload.SetRgbState -> {
        buffer.writeUByte(0x22.toUByte())
        CommandPayloadSetRgbStateCodec.encode(buffer, value, context)
      }
      is CommandPayload.GetRgbState -> {
        buffer.writeUByte(0x23.toUByte())
        CommandPayloadGetRgbStateCodec.encode(buffer, value, context)
      }
      is CommandPayload.ResetDevice -> {
        buffer.writeUByte(0x24.toUByte())
        CommandPayloadResetDeviceCodec.encode(buffer, value, context)
      }
    }
  }

  override fun wireSize(`value`: CommandPayload, context: EncodeContext): WireSize = when (value) {
    is CommandPayload.SetRgbState -> WireSize.Exact(4)
    is CommandPayload.GetRgbState -> WireSize.Exact(1)
    is CommandPayload.ResetDevice -> WireSize.Exact(1)
  }

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    if (stream.available() - baseOffset < 1) return PeekResult.NeedsMoreData
    val discriminator = stream.peekByte(baseOffset).toInt() and 0xFF
    return when (discriminator) {
      0x22 -> {
        when (val inner = CommandPayloadSetRgbStateCodec.peekFrameSize(stream, baseOffset + 1)) {
          is PeekResult.Complete -> PeekResult.Complete(1 + inner.bytes)
          else -> inner
        }
      }
      0x23 -> {
        when (val inner = CommandPayloadGetRgbStateCodec.peekFrameSize(stream, baseOffset + 1)) {
          is PeekResult.Complete -> PeekResult.Complete(1 + inner.bytes)
          else -> inner
        }
      }
      0x24 -> {
        when (val inner = CommandPayloadResetDeviceCodec.peekFrameSize(stream, baseOffset + 1)) {
          is PeekResult.Complete -> PeekResult.Complete(1 + inner.bytes)
          else -> inner
        }
      }
      else -> {
        throw DecodeException(fieldPath = "CommandPayload.discriminator", bufferPosition = baseOffset, expected = "one of {0x22, 0x23, 0x24}", actual = """0x${discriminator.toString(16).padStart(2, '0').uppercase()}""")
      }
    }
  }
}
