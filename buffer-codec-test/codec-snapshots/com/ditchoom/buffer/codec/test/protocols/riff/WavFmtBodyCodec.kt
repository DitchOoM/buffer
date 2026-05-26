package com.ditchoom.buffer.codec.test.protocols.riff

import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.buffer.swapBytes
import kotlin.Int

public object WavFmtBodyCodec : Codec<WavFmtBody> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): WavFmtBody {
    val __batch12Raw = buffer.readLong()
    val __batch12 = if (buffer.byteOrder == ByteOrder.LITTLE_ENDIAN) __batch12Raw else swapBytes(__batch12Raw)
    val audioFormat = (__batch12 and 0xFFFFL).toUShort()
    val numChannels = (__batch12 ushr 16 and 0xFFFFL).toUShort()
    val sampleRate = (__batch12 ushr 32 and 0xFFFFFFFFL).toUInt()
    val __batch13Raw = buffer.readLong()
    val __batch13 = if (buffer.byteOrder == ByteOrder.LITTLE_ENDIAN) __batch13Raw else swapBytes(__batch13Raw)
    val byteRate = (__batch13 and 0xFFFFFFFFL).toUInt()
    val blockAlign = (__batch13 ushr 32 and 0xFFFFL).toUShort()
    val bitsPerSample = (__batch13 ushr 48 and 0xFFFFL).toUShort()
    return WavFmtBody(audioFormat = audioFormat, numChannels = numChannels, sampleRate = sampleRate, byteRate = byteRate, blockAlign = blockAlign, bitsPerSample = bitsPerSample)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: WavFmtBody,
    context: EncodeContext,
  ) {
    val __batch14 = ((value.audioFormat.toLong() and 0xFFFFL) or ((value.numChannels.toLong() and 0xFFFFL) shl 16) or ((value.sampleRate.toLong() and 0xFFFFFFFFL) shl 32)).toLong()
    buffer.writeLong(if (buffer.byteOrder == ByteOrder.LITTLE_ENDIAN) __batch14 else swapBytes(__batch14))
    val __batch15 = ((value.byteRate.toLong() and 0xFFFFFFFFL) or ((value.blockAlign.toLong() and 0xFFFFL) shl 32) or ((value.bitsPerSample.toLong() and 0xFFFFL) shl 48)).toLong()
    buffer.writeLong(if (buffer.byteOrder == ByteOrder.LITTLE_ENDIAN) __batch15 else swapBytes(__batch15))
  }

  override fun wireSize(`value`: WavFmtBody, context: EncodeContext): WireSize = WireSize.Exact(16)

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = if (stream.available() - baseOffset >= 16) PeekResult.Complete(16) else PeekResult.NeedsMoreData
}
