package com.ditchoom.buffer.codec.test.protocols.boundary

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

public object BoundaryDispCodec : Codec<BoundaryDisp> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): BoundaryDisp {
    val discriminatorPosition = buffer.position()
    val discriminator = buffer.readUByte().toInt()
    return when (discriminator) {
      0x00 -> BoundaryDispNamedCodec.decode(buffer, context)
      0x01 -> BoundaryDispInheritsCodec.decode(buffer, context)
      else -> {
        throw DecodeException(fieldPath = "BoundaryDisp.discriminator", bufferPosition = discriminatorPosition, expected = "one of {0x00, 0x01}", actual = """0x${discriminator.toString(16).padStart(2, '0').uppercase()}""")
      }
    }
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: BoundaryDisp,
    context: EncodeContext,
  ) {
    when (value) {
      is BoundaryDisp.Named -> {
        buffer.writeUByte(0x00.toUByte())
        BoundaryDispNamedCodec.encode(buffer, value, context)
      }
      is BoundaryDisp.Inherits -> {
        buffer.writeUByte(0x01.toUByte())
        BoundaryDispInheritsCodec.encode(buffer, value, context)
      }
    }
  }

  override fun wireBytes(`value`: BoundaryDisp, context: EncodeContext): WireByteCount = when (value) {
    is BoundaryDisp.Named -> WireByteCount.BackPatch
    is BoundaryDisp.Inherits -> WireByteCount.exact(1L)
  }

  @Deprecated(
    message = "Call wireBytes, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("wireBytes(value, context)"),
  )
  @Suppress("DEPRECATION")
  override fun wireSize(`value`: BoundaryDisp, context: EncodeContext): WireSize = wireBytes(value, context).toWireSize()

  override fun sizeHint(`value`: BoundaryDisp, context: EncodeContext): Int = 1 + when (value) {
    is BoundaryDisp.Named -> BoundaryDispNamedCodec.sizeHint(value, context)
    is BoundaryDisp.Inherits -> BoundaryDispInheritsCodec.sizeHint(value, context)
  }

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize {
    if (stream.available() - baseOffset < 1) return FrameSize.NeedsMoreData
    val discriminator = stream.peekByte(baseOffset).toInt() and 0xFF
    return when (discriminator) {
      0x00 -> BoundaryDispNamedCodec.frameSize(stream, baseOffset + 1).fold(declared = { __h, __b -> FrameSize.declared(1 + __h, __b) }, needsMoreData = { FrameSize.NeedsMoreData }, unframed = { FrameSize.Unframed })
      0x01 -> BoundaryDispInheritsCodec.frameSize(stream, baseOffset + 1).fold(declared = { __h, __b -> FrameSize.declared(1 + __h, __b) }, needsMoreData = { FrameSize.NeedsMoreData }, unframed = { FrameSize.Unframed })
      else -> {
        throw DecodeException(fieldPath = "BoundaryDisp.discriminator", bufferPosition = baseOffset, expected = "one of {0x00, 0x01}", actual = """0x${discriminator.toString(16).padStart(2, '0').uppercase()}""")
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
