package com.ditchoom.buffer.codec.test.protocols.png

import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.codec.test.protocols.payload.BinaryDataCodec
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.buffer.swapBytes
import kotlin.Int
import kotlin.UInt

public object PngChunkCodec : Codec<PngChunk> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): PngChunk {
    val __batch1Raw = buffer.readLong()
    val __batch1 = if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) __batch1Raw else swapBytes(__batch1Raw)
    val length = (__batch1 ushr 32 and 0xFFFFFFFFL).toUInt()
    val type = (__batch1 and 0xFFFFFFFFL).toUInt()
    val __dataOuterLimit = buffer.limit()
    buffer.setLimit(__dataOuterLimit - 4)
    val data = try {
      BinaryDataCodec.decode(buffer, context)
    } finally {
      buffer.setLimit(__dataOuterLimit)
    }
    val crcRaw = buffer.readInt()
    val crc = (if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) crcRaw else swapBytes(crcRaw)).toUInt()
    return PngChunk(length = length, type = type, data = data, crc = crc)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: PngChunk,
    context: EncodeContext,
  ) {
    val __batch2 = ((value.length.toLong() and 0xFFFFFFFFL) shl 32) or (value.type.toLong() and 0xFFFFFFFFL)
    buffer.writeLong(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) __batch2 else swapBytes(__batch2))
    BinaryDataCodec.encode(buffer, value.data, context)
    val crcRaw = value.crc.toInt()
    buffer.writeInt(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) crcRaw else swapBytes(crcRaw))
  }

  override fun wireSize(`value`: PngChunk, context: EncodeContext): WireSize = WireSize.BackPatch

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = PeekResult.NoFraming

  public fun partial(buffer: ReadBuffer, context: DecodeContext): Partial {
    val __batch3Raw = buffer.readLong()
    val __batch3 = if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) __batch3Raw else swapBytes(__batch3Raw)
    val length = (__batch3 ushr 32 and 0xFFFFFFFFL).toUInt()
    val type = (__batch3 and 0xFFFFFFFFL).toUInt()
    val __payloadStart = buffer.position()
    val __payloadEnd = buffer.limit() - 4
    buffer.position(__payloadEnd)
    val crcRaw = buffer.readInt()
    val crc = (if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) crcRaw else swapBytes(crcRaw)).toUInt()
    return Partial(length = length, type = type, crc = crc, payloadStart = __payloadStart, payloadEnd = __payloadEnd, buffer = buffer, context = context)
  }

  public class Partial internal constructor(
    public val length: UInt,
    public val type: UInt,
    public val crc: UInt,
    private val payloadStart: Int,
    private val payloadEnd: Int,
    private val buffer: ReadBuffer,
    private val context: DecodeContext,
  ) {
    public fun complete(): PngChunk {
      buffer.position(payloadStart)
      val __payloadSavedLimit = buffer.limit()
      buffer.setLimit(payloadEnd)
      return try {
        val data = BinaryDataCodec.decode(buffer, context)
        PngChunk(length = length, type = type, data = data, crc = crc)
      } finally {
        buffer.setLimit(__payloadSavedLimit)
      }
    }
  }
}
