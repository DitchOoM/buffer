package com.ditchoom.buffer.codec.test.protocols.wireorderMismatch

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

public object BigWireFrameCodec : Codec<BigWireFrame> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): BigWireFrame {
    val discriminatorPosition = buffer.position()
    val discriminator = buffer.readUByte().toInt()
    return when (discriminator) {
      0x01 -> BigWireFrameSampleCodec.decode(buffer, context)
      0x02 -> BigWireFrameStatusCodec.decode(buffer, context)
      else -> {
        throw DecodeException(fieldPath = "BigWireFrame.discriminator", bufferPosition = discriminatorPosition, expected = "one of {0x01, 0x02}", actual = """0x${discriminator.toString(16).padStart(2, '0').uppercase()}""")
      }
    }
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: BigWireFrame,
    context: EncodeContext,
  ) {
    when (value) {
      is BigWireFrame.Sample -> {
        buffer.writeUByte(0x01.toUByte())
        BigWireFrameSampleCodec.encode(buffer, value, context)
      }
      is BigWireFrame.Status -> {
        buffer.writeUByte(0x02.toUByte())
        BigWireFrameStatusCodec.encode(buffer, value, context)
      }
    }
  }

  override fun wireSize(`value`: BigWireFrame, context: EncodeContext): WireSize = when (value) {
    is BigWireFrame.Sample -> WireSize.Exact(27)
    is BigWireFrame.Status -> WireSize.Exact(13)
  }

  override fun sizeHint(`value`: BigWireFrame, context: EncodeContext): Int = 1 + when (value) {
    is BigWireFrame.Sample -> BigWireFrameSampleCodec.sizeHint(value, context)
    is BigWireFrame.Status -> BigWireFrameStatusCodec.sizeHint(value, context)
  }

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    if (stream.available() - baseOffset < 1) return PeekResult.NeedsMoreData
    val discriminator = stream.peekByte(baseOffset).toInt() and 0xFF
    return when (discriminator) {
      0x01 -> {
        when (val inner = BigWireFrameSampleCodec.peekFrameSize(stream, baseOffset + 1)) {
          is PeekResult.Complete -> PeekResult.Complete(1 + inner.bytes)
          else -> inner
        }
      }
      0x02 -> {
        when (val inner = BigWireFrameStatusCodec.peekFrameSize(stream, baseOffset + 1)) {
          is PeekResult.Complete -> PeekResult.Complete(1 + inner.bytes)
          else -> inner
        }
      }
      else -> {
        throw DecodeException(fieldPath = "BigWireFrame.discriminator", bufferPosition = baseOffset, expected = "one of {0x01, 0x02}", actual = """0x${discriminator.toString(16).padStart(2, '0').uppercase()}""")
      }
    }
  }
}
