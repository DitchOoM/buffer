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
import kotlin.UByte
import kotlin.UShort

public class SmpGenericFrameCodec<P : Payload>(
  private val payloadCodec: Codec<P>,
) : Codec<SmpGenericFrame<P>> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): SmpGenericFrame<P> {
    val __batch1 = buffer.readLong()
    val op: kotlin.UByte
    val flags: kotlin.UByte
    val payloadLength: kotlin.UShort
    val group: kotlin.UShort
    val sequence: kotlin.UByte
    val commandId: kotlin.UByte
    if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) {
      op = (__batch1 ushr 56 and 0xFFL).toUByte()
      flags = (__batch1 ushr 48 and 0xFFL).toUByte()
      payloadLength = (__batch1 ushr 32 and 0xFFFFL).toUShort()
      group = (__batch1 ushr 16 and 0xFFFFL).toUShort()
      sequence = (__batch1 ushr 8 and 0xFFL).toUByte()
      commandId = (__batch1 and 0xFFL).toUByte()
    } else {
      op = (__batch1 and 0xFFL).toUByte()
      flags = (__batch1 ushr 8 and 0xFFL).toUByte()
      payloadLength = (__batch1 ushr 16 and 0xFFFFL).toUShort()
      group = (__batch1 ushr 32 and 0xFFFFL).toUShort()
      sequence = (__batch1 ushr 48 and 0xFFL).toUByte()
      commandId = (__batch1 ushr 56 and 0xFFL).toUByte()
    }
    val payloadBytes = payloadLength.toInt()
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
            fieldPath = "SmpGenericFrame.payload",
            bufferPosition = buffer.position(),
            expected = "the payload codec to consume all " + payloadBytes + " bytes",
            actual = (payloadEnd - buffer.position()).toString() + " bytes left unread",
          )
    }
    return SmpGenericFrame<P>(op = op, flags = flags, payloadLength = payloadLength, group = group, sequence = sequence, commandId = commandId, payload = payload)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: SmpGenericFrame<P>,
    context: EncodeContext,
  ) {
    if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) {
      buffer.writeLong(((value.op.toLong() and 0xFFL) shl 56) or ((value.flags.toLong() and 0xFFL) shl 48) or ((value.payloadLength.toLong() and 0xFFFFL) shl 32) or ((value.group.toLong() and 0xFFFFL) shl 16) or ((value.sequence.toLong() and 0xFFL) shl 8) or (value.commandId.toLong() and 0xFFL))
    } else {
      buffer.writeLong((value.op.toLong() and 0xFFL) or ((value.flags.toLong() and 0xFFL) shl 8) or ((value.payloadLength.toLong() and 0xFFFFL) shl 16) or ((value.group.toLong() and 0xFFFFL) shl 32) or ((value.sequence.toLong() and 0xFFL) shl 48) or ((value.commandId.toLong() and 0xFFL) shl 56))
    }
    payloadCodec.encode(buffer, value.payload, context)
  }

  override fun wireSize(`value`: SmpGenericFrame<P>, context: EncodeContext): WireSize = WireSize.BackPatch

  override fun sizeHint(`value`: SmpGenericFrame<P>, context: EncodeContext): Int = 8

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    var __offset = 0
    if (stream.available() - baseOffset < __offset + 1) return PeekResult.NeedsMoreData
    __offset += 1
    if (stream.available() - baseOffset < __offset + 1) return PeekResult.NeedsMoreData
    __offset += 1
    if (stream.available() - baseOffset < __offset + 2) return PeekResult.NeedsMoreData
    val payloadLengthB0 = stream.peekByte(baseOffset + __offset).toInt() and 0xFF
    val payloadLengthB1 = stream.peekByte(baseOffset + __offset + 1).toInt() and 0xFF
    val payloadLength = ((payloadLengthB0 shl 8) or payloadLengthB1).toUInt().toUShort()
    __offset += 2
    if (stream.available() - baseOffset < __offset + 2) return PeekResult.NeedsMoreData
    __offset += 2
    if (stream.available() - baseOffset < __offset + 1) return PeekResult.NeedsMoreData
    __offset += 1
    if (stream.available() - baseOffset < __offset + 1) return PeekResult.NeedsMoreData
    __offset += 1
    val payloadBytes = payloadLength.toInt()
    if (stream.available() - baseOffset < __offset + payloadBytes) return PeekResult.NeedsMoreData
    __offset += payloadBytes
    return if (stream.available() - baseOffset >= __offset) PeekResult.Complete(__offset) else PeekResult.NeedsMoreData
  }

  public class Partial<P : Payload> internal constructor(
    public val op: UByte,
    public val flags: UByte,
    public val payloadLength: UShort,
    public val group: UShort,
    public val sequence: UByte,
    public val commandId: UByte,
    private val payloadStart: Int,
    private val payloadEnd: Int,
    private val buffer: ReadBuffer,
    private val context: DecodeContext,
  ) {
    public fun complete(payloadCodec: Decoder<P>): SmpGenericFrame<P> {
      buffer.position(payloadStart)
      val __payloadSavedLimit = buffer.limit()
      buffer.setLimit(payloadEnd)
      return try {
        val payload = payloadCodec.decode(buffer, context)
        if (buffer.position() != payloadEnd) {
          throw DecodeException(
                fieldPath = "SmpGenericFrame.payload",
                bufferPosition = buffer.position(),
                expected = "the payload codec to consume all " + (payloadEnd - payloadStart) + " bytes",
                actual = (payloadEnd - buffer.position()).toString() + " bytes left unread",
              )
        }
        SmpGenericFrame<P>(op = op, flags = flags, payloadLength = payloadLength, group = group, sequence = sequence, commandId = commandId, payload = payload)
      } finally {
        buffer.setLimit(__payloadSavedLimit)
      }
    }
  }

  public companion object {
    public fun <P : Payload> partial(buffer: ReadBuffer, context: DecodeContext): Partial<P> {
      val __batch2 = buffer.readLong()
      val op: kotlin.UByte
      val flags: kotlin.UByte
      val payloadLength: kotlin.UShort
      val group: kotlin.UShort
      val sequence: kotlin.UByte
      val commandId: kotlin.UByte
      if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) {
        op = (__batch2 ushr 56 and 0xFFL).toUByte()
        flags = (__batch2 ushr 48 and 0xFFL).toUByte()
        payloadLength = (__batch2 ushr 32 and 0xFFFFL).toUShort()
        group = (__batch2 ushr 16 and 0xFFFFL).toUShort()
        sequence = (__batch2 ushr 8 and 0xFFL).toUByte()
        commandId = (__batch2 and 0xFFL).toUByte()
      } else {
        op = (__batch2 and 0xFFL).toUByte()
        flags = (__batch2 ushr 8 and 0xFFL).toUByte()
        payloadLength = (__batch2 ushr 16 and 0xFFFFL).toUShort()
        group = (__batch2 ushr 32 and 0xFFFFL).toUShort()
        sequence = (__batch2 ushr 48 and 0xFFL).toUByte()
        commandId = (__batch2 ushr 56 and 0xFFL).toUByte()
      }
      val __payloadStart = buffer.position()
      val __payloadEnd = __payloadStart + payloadLength.toInt()
      buffer.position(__payloadEnd)
      return Partial<P>(op = op, flags = flags, payloadLength = payloadLength, group = group, sequence = sequence, commandId = commandId, payloadStart = __payloadStart, payloadEnd = __payloadEnd, buffer = buffer, context = context)
    }
  }
}
