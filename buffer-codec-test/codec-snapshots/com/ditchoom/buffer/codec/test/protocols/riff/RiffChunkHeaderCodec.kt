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
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.buffer.swapBytes
import kotlin.Deprecated
import kotlin.Int
import kotlin.ReplaceWith
import kotlin.Suppress

public object RiffChunkHeaderCodec : Codec<RiffChunkHeader> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): RiffChunkHeader {
    val fourCCRaw = buffer.readInt()
    val fourCC = (if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) fourCCRaw else swapBytes(fourCCRaw)).toUInt()
    val chunkSizeRaw = buffer.readInt()
    val chunkSize = (if (buffer.byteOrder == ByteOrder.LITTLE_ENDIAN) chunkSizeRaw else swapBytes(chunkSizeRaw)).toUInt()
    return RiffChunkHeader(fourCC = fourCC, chunkSize = chunkSize)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: RiffChunkHeader,
    context: EncodeContext,
  ) {
    val fourCCRaw = value.fourCC.toInt()
    buffer.writeInt(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) fourCCRaw else swapBytes(fourCCRaw))
    val chunkSizeRaw = value.chunkSize.toInt()
    buffer.writeInt(if (buffer.byteOrder == ByteOrder.LITTLE_ENDIAN) chunkSizeRaw else swapBytes(chunkSizeRaw))
  }

  override fun wireBytes(`value`: RiffChunkHeader, context: EncodeContext): WireByteCount = WireByteCount.exact(8L)

  @Deprecated(
    message = "Call wireBytes.",
    replaceWith = ReplaceWith("wireBytes(value, context)"),
  )
  @Suppress("DEPRECATION")
  override fun wireSize(`value`: RiffChunkHeader, context: EncodeContext): WireSize = wireBytes(value, context).toWireSize()

  override fun sizeHint(`value`: RiffChunkHeader, context: EncodeContext): Int = 8

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.declared(8L)

  @Deprecated(
    message = "Call frameSize.",
    replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
  )
  @Suppress("DEPRECATION")
  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)
}
