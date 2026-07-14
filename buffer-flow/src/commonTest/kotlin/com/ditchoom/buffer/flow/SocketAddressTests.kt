package com.ditchoom.buffer.flow

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The address-model contract (§4 / decision §10.1): [SocketAddress] is resolved-only, inspectable,
 * and has value semantics so it can key a demux routing table. The zero-alloc-many-dest property
 * itself is proven in the JVM allocation test (`SocketAddressAllocationTest`), which can measure
 * bytes allocated; here we pin the observable behavior.
 */
@OptIn(ExperimentalDatagramApi::class)
class SocketAddressTests {
    @Test
    fun ofLiteralParsesIpv4() {
        val a = SocketAddress.ofLiteral("192.168.1.10", 4433)
        assertEquals("192.168.1.10", a.host)
        assertEquals(4433, a.port)
        assertEquals(AddressFamily.IPv4, a.family)
    }

    @Test
    fun ofLiteralParsesIpv6() {
        val a = SocketAddress.ofLiteral("::1", 443)
        assertEquals(443, a.port)
        assertEquals(AddressFamily.IPv6, a.family)
    }

    @Test
    fun ofLiteralParsesFullAndCompressedIpv6Equal() {
        val compressed = SocketAddress.ofLiteral("::1", 443)
        val full = SocketAddress.ofLiteral("0:0:0:0:0:0:0:1", 443)
        assertEquals(compressed, full)
        assertEquals(compressed.hashCode(), full.hashCode())
    }

    @Test
    fun ofLiteralParsesEmbeddedV4Ipv6() {
        val mapped = SocketAddress.ofLiteral("::ffff:1.2.3.4", 80)
        assertEquals(AddressFamily.IPv6, mapped.family)
        // ::ffff:0102:0304 — same 16 bytes whichever spelling.
        assertEquals(SocketAddress.ofLiteral("::ffff:102:304", 80), mapped)
    }

    @Test
    fun equalAddressesShareHashForMapKeying() {
        val x = SocketAddress.ofLiteral("10.0.0.1", 9000)
        val y = SocketAddress.ofLiteral("10.0.0.1", 9000)
        assertEquals(x, y)
        assertEquals(x.hashCode(), y.hashCode())
        val map = HashMap<SocketAddress, String>()
        map[x] = "peer"
        assertEquals("peer", map[y])
    }

    @Test
    fun differentPortsAreDistinct() {
        assertNotEquals(
            SocketAddress.ofLiteral("10.0.0.1", 9000),
            SocketAddress.ofLiteral("10.0.0.1", 9001),
        )
    }

    @Test
    fun invalidLiteralsThrow() {
        assertFailsWith<IllegalArgumentException> { SocketAddress.ofLiteral("not-an-ip", 80) }
        assertFailsWith<IllegalArgumentException> { SocketAddress.ofLiteral("256.1.1.1", 80) }
        assertFailsWith<IllegalArgumentException> { SocketAddress.ofLiteral("1.2.3", 80) }
        assertFailsWith<IllegalArgumentException> { SocketAddress.ofLiteral("::1::2", 80) }
    }

    @Test
    fun portRangeIsValidated() {
        assertFailsWith<IllegalArgumentException> { SocketAddress.ofLiteral("1.2.3.4", -1) }
        assertFailsWith<IllegalArgumentException> { SocketAddress.ofLiteral("1.2.3.4", 65536) }
    }

    @Test
    fun resolveHandlesLiteralsSynchronously() =
        runTest {
            val a = SocketAddress.resolve("127.0.0.1", 53)
            assertEquals(AddressFamily.IPv4, a.family)
            assertEquals(53, a.port)
        }

    @Test
    fun resolveRejectsHostnamesWithoutInstalledResolver() =
        runTest {
            // buffer-flow performs no I/O; hostname DNS arrives with :socket-udp.
            assertFailsWith<UnsupportedOperationException> {
                SocketAddress.resolve("example.com", 443)
            }
        }

    @Test
    fun toStringBracketsIpv6() {
        assertTrue(SocketAddress.ofLiteral("::1", 443).toString().startsWith("["))
        assertEquals("1.2.3.4:80", SocketAddress.ofLiteral("1.2.3.4", 80).toString())
    }
}
