package com.ditchoom.buffer.protocol.websocket

/**
 * WebSocket frame model using sealed interfaces for exhaustive matching.
 *
 * Implements RFC 6455 WebSocket protocol frame structure.
 */
sealed interface WebSocketFrame {
    val fin: Boolean
    val rsv1: Boolean
    val rsv2: Boolean
    val rsv3: Boolean
    val masked: Boolean
    val maskingKey: ByteArray?
    val payloadLength: Long
    val payload: WebSocketPayload

    /**
     * Text frame (opcode 0x1).
     */
    data class Text(
        override val fin: Boolean,
        override val rsv1: Boolean,
        override val rsv2: Boolean,
        override val rsv3: Boolean,
        override val masked: Boolean,
        override val maskingKey: ByteArray?,
        override val payloadLength: Long,
        override val payload: WebSocketPayload,
    ) : WebSocketFrame {
        val text: String get() = payload.bytes().decodeToString()

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Text) return false
            return fin == other.fin && payload == other.payload
        }

        override fun hashCode(): Int = 31 * fin.hashCode() + payload.hashCode()
    }

    /**
     * Binary frame (opcode 0x2).
     */
    data class Binary(
        override val fin: Boolean,
        override val rsv1: Boolean,
        override val rsv2: Boolean,
        override val rsv3: Boolean,
        override val masked: Boolean,
        override val maskingKey: ByteArray?,
        override val payloadLength: Long,
        override val payload: WebSocketPayload,
    ) : WebSocketFrame {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Binary) return false
            return fin == other.fin && payload == other.payload
        }

        override fun hashCode(): Int = 31 * fin.hashCode() + payload.hashCode()
    }

    /**
     * Close frame (opcode 0x8).
     */
    data class Close(
        override val fin: Boolean,
        override val rsv1: Boolean,
        override val rsv2: Boolean,
        override val rsv3: Boolean,
        override val masked: Boolean,
        override val maskingKey: ByteArray?,
        override val payloadLength: Long,
        override val payload: WebSocketPayload,
        val closeCode: WebSocketCloseCode,
        val reason: String,
    ) : WebSocketFrame {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Close) return false
            return closeCode == other.closeCode && reason == other.reason
        }

        override fun hashCode(): Int = 31 * closeCode.hashCode() + reason.hashCode()
    }

    /**
     * Ping frame (opcode 0x9).
     */
    data class Ping(
        override val fin: Boolean,
        override val rsv1: Boolean,
        override val rsv2: Boolean,
        override val rsv3: Boolean,
        override val masked: Boolean,
        override val maskingKey: ByteArray?,
        override val payloadLength: Long,
        override val payload: WebSocketPayload,
    ) : WebSocketFrame {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Ping) return false
            return payload == other.payload
        }

        override fun hashCode(): Int = payload.hashCode()
    }

    /**
     * Pong frame (opcode 0xA).
     */
    data class Pong(
        override val fin: Boolean,
        override val rsv1: Boolean,
        override val rsv2: Boolean,
        override val rsv3: Boolean,
        override val masked: Boolean,
        override val maskingKey: ByteArray?,
        override val payloadLength: Long,
        override val payload: WebSocketPayload,
    ) : WebSocketFrame {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Pong) return false
            return payload == other.payload
        }

        override fun hashCode(): Int = payload.hashCode()
    }

    /**
     * Continuation frame (opcode 0x0).
     */
    data class Continuation(
        override val fin: Boolean,
        override val rsv1: Boolean,
        override val rsv2: Boolean,
        override val rsv3: Boolean,
        override val masked: Boolean,
        override val maskingKey: ByteArray?,
        override val payloadLength: Long,
        override val payload: WebSocketPayload,
    ) : WebSocketFrame {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Continuation) return false
            return fin == other.fin && payload == other.payload
        }

        override fun hashCode(): Int = 31 * fin.hashCode() + payload.hashCode()
    }
}

/**
 * WebSocket opcode values.
 */
sealed interface WebSocketOpcode {
    val value: Int

    data object Continuation : WebSocketOpcode {
        override val value = 0x0
    }

    data object Text : WebSocketOpcode {
        override val value = 0x1
    }

    data object Binary : WebSocketOpcode {
        override val value = 0x2
    }

    data object Close : WebSocketOpcode {
        override val value = 0x8
    }

    data object Ping : WebSocketOpcode {
        override val value = 0x9
    }

