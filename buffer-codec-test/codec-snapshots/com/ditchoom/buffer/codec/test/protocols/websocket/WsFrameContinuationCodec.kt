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
import com.ditchoom.buffer.swapBytes
import kotlin.Int
import kotlin.Long
import kotlin.UShort

public class WsFrameContinuationCodec<P : Payload>(
  private val payloadCodec: Codec<P>,
) : Codec<WsFrame.Continuation<P>> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): WsFrame.Continuation<P> {
    val __batch1 = buffer.readShort().toInt() and 0xFFFF
    val byte1: com.ditchoom.buffer.codec.test.protocols.websocket.FrameHeaderByte1
    val byte2: com.ditchoom.buffer.codec.test.protocols.websocket.WsHeaderByte2
    if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) {
      byte1 = FrameHeaderByte1((__batch1 ushr 8 and 0xFF).toUByte())
      byte2 = WsHeaderByte2((__batch1 and 0xFF).toUByte())
    } else {
      byte1 = FrameHeaderByte1((__batch1 and 0xFF).toUByte())
      byte2 = WsHeaderByte2((__batch1 ushr 8 and 0xFF).toUByte())
    }
    val extendedLength16: UShort? = if (byte2.extended16) {
      val extendedLength16Raw = buffer.readShort()
      (if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) extendedLength16Raw else swapBytes(extendedLength16Raw)).toUShort()
    } else {
      null
    }
    val extendedLength64: Long? = if (byte2.extended64) {
      val extendedLength64Raw = buffer.readLong()
      if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) extendedLength64Raw else swapBytes(extendedLength64Raw)
    } else {
      null
    }
    val maskingKey: WsMaskingKey? = if (byte2.masked) WsMaskingKey(buffer.readUInt()) else null
    val payload = payloadCodec.decode(buffer, context)
    return WsFrame.Continuation<P>(byte1 = byte1, byte2 = byte2, extendedLength16 = extendedLength16, extendedLength64 = extendedLength64, maskingKey = maskingKey, payload = payload)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: WsFrame.Continuation<P>,
    context: EncodeContext,
  ) {
    if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) {
      buffer.writeShort((((value.byte1.raw.toInt() and 0xFF) shl 8) or (value.byte2.raw.toInt() and 0xFF)).toShort())
    } else {
      buffer.writeShort(((value.byte1.raw.toInt() and 0xFF) or ((value.byte2.raw.toInt() and 0xFF) shl 8)).toShort())
    }
    if (value.byte2.extended16) {
      val extendedLength16Value = value.extendedLength16 ?: throw EncodeException(fieldPath = "Continuation.extendedLength16", reason = "@When(\"byte2.extended16\") predicate is true but field is null")
      val extendedLength16ValueRaw = extendedLength16Value.toShort()
      buffer.writeShort(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) extendedLength16ValueRaw else swapBytes(extendedLength16ValueRaw))
    }
    if (value.byte2.extended64) {
      val extendedLength64Value = value.extendedLength64 ?: throw EncodeException(fieldPath = "Continuation.extendedLength64", reason = "@When(\"byte2.extended64\") predicate is true but field is null")
      val extendedLength64ValueRaw = extendedLength64Value
      buffer.writeLong(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) extendedLength64ValueRaw else swapBytes(extendedLength64ValueRaw))
    }
    if (value.byte2.masked) {
      val maskingKeyValue = value.maskingKey ?: throw EncodeException(fieldPath = "Continuation.maskingKey", reason = "@When(\"byte2.masked\") predicate is true but field is null")
      buffer.writeUInt(maskingKeyValue.raw)
    }
    payloadCodec.encode(buffer, value.payload, context)
  }

  override fun wireSize(`value`: WsFrame.Continuation<P>, context: EncodeContext): WireSize = WireSize.BackPatch

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
    public fun complete(payloadCodec: Decoder<P>): WsFrame.Continuation<P> {
      val payload = payloadCodec.decode(buffer, context)
      return WsFrame.Continuation<P>(byte1 = byte1, byte2 = byte2, extendedLength16 = extendedLength16, extendedLength64 = extendedLength64, maskingKey = maskingKey, payload = payload)
    }
  }

  public companion object {
    public fun <P : Payload> partial(buffer: ReadBuffer, context: DecodeContext): Partial<P> {
      val __batch2 = buffer.readShort().toInt() and 0xFFFF
      val byte1: com.ditchoom.buffer.codec.test.protocols.websocket.FrameHeaderByte1
      val byte2: com.ditchoom.buffer.codec.test.protocols.websocket.WsHeaderByte2
      if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) {
        byte1 = FrameHeaderByte1((__batch2 ushr 8 and 0xFF).toUByte())
        byte2 = WsHeaderByte2((__batch2 and 0xFF).toUByte())
      } else {
        byte1 = FrameHeaderByte1((__batch2 and 0xFF).toUByte())
        byte2 = WsHeaderByte2((__batch2 ushr 8 and 0xFF).toUByte())
      }
      val extendedLength16: UShort? = if (byte2.extended16) {
        val extendedLength16Raw = buffer.readShort()
        (if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) extendedLength16Raw else swapBytes(extendedLength16Raw)).toUShort()
      } else {
        null
      }
      val extendedLength64: Long? = if (byte2.extended64) {
        val extendedLength64Raw = buffer.readLong()
        if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) extendedLength64Raw else swapBytes(extendedLength64Raw)
      } else {
        null
      }
      val maskingKey: WsMaskingKey? = if (byte2.masked) WsMaskingKey(buffer.readUInt()) else null
      return Partial<P>(byte1 = byte1, byte2 = byte2, extendedLength16 = extendedLength16, extendedLength64 = extendedLength64, maskingKey = maskingKey, buffer = buffer, context = context)
    }
  }
}
