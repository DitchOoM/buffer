package com.ditchoom.buffer.codec.test.protocols

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameSize
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

  override fun wireBytes(`value`: CommandPayload, context: EncodeContext): WireByteCount = when (value) {
    is CommandPayload.SetRgbState -> WireByteCount.exact(4L)
    is CommandPayload.GetRgbState -> WireByteCount.exact(1L)
    is CommandPayload.ResetDevice -> WireByteCount.exact(1L)
  }

  @Deprecated(
    message = "Call wireBytes, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("wireBytes(value, context)"),
  )
  @Suppress("DEPRECATION")
  override fun wireSize(`value`: CommandPayload, context: EncodeContext): WireSize = wireBytes(value, context).toWireSize()

  override fun sizeHint(`value`: CommandPayload, context: EncodeContext): Int = 1 + when (value) {
    is CommandPayload.SetRgbState -> CommandPayloadSetRgbStateCodec.sizeHint(value, context)
    is CommandPayload.GetRgbState -> CommandPayloadGetRgbStateCodec.sizeHint(value, context)
    is CommandPayload.ResetDevice -> CommandPayloadResetDeviceCodec.sizeHint(value, context)
  }

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize {
    if (stream.available() - baseOffset < 1) return FrameSize.NeedsMoreData
    val discriminator = stream.peekByte(baseOffset).toInt() and 0xFF
    return when (discriminator) {
      0x22 -> CommandPayloadSetRgbStateCodec.frameSize(stream, baseOffset + 1).fold(declared = { __h, __b -> FrameSize.declared(1 + __h, __b) }, needsMoreData = { FrameSize.NeedsMoreData }, unframed = { FrameSize.Unframed })
      0x23 -> CommandPayloadGetRgbStateCodec.frameSize(stream, baseOffset + 1).fold(declared = { __h, __b -> FrameSize.declared(1 + __h, __b) }, needsMoreData = { FrameSize.NeedsMoreData }, unframed = { FrameSize.Unframed })
      0x24 -> CommandPayloadResetDeviceCodec.frameSize(stream, baseOffset + 1).fold(declared = { __h, __b -> FrameSize.declared(1 + __h, __b) }, needsMoreData = { FrameSize.NeedsMoreData }, unframed = { FrameSize.Unframed })
      else -> {
        throw DecodeException(fieldPath = "CommandPayload.discriminator", bufferPosition = baseOffset, expected = "one of {0x22, 0x23, 0x24}", actual = """0x${discriminator.toString(16).padStart(2, '0').uppercase()}""")
      }
    }
  }

  @Deprecated(
    message = "Call frameSize, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
  )
  @Suppress("DEPRECATION")
  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)
}
