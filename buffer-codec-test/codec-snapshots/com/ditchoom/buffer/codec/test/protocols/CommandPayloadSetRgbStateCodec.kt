package com.ditchoom.buffer.codec.test.protocols

import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
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

public object CommandPayloadSetRgbStateCodec : Codec<CommandPayload.SetRgbState> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): CommandPayload.SetRgbState {
    val __batch1 = buffer.readShort().toInt() and 0xFFFF
    val r: kotlin.UByte
    val g: kotlin.UByte
    if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) {
      r = (__batch1 ushr 8 and 0xFF).toUByte()
      g = (__batch1 and 0xFF).toUByte()
    } else {
      r = (__batch1 and 0xFF).toUByte()
      g = (__batch1 ushr 8 and 0xFF).toUByte()
    }
    val b = buffer.readUByte()
    return CommandPayload.SetRgbState(r = r, g = g, b = b)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: CommandPayload.SetRgbState,
    context: EncodeContext,
  ) {
    if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) {
      buffer.writeShort((((value.r.toInt() and 0xFF) shl 8) or (value.g.toInt() and 0xFF)).toShort())
    } else {
      buffer.writeShort(((value.r.toInt() and 0xFF) or ((value.g.toInt() and 0xFF) shl 8)).toShort())
    }
    buffer.writeUByte(value.b)
  }

  override fun wireBytes(`value`: CommandPayload.SetRgbState, context: EncodeContext): WireByteCount = WireByteCount.exact(3L)

  @Deprecated(
    message = "Call wireBytes, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("wireBytes(value, context)"),
  )
  @Suppress("DEPRECATION")
  override fun wireSize(`value`: CommandPayload.SetRgbState, context: EncodeContext): WireSize = wireBytes(value, context).toWireSize()

  override fun sizeHint(`value`: CommandPayload.SetRgbState, context: EncodeContext): Int = 3

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.declared(0, 3L)

  @Deprecated(
    message = "Call frameSize, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
  )
  @Suppress("DEPRECATION")
  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)
}
