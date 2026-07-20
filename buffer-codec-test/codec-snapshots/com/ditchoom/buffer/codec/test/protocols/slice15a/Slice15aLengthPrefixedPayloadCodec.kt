package com.ditchoom.buffer.codec.test.protocols.slice15a

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.EncodeException
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.codec.test.protocols.payload.BinaryDataCodec
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object Slice15aLengthPrefixedPayloadCodec : Codec<Slice15aLengthPrefixedPayload> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): Slice15aLengthPrefixedPayload {
    val dataPrefixB0 = buffer.readUByte().toUInt()
    val dataPrefixB1 = buffer.readUByte().toUInt()
    val dataPrefix = ((dataPrefixB0 shl 8) or dataPrefixB1)
    if (dataPrefix > Int.MAX_VALUE.toUInt()) {
      throw DecodeException(fieldPath = "Slice15aLengthPrefixedPayload.data", bufferPosition = -1, expected = "length prefix <= ${'$'}{Int.MAX_VALUE}", actual = dataPrefix.toString())
    }
    val dataLength = dataPrefix.toInt()
    val __dataOuterLimit = buffer.limit()
    buffer.setLimit(buffer.position() + dataLength)
    val data = try {
      BinaryDataCodec.decode(buffer, context)
    } finally {
      buffer.setLimit(__dataOuterLimit)
    }
    return Slice15aLengthPrefixedPayload(data = data)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: Slice15aLengthPrefixedPayload,
    context: EncodeContext,
  ) {
    val dataSizePosition = buffer.position()
    repeat(2) { buffer.writeUByte(0u) }
    val dataBodyStart = buffer.position()
    BinaryDataCodec.encode(buffer, value.data, context)
    val dataEndPosition = buffer.position()
    val dataByteCount = dataEndPosition - dataBodyStart
    if (dataByteCount > 65_535) {
      throw EncodeException(fieldPath = "Slice15aLengthPrefixedPayload.data", reason = """encoded payload byte length ${dataByteCount} exceeds @LengthPrefixed(LengthPrefix.Short) max 65535""")
    }
    buffer.position(dataSizePosition)
    val dataPrefix = dataByteCount.toUInt()
    buffer.writeUByte(((dataPrefix shr 8) and 0xFFu).toUByte())
    buffer.writeUByte((dataPrefix and 0xFFu).toUByte())
    buffer.position(dataEndPosition)
  }

  override fun wireSize(`value`: Slice15aLengthPrefixedPayload, context: EncodeContext): WireSize = WireSize.BackPatch

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    var __offset = 0
    if (stream.available() - baseOffset < __offset + 2) return PeekResult.NeedsMoreData
    val dataPrefixB0 = stream.peekByte(baseOffset + __offset).toInt() and 0xFF
    val dataPrefixB1 = stream.peekByte(baseOffset + __offset + 1).toInt() and 0xFF
    val dataPrefix = ((dataPrefixB0 shl 8) or dataPrefixB1).toUInt()
    if (dataPrefix > (Int.MAX_VALUE - __offset - 2).toUInt()) {
      throw DecodeException(fieldPath = "Slice15aLengthPrefixedPayload.data", bufferPosition = baseOffset + __offset, expected = "__offset + 2 + length prefix <= ${'$'}{Int.MAX_VALUE}", actual = """${__offset + 2 + dataPrefix.toInt()}""")
    }
    __offset += 2 + dataPrefix.toInt()
    return if (stream.available() - baseOffset >= __offset) PeekResult.Complete(__offset) else PeekResult.NeedsMoreData
  }
}
