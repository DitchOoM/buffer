package com.ditchoom.buffer.codec.test.protocols.deferredpayload

import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.Decoder
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.Payload
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int
import kotlin.UShort

public class DeferredDispatchFrameCommandCodec<P : Payload>(
  private val payloadCodec: Codec<P>,
) : Codec<DeferredDispatchFrame.Command<P>> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): DeferredDispatchFrame.Command<P> {
    val __batch1 = buffer.readInt()
    val counter: kotlin.UShort
    val payloadLength: kotlin.UShort
    if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) {
      counter = (__batch1 ushr 16 and 0xFFFF).toUShort()
      payloadLength = (__batch1 and 0xFFFF).toUShort()
    } else {
      counter = (__batch1 and 0xFFFF).toUShort()
      payloadLength = (__batch1 ushr 16 and 0xFFFF).toUShort()
    }
    val payloadBytes = payloadLength.toInt()
    if (payloadBytes > buffer.remaining()) {
      throw DecodeException(
            fieldPath = "Command.payload",
            bufferPosition = buffer.position(),
            expected = "a " + payloadBytes + "-byte bounded region within the enclosing limit",
            actual = buffer.remaining().toString() + " bytes available",
          )
    }
    val payloadOuterLimit = buffer.limit()
    val payloadEnd = buffer.position() + payloadBytes
    buffer.setLimit(payloadEnd)
    val payload = try {
      payloadCodec.decode(buffer, context)
    } finally {
      buffer.setLimit(payloadOuterLimit)
    }
    if (buffer.position() != payloadEnd) {
      throw DecodeException(
            fieldPath = "Command.payload",
            bufferPosition = buffer.position(),
            expected = "the payload codec to consume all " + payloadBytes + " bytes",
            actual = (payloadEnd - buffer.position()).toString() + " bytes left unread",
          )
    }
    val checksum = buffer.readUShort()
    return DeferredDispatchFrame.Command<P>(counter = counter, payloadLength = payloadLength, payload = payload, checksum = checksum)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: DeferredDispatchFrame.Command<P>,
    context: EncodeContext,
  ) {
    if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) {
      buffer.writeInt(((value.counter.toInt() and 0xFFFF) shl 16) or (value.payloadLength.toInt() and 0xFFFF))
    } else {
      buffer.writeInt((value.counter.toInt() and 0xFFFF) or ((value.payloadLength.toInt() and 0xFFFF) shl 16))
    }
    payloadCodec.encode(buffer, value.payload, context)
    buffer.writeUShort(value.checksum)
  }

  override fun wireSize(`value`: DeferredDispatchFrame.Command<P>, context: EncodeContext): WireSize = WireSize.BackPatch

  override fun sizeHint(`value`: DeferredDispatchFrame.Command<P>, context: EncodeContext): Int = 6

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    var __offset = 0
    if (stream.available() - baseOffset < __offset + 2) return PeekResult.NeedsMoreData
    __offset += 2
    if (stream.available() - baseOffset < __offset + 2) return PeekResult.NeedsMoreData
    val payloadLengthB0 = stream.peekByte(baseOffset + __offset).toInt() and 0xFF
    val payloadLengthB1 = stream.peekByte(baseOffset + __offset + 1).toInt() and 0xFF
    val payloadLength = ((payloadLengthB0 shl 8) or payloadLengthB1).toUInt().toUShort()
    __offset += 2
    val payloadBytes = payloadLength.toInt()
    if (payloadBytes < 0 || payloadBytes > Int.MAX_VALUE - __offset) {
      throw DecodeException(fieldPath = "Command.payload", bufferPosition = baseOffset + __offset, expected = "__offset + @LengthFrom source in 0..${'$'}{Int.MAX_VALUE}", actual = """${__offset.toLong() + payloadBytes.toLong()}""")
    }
    if (stream.available() - baseOffset < __offset + payloadBytes) return PeekResult.NeedsMoreData
    __offset += payloadBytes
    if (stream.available() - baseOffset < __offset + 2) return PeekResult.NeedsMoreData
    __offset += 2
    return if (stream.available() - baseOffset >= __offset) PeekResult.Complete(__offset) else PeekResult.NeedsMoreData
  }

  public class Partial<P : Payload> internal constructor(
    public val counter: UShort,
    public val payloadLength: UShort,
    public val checksum: UShort,
    private val payloadStart: Int,
    private val payloadEnd: Int,
    private val buffer: ReadBuffer,
    private val context: DecodeContext,
  ) {
    public fun complete(payloadCodec: Decoder<P>): DeferredDispatchFrame.Command<P> {
      buffer.position(payloadStart)
      val __payloadSavedLimit = buffer.limit()
      buffer.setLimit(payloadEnd)
      return try {
        val payload = payloadCodec.decode(buffer, context)
        if (buffer.position() != payloadEnd) {
          throw DecodeException(
                fieldPath = "Command.payload",
                bufferPosition = buffer.position(),
                expected = "the payload codec to consume all " + (payloadEnd - payloadStart) + " bytes",
                actual = (payloadEnd - buffer.position()).toString() + " bytes left unread",
              )
        }
        DeferredDispatchFrame.Command<P>(counter = counter, payloadLength = payloadLength, payload = payload, checksum = checksum)
      } finally {
        buffer.setLimit(__payloadSavedLimit)
      }
    }
  }

  public companion object {
    public fun <P : Payload> partial(buffer: ReadBuffer, context: DecodeContext): Partial<P> {
      val __batch2 = buffer.readInt()
      val counter: kotlin.UShort
      val payloadLength: kotlin.UShort
      if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) {
        counter = (__batch2 ushr 16 and 0xFFFF).toUShort()
        payloadLength = (__batch2 and 0xFFFF).toUShort()
      } else {
        counter = (__batch2 and 0xFFFF).toUShort()
        payloadLength = (__batch2 ushr 16 and 0xFFFF).toUShort()
      }
      val __payloadStart = buffer.position()
      if (payloadLength.toInt() > buffer.remaining()) {
        throw DecodeException(
              fieldPath = "Command.payload",
              bufferPosition = buffer.position(),
              expected = "a " + payloadLength.toInt() + "-byte bounded region within the enclosing limit",
              actual = buffer.remaining().toString() + " bytes available",
            )
      }
      val __payloadEnd = __payloadStart + payloadLength.toInt()
      buffer.position(__payloadEnd)
      val checksum = buffer.readUShort()
      return Partial<P>(counter = counter, payloadLength = payloadLength, checksum = checksum, payloadStart = __payloadStart, payloadEnd = __payloadEnd, buffer = buffer, context = context)
    }
  }
}
