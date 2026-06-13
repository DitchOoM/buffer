package com.ditchoom.buffer.codec.test.protocols.mqttv5

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.EncodeException
import com.ditchoom.buffer.codec.FramedEncoder
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.test.protocols.mqtt.MqttConnectFlags
import com.ditchoom.buffer.codec.test.protocols.mqtt.MqttFixedHeader
import com.ditchoom.buffer.codec.test.protocols.mqtt.MqttRemainingLengthCodec
import com.ditchoom.buffer.codec.test.protocols.payload.BinaryData
import com.ditchoom.buffer.codec.test.protocols.payload.BinaryDataCodec
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.buffer.swapBytes
import kotlin.Int
import kotlin.String
import kotlin.Throwable

public object MqttV5PacketConnectCodec {
  public fun decode(buffer: ReadBuffer, context: DecodeContext): MqttV5Packet.Connect {
    val header = MqttFixedHeader(buffer.readUByte())
    val __framingOuterLimit = buffer.limit()
    val __framingLength = MqttRemainingLengthCodec.decode(buffer, context)
    if (__framingLength.toInt() > buffer.remaining()) {
      throw DecodeException(
            fieldPath = "Connect.@FramedBy",
            bufferPosition = buffer.position(),
            expected = "a fully-buffered " + __framingLength + "-byte framed body",
            actual = buffer.remaining().toString() + " bytes available",
          )
    }
    MqttRemainingLengthCodec.applyBound(buffer, __framingLength)
    val __framingStart = buffer.position()
    val __framingBound = __framingStart + __framingLength.toInt()
    return try {
      val protocolNamePrefixB0 = buffer.readUByte().toUInt()
      val protocolNamePrefixB1 = buffer.readUByte().toUInt()
      val protocolNamePrefix = ((protocolNamePrefixB0 shl 8) or protocolNamePrefixB1)
      if (protocolNamePrefix > Int.MAX_VALUE.toUInt()) {
        throw DecodeException(fieldPath = "Connect.protocolName", bufferPosition = -1, expected = "length prefix <= ${'$'}{Int.MAX_VALUE}", actual = protocolNamePrefix.toString())
      }
      val protocolNameLength = protocolNamePrefix.toInt()
      val protocolName = buffer.readString(protocolNameLength, Charset.UTF8)
      val protocolLevel = buffer.readUByte()
      val connectFlags = MqttConnectFlags(buffer.readUByte())
      val keepAliveSecondsRaw = buffer.readShort()
      val keepAliveSeconds = (if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) keepAliveSecondsRaw else swapBytes(keepAliveSecondsRaw)).toUShort()
      val properties = V5PropertyBagCodec.decode(buffer, context)
      val clientIdPrefixB0 = buffer.readUByte().toUInt()
      val clientIdPrefixB1 = buffer.readUByte().toUInt()
      val clientIdPrefix = ((clientIdPrefixB0 shl 8) or clientIdPrefixB1)
      if (clientIdPrefix > Int.MAX_VALUE.toUInt()) {
        throw DecodeException(fieldPath = "Connect.clientId", bufferPosition = -1, expected = "length prefix <= ${'$'}{Int.MAX_VALUE}", actual = clientIdPrefix.toString())
      }
      val clientIdLength = clientIdPrefix.toInt()
      val clientId = buffer.readString(clientIdLength, Charset.UTF8)
      val willProperties: V5PropertyBag? = if (connectFlags.willPresent) V5PropertyBagCodec.decode(buffer, context) else null
      val willTopic: String? = if (connectFlags.willPresent) {
        val willTopicPrefixB0 = buffer.readUByte().toUInt()
        val willTopicPrefixB1 = buffer.readUByte().toUInt()
        val willTopicPrefix = ((willTopicPrefixB0 shl 8) or willTopicPrefixB1)
        if (willTopicPrefix > Int.MAX_VALUE.toUInt()) {
          throw DecodeException(fieldPath = "Connect.willTopic", bufferPosition = -1, expected = "length prefix <= ${'$'}{Int.MAX_VALUE}", actual = willTopicPrefix.toString())
        }
        val willTopicLength = willTopicPrefix.toInt()
        buffer.readString(willTopicLength, Charset.UTF8)
      } else {
        null
      }
      val willPayload: BinaryData? = if (connectFlags.willPresent) {
        val willPayloadPrefixB0 = buffer.readUByte().toUInt()
        val willPayloadPrefixB1 = buffer.readUByte().toUInt()
        val willPayloadPrefix = ((willPayloadPrefixB0 shl 8) or willPayloadPrefixB1)
        if (willPayloadPrefix > Int.MAX_VALUE.toUInt()) {
          throw DecodeException(fieldPath = "Connect.willPayload", bufferPosition = -1, expected = "length prefix <= ${'$'}{Int.MAX_VALUE}", actual = willPayloadPrefix.toString())
        }
        val willPayloadLength = willPayloadPrefix.toInt()
        val __willPayloadOuterLimit = buffer.limit()
        buffer.setLimit(buffer.position() + willPayloadLength)
        try {
          BinaryDataCodec.decode(buffer, context)
        } finally {
          buffer.setLimit(__willPayloadOuterLimit)
        }
      } else {
        null
      }
      val username: String? = if (connectFlags.usernamePresent) {
        val usernamePrefixB0 = buffer.readUByte().toUInt()
        val usernamePrefixB1 = buffer.readUByte().toUInt()
        val usernamePrefix = ((usernamePrefixB0 shl 8) or usernamePrefixB1)
        if (usernamePrefix > Int.MAX_VALUE.toUInt()) {
          throw DecodeException(fieldPath = "Connect.username", bufferPosition = -1, expected = "length prefix <= ${'$'}{Int.MAX_VALUE}", actual = usernamePrefix.toString())
        }
        val usernameLength = usernamePrefix.toInt()
        buffer.readString(usernameLength, Charset.UTF8)
      } else {
        null
      }
      val password: BinaryData? = if (connectFlags.passwordPresent) {
        val passwordPrefixB0 = buffer.readUByte().toUInt()
        val passwordPrefixB1 = buffer.readUByte().toUInt()
        val passwordPrefix = ((passwordPrefixB0 shl 8) or passwordPrefixB1)
        if (passwordPrefix > Int.MAX_VALUE.toUInt()) {
          throw DecodeException(fieldPath = "Connect.password", bufferPosition = -1, expected = "length prefix <= ${'$'}{Int.MAX_VALUE}", actual = passwordPrefix.toString())
        }
        val passwordLength = passwordPrefix.toInt()
        val __passwordOuterLimit = buffer.limit()
        buffer.setLimit(buffer.position() + passwordLength)
        try {
          BinaryDataCodec.decode(buffer, context)
        } finally {
          buffer.setLimit(__passwordOuterLimit)
        }
      } else {
        null
      }
      if (buffer.position() != __framingBound) {
        throw DecodeException(
              fieldPath = "Connect.@FramedBy",
              bufferPosition = buffer.position(),
              expected = "body to consume " + __framingLength + " bytes",
              actual = (buffer.position() - __framingStart).toString() + " bytes",
            )
      }
      MqttV5Packet.Connect(header = header, protocolName = protocolName, protocolLevel = protocolLevel, connectFlags = connectFlags, keepAliveSeconds = keepAliveSeconds, properties = properties, clientId = clientId, willProperties = willProperties, willTopic = willTopic, willPayload = willPayload, username = username, password = password)
    } finally {
      buffer.setLimit(__framingOuterLimit)
    }
  }

  public fun encode(
    `value`: MqttV5Packet.Connect,
    context: EncodeContext,
    factory: BufferFactory,
  ): ReadBuffer = FramedEncoder.encode(
    factory = factory,
    framingCodec = MqttRemainingLengthCodec,
    context = context,
    headerWireWidth = 1,
    writeHeader = { buffer ->
      buffer.writeUByte(value.header.raw)
    },
  ) { buffer ->
    val protocolNameSizePosition = buffer.position()
    buffer.position(protocolNameSizePosition + 2)
    val protocolNameBodyStart = buffer.position()
    buffer.writeString(value.protocolName, Charset.UTF8)
    val protocolNameEndPosition = buffer.position()
    val protocolNameByteCount = protocolNameEndPosition - protocolNameBodyStart
    if (protocolNameByteCount > 65_535) {
      throw EncodeException(fieldPath = "Connect.protocolName", reason = """UTF-8 byte length ${protocolNameByteCount} exceeds @LengthPrefixed(LengthPrefix.Short) max 65535""")
    }
    buffer.position(protocolNameSizePosition)
    val protocolNamePrefix = protocolNameByteCount.toUInt()
    buffer.writeUByte(((protocolNamePrefix shr 8) and 0xFFu).toUByte())
    buffer.writeUByte((protocolNamePrefix and 0xFFu).toUByte())
    buffer.position(protocolNameEndPosition)
    buffer.writeUByte(value.protocolLevel)
    buffer.writeUByte(value.connectFlags.raw)
    val keepAliveSecondsRaw = value.keepAliveSeconds.toShort()
    buffer.writeShort(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) keepAliveSecondsRaw else swapBytes(keepAliveSecondsRaw))
    V5PropertyBagCodec.encode(buffer, value.properties, context)
    val clientIdSizePosition = buffer.position()
    buffer.position(clientIdSizePosition + 2)
    val clientIdBodyStart = buffer.position()
    buffer.writeString(value.clientId, Charset.UTF8)
    val clientIdEndPosition = buffer.position()
    val clientIdByteCount = clientIdEndPosition - clientIdBodyStart
    if (clientIdByteCount > 65_535) {
      throw EncodeException(fieldPath = "Connect.clientId", reason = """UTF-8 byte length ${clientIdByteCount} exceeds @LengthPrefixed(LengthPrefix.Short) max 65535""")
    }
    buffer.position(clientIdSizePosition)
    val clientIdPrefix = clientIdByteCount.toUInt()
    buffer.writeUByte(((clientIdPrefix shr 8) and 0xFFu).toUByte())
    buffer.writeUByte((clientIdPrefix and 0xFFu).toUByte())
    buffer.position(clientIdEndPosition)
    if (value.connectFlags.willPresent) {
      val willPropertiesValue = value.willProperties ?: throw EncodeException(fieldPath = "Connect.willProperties", reason = "@When(\"connectFlags.willPresent\") predicate is true but field is null")
      V5PropertyBagCodec.encode(buffer, willPropertiesValue, context)
    }
    if (value.connectFlags.willPresent) {
      val willTopicValue = value.willTopic ?: throw EncodeException(fieldPath = "Connect.willTopic", reason = "@When(\"connectFlags.willPresent\") predicate is true but field is null")
      val willTopicSizePosition = buffer.position()
      buffer.position(willTopicSizePosition + 2)
      val willTopicBodyStart = buffer.position()
      buffer.writeString(willTopicValue, Charset.UTF8)
      val willTopicEndPosition = buffer.position()
      val willTopicByteCount = willTopicEndPosition - willTopicBodyStart
      if (willTopicByteCount > 65_535) {
        throw EncodeException(fieldPath = "Connect.willTopic", reason = """UTF-8 byte length ${willTopicByteCount} exceeds @LengthPrefixed(LengthPrefix.Short) max 65535""")
      }
      buffer.position(willTopicSizePosition)
      val willTopicPrefix = willTopicByteCount.toUInt()
      buffer.writeUByte(((willTopicPrefix shr 8) and 0xFFu).toUByte())
      buffer.writeUByte((willTopicPrefix and 0xFFu).toUByte())
      buffer.position(willTopicEndPosition)
    }
    if (value.connectFlags.willPresent) {
      val willPayloadValue = value.willPayload ?: throw EncodeException(fieldPath = "Connect.willPayload", reason = "@When(\"connectFlags.willPresent\") predicate is true but field is null")
      val willPayloadSizePosition = buffer.position()
      buffer.position(willPayloadSizePosition + 2)
      val willPayloadBodyStart = buffer.position()
      BinaryDataCodec.encode(buffer, willPayloadValue, context)
      val willPayloadEndPosition = buffer.position()
      val willPayloadByteCount = willPayloadEndPosition - willPayloadBodyStart
      if (willPayloadByteCount > 65_535) {
        throw EncodeException(fieldPath = "Connect.willPayload", reason = """encoded payload byte length ${willPayloadByteCount} exceeds @LengthPrefixed(LengthPrefix.Short) max 65535""")
      }
      buffer.position(willPayloadSizePosition)
      val willPayloadPrefix = willPayloadByteCount.toUInt()
      buffer.writeUByte(((willPayloadPrefix shr 8) and 0xFFu).toUByte())
      buffer.writeUByte((willPayloadPrefix and 0xFFu).toUByte())
      buffer.position(willPayloadEndPosition)
    }
    if (value.connectFlags.usernamePresent) {
      val usernameValue = value.username ?: throw EncodeException(fieldPath = "Connect.username", reason = "@When(\"connectFlags.usernamePresent\") predicate is true but field is null")
      val usernameSizePosition = buffer.position()
      buffer.position(usernameSizePosition + 2)
      val usernameBodyStart = buffer.position()
      buffer.writeString(usernameValue, Charset.UTF8)
      val usernameEndPosition = buffer.position()
      val usernameByteCount = usernameEndPosition - usernameBodyStart
      if (usernameByteCount > 65_535) {
        throw EncodeException(fieldPath = "Connect.username", reason = """UTF-8 byte length ${usernameByteCount} exceeds @LengthPrefixed(LengthPrefix.Short) max 65535""")
      }
      buffer.position(usernameSizePosition)
      val usernamePrefix = usernameByteCount.toUInt()
      buffer.writeUByte(((usernamePrefix shr 8) and 0xFFu).toUByte())
      buffer.writeUByte((usernamePrefix and 0xFFu).toUByte())
      buffer.position(usernameEndPosition)
    }
    if (value.connectFlags.passwordPresent) {
      val passwordValue = value.password ?: throw EncodeException(fieldPath = "Connect.password", reason = "@When(\"connectFlags.passwordPresent\") predicate is true but field is null")
      val passwordSizePosition = buffer.position()
      buffer.position(passwordSizePosition + 2)
      val passwordBodyStart = buffer.position()
      BinaryDataCodec.encode(buffer, passwordValue, context)
      val passwordEndPosition = buffer.position()
      val passwordByteCount = passwordEndPosition - passwordBodyStart
      if (passwordByteCount > 65_535) {
        throw EncodeException(fieldPath = "Connect.password", reason = """encoded payload byte length ${passwordByteCount} exceeds @LengthPrefixed(LengthPrefix.Short) max 65535""")
      }
      buffer.position(passwordSizePosition)
      val passwordPrefix = passwordByteCount.toUInt()
      buffer.writeUByte(((passwordPrefix shr 8) and 0xFFu).toUByte())
      buffer.writeUByte((passwordPrefix and 0xFFu).toUByte())
      buffer.position(passwordEndPosition)
    }
  }

  public fun peekFrameSize(stream: StreamProcessor, baseOffset: Int = 0): PeekResult {
    if (stream.available() - baseOffset < 2) return PeekResult.NeedsMoreData
    val __framingPeek = stream.peekBuffer(baseOffset + 1, 5) ?: return PeekResult.NeedsMoreData
    try {
      val __framingPeekStart = __framingPeek.position()
      val __framingLength = try {
        MqttRemainingLengthCodec.decode(__framingPeek, DecodeContext.Empty)
      } catch (__e: Throwable) {
        when (__e::class.simpleName) {
          "BufferUnderflowException", "IndexOutOfBoundsException", "ArrayIndexOutOfBoundsException" -> return PeekResult.NeedsMoreData
          else -> throw __e
        }
      }
      val __framingPrefixWidth = __framingPeek.position() - __framingPeekStart
      val __total = 1 + __framingPrefixWidth + __framingLength.toInt()
      return if (stream.available() - baseOffset >= __total) PeekResult.Complete(__total) else PeekResult.NeedsMoreData
    } finally {
      (__framingPeek as? PlatformBuffer)?.freeNativeMemory()
    }
  }
}
