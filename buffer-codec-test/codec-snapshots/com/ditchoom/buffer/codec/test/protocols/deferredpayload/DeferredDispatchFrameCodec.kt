package com.ditchoom.buffer.codec.test.protocols.deferredpayload

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FramePeek
import com.ditchoom.buffer.codec.Payload
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public class DeferredDispatchFrameCodec<P : Payload>(
  private val payloadCodec: Codec<P>,
) : Codec<DeferredDispatchFrame<P>> {
  private val commandCodec: DeferredDispatchFrameCommandCodec<P> =
      DeferredDispatchFrameCommandCodec(payloadCodec)

  override fun decode(buffer: ReadBuffer, context: DecodeContext): DeferredDispatchFrame<P> {
    val discriminatorPosition = buffer.position()
    val discriminator = buffer.readUByte().toInt()
    return when (discriminator) {
      0x0A -> commandCodec.decode(buffer, context)
      0xA0 -> DeferredDispatchFrameStatusCodec.decode(buffer, context)
      else -> {
        throw DecodeException(fieldPath = "DeferredDispatchFrame.discriminator", bufferPosition = discriminatorPosition, expected = "one of {0x0A, 0xA0}", actual = """0x${discriminator.toString(16).padStart(2, '0').uppercase()}""")
      }
    }
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: DeferredDispatchFrame<P>,
    context: EncodeContext,
  ) {
    @Suppress("UNCHECKED_CAST")
    when (value) {
      is DeferredDispatchFrame.Command<*> -> {
        buffer.writeUByte(0x0A.toUByte())
        commandCodec.encode(buffer, value as DeferredDispatchFrame.Command<P>, context)
      }
      is DeferredDispatchFrame.Status -> {
        buffer.writeUByte(0xA0.toUByte())
        DeferredDispatchFrameStatusCodec.encode(buffer, value, context)
      }
    }
  }

  override fun wireSize(`value`: DeferredDispatchFrame<P>, context: EncodeContext): WireSize = when (value) {
    is DeferredDispatchFrame.Command<*> -> WireSize.BackPatch
    is DeferredDispatchFrame.Status -> WireSize.Exact(2)
  }

  override fun sizeHint(`value`: DeferredDispatchFrame<P>, context: EncodeContext): Int {
    @Suppress("UNCHECKED_CAST")
    return 1 + when (value) {
      is DeferredDispatchFrame.Command<*> -> commandCodec.sizeHint(value as DeferredDispatchFrame.Command<P>, context)
      is DeferredDispatchFrame.Status -> DeferredDispatchFrameStatusCodec.sizeHint(value, context)
    }
  }

  override fun peekFrame(stream: StreamProcessor, baseOffset: Int): FramePeek {
    if (stream.available() - baseOffset < 1) return FramePeek.needsMoreData
    val discriminator = stream.peekByte(baseOffset).toInt() and 0xFF
    return when (discriminator) {
      0x0A -> commandCodec.peekFrame(stream, baseOffset + 1).withPrefix(1)
      0xA0 -> DeferredDispatchFrameStatusCodec.peekFrame(stream, baseOffset + 1).withPrefix(1)
      else -> {
        throw DecodeException(fieldPath = "DeferredDispatchFrame.discriminator", bufferPosition = baseOffset, expected = "one of {0x0A, 0xA0}", actual = """0x${discriminator.toString(16).padStart(2, '0').uppercase()}""")
      }
    }
  }

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = peekFrame(stream, baseOffset).toPeekResult()
}
