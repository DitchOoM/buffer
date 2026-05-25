package com.ditchoom.buffer.codec.test.protocols.flv

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.EncodeException
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object FlvTagHeaderCodec : Codec<FlvTagHeader> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): FlvTagHeader {
    val tagType = buffer.readUByte()
    val dataSizeB0 = buffer.readUByte().toUInt()
    val dataSizeB1 = buffer.readUByte().toUInt()
    val dataSizeB2 = buffer.readUByte().toUInt()
    val dataSize = ((dataSizeB0 shl 16) or (dataSizeB1 shl 8) or dataSizeB2)
    val timestampB0 = buffer.readUByte().toUInt()
    val timestampB1 = buffer.readUByte().toUInt()
    val timestampB2 = buffer.readUByte().toUInt()
    val timestamp = ((timestampB0 shl 16) or (timestampB1 shl 8) or timestampB2)
    val timestampExtended = buffer.readUByte()
    val streamIdB0 = buffer.readUByte().toUInt()
    val streamIdB1 = buffer.readUByte().toUInt()
    val streamIdB2 = buffer.readUByte().toUInt()
    val streamId = ((streamIdB0 shl 16) or (streamIdB1 shl 8) or streamIdB2)
    return FlvTagHeader(tagType = tagType, dataSize = dataSize, timestamp = timestamp, timestampExtended = timestampExtended, streamId = streamId)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: FlvTagHeader,
    context: EncodeContext,
  ) {
    buffer.writeUByte(value.tagType)
    if (value.dataSize > ((1u shl 24) - 1u)) {
      throw EncodeException(fieldPath = "FlvTagHeader.dataSize", reason = "value exceeds @WireBytes(3) range (max 16777215)")
    }
    buffer.writeUByte(((value.dataSize shr 16) and 0xFFu).toUByte())
    buffer.writeUByte(((value.dataSize shr 8) and 0xFFu).toUByte())
    buffer.writeUByte((value.dataSize and 0xFFu).toUByte())
    if (value.timestamp > ((1u shl 24) - 1u)) {
      throw EncodeException(fieldPath = "FlvTagHeader.timestamp", reason = "value exceeds @WireBytes(3) range (max 16777215)")
    }
    buffer.writeUByte(((value.timestamp shr 16) and 0xFFu).toUByte())
    buffer.writeUByte(((value.timestamp shr 8) and 0xFFu).toUByte())
    buffer.writeUByte((value.timestamp and 0xFFu).toUByte())
    buffer.writeUByte(value.timestampExtended)
    if (value.streamId > ((1u shl 24) - 1u)) {
      throw EncodeException(fieldPath = "FlvTagHeader.streamId", reason = "value exceeds @WireBytes(3) range (max 16777215)")
    }
    buffer.writeUByte(((value.streamId shr 16) and 0xFFu).toUByte())
    buffer.writeUByte(((value.streamId shr 8) and 0xFFu).toUByte())
    buffer.writeUByte((value.streamId and 0xFFu).toUByte())
  }

  override fun wireSize(`value`: FlvTagHeader, context: EncodeContext): WireSize = WireSize.Exact(11)

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = if (stream.available() - baseOffset >= 11) PeekResult.Complete(11) else PeekResult.NeedsMoreData
}
