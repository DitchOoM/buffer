package com.ditchoom.buffer.codec.test.protocols.simplegeneric

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.Payload
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireByteCount
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Deprecated
import kotlin.Int
import kotlin.ReplaceWith
import kotlin.Suppress

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

  override fun wireBytes(`value`: SimpleGenericFrame<P>, context: EncodeContext): WireByteCount = when (value) {
    is SimpleGenericFrame.Command<*> -> WireByteCount.BackPatch
    is SimpleGenericFrame.Status -> WireByteCount.exact(2L)
  }

  @Deprecated(
    message = "Call wireBytes.",
    replaceWith = ReplaceWith("wireBytes(value, context)"),
  )
  @Suppress("DEPRECATION")
  override fun wireSize(`value`: SimpleGenericFrame<P>, context: EncodeContext): WireSize = wireBytes(value, context).toWireSize()

  override fun sizeHint(`value`: SimpleGenericFrame<P>, context: EncodeContext): Int {
    @Suppress("UNCHECKED_CAST")
    return 1 + when (value) {
      is SimpleGenericFrame.Command<*> -> commandCodec.sizeHint(value as SimpleGenericFrame.Command<P>, context)
      is SimpleGenericFrame.Status -> SimpleGenericFrameStatusCodec.sizeHint(value, context)
    }
  }

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize {
    if (stream.available() - baseOffset < 1) return FrameSize.NeedsMoreData
    val discriminator = stream.peekByte(baseOffset).toInt() and 0xFF
    return when (discriminator) {
      0x0A -> commandCodec.frameSize(stream, baseOffset + 1).fold(declared = { FrameSize.declared(1, it) }, needsMoreData = { FrameSize.NeedsMoreData }, unframed = { FrameSize.Unframed })
      0xA0 -> SimpleGenericFrameStatusCodec.frameSize(stream, baseOffset + 1).fold(declared = { FrameSize.declared(1, it) }, needsMoreData = { FrameSize.NeedsMoreData }, unframed = { FrameSize.Unframed })
      else -> {
        throw DecodeException(fieldPath = "SimpleGenericFrame.discriminator", bufferPosition = baseOffset, expected = "one of {0x0A, 0xA0}", actual = """0x${discriminator.toString(16).padStart(2, '0').uppercase()}""")
      }
    }
  }

  @Deprecated(
    message = "Call frameSize.",
    replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
  )
  @Suppress("DEPRECATION")
  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)
}
