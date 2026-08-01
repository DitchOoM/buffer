package com.ditchoom.buffer.flow

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The runtime witness for the §1 unboxing guarantee: [HopLimit] and [LocalAddress] are `@JvmInline`
 * value classes stored as bare `Int` / bare reference fields of [Datagram], so a received datagram
 * with *known* control-plane values costs exactly the same single allocation as one built from the
 * all-Unknown defaults. If either value class boxed (nullable position, generic position, or a
 * non-flattened field), the known-values loop would allocate a wrapper per datagram and the
 * per-iteration delta below would blow past the slack.
 *
 * Mirrors [SocketAddressAllocationTest]'s HotSpot `ThreadMXBean` methodology.
 */
class DatagramValueClassAllocationTest {
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
    fun knownControlPlaneValuesAddNoAllocationOverTheDatagramItself() {
        if (!bean.isThreadAllocatedMemorySupported) return // HotSpot only; skip elsewhere.
        bean.isThreadAllocatedMemoryEnabled = true

        val n = 1000
        val sharedPayload: PlatformBuffer = BufferFactory.Default.allocate(1)
        val peer = SocketAddress.ofLiteral("10.0.0.1", 1111)
        val local = SocketAddress.ofLiteral("10.0.0.2", 2222)

        var blackhole = 0L

        // N datagrams with fully-known control plane: HopLimit.of + LocalAddress.of + Ecn.Ce.
        // The hop limit varies per iteration so nothing can be constant-folded away.
        val knownLoop: () -> Unit = {
            for (i in 0 until n) {
                val d =
                    Datagram(
                        payload = sharedPayload,
                        peer = peer,
                        ecn = Ecn.Ce,
                        localAddress = LocalAddress.of(local),
                        hopLimit = HopLimit.of(i % 256),
                    )
                blackhole += d.hopLimit.value.toLong()
                if (d.localAddress.isKnown) blackhole++
            }
        }

        // N datagrams with the all-Unknown control plane — the baseline single-allocation cost.
        // All five arguments are EXPLICIT, per the hot-path discipline in Datagram's KDoc: leaning on
        // the defaults routes construction through the default-arguments bridge, which transiently
        // boxes LocalAddress (~16 bytes/datagram whenever escape analysis doesn't elide it) — this
        // test caught exactly that when it used `Datagram(payload, peer)` here.
        val unknownLoop: () -> Unit = {
            for (i in 0 until n) {
                val d =
                    Datagram(
                        payload = sharedPayload,
                        peer = peer,
                        ecn = Ecn.Unknown,
                        localAddress = LocalAddress.Unknown,
                        hopLimit = HopLimit.Unknown,
                    )
                if (d.hopLimit.isKnown) blackhole++
                if (d.localAddress.isKnown) blackhole++
            }
        }

        // Warm up both paths (JIT, class init, first ThreadMXBean call).
        repeat(20) {
            knownLoop()
            unknownLoop()
        }

        val knownBytes = minAllocatedBytes(10, knownLoop)
        val unknownBytes = minAllocatedBytes(10, unknownLoop)

        // Keep blackhole observable so the loops can't be optimized away.
        assertTrue(blackhole != Long.MIN_VALUE)

        // The value classes must add ~zero allocation over the Datagram instance itself: the delta
        // between known-values and all-Unknown loops stays within measurement slack, nowhere near
        // the >= 16 bytes/instance a boxed HopLimit or LocalAddress would cost across N datagrams.
        val delta = abs(knownBytes - unknownBytes)
        assertTrue(
            delta < 1024,
            "known-control-plane datagrams must allocate the same as all-Unknown ones " +
                "(known=$knownBytes, unknown=$unknownBytes, delta=$delta over $n datagrams)",
        )
    }
}
