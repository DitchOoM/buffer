package com.ditchoom.buffer.codec.test.protocols.batch

import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.EncodeException
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireByteCount
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.codec.toPeekResult
import com.ditchoom.buffer.codec.toWireSize
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.buffer.swapBytes
import kotlin.Deprecated
import kotlin.Int
import kotlin.ReplaceWith
import kotlin.Suppress
import kotlin.UInt

public object SandwichBatchCodec : Codec<SandwichBatch> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): SandwichBatch {
    val __batch1Raw = buffer.readInt()
    val __batch1 = if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) __batch1Raw else swapBytes(__batch1Raw)
    val a = (__batch1 ushr 16 and 0xFFFF).toUShort()
    val b = (__batch1 and 0xFFFF).toUShort()
    val gate = buffer.readByte() != 0.toByte()
    val middle: UInt? = if (gate) {
      val middleRaw = buffer.readInt()
      (if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) middleRaw else swapBytes(middleRaw)).toUInt()
    } else {
      null
    }
    val __batch2Raw = buffer.readInt()
    val __batch2 = if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) __batch2Raw else swapBytes(__batch2Raw)
    val c = (__batch2 ushr 16 and 0xFFFF).toUShort()
    val d = (__batch2 and 0xFFFF).toUShort()
    return SandwichBatch(a = a, b = b, gate = gate, middle = middle, c = c, d = d)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: SandwichBatch,
    context: EncodeContext,
  ) {
    val __batch3 = ((value.a.toInt() and 0xFFFF) shl 16) or (value.b.toInt() and 0xFFFF)
    buffer.writeInt(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) __batch3 else swapBytes(__batch3))
    buffer.writeByte(if (value.gate) 1.toByte() else 0.toByte())
    if (value.gate) {
      val middleValue = value.middle ?: throw EncodeException(fieldPath = "SandwichBatch.middle", reason = "@When(\"gate\") predicate is true but field is null")
      val middleValueRaw = middleValue.toInt()
      buffer.writeInt(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) middleValueRaw else swapBytes(middleValueRaw))
    }
    val __batch4 = ((value.c.toInt() and 0xFFFF) shl 16) or (value.d.toInt() and 0xFFFF)
    buffer.writeInt(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) __batch4 else swapBytes(__batch4))
  }

  override fun wireBytes(`value`: SandwichBatch, context: EncodeContext): WireByteCount = WireByteCount.BackPatch

  @Deprecated(
    message = "Call wireBytes, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("wireBytes(value, context)"),
  )
  @Suppress("DEPRECATION")
  override fun wireSize(`value`: SandwichBatch, context: EncodeContext): WireSize = wireBytes(value, context).toWireSize()

  override fun sizeHint(`value`: SandwichBatch, context: EncodeContext): Int = 9

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize {
    var __offset = 0
    if (stream.available() - baseOffset < __offset + 2) return FrameSize.NeedsMoreData
    __offset += 2
    if (stream.available() - baseOffset < __offset + 2) return FrameSize.NeedsMoreData
    __offset += 2
    if (stream.available() - baseOffset < __offset + 1) return FrameSize.NeedsMoreData
    val gate = stream.peekByte(baseOffset + __offset) != 0.toByte()
    __offset += 1
    if (gate) {
      if (stream.available() - baseOffset < __offset + 4) return FrameSize.NeedsMoreData
      __offset += 4
    }
    if (stream.available() - baseOffset < __offset + 2) return FrameSize.NeedsMoreData
    __offset += 2
    if (stream.available() - baseOffset < __offset + 2) return FrameSize.NeedsMoreData
    __offset += 2
    return FrameSize.declared(0, __offset.toLong())
  }

  @Deprecated(
    message = "Call frameSize, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
  )
  @Suppress("DEPRECATION")
  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)
}
