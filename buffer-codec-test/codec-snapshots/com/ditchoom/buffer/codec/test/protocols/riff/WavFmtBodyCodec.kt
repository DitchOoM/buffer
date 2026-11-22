package com.ditchoom.buffer.codec.test.protocols.riff

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
import com.ditchoom.buffer.codec.toPeekResult
import com.ditchoom.buffer.codec.toWireSize
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.buffer.swapBytes
import kotlin.Deprecated
import kotlin.Int
import kotlin.ReplaceWith
import kotlin.Suppress

public object WavFmtBodyCodec : Codec<WavFmtBody> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): WavFmtBody {
    val __batch1Raw = buffer.readLong()
    val __batch1 = if (buffer.byteOrder == ByteOrder.LITTLE_ENDIAN) __batch1Raw else swapBytes(__batch1Raw)
    val audioFormat = (__batch1 and 0xFFFFL).toUShort()
    val numChannels = (__batch1 ushr 16 and 0xFFFFL).toUShort()
    val sampleRate = (__batch1 ushr 32 and 0xFFFFFFFFL).toUInt()
    val __batch2Raw = buffer.readLong()
    val __batch2 = if (buffer.byteOrder == ByteOrder.LITTLE_ENDIAN) __batch2Raw else swapBytes(__batch2Raw)
    val byteRate = (__batch2 and 0xFFFFFFFFL).toUInt()
    val blockAlign = (__batch2 ushr 32 and 0xFFFFL).toUShort()
    val bitsPerSample = (__batch2 ushr 48 and 0xFFFFL).toUShort()
    return WavFmtBody(audioFormat = audioFormat, numChannels = numChannels, sampleRate = sampleRate, byteRate = byteRate, blockAlign = blockAlign, bitsPerSample = bitsPerSample)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: WavFmtBody,
    context: EncodeContext,
  ) {
    val __batch3 = (value.audioFormat.toLong() and 0xFFFFL) or ((value.numChannels.toLong() and 0xFFFFL) shl 16) or ((value.sampleRate.toLong() and 0xFFFFFFFFL) shl 32)
    buffer.writeLong(if (buffer.byteOrder == ByteOrder.LITTLE_ENDIAN) __batch3 else swapBytes(__batch3))
    val __batch4 = (value.byteRate.toLong() and 0xFFFFFFFFL) or ((value.blockAlign.toLong() and 0xFFFFL) shl 32) or ((value.bitsPerSample.toLong() and 0xFFFFL) shl 48)
    buffer.writeLong(if (buffer.byteOrder == ByteOrder.LITTLE_ENDIAN) __batch4 else swapBytes(__batch4))
  }

  override fun wireBytes(`value`: WavFmtBody, context: EncodeContext): WireByteCount = WireByteCount.exact(16L)

  @Deprecated(
    message = "Call wireBytes, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("wireBytes(value, context)"),
  )
  @Suppress("DEPRECATION")
  override fun wireSize(`value`: WavFmtBody, context: EncodeContext): WireSize = wireBytes(value, context).toWireSize()

  override fun sizeHint(`value`: WavFmtBody, context: EncodeContext): Int = 16

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.declared(0, 16L)

  @Deprecated(
    message = "Call frameSize, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
  )
  @Suppress("DEPRECATION")
  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)
}
