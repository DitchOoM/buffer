package com.ditchoom.buffer.codec.test.protocols.batch

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

public object BigDispatchFrameCodec : Codec<BigDispatchFrame> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): BigDispatchFrame {
    val discriminatorPosition = buffer.position()
    val discriminator = buffer.readUByte().toInt()
    return when (discriminator) {
      0x01 -> BigDispatchFrameTypeACodec.decode(buffer, context)
      0x02 -> BigDispatchFrameTypeBCodec.decode(buffer, context)
      else -> {
        throw DecodeException(fieldPath = "BigDispatchFrame.discriminator", bufferPosition = discriminatorPosition, expected = "one of {0x01, 0x02}", actual = """0x${discriminator.toString(16).padStart(2, '0').uppercase()}""")
      }
    }
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: BigDispatchFrame,
    context: EncodeContext,
  ) {
    when (value) {
      is BigDispatchFrame.TypeA -> {
        buffer.writeUByte(0x01.toUByte())
        BigDispatchFrameTypeACodec.encode(buffer, value, context)
      }
      is BigDispatchFrame.TypeB -> {
        buffer.writeUByte(0x02.toUByte())
        BigDispatchFrameTypeBCodec.encode(buffer, value, context)
      }
    }
  }

  override fun wireSize(`value`: BigDispatchFrame, context: EncodeContext): WireSize = when (value) {
    is BigDispatchFrame.TypeA -> WireSize.Exact(9)
    is BigDispatchFrame.TypeB -> WireSize.Exact(9)
  }

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    if (stream.available() - baseOffset < 1) return PeekResult.NeedsMoreData
    val discriminator = stream.peekByte(baseOffset).toInt() and 0xFF
    return when (discriminator) {
      0x01 -> {
        when (val inner = BigDispatchFrameTypeACodec.peekFrameSize(stream, baseOffset + 1)) {
          is PeekResult.Complete -> PeekResult.Complete(1 + inner.bytes)
          else -> inner
        }
      }
      0x02 -> {
        when (val inner = BigDispatchFrameTypeBCodec.peekFrameSize(stream, baseOffset + 1)) {
          is PeekResult.Complete -> PeekResult.Complete(1 + inner.bytes)
          else -> inner
        }
      }
      else -> {
        throw DecodeException(fieldPath = "BigDispatchFrame.discriminator", bufferPosition = baseOffset, expected = "one of {0x01, 0x02}", actual = """0x${discriminator.toString(16).padStart(2, '0').uppercase()}""")
      }
    }
  }
}
