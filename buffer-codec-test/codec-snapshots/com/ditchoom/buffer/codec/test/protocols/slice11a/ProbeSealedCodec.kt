package com.ditchoom.buffer.codec.test.protocols.slice11a

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

public object ProbeSealedCodec : Codec<ProbeSealed> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): ProbeSealed {
    val discriminatorPosition = buffer.position()
    val discriminator = buffer.readUByte().toInt()
    return when (discriminator) {
      0x01 -> ProbeSealedTag1Codec.decode(buffer, context)
      0x02 -> ProbeSealedTag2Codec.decode(buffer, context)
      else -> {
        throw DecodeException(fieldPath = "ProbeSealed.discriminator", bufferPosition = discriminatorPosition, expected = "one of {0x01, 0x02}", actual = """0x${discriminator.toString(16).padStart(2, '0').uppercase()}""")
      }
    }
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: ProbeSealed,
    context: EncodeContext,
  ) {
    when (value) {
      is ProbeSealed.Tag1 -> {
        buffer.writeUByte(0x01.toUByte())
        ProbeSealedTag1Codec.encode(buffer, value, context)
      }
      is ProbeSealed.Tag2 -> {
        buffer.writeUByte(0x02.toUByte())
        ProbeSealedTag2Codec.encode(buffer, value, context)
      }
    }
  }

  override fun wireSize(`value`: ProbeSealed, context: EncodeContext): WireSize = when (value) {
    is ProbeSealed.Tag1 -> WireSize.Exact(2)
    is ProbeSealed.Tag2 -> WireSize.BackPatch
  }

  override fun sizeHint(`value`: ProbeSealed, context: EncodeContext): Int = 1 + when (value) {
    is ProbeSealed.Tag1 -> ProbeSealedTag1Codec.sizeHint(value, context)
    is ProbeSealed.Tag2 -> ProbeSealedTag2Codec.sizeHint(value, context)
  }

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    if (stream.available() - baseOffset < 1) return PeekResult.NeedsMoreData
    val discriminator = stream.peekByte(baseOffset).toInt() and 0xFF
    return when (discriminator) {
      0x01 -> {
        when (val inner = ProbeSealedTag1Codec.peekFrameSize(stream, baseOffset + 1)) {
          is PeekResult.Complete -> PeekResult.Complete(1 + inner.bytes)
          else -> inner
        }
      }
      0x02 -> {
        when (val inner = ProbeSealedTag2Codec.peekFrameSize(stream, baseOffset + 1)) {
          is PeekResult.Complete -> PeekResult.Complete(1 + inner.bytes)
          else -> inner
        }
      }
      else -> {
        throw DecodeException(fieldPath = "ProbeSealed.discriminator", bufferPosition = baseOffset, expected = "one of {0x01, 0x02}", actual = """0x${discriminator.toString(16).padStart(2, '0').uppercase()}""")
      }
    }
  }
}
