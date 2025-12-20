package com.ditchoom.buffer.protocol.mqtt

import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.PooledBuffer
import com.ditchoom.buffer.stream.AccumulatingBufferReader
import com.ditchoom.buffer.stream.BufferChunk
import com.ditchoom.buffer.stream.BufferStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * High-performance MQTT packet parser.
 *
 * Implements MQTT 3.1.1 packet parsing with support for:
 * - Variable length encoding
 * - All standard packet types
 * - Payload compression (when decompressor is provided)
 */
class MqttParser(
    private val pool: BufferPool,
    private val decompressor: ((ByteArray) -> ByteArray)? = null,
) {
    /**
     * Parses MQTT packets from a buffer stream.
     */
    fun parsePackets(stream: BufferStream): Flow<MqttPacket> =
        flow {
            val reader = AccumulatingBufferReader(pool)

            try {
                stream.chunks.collect { chunk ->
                    reader.append(chunk)

                    while (reader.available() >= 2) {
                        val packet = tryParsePacket(reader)
                        if (packet != null) {
                            emit(packet)
                            reader.compact()
                        } else {
                            break // Need more data
                        }
                    }
                }
            } finally {
                reader.release()
            }
        }

    /**
     * Parses a single packet from a byte array.
     */
    fun parsePacket(data: ByteArray): MqttPacket {
        val reader = AccumulatingBufferReader(pool)
        try {
            val buffer = pool.acquire(data.size)
            buffer.writeBytes(data)
            buffer.resetForRead()
            reader.append(BufferChunk(buffer, true, 0))
            return tryParsePacket(reader)
                ?: throw MqttParseException("Incomplete MQTT packet")
        } finally {
            reader.release()
        }
    }

    private fun tryParsePacket(reader: AccumulatingBufferReader): MqttPacket? {
        if (reader.available() < 2) return null

        // Peek at the fixed header
        val headerBytes = reader.peek(2)
        val fixedHeader = headerBytes[0].toInt() and 0xFF
        val packetTypeValue = (fixedHeader shr 4) and 0x0F
        val flags = fixedHeader and 0x0F

        // Calculate remaining length (variable length encoding)
        val remainingLengthResult =
            peekRemainingLength(reader, 1)
                ?: return null

        val (remainingLength, headerLength) = remainingLengthResult
        val totalPacketLength = headerLength + remainingLength

        if (reader.available() < totalPacketLength) return null

        // Now actually consume the header
        reader.readByte() // Fixed header byte

        // Read remaining length bytes
        readRemainingLength(reader)

        // Parse based on packet type
        val packetType = MqttPacketType.fromInt(packetTypeValue)

        return when (packetType) {
            MqttPacketType.Connect -> parseConnect(reader, remainingLength)
            MqttPacketType.ConnAck -> parseConnAck(reader, remainingLength)
            MqttPacketType.Publish -> parsePublish(reader, remainingLength, flags)
            MqttPacketType.PubAck -> parsePubAck(reader, remainingLength)
            MqttPacketType.PubRec -> parsePubRec(reader, remainingLength)
            MqttPacketType.PubRel -> parsePubRel(reader, remainingLength)
            MqttPacketType.PubComp -> parsePubComp(reader, remainingLength)
            MqttPacketType.Subscribe -> parseSubscribe(reader, remainingLength)
            MqttPacketType.SubAck -> parseSubAck(reader, remainingLength)
            MqttPacketType.Unsubscribe -> parseUnsubscribe(reader, remainingLength)
            MqttPacketType.UnsubAck -> parseUnsubAck(reader, remainingLength)
            MqttPacketType.PingReq -> MqttPingReq
            MqttPacketType.PingResp -> MqttPingResp
            MqttPacketType.Disconnect -> parseDisconnect(reader, remainingLength)
            MqttPacketType.Auth -> throw MqttParseException("AUTH packet not supported in MQTT 3.1.1")
            is MqttPacketType.Reserved -> throw MqttParseException(
                "Reserved packet type: ${packetType.value}",
            )
        }
    }

    private fun peekRemainingLength(
        reader: AccumulatingBufferReader,
        startOffset: Int,
    ): Pair<Int, Int>? {
        var multiplier = 1
        var value = 0
        var bytesRead = 0

        while (true) {
            if (reader.available() < startOffset + bytesRead + 1) return null

            val data = reader.peek(startOffset + bytesRead + 1)
            val encodedByte = data[startOffset + bytesRead].toInt() and 0xFF
            value += (encodedByte and 0x7F) * multiplier

            bytesRead++
            if (bytesRead > 4) throw MqttParseException("Malformed remaining length")

            if ((encodedByte and 0x80) == 0) break
            multiplier *= 128
        }

        return value to (1 + bytesRead) // Fixed header byte + remaining length bytes
    }

    private fun readRemainingLength(reader: AccumulatingBufferReader): Int {
        var multiplier = 1
        var value = 0
        var bytesRead = 0

        while (true) {
            val encodedByte = reader.readByte().toInt() and 0xFF
            value += (encodedByte and 0x7F) * multiplier

            bytesRead++
            if (bytesRead > 4) throw MqttParseException("Malformed remaining length")

            if ((encodedByte and 0x80) == 0) break
            multiplier *= 128
        }

        return value
    }

    private fun readMqttString(reader: AccumulatingBufferReader): String {
        val length = reader.readShort().toInt() and 0xFFFF
        val bytes = reader.readBytes(length)
        return bytes.decodeToString()
    }

    private fun parseConnect(
        reader: AccumulatingBufferReader,
        remainingLength: Int,
    ): MqttConnect {
        val protocolName = readMqttString(reader)
        val protocolLevel = reader.readByte().toInt() and 0xFF
        val connectFlags = reader.readByte().toInt() and 0xFF

        val cleanSession = (connectFlags and 0x02) != 0
        val willFlag = (connectFlags and 0x04) != 0
        val willQos = MqttQos.fromInt((connectFlags and 0x18) shr 3)
        val willRetain = (connectFlags and 0x20) != 0
        val passwordFlag = (connectFlags and 0x40) != 0
        val usernameFlag = (connectFlags and 0x80) != 0

        val keepAlive = reader.readShort().toInt() and 0xFFFF

        val clientId = readMqttString(reader)

        val willTopic = if (willFlag) readMqttString(reader) else null
        val willPayload =
            if (willFlag) {
                val payloadLength = reader.readShort().toInt() and 0xFFFF
                val payloadBytes = reader.readBytes(payloadLength)
                createPayload(payloadBytes)
            } else {
                null
            }

        val username = if (usernameFlag) readMqttString(reader) else null
        val password =
            if (passwordFlag) {
                val pwdLength = reader.readShort().toInt() and 0xFFFF
                reader.readBytes(pwdLength)
            } else {
                null
            }

        return MqttConnect(
            remainingLength = remainingLength,
            protocolName = protocolName,
            protocolLevel = protocolLevel,
            cleanSession = cleanSession,
            willFlag = willFlag,
            willQos = willQos,
            willRetain = willRetain,
            passwordFlag = passwordFlag,
            usernameFlag = usernameFlag,
            keepAliveSeconds = keepAlive,
            clientId = clientId,
            willTopic = willTopic,
            willPayload = willPayload,
            username = username,
            password = password,
        )
    }

    private fun parseConnAck(
        reader: AccumulatingBufferReader,
        remainingLength: Int,
    ): MqttConnAck {
        val ackFlags = reader.readByte().toInt() and 0xFF
        val sessionPresent = (ackFlags and 0x01) != 0
        val returnCode = MqttConnectReturnCode.fromInt(reader.readByte().toInt() and 0xFF)

        return MqttConnAck(
            remainingLength = remainingLength,
            sessionPresent = sessionPresent,
            returnCode = returnCode,
        )
    }

    private fun parsePublish(
        reader: AccumulatingBufferReader,
        remainingLength: Int,
        flags: Int,
    ): MqttPublish {
        val dup = (flags and 0x08) != 0
        val qos = MqttQos.fromInt((flags and 0x06) shr 1)
        val retain = (flags and 0x01) != 0

        var bytesRead = 0

        val topicName = readMqttString(reader)
        bytesRead += 2 + topicName.encodeToByteArray().size

        val packetId =
            if (qos != MqttQos.AtMostOnce) {
                bytesRead += 2
                reader.readShort().toInt() and 0xFFFF
            } else {
                null
            }

        val payloadLength = remainingLength - bytesRead
        val payloadBytes =
            if (payloadLength > 0) {
                reader.readBytes(payloadLength)
            } else {
                ByteArray(0)
            }

        return MqttPublish(
            remainingLength = remainingLength,
            dup = dup,
            qos = qos,
            retain = retain,
            topicName = topicName,
            packetId = packetId,
            payload = createPayload(payloadBytes),
        )
    }

    private fun parsePubAck(
        reader: AccumulatingBufferReader,
        remainingLength: Int,
    ): MqttPubAck {
        val packetId = reader.readShort().toInt() and 0xFFFF
        return MqttPubAck(remainingLength, packetId)
    }

    private fun parsePubRec(
        reader: AccumulatingBufferReader,
        remainingLength: Int,
    ): MqttPubRec {
        val packetId = reader.readShort().toInt() and 0xFFFF
        return MqttPubRec(remainingLength, packetId)
    }

    private fun parsePubRel(
        reader: AccumulatingBufferReader,
        remainingLength: Int,
    ): MqttPubRel {
        val packetId = reader.readShort().toInt() and 0xFFFF
        return MqttPubRel(remainingLength, packetId)
    }

    private fun parsePubComp(
        reader: AccumulatingBufferReader,
        remainingLength: Int,
    ): MqttPubComp {
        val packetId = reader.readShort().toInt() and 0xFFFF
        return MqttPubComp(remainingLength, packetId)
    }

    private fun parseSubscribe(
        reader: AccumulatingBufferReader,
        remainingLength: Int,
    ): MqttSubscribe {
        val packetId = reader.readShort().toInt() and 0xFFFF
        var bytesRead = 2

        val subscriptions = mutableListOf<MqttSubscription>()
        while (bytesRead < remainingLength) {
            val topicFilter = readMqttString(reader)
            bytesRead += 2 + topicFilter.encodeToByteArray().size
            val qos = MqttQos.fromInt(reader.readByte().toInt() and 0x03)
            bytesRead += 1
            subscriptions.add(MqttSubscription(topicFilter, qos))
        }

        return MqttSubscribe(remainingLength, packetId, subscriptions)
    }

    private fun parseSubAck(
        reader: AccumulatingBufferReader,
        remainingLength: Int,
    ): MqttSubAck {
        val packetId = reader.readShort().toInt() and 0xFFFF
        val returnCodes = mutableListOf<MqttSubAckReturnCode>()

        for (i in 2 until remainingLength) {
            val code = reader.readByte().toInt() and 0xFF
            returnCodes.add(MqttSubAckReturnCode.fromInt(code))
        }

        return MqttSubAck(remainingLength, packetId, returnCodes)
    }

    private fun parseUnsubscribe(
        reader: AccumulatingBufferReader,
        remainingLength: Int,
    ): MqttUnsubscribe {
        val packetId = reader.readShort().toInt() and 0xFFFF
        var bytesRead = 2

        val topicFilters = mutableListOf<String>()
        while (bytesRead < remainingLength) {
            val topicFilter = readMqttString(reader)
            bytesRead += 2 + topicFilter.encodeToByteArray().size
            topicFilters.add(topicFilter)
        }

        return MqttUnsubscribe(remainingLength, packetId, topicFilters)
    }

    private fun parseUnsubAck(
        reader: AccumulatingBufferReader,
        remainingLength: Int,
    ): MqttUnsubAck {
        val packetId = reader.readShort().toInt() and 0xFFFF
        return MqttUnsubAck(remainingLength, packetId)
    }

    private fun parseDisconnect(
        reader: AccumulatingBufferReader,
        remainingLength: Int,
    ): MqttDisconnect {
        val reasonCode =
            if (remainingLength > 0) {
                MqttDisconnectReason.fromInt(reader.readByte().toInt() and 0xFF)
            } else {
                null
            }
        return MqttDisconnect(remainingLength, reasonCode)
    }

    private fun createPayload(bytes: ByteArray): MqttPayload =
        if (bytes.isEmpty()) {
            MqttPayload.Empty
        } else if (decompressor != null) {
            MqttPayload.Compressed(bytes, decompressor)
        } else {
            MqttPayload.Raw(bytes)
        }
}

