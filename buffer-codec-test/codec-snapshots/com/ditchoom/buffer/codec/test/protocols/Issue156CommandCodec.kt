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

public object Issue156CommandCodec : Codec<Issue156Command> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): Issue156Command {
    val discriminatorPosition = buffer.position()
    val discriminator = buffer.readUByte().toInt()
    return when (discriminator) {
      0x20 -> Issue156CommandLedStateSetCodec.decode(buffer, context)
      else -> {
        throw DecodeException(fieldPath = "Issue156Command.discriminator", bufferPosition = discriminatorPosition, expected = "one of {0x20}", actual = """0x${discriminator.toString(16).padStart(2, '0').uppercase()}""")
      }
    }
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: Issue156Command,
    context: EncodeContext,
  ) {
    when (value) {
      is Issue156Command.LedStateSet -> {
        buffer.writeUByte(0x20.toUByte())
        Issue156CommandLedStateSetCodec.encode(buffer, value, context)
      }
    }
  }

  override fun wireSize(`value`: Issue156Command, context: EncodeContext): WireSize = when (value) {
    is Issue156Command.LedStateSet -> WireSize.Exact(4)
  }

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    if (stream.available() - baseOffset < 1) return PeekResult.NeedsMoreData
    val discriminator = stream.peekByte(baseOffset).toInt() and 0xFF
    return when (discriminator) {
      0x20 -> {
        when (val inner = Issue156CommandLedStateSetCodec.peekFrameSize(stream, baseOffset + 1)) {
          is PeekResult.Complete -> PeekResult.Complete(1 + inner.bytes)
          else -> inner
        }
      }
      else -> {
        throw DecodeException(fieldPath = "Issue156Command.discriminator", bufferPosition = baseOffset, expected = "one of {0x20}", actual = """0x${discriminator.toString(16).padStart(2, '0').uppercase()}""")
      }
    }
  }
}
