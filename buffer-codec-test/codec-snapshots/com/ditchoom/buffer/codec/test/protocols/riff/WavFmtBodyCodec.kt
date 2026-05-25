package com.ditchoom.buffer.codec.test.protocols.riff

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object WavFmtBodyCodec : Codec<WavFmtBody> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): WavFmtBody {
    val audioFormatB0 = buffer.readUByte().toUInt()
    val audioFormatB1 = buffer.readUByte().toUInt()
    val audioFormat = (audioFormatB0 or (audioFormatB1 shl 8)).toUShort()
    val numChannelsB0 = buffer.readUByte().toUInt()
    val numChannelsB1 = buffer.readUByte().toUInt()
    val numChannels = (numChannelsB0 or (numChannelsB1 shl 8)).toUShort()
    val sampleRateB0 = buffer.readUByte().toUInt()
    val sampleRateB1 = buffer.readUByte().toUInt()
    val sampleRateB2 = buffer.readUByte().toUInt()
    val sampleRateB3 = buffer.readUByte().toUInt()
    val sampleRate = (sampleRateB0 or (sampleRateB1 shl 8) or (sampleRateB2 shl 16) or (sampleRateB3 shl 24))
    val byteRateB0 = buffer.readUByte().toUInt()
    val byteRateB1 = buffer.readUByte().toUInt()
    val byteRateB2 = buffer.readUByte().toUInt()
    val byteRateB3 = buffer.readUByte().toUInt()
    val byteRate = (byteRateB0 or (byteRateB1 shl 8) or (byteRateB2 shl 16) or (byteRateB3 shl 24))
    val blockAlignB0 = buffer.readUByte().toUInt()
    val blockAlignB1 = buffer.readUByte().toUInt()
    val blockAlign = (blockAlignB0 or (blockAlignB1 shl 8)).toUShort()
    val bitsPerSampleB0 = buffer.readUByte().toUInt()
    val bitsPerSampleB1 = buffer.readUByte().toUInt()
    val bitsPerSample = (bitsPerSampleB0 or (bitsPerSampleB1 shl 8)).toUShort()
    return WavFmtBody(audioFormat = audioFormat, numChannels = numChannels, sampleRate = sampleRate, byteRate = byteRate, blockAlign = blockAlign, bitsPerSample = bitsPerSample)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: WavFmtBody,
    context: EncodeContext,
  ) {
    buffer.writeUByte((value.audioFormat.toUInt() and 0xFFu).toUByte())
    buffer.writeUByte(((value.audioFormat.toUInt() shr 8) and 0xFFu).toUByte())
    buffer.writeUByte((value.numChannels.toUInt() and 0xFFu).toUByte())
    buffer.writeUByte(((value.numChannels.toUInt() shr 8) and 0xFFu).toUByte())
    buffer.writeUByte((value.sampleRate and 0xFFu).toUByte())
    buffer.writeUByte(((value.sampleRate shr 8) and 0xFFu).toUByte())
    buffer.writeUByte(((value.sampleRate shr 16) and 0xFFu).toUByte())
    buffer.writeUByte(((value.sampleRate shr 24) and 0xFFu).toUByte())
    buffer.writeUByte((value.byteRate and 0xFFu).toUByte())
    buffer.writeUByte(((value.byteRate shr 8) and 0xFFu).toUByte())
    buffer.writeUByte(((value.byteRate shr 16) and 0xFFu).toUByte())
    buffer.writeUByte(((value.byteRate shr 24) and 0xFFu).toUByte())
    buffer.writeUByte((value.blockAlign.toUInt() and 0xFFu).toUByte())
    buffer.writeUByte(((value.blockAlign.toUInt() shr 8) and 0xFFu).toUByte())
    buffer.writeUByte((value.bitsPerSample.toUInt() and 0xFFu).toUByte())
    buffer.writeUByte(((value.bitsPerSample.toUInt() shr 8) and 0xFFu).toUByte())
  }

  override fun wireSize(`value`: WavFmtBody, context: EncodeContext): WireSize = WireSize.Exact(16)

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = if (stream.available() - baseOffset >= 16) PeekResult.Complete(16) else PeekResult.NeedsMoreData
}
