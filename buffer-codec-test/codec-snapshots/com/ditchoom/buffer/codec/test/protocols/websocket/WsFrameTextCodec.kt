package com.ditchoom.buffer.codec.test.protocols.websocket

import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.Decoder
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.EncodeException
import com.ditchoom.buffer.codec.Payload
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int
import kotlin.Long
import kotlin.UShort

public class WsFrameTextCodec<P : Payload>(
  private val payloadCodec: Codec<P>,
) : Codec<WsFrame.Text<P>> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): WsFrame.Text<P> {
    val __batch33 = buffer.readShort().toInt() and 0xFFFF
    val byte1: com.ditchoom.buffer.codec.test.protocols.websocket.FrameHeaderByte1
    val byte2: com.ditchoom.buffer.codec.test.protocols.websocket.WsHeaderByte2
    if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) {
      byte1 = FrameHeaderByte1((__batch33 ushr 8 and 0xFF).toUByte())
      byte2 = WsHeaderByte2((__batch33 and 0xFF).toUByte())
    } else {
      byte1 = FrameHeaderByte1((__batch33 and 0xFF).toUByte())
      byte2 = WsHeaderByte2((__batch33 ushr 8 and 0xFF).toUByte())
    }
    val extendedLength16: UShort? = if (byte2.extended16) buffer.readUShort() else null
    val extendedLength64: Long? = if (byte2.extended64) buffer.readLong() else null
    val maskingKey: WsMaskingKey? = if (byte2.masked) WsMaskingKey(buffer.readUInt()) else null
    val payload = payloadCodec.decode(buffer, context)
    return WsFrame.Text<P>(byte1 = byte1, byte2 = byte2, extendedLength16 = extendedLength16, extendedLength64 = extendedLength64, maskingKey = maskingKey, payload = payload)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: WsFrame.Text<P>,
    context: EncodeContext,
  ) {
    if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) {
      buffer.writeShort((((value.byte1.raw.toInt() and 0xFF) shl 8) or (value.byte2.raw.toInt() and 0xFF)).toShort())
    } else {
      buffer.writeShort(((value.byte1.raw.toInt() and 0xFF) or ((value.byte2.raw.toInt() and 0xFF) shl 8)).toShort())
    }
    if (value.byte2.extended16) {
      val extendedLength16Value = value.extendedLength16 ?: throw EncodeException(fieldPath = "Text.extendedLength16", reason = "@When(\"byte2.extended16\") predicate is true but field is null")
      buffer.writeUShort(extendedLength16Value)
    }
    if (value.byte2.extended64) {
      val extendedLength64Value = value.extendedLength64 ?: throw EncodeException(fieldPath = "Text.extendedLength64", reason = "@When(\"byte2.extended64\") predicate is true but field is null")
      buffer.writeLong(extendedLength64Value)
    }
    if (value.byte2.masked) {
      val maskingKeyValue = value.maskingKey ?: throw EncodeException(fieldPath = "Text.maskingKey", reason = "@When(\"byte2.masked\") predicate is true but field is null")
      buffer.writeUInt(maskingKeyValue.raw)
    }
    payloadCodec.encode(buffer, value.payload, context)
  }

  override fun wireSize(`value`: WsFrame.Text<P>, context: EncodeContext): WireSize = WireSize.BackPatch

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = PeekResult.NoFraming

  public class Partial<P : Payload> internal constructor(
    public val byte1: FrameHeaderByte1,
    public val byte2: WsHeaderByte2,
    public val extendedLength16: UShort?,
    public val extendedLength64: Long?,
    public val maskingKey: WsMaskingKey?,
    private val buffer: ReadBuffer,
    private val context: DecodeContext,
  ) {
    public fun complete(payloadCodec: Decoder<P>): WsFrame.Text<P> {
      val payload = payloadCodec.decode(buffer, context)
      return WsFrame.Text<P>(byte1 = byte1, byte2 = byte2, extendedLength16 = extendedLength16, extendedLength64 = extendedLength64, maskingKey = maskingKey, payload = payload)
    }
  }

  public companion object {
    public fun <P : Payload> partial(buffer: ReadBuffer, context: DecodeContext): Partial<P> {
      val __batch34 = buffer.readShort().toInt() and 0xFFFF
      val byte1: com.ditchoom.buffer.codec.test.protocols.websocket.FrameHeaderByte1
      val byte2: com.ditchoom.buffer.codec.test.protocols.websocket.WsHeaderByte2
      if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) {
        byte1 = FrameHeaderByte1((__batch34 ushr 8 and 0xFF).toUByte())
        byte2 = WsHeaderByte2((__batch34 and 0xFF).toUByte())
      } else {
        byte1 = FrameHeaderByte1((__batch34 and 0xFF).toUByte())
        byte2 = WsHeaderByte2((__batch34 ushr 8 and 0xFF).toUByte())
      }
      val extendedLength16: UShort? = if (byte2.extended16) buffer.readUShort() else null
      val extendedLength64: Long? = if (byte2.extended64) buffer.readLong() else null
      val maskingKey: WsMaskingKey? = if (byte2.masked) WsMaskingKey(buffer.readUInt()) else null
      return Partial<P>(byte1 = byte1, byte2 = byte2, extendedLength16 = extendedLength16, extendedLength64 = extendedLength64, maskingKey = maskingKey, buffer = buffer, context = context)
    }
  }
}
