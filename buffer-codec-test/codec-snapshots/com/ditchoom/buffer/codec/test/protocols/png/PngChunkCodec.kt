package com.ditchoom.buffer.codec.test.protocols.png

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.codec.test.protocols.payload.BinaryDataCodec
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object PngChunkCodec : Codec<PngChunk> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): PngChunk {
    val lengthB0 = buffer.readUByte().toUInt()
    val lengthB1 = buffer.readUByte().toUInt()
    val lengthB2 = buffer.readUByte().toUInt()
    val lengthB3 = buffer.readUByte().toUInt()
    val length = ((lengthB0 shl 24) or (lengthB1 shl 16) or (lengthB2 shl 8) or lengthB3)
    val typeB0 = buffer.readUByte().toUInt()
    val typeB1 = buffer.readUByte().toUInt()
    val typeB2 = buffer.readUByte().toUInt()
    val typeB3 = buffer.readUByte().toUInt()
    val type = ((typeB0 shl 24) or (typeB1 shl 16) or (typeB2 shl 8) or typeB3)
    val __dataOuterLimit = buffer.limit()
    buffer.setLimit(__dataOuterLimit - 4)
    val data = try {
      BinaryDataCodec.decode(buffer, context)
    } finally {
      buffer.setLimit(__dataOuterLimit)
    }
    val crcB0 = buffer.readUByte().toUInt()
    val crcB1 = buffer.readUByte().toUInt()
    val crcB2 = buffer.readUByte().toUInt()
    val crcB3 = buffer.readUByte().toUInt()
    val crc = ((crcB0 shl 24) or (crcB1 shl 16) or (crcB2 shl 8) or crcB3)
    return PngChunk(length = length, type = type, data = data, crc = crc)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: PngChunk,
    context: EncodeContext,
  ) {
    buffer.writeUByte(((value.length shr 24) and 0xFFu).toUByte())
    buffer.writeUByte(((value.length shr 16) and 0xFFu).toUByte())
    buffer.writeUByte(((value.length shr 8) and 0xFFu).toUByte())
    buffer.writeUByte((value.length and 0xFFu).toUByte())
    buffer.writeUByte(((value.type shr 24) and 0xFFu).toUByte())
    buffer.writeUByte(((value.type shr 16) and 0xFFu).toUByte())
    buffer.writeUByte(((value.type shr 8) and 0xFFu).toUByte())
    buffer.writeUByte((value.type and 0xFFu).toUByte())
    BinaryDataCodec.encode(buffer, value.data, context)
    buffer.writeUByte(((value.crc shr 24) and 0xFFu).toUByte())
    buffer.writeUByte(((value.crc shr 16) and 0xFFu).toUByte())
    buffer.writeUByte(((value.crc shr 8) and 0xFFu).toUByte())
    buffer.writeUByte((value.crc and 0xFFu).toUByte())
  }

  override fun wireSize(`value`: PngChunk, context: EncodeContext): WireSize = WireSize.BackPatch

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = PeekResult.NoFraming
}
