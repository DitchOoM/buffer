package com.ditchoom.buffer.codec.test.protocols.batch

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
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.buffer.swapBytes
import kotlin.Deprecated
import kotlin.Int
import kotlin.ReplaceWith
import kotlin.Suppress

public object MixedOrderValueClassCodec : Codec<MixedOrderValueClass> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): MixedOrderValueClass {
    val leadingBigRaw = buffer.readShort()
    val leadingBig = (if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) leadingBigRaw else swapBytes(leadingBigRaw)).toUShort()
    val littleTagRaw = buffer.readShort()
    val littleTag = LittleTag((if (buffer.byteOrder == ByteOrder.LITTLE_ENDIAN) littleTagRaw else swapBytes(littleTagRaw)).toUShort())
    val trailingBigRaw = buffer.readInt()
    val trailingBig = (if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) trailingBigRaw else swapBytes(trailingBigRaw)).toUInt()
    return MixedOrderValueClass(leadingBig = leadingBig, littleTag = littleTag, trailingBig = trailingBig)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: MixedOrderValueClass,
    context: EncodeContext,
  ) {
    val leadingBigRaw = value.leadingBig.toShort()
    buffer.writeShort(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) leadingBigRaw else swapBytes(leadingBigRaw))
    val littleTagRaw = value.littleTag.raw.toShort()
    buffer.writeShort(if (buffer.byteOrder == ByteOrder.LITTLE_ENDIAN) littleTagRaw else swapBytes(littleTagRaw))
    val trailingBigRaw = value.trailingBig.toInt()
    buffer.writeInt(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) trailingBigRaw else swapBytes(trailingBigRaw))
  }

  override fun wireBytes(`value`: MixedOrderValueClass, context: EncodeContext): WireByteCount = WireByteCount.exact(8L)

  @Deprecated(
    message = "Call wireBytes.",
    replaceWith = ReplaceWith("wireBytes(value, context)"),
  )
  @Suppress("DEPRECATION")
  override fun wireSize(`value`: MixedOrderValueClass, context: EncodeContext): WireSize = wireBytes(value, context).toWireSize()

  override fun sizeHint(`value`: MixedOrderValueClass, context: EncodeContext): Int = 8

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.declared(8L)

  @Deprecated(
    message = "Call frameSize.",
    replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
  )
  @Suppress("DEPRECATION")
  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)
}