/**
 * Serializes MQTT packets.
 */
class MqttSerializer(
    private val pool: BufferPool,
) {
    /**
     * Serializes an MQTT packet to bytes.
     */
    fun serialize(packet: MqttPacket): ByteArray =
        when (packet) {
            is MqttConnect -> serializeConnect(packet)
            is MqttConnAck -> serializeConnAck(packet)
            is MqttPublish -> serializePublish(packet)
            is MqttPubAck -> serializeSimpleAck(MqttPacketType.PubAck, packet.packetId)
            is MqttPubRec -> serializeSimpleAck(MqttPacketType.PubRec, packet.packetId)
            is MqttPubRel -> serializeSimpleAck(MqttPacketType.PubRel, packet.packetId, 0x02)
            is MqttPubComp -> serializeSimpleAck(MqttPacketType.PubComp, packet.packetId)
            is MqttSubscribe -> serializeSubscribe(packet)
            is MqttSubAck -> serializeSubAck(packet)
            is MqttUnsubscribe -> serializeUnsubscribe(packet)
            is MqttUnsubAck -> serializeSimpleAck(MqttPacketType.UnsubAck, packet.packetId)
            MqttPingReq -> byteArrayOf((MqttPacketType.PingReq.value shl 4).toByte(), 0)
            MqttPingResp -> byteArrayOf((MqttPacketType.PingResp.value shl 4).toByte(), 0)
            is MqttDisconnect -> serializeDisconnect(packet)
        }

    private fun serializeConnect(packet: MqttConnect): ByteArray {
        val buffer = pool.acquire(256 + packet.clientId.length)
        try {
            // Protocol name
            writeMqttString(buffer, packet.protocolName)

            // Protocol level
            buffer.writeByte(packet.protocolLevel.toByte())

            // Connect flags
            var flags = 0
            if (packet.cleanSession) flags = flags or 0x02
            if (packet.willFlag) flags = flags or 0x04
            flags = flags or ((packet.willQos.value and 0x03) shl 3)
            if (packet.willRetain) flags = flags or 0x20
            if (packet.passwordFlag) flags = flags or 0x40
            if (packet.usernameFlag) flags = flags or 0x80
            buffer.writeByte(flags.toByte())

            // Keep alive
            buffer.writeShort(packet.keepAliveSeconds.toShort())

            // Client ID
            writeMqttString(buffer, packet.clientId)

            // Will topic and payload
            if (packet.willFlag && packet.willTopic != null) {
                writeMqttString(buffer, packet.willTopic)
                val willBytes = packet.willPayload?.bytes() ?: ByteArray(0)
                buffer.writeShort(willBytes.size.toShort())
                buffer.writeBytes(willBytes)
            }

            // Username
            if (packet.usernameFlag && packet.username != null) {
                writeMqttString(buffer, packet.username)
            }

            // Password
            if (packet.passwordFlag && packet.password != null) {
                buffer.writeShort(packet.password.size.toShort())
                buffer.writeBytes(packet.password)
            }

            buffer.resetForRead()
            val payload = buffer.readByteArray(buffer.remaining())
            return wrapWithHeader(MqttPacketType.Connect, payload)
        } finally {
            (buffer as PooledBuffer).release()
        }
    }

    private fun serializeConnAck(packet: MqttConnAck): ByteArray {
        val payload =
            byteArrayOf(
                if (packet.sessionPresent) 0x01 else 0x00,
                packet.returnCode.value.toByte(),
            )
        return wrapWithHeader(MqttPacketType.ConnAck, payload)
    }

    private fun serializePublish(packet: MqttPublish): ByteArray {
        val buffer = pool.acquire(256 + packet.payload.length)
        try {
            writeMqttString(buffer, packet.topicName)

            if (packet.qos != MqttQos.AtMostOnce && packet.packetId != null) {
                buffer.writeShort(packet.packetId.toShort())
            }

            buffer.writeBytes(packet.payload.bytes())

            buffer.resetForRead()
            val payload = buffer.readByteArray(buffer.remaining())

            // Build fixed header with flags
            var flags = 0
            if (packet.dup) flags = flags or 0x08
            flags = flags or ((packet.qos.value and 0x03) shl 1)
            if (packet.retain) flags = flags or 0x01

            return wrapWithHeader(MqttPacketType.Publish, payload, flags)
        } finally {
            (buffer as PooledBuffer).release()
        }
    }

    private fun serializeSimpleAck(
        packetType: MqttPacketType,
        packetId: Int,
        flags: Int = 0,
    ): ByteArray {
        val payload =
            byteArrayOf(
                ((packetId shr 8) and 0xFF).toByte(),
                (packetId and 0xFF).toByte(),
            )
        return wrapWithHeader(packetType, payload, flags)
    }

    private fun serializeSubscribe(packet: MqttSubscribe): ByteArray {
        val buffer = pool.acquire(256)
        try {
            buffer.writeShort(packet.packetId.toShort())

            for (sub in packet.subscriptions) {
                writeMqttString(buffer, sub.topicFilter)
                buffer.writeByte(sub.qos.value.toByte())
            }

            buffer.resetForRead()
            val payload = buffer.readByteArray(buffer.remaining())
            return wrapWithHeader(MqttPacketType.Subscribe, payload, 0x02)
        } finally {
            (buffer as PooledBuffer).release()
        }
    }

    private fun serializeSubAck(packet: MqttSubAck): ByteArray {
        val buffer = pool.acquire(256)
        try {
            buffer.writeShort(packet.packetId.toShort())

            for (code in packet.returnCodes) {
                buffer.writeByte(code.value.toByte())
            }

            buffer.resetForRead()
            val payload = buffer.readByteArray(buffer.remaining())
            return wrapWithHeader(MqttPacketType.SubAck, payload)
        } finally {
            (buffer as PooledBuffer).release()
        }
    }

    private fun serializeUnsubscribe(packet: MqttUnsubscribe): ByteArray {
        val buffer = pool.acquire(256)
        try {
            buffer.writeShort(packet.packetId.toShort())

            for (topic in packet.topicFilters) {
                writeMqttString(buffer, topic)
            }

            buffer.resetForRead()
            val payload = buffer.readByteArray(buffer.remaining())
            return wrapWithHeader(MqttPacketType.Unsubscribe, payload, 0x02)
        } finally {
            (buffer as PooledBuffer).release()
        }
    }

    private fun serializeDisconnect(packet: MqttDisconnect): ByteArray {
        val payload =
            if (packet.reasonCode != null) {
                byteArrayOf(packet.reasonCode.value.toByte())
            } else {
                ByteArray(0)
            }
        return wrapWithHeader(MqttPacketType.Disconnect, payload)
    }

    private fun writeMqttString(
        buffer: com.ditchoom.buffer.pool.PooledBuffer,
        value: String,
    ) {
        val bytes = value.encodeToByteArray()
        buffer.writeShort(bytes.size.toShort())
        buffer.writeBytes(bytes)
    }

    private fun wrapWithHeader(
        packetType: MqttPacketType,
        payload: ByteArray,
        flags: Int = 0,
    ): ByteArray {
        val remainingLengthBytes = encodeRemainingLength(payload.size)
        val result = ByteArray(1 + remainingLengthBytes.size + payload.size)

        result[0] = ((packetType.value shl 4) or flags).toByte()
        remainingLengthBytes.copyInto(result, 1)
        payload.copyInto(result, 1 + remainingLengthBytes.size)

        return result
    }

    private fun encodeRemainingLength(length: Int): ByteArray {
        val bytes = mutableListOf<Byte>()
        var x = length

        do {
            var encodedByte = x % 128
            x /= 128
            if (x > 0) {
                encodedByte = encodedByte or 0x80
            }
            bytes.add(encodedByte.toByte())
        } while (x > 0)

        return bytes.toByteArray()
    }
}

/**
 * Exception thrown when MQTT parsing fails.
 */
class MqttParseException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
