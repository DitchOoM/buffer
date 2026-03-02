package com.ditchoom.buffer.codec.test.protocols

import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import kotlin.jvm.JvmInline

@JvmInline
value class DnsFlags(
    val raw: UShort,
) {
    val qr: Boolean get() = (raw.toInt() shr 15) and 1 == 1
    val opcode: Int get() = (raw.toInt() shr 11) and 0xF
    val aa: Boolean get() = (raw.toInt() shr 10) and 1 == 1
    val tc: Boolean get() = (raw.toInt() shr 9) and 1 == 1
    val rd: Boolean get() = (raw.toInt() shr 8) and 1 == 1
    val ra: Boolean get() = (raw.toInt() shr 7) and 1 == 1
    val rcode: Int get() = raw.toInt() and 0xF
}

@ProtocolMessage
data class DnsHeader(
    val id: UShort,
    val flags: DnsFlags,
    val qdCount: UShort,
    val anCount: UShort,
    val nsCount: UShort,
    val arCount: UShort,
)
