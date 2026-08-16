package com.ditchoom.buffer.flow

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ExperimentalFanoutApi
import com.ditchoom.buffer.pool.SharedBytes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Integration of the two halves that were built (and unit-tested) apart: [OutboundWriter]'s
 * exactly-once accounting driving real [SharedBytes] reference releases. The contract under test
 * is single-sentence: a reference transferred via [OutboundWriter.sendShared] is released exactly
 * once on WHICHEVER path the element takes — written, refused, evicted, or discarded at abort.
 *
 * Release-exactly-once is probed through [SharedBytes]' own strictness: a missed release leaves
 * `retain()` succeeding after the creator's close; a double release makes the creator's close
 * throw. No test-only counters — the accounting under test is the accounting doing the asserting.
 */
@OptIn(ExperimentalFanoutApi::class, ExperimentalCoroutinesApi::class)
class OutboundWriterSharedBytesTests {
    private val marker = 0x5EED_BEEF.toInt()

    private fun encodedFrame(): SharedBytes {
        val buffer = BufferFactory.Default.allocate(8)
        buffer.writeInt(marker)
        buffer.writeInt(marker + 1)
        buffer.resetForRead()
        return SharedBytes.adopt(buffer)
    }

    /** Asserts the storage is fully freed: strict refcounting refuses to resurrect it. */
    private fun assertFreed(bytes: SharedBytes) {
        assertFailsWith<IllegalStateException> { bytes.retain() }
    }

    private fun TestScope.sharedWriter(
        mode: SendMode<String>,
        onView: (bytesRead: Int) -> Unit = {},
    ): OutboundWriter<String> =
        OutboundWriter(mode, { outgoing ->
            when (outgoing) {
                is Outgoing.Encode -> TransmitOutcome.Written
                is Outgoing.Prewritten -> {
                    // Prove the view is a full, private-cursor window: both ints readable here,
                    // regardless of what any other consumer's cursor has done.
                    assertEquals(marker, outgoing.view.readInt())
                    assertEquals(marker + 1, outgoing.view.readInt())
                    onView(8)
                    TransmitOutcome.Written
                }
            }
        }, StandardTestDispatcher(testScheduler))

    @Test
    fun writtenPathReleasesTheTransferredReferenceExactlyOnce() =
        runTest {
            val bytes = encodedFrame()
            var viewedBytes = 0
            val writer = sharedWriter(SendMode.AwaitWritten) { viewedBytes += it }

            writer.sendShared(bytes.retain(), "m1")
            assertEquals(8, viewedBytes)

            // Writer released its transferred ref; the creator's is the last one standing.
            bytes.release()
            assertFreed(bytes)
            writer.close()
        }

    @Test
    fun fanOutToThreeWritersFreesOnceAfterTheLastRelease() =
        runTest {
            val bytes = encodedFrame()
            var views = 0
            val writers = List(3) { sharedWriter(SendMode.AwaitWritten) { views++ } }

            writers.forEach { writer -> writer.sendShared(bytes.retain(), "broadcast") }
            assertEquals(3, views)

            bytes.release()
            assertFreed(bytes)
            writers.forEach { it.close() }
        }

    @Test
    fun refusedSendStillReleasesTheTransfer() =
        runTest {
            val bytes = encodedFrame()
            val writer = sharedWriter(SendMode.AwaitWritten)
            writer.close()

            assertFailsWith<OutboundClosedException> { writer.sendShared(bytes.retain(), "late") }

            bytes.release()
            assertFreed(bytes)
        }

    @Test
    fun capacityEvictionReleasesTheVictimAndReportsItsOrigin() =
        runTest {
            val lost = mutableListOf<Pair<String, NotSentReason>>()
            val bytes = encodedFrame()
            // A writer that never drains: transmit parks forever on a never-completed gate is not
            // needed — simply never advance past a queue the writer hasn't been dispatched to yet.
            val writer =
                OutboundWriter<String>(
                    SendMode.Handoff(
                        OutboundCapacity(1),
                        CapacityBehavior.DropOldest,
                        Linger.UntilDrained,
                        { m, r -> lost += m to r },
                    ),
                    { TransmitOutcome.Written },
                    StandardTestDispatcher(testScheduler),
                )

            // Two undispatched sends: the second evicts the first before the writer ever runs.
            writer.sendShared(bytes.retain(), "victim")
            writer.sendShared(bytes.retain(), "survivor")
            assertEquals(listOf("victim" to (NotSentReason.CapacityExceeded as NotSentReason)), lost)

            advanceUntilIdle()
            writer.close()

            bytes.release()
            assertFreed(bytes)
        }

    @Test
    fun abortReleasesEveryQueuedTransferAndReportsEachOnce() =
        runTest {
            val lost = mutableListOf<String>()
            val bytes = encodedFrame()
            val writer =
                OutboundWriter<String>(
                    SendMode.Handoff(
                        OutboundCapacity(4),
                        CapacityBehavior.Suspend,
                        Linger.UntilDrained,
                        { m, _ -> lost += m },
                    ),
                    { TransmitOutcome.Written },
                    StandardTestDispatcher(testScheduler),
                )

            repeat(3) { index -> writer.sendShared(bytes.retain(), "q$index") }
            writer.abort()
            assertEquals(listOf("q0", "q1", "q2"), lost.sorted())
            assertTrue(writer.phase.value.let { it is ConnectionPhase.Closed && it.cause is CloseCause.Aborted })

            bytes.release()
            assertFreed(bytes)
        }
}
