package com.ditchoom.buffer.codec.test

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.codec.test.protocols.DnsFlags
import com.ditchoom.buffer.codec.test.protocols.DnsHeader
import com.ditchoom.buffer.codec.test.protocols.DnsHeaderCodec
import kotlin.test.Test
import kotlin.test.assertEquals

class DnsHeaderRoundTripTest {
    @Test
    fun `round trip dns header`() {
        val original =
            DnsHeader(
                id = 0x1234u,
                flags = DnsFlags(0x8180u), // standard query response, recursion available
                qdCount = 1u,
                anCount = 2u,
                nsCount = 0u,
                arCount = 0u,
            )
        val buffer = BufferFactory.Default.allocate(64)
        DnsHeaderCodec.encode(buffer, original)
        buffer.resetForRead()
        val decoded = DnsHeaderCodec.decode(buffer)
        assertEquals(original, decoded)
    }

    @Test
    fun `flags extraction works`() {
        val flags = DnsFlags(0x8180u)
        assertEquals(true, flags.qr)
        assertEquals(0, flags.opcode)
        assertEquals(false, flags.aa)
        assertEquals(false, flags.tc)
        assertEquals(true, flags.rd)
        assertEquals(true, flags.ra)
        assertEquals(0, flags.rcode)
    }

    @Test
    fun `round trip all zeros`() {
        val original = DnsHeader(0u, DnsFlags(0u), 0u, 0u, 0u, 0u)
        val buffer = BufferFactory.Default.allocate(64)
        DnsHeaderCodec.encode(buffer, original)
        buffer.resetForRead()
        val decoded = DnsHeaderCodec.decode(buffer)
        assertEquals(original, decoded)
    }

    @Test
    fun `round trip all max`() {
        val original =
            DnsHeader(
                UShort.MAX_VALUE,
                DnsFlags(UShort.MAX_VALUE),
                UShort.MAX_VALUE,
                UShort.MAX_VALUE,
                UShort.MAX_VALUE,
                UShort.MAX_VALUE,
            )
        val buffer = BufferFactory.Default.allocate(64)
        DnsHeaderCodec.encode(buffer, original)
        buffer.resetForRead()
        val decoded = DnsHeaderCodec.decode(buffer)
        assertEquals(original, decoded)
    }
}
