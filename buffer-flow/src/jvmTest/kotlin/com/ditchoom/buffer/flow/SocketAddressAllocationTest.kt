package com.ditchoom.buffer.flow

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The **make-or-break** (§4): sending to MANY distinct destinations must be zero-alloc.
 *
 * `send(payload, to)` on a relay fanning out to N peers must not allocate per packet. The design's
 * answer is that a [SocketAddress] *owns* its resolved platform representation from construction — on
 * JVM, an interned `InetSocketAddress` — so a platform sink extracts the send target with a field
 * read, never a resolve-and-pin. This is exactly what deletes the `PathKey → InetSocketAddress`
 * reconstruction the current quiche `NioUdpChannel` 1-entry `lastDest` cache exists to amortize.
 *
 * This test measures per-thread bytes allocated (HotSpot `ThreadMXBean`) while extracting the send
 * target across 1000 DISTINCT destinations, two ways:
 *  - **owned** (the design): read the interned `InetSocketAddress` off each [SocketAddress] — must be
 *    essentially zero regardless of destination count.
 *  - **reconstructed** (today's cache-defeating path): rebuild `InetSocketAddress` from packed address
 *    bits every time, as a relay with a 1-entry cache is forced to — allocates real memory per packet.
 *
 * If the owned path does NOT hold zero-alloc, the address model — and the whole substrate design —
 * re-opens.
 */
class SocketAddressAllocationTest {
    private val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean

    private inline fun allocatedBytes(block: () -> Unit): Long {
        val tid = Thread.currentThread().threadId()
        val before = bean.getThreadAllocatedBytes(tid)
        block()
        val after = bean.getThreadAllocatedBytes(tid)
        return after - before
    }

    /** Take the minimum over several trials to squeeze out measurement/JIT noise. */
    private inline fun minAllocatedBytes(
        trials: Int,
        block: () -> Unit,
    ): Long {
        var min = Long.MAX_VALUE
        repeat(trials) {
            val b = allocatedBytes(block)
            if (b < min) min = b
        }
        return min
    }

    @OptIn(ExperimentalDatagramApi::class)
    @Test
    fun sendToManyDistinctDestinationsIsZeroAlloc() {
        if (!bean.isThreadAllocatedMemorySupported) return // HotSpot only; skip elsewhere.
        bean.isThreadAllocatedMemoryEnabled = true

        val n = 1000

        // Pre-resolve N distinct destinations ONCE (out of band, per §10.1). Each owns its interned
        // InetSocketAddress. A numeric-literal InetSocketAddress performs no DNS lookup.
        val destinations =
            Array(n) { i ->
                InternedJvmSocketAddress(
                    InetSocketAddress(
                        InetAddress.getByAddress(byteArrayOf(10, (i / 256).toByte(), (i % 256).toByte(), 1)),
                        4000 + (i % 1000),
                    ),
                ) as SocketAddress
            }

        // The packed (family/hi/lo)-style bits a demux table would carry, forcing reconstruction.
        val packedPort = IntArray(n) { 4000 + (it % 1000) }
        val packedIp = Array(n) { i -> byteArrayOf(10, (i / 256).toByte(), (i % 256).toByte(), 1) }

        var blackhole = 0L

        // The design's send-target extraction: a field read off the owned address.
        val ownedExtract: () -> Unit = {
            for (i in 0 until n) {
                val inet = (destinations[i] as InternedJvmSocketAddress).inet
                blackhole += inet.port.toLong()
            }
        }

        // The reconstruction a 1-entry cache is defeated into on every distinct destination.
        val reconstructExtract: () -> Unit = {
            for (i in 0 until n) {
                val inet = InetSocketAddress(InetAddress.getByAddress(packedIp[i]), packedPort[i])
                blackhole += inet.port.toLong()
            }
        }

        // Warm up both paths (JIT, class init, first ThreadMXBean call).
        repeat(20) {
            ownedExtract()
            reconstructExtract()
        }

        val ownedBytes = minAllocatedBytes(10, ownedExtract)
        val reconstructBytes = minAllocatedBytes(10, reconstructExtract)

        // Keep blackhole observable so the loops can't be optimized away.
        assertTrue(blackhole != Long.MIN_VALUE)

        // 1. The owned path is essentially zero-alloc for 1000 distinct destinations. Allow a tiny
        //    absolute slack for measurement jitter; the design demands O(1), not O(n).
        assertTrue(
            ownedBytes < 1024,
            "owned send-target extraction must be ~zero-alloc across $n distinct destinations, " +
                "measured $ownedBytes bytes",
        )

        // 2. The reconstruction path allocates real memory per destination — proving the owned model
        //    is what buys the zero-alloc, not the measurement being blind.
        assertTrue(
            reconstructBytes > n.toLong() * 16,
            "reconstruction should allocate per-destination (sanity floor); measured $reconstructBytes bytes",
        )

        // 3. The owned path allocates at least 50x less than reconstruction — the make-or-break margin.
        assertTrue(
            ownedBytes * 50 < reconstructBytes,
            "owned=$ownedBytes must be <1/50th of reconstruct=$reconstructBytes",
        )
    }
}

/**
 * A JVM [SocketAddress] that owns an interned `InetSocketAddress` — the platform-representation-owning
 * shape `:socket-udp`'s JVM actual will use (and quiche reads via a platform SPI). Constructed once;
 * reuse as a send target is a field read.
 */
@OptIn(ExperimentalDatagramApi::class)
private class InternedJvmSocketAddress(
    val inet: InetSocketAddress,
) : SocketAddress {
    override val host: String get() = inet.address.hostAddress
    override val port: Int get() = inet.port
    override val family: AddressFamily
        get() = if (inet.address is java.net.Inet6Address) AddressFamily.IPv6 else AddressFamily.IPv4
}
