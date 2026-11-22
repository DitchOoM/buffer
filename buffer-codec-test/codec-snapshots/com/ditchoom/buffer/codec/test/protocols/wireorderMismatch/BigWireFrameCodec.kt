package com.ditchoom.buffer.codec.test.protocols.wireorderMismatch

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
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Deprecated
import kotlin.Int
import kotlin.ReplaceWith
import kotlin.Suppress

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

  override fun wireBytes(`value`: BigWireFrame, context: EncodeContext): WireByteCount = when (value) {
    is BigWireFrame.Sample -> WireByteCount.exact(27L)
    is BigWireFrame.Status -> WireByteCount.exact(13L)
  }

  @Deprecated(
    message = "Call wireBytes.",
    replaceWith = ReplaceWith("wireBytes(value, context)"),
  )
  @Suppress("DEPRECATION")
  override fun wireSize(`value`: BigWireFrame, context: EncodeContext): WireSize = wireBytes(value, context).toWireSize()

  override fun sizeHint(`value`: BigWireFrame, context: EncodeContext): Int = 1 + when (value) {
    is BigWireFrame.Sample -> BigWireFrameSampleCodec.sizeHint(value, context)
    is BigWireFrame.Status -> BigWireFrameStatusCodec.sizeHint(value, context)
  }

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize {
    if (stream.available() - baseOffset < 1) return FrameSize.NeedsMoreData
    val discriminator = stream.peekByte(baseOffset).toInt() and 0xFF
    return when (discriminator) {
      0x01 -> BigWireFrameSampleCodec.frameSize(stream, baseOffset + 1).fold(declared = { FrameSize.declared(1, it) }, needsMoreData = { FrameSize.NeedsMoreData }, unframed = { FrameSize.Unframed })
      0x02 -> BigWireFrameStatusCodec.frameSize(stream, baseOffset + 1).fold(declared = { FrameSize.declared(1, it) }, needsMoreData = { FrameSize.NeedsMoreData }, unframed = { FrameSize.Unframed })
      else -> {
        throw DecodeException(fieldPath = "BigWireFrame.discriminator", bufferPosition = baseOffset, expected = "one of {0x01, 0x02}", actual = """0x${discriminator.toString(16).padStart(2, '0').uppercase()}""")
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
