package com.ditchoom.buffer.codec.test.protocols.partialnonterminal

import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.Decoder
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.Payload
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.buffer.swapBytes
import kotlin.Int
import kotlin.UShort

public class CommandFrameCodec<P : Payload>(
  private val payloadCodec: Codec<P>,
) : Codec<CommandFrame<P>> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): CommandFrame<P> {
    val __batch1Raw = buffer.readInt()
    val __batch1 = if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) __batch1Raw else swapBytes(__batch1Raw)
    val counter = (__batch1 ushr 16 and 0xFFFF).toUShort()
    val length = (__batch1 and 0xFFFF).toUShort()
    val __payloadOuterLimit = buffer.limit()
    buffer.setLimit(__payloadOuterLimit - 2)
    val payload = try {
      payloadCodec.decode(buffer, context)
    } finally {
      buffer.setLimit(__payloadOuterLimit)
    }
    val checksumRaw = buffer.readShort()
    val checksum = (if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) checksumRaw else swapBytes(checksumRaw)).toUShort()
    return CommandFrame<P>(counter = counter, length = length, payload = payload, checksum = checksum)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: CommandFrame<P>,
    context: EncodeContext,
  ) {
    val __batch2 = ((value.counter.toInt() and 0xFFFF) shl 16) or (value.length.toInt() and 0xFFFF)
    buffer.writeInt(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) __batch2 else swapBytes(__batch2))
    payloadCodec.encode(buffer, value.payload, context)
    val checksumRaw = value.checksum.toShort()
    buffer.writeShort(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) checksumRaw else swapBytes(checksumRaw))
  }

  override fun wireSize(`value`: CommandFrame<P>, context: EncodeContext): WireSize = WireSize.BackPatch

  override fun sizeHint(`value`: CommandFrame<P>, context: EncodeContext): Int = 6

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = PeekResult.NoFraming

  public class Partial<P : Payload> internal constructor(
    public val counter: UShort,
    public val length: UShort,
    public val checksum: UShort,
    private val payloadStart: Int,
    private val payloadEnd: Int,
    private val buffer: ReadBuffer,
    private val context: DecodeContext,
  ) {
    public fun complete(payloadCodec: Decoder<P>): CommandFrame<P> {
      buffer.position(payloadStart)
      val __payloadSavedLimit = buffer.limit()
      buffer.setLimit(payloadEnd)
      return try {
        val payload = payloadCodec.decode(buffer, context)
        CommandFrame<P>(counter = counter, length = length, payload = payload, checksum = checksum)
      } finally {
        buffer.setLimit(__payloadSavedLimit)
      }
    }
  }

  public companion object {
    public fun <P : Payload> partial(buffer: ReadBuffer, context: DecodeContext): Partial<P> {
      val __batch3Raw = buffer.readInt()
      val __batch3 = if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) __batch3Raw else swapBytes(__batch3Raw)
      val counter = (__batch3 ushr 16 and 0xFFFF).toUShort()
      val length = (__batch3 and 0xFFFF).toUShort()
      val __payloadStart = buffer.position()
      val __payloadEnd = buffer.limit() - 2
      buffer.position(__payloadEnd)
      val checksumRaw = buffer.readShort()
      val checksum = (if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) checksumRaw else swapBytes(checksumRaw)).toUShort()
      return Partial<P>(counter = counter, length = length, checksum = checksum, payloadStart = __payloadStart, payloadEnd = __payloadEnd, buffer = buffer, context = context)
    }
  }
}
