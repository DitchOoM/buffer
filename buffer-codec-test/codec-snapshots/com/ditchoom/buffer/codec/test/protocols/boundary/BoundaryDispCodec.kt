package com.ditchoom.buffer.codec.test.protocols.boundary

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

  override fun wireSize(`value`: BoundaryDisp, context: EncodeContext): WireSize = when (value) {
    is BoundaryDisp.Named -> WireSize.BackPatch
    is BoundaryDisp.Inherits -> WireSize.Exact(1)
  }

  override fun sizeHint(`value`: BoundaryDisp, context: EncodeContext): Int = 1 + when (value) {
    is BoundaryDisp.Named -> BoundaryDispNamedCodec.sizeHint(value, context)
    is BoundaryDisp.Inherits -> BoundaryDispInheritsCodec.sizeHint(value, context)
  }

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    if (stream.available() - baseOffset < 1) return PeekResult.NeedsMoreData
    val discriminator = stream.peekByte(baseOffset).toInt() and 0xFF
    return when (discriminator) {
      0x00 -> {
        when (val inner = BoundaryDispNamedCodec.peekFrameSize(stream, baseOffset + 1)) {
          is PeekResult.Complete -> PeekResult.Complete(1 + inner.bytes)
          else -> inner
        }
      }
      0x01 -> {
        when (val inner = BoundaryDispInheritsCodec.peekFrameSize(stream, baseOffset + 1)) {
          is PeekResult.Complete -> PeekResult.Complete(1 + inner.bytes)
          else -> inner
        }
      }
      else -> {
        throw DecodeException(fieldPath = "BoundaryDisp.discriminator", bufferPosition = baseOffset, expected = "one of {0x00, 0x01}", actual = """0x${discriminator.toString(16).padStart(2, '0').uppercase()}""")
      }
    }
  }
}
