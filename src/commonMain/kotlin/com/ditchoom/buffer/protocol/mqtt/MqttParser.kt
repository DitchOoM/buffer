package com.ditchoom.buffer.protocol.mqtt

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.allocate
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.AccumulatingBufferReader
import com.ditchoom.buffer.stream.BufferChunk
import com.ditchoom.buffer.stream.BufferStream

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
    private val decompressor: ((ReadBuffer) -> ReadBuffer)? = null,
) {
    /**
     * Parses MQTT packets from a buffer stream.
     */
    fun parsePackets(
        stream: BufferStream,
        handler: (MqttPacket) -> Unit,
    ) {
        val reader = AccumulatingBufferReader(pool)

        try {
            stream.forEachChunk { chunk ->
                reader.append(chunk)

                while (reader.available() >= 2) {
                    val packet = tryParsePacket(reader)
                    if (packet != null) {
                        handler(packet)
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
     * Parses a single packet from a ReadBuffer.
     */
    fun parsePacket(buffer: ReadBuffer): MqttPacket {
        val reader = AccumulatingBufferReader(pool)
        try {
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
        val fixedHeader = reader.peekByte(0).toInt() and 0xFF
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

            val encodedByte = reader.peekByte(startOffset + bytesRead).toInt() and 0xFF
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
        if (length == 0) return ""
        val buffer = reader.readBuffer(length)
        return buffer.readString(length)
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
                if (payloadLength > 0) {
                    val payloadBuffer = reader.readBuffer(payloadLength)
                    createPayload(payloadBuffer, payloadLength)
                } else {
                    MqttPayload.Empty
                }
            } else {
                null
            }

        val username = if (usernameFlag) readMqttString(reader) else null
        val password =
            if (passwordFlag) {
                val pwdLength = reader.readShort().toInt() and 0xFFFF
                if (pwdLength > 0) reader.readBuffer(pwdLength) else null
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

        // Read topic name length first
        val topicLength = reader.readShort().toInt() and 0xFFFF
        var bytesRead = 2

        val topicName =
            if (topicLength > 0) {
                val topicBuffer = reader.readBuffer(topicLength)
                bytesRead += topicLength
                topicBuffer.readString(topicLength)
            } else {
                ""
            }

        val packetId =
            if (qos != MqttQos.AtMostOnce) {
                bytesRead += 2
                reader.readShort().toInt() and 0xFFFF
            } else {
                null
            }

        val payloadLength = remainingLength - bytesRead
        val payload =
            if (payloadLength > 0) {
                val payloadBuffer = reader.readBuffer(payloadLength)
                createPayload(payloadBuffer, payloadLength)
            } else {
                MqttPayload.Empty
            }

        return MqttPublish(
            remainingLength = remainingLength,
            dup = dup,
            qos = qos,
            retain = retain,
            topicName = topicName,
            packetId = packetId,
            payload = payload,
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
            val filterLength = reader.readShort().toInt() and 0xFFFF
            bytesRead += 2
            val topicFilter =
                if (filterLength > 0) {
                    val filterBuffer = reader.readBuffer(filterLength)
                    bytesRead += filterLength
                    filterBuffer.readString(filterLength)
                } else {
                    ""
                }
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
            val filterLength = reader.readShort().toInt() and 0xFFFF
            bytesRead += 2
            val topicFilter =
                if (filterLength > 0) {
                    val filterBuffer = reader.readBuffer(filterLength)
                    bytesRead += filterLength
                    filterBuffer.readString(filterLength)
                } else {
                    ""
                }
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

    private fun createPayload(
        buffer: ReadBuffer,
        length: Int,
    ): MqttPayload =
        if (length == 0) {
            MqttPayload.Empty
        } else if (decompressor != null) {
            MqttPayload.Compressed(buffer, decompressor)
        } else {
            MqttPayload.Buffered(buffer, length)
        }
}

/**
 * Serializes MQTT packets to buffers.
 */
class MqttSerializer(
    private val pool: BufferPool,
) {
    /**
     * Serializes an MQTT packet to a WriteBuffer.
     * Returns the number of bytes written.
     */
    fun serializeTo(
        packet: MqttPacket,
        buffer: WriteBuffer,
    ): Int =
        when (packet) {
            is MqttConnect -> serializeConnect(packet, buffer)
            is MqttConnAck -> serializeConnAck(packet, buffer)
            is MqttPublish -> serializePublish(packet, buffer)
            is MqttPubAck -> serializeSimpleAck(MqttPacketType.PubAck, packet.packetId, buffer)
            is MqttPubRec -> serializeSimpleAck(MqttPacketType.PubRec, packet.packetId, buffer)
            is MqttPubRel -> serializeSimpleAck(MqttPacketType.PubRel, packet.packetId, buffer, 0x02)
            is MqttPubComp -> serializeSimpleAck(MqttPacketType.PubComp, packet.packetId, buffer)
            is MqttSubscribe -> serializeSubscribe(packet, buffer)
            is MqttSubAck -> serializeSubAck(packet, buffer)
            is MqttUnsubscribe -> serializeUnsubscribe(packet, buffer)
            is MqttUnsubAck -> serializeSimpleAck(MqttPacketType.UnsubAck, packet.packetId, buffer)
            MqttPingReq -> serializePing(MqttPacketType.PingReq, buffer)
            MqttPingResp -> serializePing(MqttPacketType.PingResp, buffer)
            is MqttDisconnect -> serializeDisconnect(packet, buffer)
        }

    /**
     * Calculates the size needed to serialize an MQTT packet.
     */
    fun calculateSize(packet: MqttPacket): Int =
        when (packet) {
            is MqttConnect -> calculateConnectSize(packet)
            is MqttConnAck -> 4 // Fixed header (2) + ack flags (1) + return code (1)
            is MqttPublish -> calculatePublishSize(packet)
            is MqttPubAck, is MqttPubRec, is MqttPubRel, is MqttPubComp, is MqttUnsubAck -> 4
            is MqttSubscribe -> calculateSubscribeSize(packet)
            is MqttSubAck -> 2 + 2 + packet.returnCodes.size // header + packetId + codes
            is MqttUnsubscribe -> calculateUnsubscribeSize(packet)
            MqttPingReq, MqttPingResp -> 2
            is MqttDisconnect -> if (packet.reasonCode != null) 3 else 2
        }

    private fun calculateConnectSize(packet: MqttConnect): Int {
        var size = 2 // Fixed header + remaining length (min 1 byte)
        size += 2 + packet.protocolName.length // Protocol name
        size += 1 + 1 + 2 // Protocol level + flags + keep alive
        size += 2 + packet.clientId.length // Client ID
        if (packet.willFlag && packet.willTopic != null) {
            size += 2 + packet.willTopic.length
            size += 2 + (packet.willPayload?.length ?: 0)
        }
        if (packet.usernameFlag && packet.username != null) {
            size += 2 + packet.username.length
        }
        if (packet.passwordFlag && packet.password != null) {
            size += 2 + packet.password.remaining()
        }
        return size + calculateRemainingLengthBytes(size - 2)
    }

    private fun calculatePublishSize(packet: MqttPublish): Int {
        var varHeaderSize = 2 + packet.topicName.length // Topic name
        if (packet.qos != MqttQos.AtMostOnce) varHeaderSize += 2 // Packet ID
        varHeaderSize += packet.payload.length
        return 1 + calculateRemainingLengthBytes(varHeaderSize) + varHeaderSize
    }

    private fun calculateSubscribeSize(packet: MqttSubscribe): Int {
        var size = 2 // Packet ID
        for (sub in packet.subscriptions) {
            size += 2 + sub.topicFilter.length + 1 // Length + filter + QoS
        }
        return 1 + calculateRemainingLengthBytes(size) + size
    }

    private fun calculateUnsubscribeSize(packet: MqttUnsubscribe): Int {
        var size = 2 // Packet ID
        for (topic in packet.topicFilters) {
            size += 2 + topic.length
        }
        return 1 + calculateRemainingLengthBytes(size) + size
    }

    private fun calculateRemainingLengthBytes(length: Int): Int {
        var x = length
        var count = 0
        do {
            x /= 128
            count++
        } while (x > 0)
        return count
    }

    private fun serializeConnect(
        packet: MqttConnect,
        buffer: WriteBuffer,
    ): Int {
        val startPos = buffer.position()

        // Calculate variable header + payload size
        val varBuffer = pool.acquire(256 + packet.clientId.length * 4)
        try {
            // Protocol name
            writeMqttString(varBuffer, packet.protocolName)

            // Protocol level
            varBuffer.writeByte(packet.protocolLevel.toByte())

            // Connect flags
            var flags = 0
            if (packet.cleanSession) flags = flags or 0x02
            if (packet.willFlag) flags = flags or 0x04
            flags = flags or ((packet.willQos.value and 0x03) shl 3)
            if (packet.willRetain) flags = flags or 0x20
            if (packet.passwordFlag) flags = flags or 0x40
            if (packet.usernameFlag) flags = flags or 0x80
            varBuffer.writeByte(flags.toByte())

            // Keep alive
            varBuffer.writeShort(packet.keepAliveSeconds.toShort())

            // Client ID
            writeMqttString(varBuffer, packet.clientId)

            // Will topic and payload
            if (packet.willFlag && packet.willTopic != null) {
                writeMqttString(varBuffer, packet.willTopic)
                val willPayload = packet.willPayload
                if (willPayload != null && willPayload.length > 0) {
                    varBuffer.writeShort(willPayload.length.toShort())
                    varBuffer.write(willPayload.asBuffer())
                } else {
                    varBuffer.writeShort(0)
                }
            }

            // Username
            if (packet.usernameFlag && packet.username != null) {
                writeMqttString(varBuffer, packet.username)
            }

            // Password
            if (packet.passwordFlag && packet.password != null) {
                varBuffer.writeShort(packet.password.remaining().toShort())
                varBuffer.write(packet.password)
            }

            varBuffer.resetForRead()
            val remainingLength = varBuffer.remaining()

            // Write fixed header
            buffer.writeByte((MqttPacketType.Connect.value shl 4).toByte())
            writeRemainingLength(buffer, remainingLength)

            // Write variable header + payload
            buffer.write(varBuffer)

            return buffer.position() - startPos
        } finally {
            (varBuffer as com.ditchoom.buffer.pool.PooledBuffer).release()
        }
    }

    private fun serializeConnAck(
        packet: MqttConnAck,
        buffer: WriteBuffer,
    ): Int {
        val startPos = buffer.position()

        buffer.writeByte((MqttPacketType.ConnAck.value shl 4).toByte())
        buffer.writeByte(2) // Remaining length
        buffer.writeByte(if (packet.sessionPresent) 0x01 else 0x00)
        buffer.writeByte(packet.returnCode.value.toByte())

        return buffer.position() - startPos
    }

    private fun serializePublish(
        packet: MqttPublish,
        buffer: WriteBuffer,
    ): Int {
        val startPos = buffer.position()

        val varBuffer = pool.acquire(256 + packet.payload.length)
        try {
            writeMqttString(varBuffer, packet.topicName)

            if (packet.qos != MqttQos.AtMostOnce && packet.packetId != null) {
                varBuffer.writeShort(packet.packetId.toShort())
            }

            if (packet.payload.length > 0) {
                varBuffer.write(packet.payload.asBuffer())
            }

            varBuffer.resetForRead()
            val remainingLength = varBuffer.remaining()

            // Build fixed header with flags
            var flags = 0
            if (packet.dup) flags = flags or 0x08
            flags = flags or ((packet.qos.value and 0x03) shl 1)
            if (packet.retain) flags = flags or 0x01

            buffer.writeByte(((MqttPacketType.Publish.value shl 4) or flags).toByte())
            writeRemainingLength(buffer, remainingLength)
            buffer.write(varBuffer)

            return buffer.position() - startPos
        } finally {
            (varBuffer as com.ditchoom.buffer.pool.PooledBuffer).release()
        }
    }

    private fun serializeSimpleAck(
        packetType: MqttPacketType,
        packetId: Int,
        buffer: WriteBuffer,
        flags: Int = 0,
    ): Int {
        val startPos = buffer.position()

        buffer.writeByte(((packetType.value shl 4) or flags).toByte())
        buffer.writeByte(2) // Remaining length
        buffer.writeByte(((packetId shr 8) and 0xFF).toByte())
        buffer.writeByte((packetId and 0xFF).toByte())

        return buffer.position() - startPos
    }

    private fun serializePing(
        packetType: MqttPacketType,
        buffer: WriteBuffer,
    ): Int {
        val startPos = buffer.position()

        buffer.writeByte((packetType.value shl 4).toByte())
        buffer.writeByte(0) // Remaining length

        return buffer.position() - startPos
    }

    private fun serializeSubscribe(
        packet: MqttSubscribe,
        buffer: WriteBuffer,
    ): Int {
        val startPos = buffer.position()

        val varBuffer = pool.acquire(256)
        try {
            varBuffer.writeShort(packet.packetId.toShort())

            for (sub in packet.subscriptions) {
                writeMqttString(varBuffer, sub.topicFilter)
                varBuffer.writeByte(sub.qos.value.toByte())
            }

            varBuffer.resetForRead()
            val remainingLength = varBuffer.remaining()

            buffer.writeByte(((MqttPacketType.Subscribe.value shl 4) or 0x02).toByte())
            writeRemainingLength(buffer, remainingLength)
            buffer.write(varBuffer)

            return buffer.position() - startPos
        } finally {
            (varBuffer as com.ditchoom.buffer.pool.PooledBuffer).release()
        }
    }

    private fun serializeSubAck(
        packet: MqttSubAck,
        buffer: WriteBuffer,
    ): Int {
        val startPos = buffer.position()

        val remainingLength = 2 + packet.returnCodes.size

        buffer.writeByte((MqttPacketType.SubAck.value shl 4).toByte())
        writeRemainingLength(buffer, remainingLength)
        buffer.writeShort(packet.packetId.toShort())

        for (code in packet.returnCodes) {
            buffer.writeByte(code.value.toByte())
        }

        return buffer.position() - startPos
    }

    private fun serializeUnsubscribe(
        packet: MqttUnsubscribe,
        buffer: WriteBuffer,
    ): Int {
        val startPos = buffer.position()

        val varBuffer = pool.acquire(256)
        try {
            varBuffer.writeShort(packet.packetId.toShort())

            for (topic in packet.topicFilters) {
                writeMqttString(varBuffer, topic)
            }

            varBuffer.resetForRead()
            val remainingLength = varBuffer.remaining()

            buffer.writeByte(((MqttPacketType.Unsubscribe.value shl 4) or 0x02).toByte())
            writeRemainingLength(buffer, remainingLength)
            buffer.write(varBuffer)

            return buffer.position() - startPos
        } finally {
            (varBuffer as com.ditchoom.buffer.pool.PooledBuffer).release()
        }
    }

    private fun serializeDisconnect(
        packet: MqttDisconnect,
        buffer: WriteBuffer,
    ): Int {
        val startPos = buffer.position()

        buffer.writeByte((MqttPacketType.Disconnect.value shl 4).toByte())
        if (packet.reasonCode != null) {
            buffer.writeByte(1) // Remaining length
            buffer.writeByte(packet.reasonCode.value.toByte())
        } else {
            buffer.writeByte(0) // Remaining length
        }

        return buffer.position() - startPos
    }

    private fun writeMqttString(
        buffer: WriteBuffer,
        value: String,
    ) {
        val strBuffer = PlatformBuffer.allocate(value.length * 4)
        strBuffer.writeString(value)
        strBuffer.resetForRead()
        val length = strBuffer.remaining()
        buffer.writeShort(length.toShort())
        buffer.write(strBuffer)
    }

    private fun writeRemainingLength(
        buffer: WriteBuffer,
        length: Int,
    ) {
        var x = length
        do {
            var encodedByte = x % 128
            x /= 128
            if (x > 0) {
                encodedByte = encodedByte or 0x80
            }
            buffer.writeByte(encodedByte.toByte())
        } while (x > 0)
    }
}

/**
 * Exception thrown when MQTT parsing fails.
 */
class MqttParseException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
