package com.ditchoom.buffer.codec.test.protocols.simplegeneric

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.Payload
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public class SimpleGenericFrameCodec<P : Payload>(
  private val payloadCodec: Codec<P>,
) : Codec<SimpleGenericFrame<P>> {
  private val commandCodec: SimpleGenericFrameCommandCodec<P> =
      SimpleGenericFrameCommandCodec(payloadCodec)

  override fun decode(buffer: ReadBuffer, context: DecodeContext): SimpleGenericFrame<P> {
    val discriminatorPosition = buffer.position()
    val discriminator = buffer.readUByte().toInt()
    return when (discriminator) {
      0x0A -> commandCodec.decode(buffer, context)
      0xA0 -> SimpleGenericFrameStatusCodec.decode(buffer, context)
      else -> {
        throw DecodeException(fieldPath = "SimpleGenericFrame.discriminator", bufferPosition = discriminatorPosition, expected = "one of {0x0A, 0xA0}", actual = """0x${discriminator.toString(16).padStart(2, '0').uppercase()}""")
      }
    }
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: SimpleGenericFrame<P>,
    context: EncodeContext,
  ) {
    @Suppress("UNCHECKED_CAST")
    when (value) {
      is SimpleGenericFrame.Command<*> -> {
        buffer.writeUByte(0x0A.toUByte())
        commandCodec.encode(buffer, value as SimpleGenericFrame.Command<P>, context)
      }
      is SimpleGenericFrame.Status -> {
        buffer.writeUByte(0xA0.toUByte())
        SimpleGenericFrameStatusCodec.encode(buffer, value, context)
      }
    }
  }

  override fun wireSize(`value`: SimpleGenericFrame<P>, context: EncodeContext): WireSize = when (value) {
    is SimpleGenericFrame.Command<*> -> WireSize.BackPatch
    is SimpleGenericFrame.Status -> WireSize.Exact(2)
  }

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    if (stream.available() - baseOffset < 1) return PeekResult.NeedsMoreData
    val discriminator = stream.peekByte(baseOffset).toInt() and 0xFF
    return when (discriminator) {
      0x0A -> {
        when (val inner = commandCodec.peekFrameSize(stream, baseOffset + 1)) {
          is PeekResult.Complete -> PeekResult.Complete(1 + inner.bytes)
          else -> inner
        }
      }
      0xA0 -> {
        when (val inner = SimpleGenericFrameStatusCodec.peekFrameSize(stream, baseOffset + 1)) {
          is PeekResult.Complete -> PeekResult.Complete(1 + inner.bytes)
          else -> inner
        }
      }
      else -> {
        throw DecodeException(fieldPath = "SimpleGenericFrame.discriminator", bufferPosition = baseOffset, expected = "one of {0x0A, 0xA0}", actual = """0x${discriminator.toString(16).padStart(2, '0').uppercase()}""")
      }
    }
  }
}
