package com.ditchoom.buffer.codec.test.protocols.mqttv5

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

public object MqttV5PropertyAuthenticationDataCodec : Codec<MqttV5Property.AuthenticationData> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): MqttV5Property.AuthenticationData {
    val id = MqttV5PropertyId(buffer.readUByte())
    val dataPrefixB0 = buffer.readUByte().toUInt()
    val dataPrefixB1 = buffer.readUByte().toUInt()
    val dataPrefix = ((dataPrefixB0 shl 8) or dataPrefixB1)
    if (dataPrefix > Int.MAX_VALUE.toUInt()) {
      throw DecodeException(fieldPath = "AuthenticationData.data", bufferPosition = -1, expected = "length prefix <= ${'$'}{Int.MAX_VALUE}", actual = dataPrefix.toString())
    }
    val dataLength = dataPrefix.toInt()
    val __dataOuterLimit = buffer.limit()
    buffer.setLimit(buffer.position() + dataLength)
    val data = try {
      BinaryDataCodec.decode(buffer, context)
    } finally {
      buffer.setLimit(__dataOuterLimit)
    }
    return MqttV5Property.AuthenticationData(id = id, data = data)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: MqttV5Property.AuthenticationData,
    context: EncodeContext,
  ) {
    buffer.writeUByte(value.id.raw)
    val dataSizePosition = buffer.position()
    buffer.position(dataSizePosition + 2)
    val dataBodyStart = buffer.position()
    BinaryDataCodec.encode(buffer, value.data, context)
    val dataEndPosition = buffer.position()
    val dataByteCount = dataEndPosition - dataBodyStart
    if (dataByteCount > 65_535) {
      throw EncodeException(fieldPath = "AuthenticationData.data", reason = """encoded payload byte length ${dataByteCount} exceeds @LengthPrefixed(LengthPrefix.Short) max 65535""")
    }
    buffer.position(dataSizePosition)
    val dataPrefix = dataByteCount.toUInt()
    buffer.writeUByte(((dataPrefix shr 8) and 0xFFu).toUByte())
    buffer.writeUByte((dataPrefix and 0xFFu).toUByte())
    buffer.position(dataEndPosition)
  }

  override fun wireSize(`value`: MqttV5Property.AuthenticationData, context: EncodeContext): WireSize = WireSize.BackPatch

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    var __offset = 0
    if (stream.available() - baseOffset < __offset + 1) return PeekResult.NeedsMoreData
    __offset += 1
    if (stream.available() - baseOffset < __offset + 2) return PeekResult.NeedsMoreData
    val dataPrefixB0 = stream.peekByte(baseOffset + __offset).toInt() and 0xFF
    val dataPrefixB1 = stream.peekByte(baseOffset + __offset + 1).toInt() and 0xFF
    val dataPrefix = ((dataPrefixB0 shl 8) or dataPrefixB1).toUInt()
    if (dataPrefix > (Int.MAX_VALUE - __offset - 2).toUInt()) {
      throw DecodeException(fieldPath = "AuthenticationData.data", bufferPosition = baseOffset + __offset, expected = "__offset + 2 + length prefix <= ${'$'}{Int.MAX_VALUE}", actual = """${__offset + 2 + dataPrefix.toInt()}""")
    }
    __offset += 2 + dataPrefix.toInt()
    return if (stream.available() - baseOffset >= __offset) PeekResult.Complete(__offset) else PeekResult.NeedsMoreData
  }
}
