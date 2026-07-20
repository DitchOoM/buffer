package com.ditchoom.buffer.codec.test.protocols

import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

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

  override fun wireSize(`value`: CommandPayload.SetRgbState, context: EncodeContext): WireSize = WireSize.Exact(3)

  override fun sizeHint(`value`: CommandPayload.SetRgbState, context: EncodeContext): Int = 3

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = if (stream.available() - baseOffset >= 3) PeekResult.Complete(3) else PeekResult.NeedsMoreData
}