    data object Pong : WebSocketOpcode {
        override val value = 0xA
    }

    data class Reserved(
        override val value: Int,
    ) : WebSocketOpcode

    companion object {
        fun fromInt(value: Int): WebSocketOpcode =
            when (value) {
                0x0 -> Continuation
                0x1 -> Text
                0x2 -> Binary
                0x8 -> Close
                0x9 -> Ping
                0xA -> Pong
                else -> Reserved(value)
            }
    }
}

/**
 * WebSocket close codes (RFC 6455 Section 7.4).
 */
sealed interface WebSocketCloseCode {
    val code: Int
    val description: String

    data object NormalClosure : WebSocketCloseCode {
        override val code = 1000
        override val description = "Normal Closure"
    }

    data object GoingAway : WebSocketCloseCode {
        override val code = 1001
        override val description = "Going Away"
    }

    data object ProtocolError : WebSocketCloseCode {
        override val code = 1002
        override val description = "Protocol Error"
    }

    data object UnsupportedData : WebSocketCloseCode {
        override val code = 1003
        override val description = "Unsupported Data"
    }

    data object NoStatusReceived : WebSocketCloseCode {
        override val code = 1005
        override val description = "No Status Received"
    }

    data object AbnormalClosure : WebSocketCloseCode {
        override val code = 1006
        override val description = "Abnormal Closure"
    }

    data object InvalidPayload : WebSocketCloseCode {
        override val code = 1007
        override val description = "Invalid Payload Data"
    }

    data object PolicyViolation : WebSocketCloseCode {
        override val code = 1008
        override val description = "Policy Violation"
    }

    data object MessageTooBig : WebSocketCloseCode {
        override val code = 1009
        override val description = "Message Too Big"
    }

    data object MandatoryExtension : WebSocketCloseCode {
        override val code = 1010
        override val description = "Mandatory Extension"
    }

    data object InternalError : WebSocketCloseCode {
        override val code = 1011
        override val description = "Internal Error"
    }

    data object ServiceRestart : WebSocketCloseCode {
        override val code = 1012
        override val description = "Service Restart"
    }

    data object TryAgainLater : WebSocketCloseCode {
        override val code = 1013
        override val description = "Try Again Later"
    }

    data object TLSHandshake : WebSocketCloseCode {
        override val code = 1015
        override val description = "TLS Handshake Failure"
    }

    data class Custom(
        override val code: Int,
    ) : WebSocketCloseCode {
        override val description = "Custom ($code)"
    }

    companion object {
        fun fromInt(code: Int): WebSocketCloseCode =
            when (code) {
                1000 -> NormalClosure
                1001 -> GoingAway
                1002 -> ProtocolError
                1003 -> UnsupportedData
                1005 -> NoStatusReceived
                1006 -> AbnormalClosure
                1007 -> InvalidPayload
                1008 -> PolicyViolation
                1009 -> MessageTooBig
                1010 -> MandatoryExtension
                1011 -> InternalError
                1012 -> ServiceRestart
                1013 -> TryAgainLater
                1015 -> TLSHandshake
                else -> Custom(code)
            }
    }
}

/**
 * WebSocket payload with lazy evaluation and compression support.
 */
sealed interface WebSocketPayload {
    fun bytes(): ByteArray

    val length: Long
    val isCompressed: Boolean

    data object Empty : WebSocketPayload {
        override fun bytes() = ByteArray(0)

        override val length = 0L
        override val isCompressed = false
    }

    data class Raw(
        private val data: ByteArray,
        override val isCompressed: Boolean = false,
    ) : WebSocketPayload {
        override fun bytes() = data

        override val length = data.size.toLong()

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Raw) return false
            return data.contentEquals(other.data)
        }

        override fun hashCode() = data.contentHashCode()
    }

    /**
     * Per-message deflate compressed payload.
     */
    data class Compressed(
        private val compressedData: ByteArray,
        private val decompressor: (ByteArray) -> ByteArray,
    ) : WebSocketPayload {
        override val isCompressed = true
        override val length = compressedData.size.toLong()

        private var decompressedCache: ByteArray? = null

        override fun bytes(): ByteArray =
            decompressedCache ?: decompressor(compressedData).also {
                decompressedCache = it
            }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Compressed) return false
            return compressedData.contentEquals(other.compressedData)
        }

        override fun hashCode() = compressedData.contentHashCode()
    }
}
