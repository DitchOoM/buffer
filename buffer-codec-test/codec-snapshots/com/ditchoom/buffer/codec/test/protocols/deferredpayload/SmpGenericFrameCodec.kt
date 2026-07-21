package com.ditchoom.buffer.codec.test.protocols.deferredpayload

import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.Payload
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

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
}
