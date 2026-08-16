package com.ditchoom.buffer.flow

import com.ditchoom.buffer.ExperimentalFanoutApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** A transport failure: transmit *throws* these, and they fail the writer. */
private class TransmitBoom(
    message: String,
) : RuntimeException(message)

/** The origin message of an element, whichever [Outgoing] arm carries it. */
@OptIn(ExperimentalFanoutApi::class)
private fun originOf(outgoing: Outgoing<String>): String =
    when (outgoing) {
        is Outgoing.Encode -> outgoing.message
        is Outgoing.Prewritten -> outgoing.origin
    }

/**
 * Records both halves of the exactly-once ledger: what reached the wire and what was reported
 * lost. Every test runs on one test dispatcher, so plain collections are the honest recorders.
 */
@OptIn(ExperimentalFanoutApi::class)
private class Ledger {
    val transmitted = mutableListOf<String>()
    val notSent = mutableListOf<Pair<String, NotSentReason>>()

    /** Set if two transmits are ever in flight at once — the serialization invariant, observed. */
    var overlapped = false
    private var inTransmit = false

    fun enterTransmit() {
        if (inTransmit) overlapped = true
        inTransmit = true
    }

    fun leaveTransmit(outgoing: Outgoing<String>) {
        inTransmit = false
        transmitted += originOf(outgoing)
    }

    fun record(outgoing: Outgoing<String>) {
        transmitted += originOf(outgoing)
    }

    val lost: List<String> get() = notSent.map { it.first }

    val reasons: List<NotSentReason> get() = notSent.map { it.second }
}

/**
 * Every accepted message leaves through exactly one path. [accepted] is the set of messages whose
 * `send` returned normally — a refused or cancelled-before-acceptance send is not accepted and is
 * deliberately reported nowhere.
 */
private fun assertExactlyOnce(
    accepted: List<String>,
    ledger: Ledger,
) {
    val outcomes = ledger.transmitted + ledger.lost
    assertEquals(accepted.size, outcomes.size, "accepted=$accepted transmitted=${ledger.transmitted} lost=${ledger.lost}")
    assertEquals(accepted.toSet(), outcomes.toSet(), "every accepted message must leave exactly once")
    assertTrue(ledger.transmitted.none { it in ledger.lost }, "a message left through both paths")
}

@OptIn(ExperimentalFanoutApi::class, ExperimentalCoroutinesApi::class)
private fun TestScope.awaitWrittenWriter(transmit: suspend (Outgoing<String>) -> TransmitOutcome): OutboundWriter<String> =
    OutboundWriter(SendMode.AwaitWritten, transmit, StandardTestDispatcher(testScheduler))

@OptIn(ExperimentalFanoutApi::class, ExperimentalCoroutinesApi::class)
private fun TestScope.handoffWriter(
    capacity: Int,
    onCapacity: CapacityBehavior,
    onNotSent: suspend (String, NotSentReason) -> Unit,
    linger: Linger = Linger.UntilDrained,
    transmit: suspend (Outgoing<String>) -> TransmitOutcome,
): OutboundWriter<String> =
    OutboundWriter(
        SendMode.Handoff(OutboundCapacity(capacity), onCapacity, linger, onNotSent),
        transmit,
        StandardTestDispatcher(testScheduler),
    )

@OptIn(ExperimentalFanoutApi::class)
class OutboundWriterTests {
    // ----- AwaitWritten: completion semantics -------------------------------------------------

    @Test
    fun awaitWrittenSendReturnsOnlyAfterTransmitCompletes() =
        runTest {
            val ledger = Ledger()
            val gate = CompletableDeferred<Unit>()
            val writer =
                awaitWrittenWriter { outgoing ->
                    gate.await()
                    ledger.record(outgoing)
                    TransmitOutcome.Written
                }
            var returned = false
            val sender =
                launch {
                    writer.send("a")
                    returned = true
                }
            advanceUntilIdle()
            assertFalse(returned, "send returned before the frame reached the wire")
            assertTrue(ledger.transmitted.isEmpty())

            gate.complete(Unit)
            advanceUntilIdle()
            assertTrue(returned)
            assertEquals(listOf("a"), ledger.transmitted)
            sender.join()
            writer.close()
            assertEquals(ConnectionPhase.Closed(CloseCause.Graceful), writer.phase.value)
        }

    @Test
    fun awaitWrittenTransmitFailureFailsTheSenderAndThePhase() =
        runTest {
            val boom = TransmitBoom("transport died")
            val writer = awaitWrittenWriter { throw boom }

            assertSame(boom, assertFailsWith<TransmitBoom> { writer.send("a") })
            advanceUntilIdle()
            assertEquals(ConnectionPhase.Closed(CloseCause.Failed(boom)), writer.phase.value)

            val refused = assertFailsWith<OutboundClosedException> { writer.send("b") }
            assertSame(boom, (refused.closeCause as CloseCause.Failed).cause)

            // The first terminal cause wins: a later graceful close cannot overwrite the failure.
            writer.close()
            assertEquals(ConnectionPhase.Closed(CloseCause.Failed(boom)), writer.phase.value)
        }

    @Test
    fun awaitWrittenEncodeFailureThrowsToTheSenderAndKeepsTheConnectionOpen() =
        runTest {
            val ledger = Ledger()
            val encodeFailure = TransmitBoom("cannot encode")
            val writer =
                awaitWrittenWriter { outgoing ->
                    if (originOf(outgoing) == "poison") {
                        TransmitOutcome.EncodeFailed(encodeFailure)
                    } else {
                        ledger.record(outgoing)
                        TransmitOutcome.Written
                    }
                }

            assertSame(encodeFailure, assertFailsWith<TransmitBoom> { writer.send("poison") })
            assertEquals(ConnectionPhase.Open, writer.phase.value)

            writer.send("after")
            assertEquals(listOf("after"), ledger.transmitted)
            writer.close()
            assertEquals(ConnectionPhase.Closed(CloseCause.Graceful), writer.phase.value)
        }

    @Test
    fun awaitWrittenSerializesConcurrentSenders() =
        runTest {
            val ledger = Ledger()
            val writer =
                awaitWrittenWriter { outgoing ->
                    ledger.enterTransmit()
                    delay(5)
                    ledger.leaveTransmit(outgoing)
                    TransmitOutcome.Written
                }
            val senders = (0 until 8).map { index -> launch { writer.send("m$index") } }
            advanceUntilIdle()

            assertTrue(senders.all { it.isCompleted }, "every concurrent sender completed")
            assertFalse(ledger.overlapped, "two transmits overlapped")
            assertEquals((0 until 8).map { "m$it" }.toSet(), ledger.transmitted.toSet())
            writer.close()
        }

    // ----- AwaitWritten: cancellation atomicity -----------------------------------------------

    @Test
    fun awaitWrittenSenderCancelledMidTransmitStillCompletesTheFrame() =
        runTest {
            val ledger = Ledger()
            val gate = CompletableDeferred<Unit>()
            val writer =
                awaitWrittenWriter { outgoing ->
                    if (originOf(outgoing) == "first") gate.await()
                    ledger.record(outgoing)
                    TransmitOutcome.Written
                }
            val sender = launch { writer.send("first") }
            advanceUntilIdle()

            // The writer holds the element and is inside transmit; the sender abandons the wait.
            sender.cancel()
            advanceUntilIdle()
            assertTrue(ledger.transmitted.isEmpty())

            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(listOf("first"), ledger.transmitted, "cancelled does not imply not sent")

            // The next frame is intact and follows the abandoned one, whole.
            writer.send("second")
            assertEquals(listOf("first", "second"), ledger.transmitted)
            writer.close()
        }

    @Test
    fun awaitWrittenSenderCancelledBeforeHandoffNeverTransmits() =
        runTest {
            val ledger = Ledger()
            val gate = CompletableDeferred<Unit>()
            val writer =
                awaitWrittenWriter { outgoing ->
                    if (originOf(outgoing) == "first") gate.await()
                    ledger.record(outgoing)
                    TransmitOutcome.Written
                }
            val first = launch { writer.send("first") }
            advanceUntilIdle()

            // The writer is busy, so this one is still holding its element at the rendezvous.
            val second = launch { writer.send("second") }
            advanceUntilIdle()
            second.cancel()
            advanceUntilIdle()

            gate.complete(Unit)
            advanceUntilIdle()
            first.join()
            assertEquals(listOf("first"), ledger.transmitted, "an unhanded-off element never reaches transmit")
            writer.close()
        }

    // ----- Handoff: capacity ------------------------------------------------------------------

    @Test
    fun handoffSuspendEngagesAtExactlyCapacity() =
        runTest {
            val ledger = Ledger()
            val accepted = mutableListOf<String>()
            val writer =
                handoffWriter(capacity = 3, onCapacity = CapacityBehavior.Suspend, onNotSent = { m, r ->
                    ledger.notSent += m to r
                }) { outgoing ->
                    ledger.record(outgoing)
                    TransmitOutcome.Written
                }

            val senders =
                (0 until 4).map { index ->
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        writer.send("m$index")
                        accepted += "m$index"
                    }
                }
            // Nothing has been dispatched yet, so the writer has never run and no slot has been
            // freed: the deque's own depth is the whole accounting, and it bounds at exactly 3.
            assertEquals(listOf("m0", "m1", "m2"), accepted, "capacity must engage at exactly 3")

            advanceUntilIdle()
            senders.forEach { it.join() }
            assertEquals(listOf("m0", "m1", "m2", "m3"), accepted)
            assertEquals(listOf("m0", "m1", "m2", "m3"), ledger.transmitted)
            assertTrue(ledger.notSent.isEmpty())
            writer.close()
            assertExactlyOnce(accepted, ledger)
        }

    @Test
    fun handoffSuspendResumesParkedSendersInArrivalOrder() =
        runTest {
            val ledger = Ledger()
            val accepted = mutableListOf<String>()
            val writer =
                handoffWriter(capacity = 1, onCapacity = CapacityBehavior.Suspend, onNotSent = { m, r ->
                    ledger.notSent += m to r
                }) { outgoing ->
                    delay(1)
                    ledger.record(outgoing)
                    TransmitOutcome.Written
                }

            val senders =
                (0 until 3).map { index ->
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        writer.send("m$index")
                        accepted += "m$index"
                    }
                }
            assertEquals(listOf("m0"), accepted)

            advanceUntilIdle()
            senders.forEach { it.join() }
            assertEquals(listOf("m0", "m1", "m2"), accepted, "freed slots go to the longest-parked sender")
            assertEquals(listOf("m0", "m1", "m2"), ledger.transmitted)
            writer.close()
            assertExactlyOnce(accepted, ledger)
        }

    @Test
    fun handoffSuspendCancelledParkedSenderLosesOnlyItsOwnMessage() =
        runTest {
            val ledger = Ledger()
            val accepted = mutableListOf<String>()
            var gate = CompletableDeferred<Unit>()
            val writer =
                handoffWriter(capacity = 2, onCapacity = CapacityBehavior.Suspend, onNotSent = { m, r ->
                    ledger.notSent += m to r
                }) { outgoing ->
                    gate.await()
                    ledger.record(outgoing)
                    TransmitOutcome.Written
                }

            val senders =
                (0 until 4).map { index ->
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        writer.send("m$index")
                        accepted += "m$index"
                    }
                }
            assertEquals(listOf("m0", "m1"), accepted)

            // The writer takes m0 and stalls inside transmit; the slot it freed admits m2, so m3
            // is the one still parked when its sender dies.
            advanceUntilIdle()
            senders[3].cancel()
            advanceUntilIdle()

            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(listOf("m0", "m1", "m2"), ledger.transmitted, "only the cancelled sender's message is lost")
            assertTrue(ledger.notSent.isEmpty(), "a never-accepted message is reported nowhere")

            // No permit can leak because there are no permits: refill and the bound is still 2.
            gate = CompletableDeferred()
            val refill =
                (0 until 4).map { index ->
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        writer.send("n$index")
                        accepted += "n$index"
                    }
                }
            assertEquals(listOf("m0", "m1", "m2", "n0", "n1"), accepted, "capacity is still exactly 2")

            gate.complete(Unit)
            advanceUntilIdle()
            refill.forEach { it.join() }
            assertEquals(listOf("m0", "m1", "m2", "n0", "n1", "n2", "n3"), ledger.transmitted)
            writer.close()
            assertExactlyOnce(accepted, ledger)
        }

    @Test
    fun handoffDropOldestEvictsTheQueueHead() =
        runTest {
            val ledger = Ledger()
            val writer =
                handoffWriter(capacity = 2, onCapacity = CapacityBehavior.DropOldest, onNotSent = { m, r ->
                    ledger.notSent += m to r
                }) { outgoing ->
                    ledger.record(outgoing)
                    TransmitOutcome.Written
                }

            writer.send("m0")
            writer.send("m1")
            writer.send("m2")
            assertEquals(listOf("m0"), ledger.lost, "the stale head is the victim")
            assertEquals(listOf(NotSentReason.CapacityExceeded), ledger.reasons)

            advanceUntilIdle()
            assertEquals(listOf("m1", "m2"), ledger.transmitted, "queue order survives the eviction")
            writer.close()
            assertExactlyOnce(listOf("m0", "m1", "m2"), ledger)
        }

    @Test
    fun handoffDropNewestRejectsTheArrival() =
        runTest {
            val ledger = Ledger()
            val writer =
                handoffWriter(capacity = 2, onCapacity = CapacityBehavior.DropNewest, onNotSent = { m, r ->
                    ledger.notSent += m to r
                }) { outgoing ->
                    ledger.record(outgoing)
                    TransmitOutcome.Written
                }

            writer.send("m0")
            writer.send("m1")
            writer.send("m2")
            assertEquals(listOf("m2"), ledger.lost, "the arrival is the victim")
            assertEquals(listOf(NotSentReason.CapacityExceeded), ledger.reasons)

            advanceUntilIdle()
            assertEquals(listOf("m0", "m1"), ledger.transmitted, "the queue is untouched")
            writer.close()
            assertExactlyOnce(listOf("m0", "m1", "m2"), ledger)
        }

    // ----- Handoff: re-entrancy from the loss handler ------------------------------------------

    @Test
    fun onNotSentMayReenterSend() =
        runTest {
            val ledger = Ledger()
            var replaced = false
            lateinit var writer: OutboundWriter<String>
            writer =
                handoffWriter(capacity = 2, onCapacity = CapacityBehavior.DropOldest, onNotSent = { message, reason ->
                    ledger.notSent += message to reason
                    if (!replaced) {
                        replaced = true
                        writer.send("$message-retry")
                    }
                }) { outgoing ->
                    ledger.record(outgoing)
                    TransmitOutcome.Written
                }

            writer.send("m0")
            writer.send("m1")
            // Evicts m0; the handler immediately re-enters send, which evicts m1 in turn.
            writer.send("m2")
            assertEquals(listOf("m0", "m1"), ledger.lost)

            advanceUntilIdle()
            assertEquals(listOf("m2", "m0-retry"), ledger.transmitted)
            writer.close()
            assertExactlyOnce(listOf("m0", "m1", "m2", "m0-retry"), ledger)
        }

    @Test
    fun onNotSentMayReenterCloseFromTheSender() =
        runTest {
            val ledger = Ledger()
            lateinit var writer: OutboundWriter<String>
            writer =
                handoffWriter(capacity = 1, onCapacity = CapacityBehavior.DropNewest, onNotSent = { message, reason ->
                    ledger.notSent += message to reason
                    writer.close()
                }) { outgoing ->
                    ledger.record(outgoing)
                    TransmitOutcome.Written
                }

            writer.send("m0")
            // Drops m1; the handler closes from the sender's coroutine and must not deadlock.
            writer.send("m1")
            assertEquals(listOf("m1"), ledger.lost)
            assertEquals(listOf("m0"), ledger.transmitted)
            assertEquals(ConnectionPhase.Closed(CloseCause.Graceful), writer.phase.value)
            assertExactlyOnce(listOf("m0", "m1"), ledger)
        }

    @Test
    fun onNotSentMayReenterCloseFromTheWriter() =
        runTest {
            val ledger = Ledger()
            val encodeFailure = TransmitBoom("cannot encode")
            lateinit var writer: OutboundWriter<String>
            writer =
                handoffWriter(capacity = 4, onCapacity = CapacityBehavior.DropNewest, onNotSent = { message, reason ->
                    ledger.notSent += message to reason
                    // Runs on the writer coroutine: closing here must initiate, not join itself.
                    writer.close()
                }) { outgoing ->
                    if (originOf(outgoing) == "poison") {
                        TransmitOutcome.EncodeFailed(encodeFailure)
                    } else {
                        ledger.record(outgoing)
                        TransmitOutcome.Written
                    }
                }

            writer.send("poison")
            writer.send("later")
            advanceUntilIdle()

            assertEquals(listOf("poison"), ledger.lost)
            assertEquals(listOf(NotSentReason.EncodeFailed(encodeFailure)), ledger.reasons)
            assertEquals(listOf("later"), ledger.transmitted, "the drain finishes after the re-entrant close")
            assertEquals(ConnectionPhase.Closed(CloseCause.Graceful), writer.phase.value)
            assertExactlyOnce(listOf("poison", "later"), ledger)
        }

    // ----- Close ladder ------------------------------------------------------------------------

    @Test
    fun gracefulCloseDrainsEveryQueuedMessage() =
        runTest {
            val ledger = Ledger()
            val writer =
                handoffWriter(capacity = 8, onCapacity = CapacityBehavior.Suspend, onNotSent = { m, r ->
                    ledger.notSent += m to r
                }) { outgoing ->
                    delay(1)
                    ledger.record(outgoing)
                    TransmitOutcome.Written
                }
            val accepted = (0 until 5).map { "m$it" }
            accepted.forEach { writer.send(it) }

            writer.close()
            assertEquals(accepted, ledger.transmitted)
            assertTrue(ledger.notSent.isEmpty())
            assertEquals(ConnectionPhase.Closed(CloseCause.Graceful), writer.phase.value)
            assertExactlyOnce(accepted, ledger)
        }

    @Test
    fun boundedLingerEscalatesToAbort() =
        runTest {
            val ledger = Ledger()
            val stall = CompletableDeferred<Unit>()
            val writer =
                handoffWriter(
                    capacity = 8,
                    onCapacity = CapacityBehavior.Suspend,
                    onNotSent = { m, r -> ledger.notSent += m to r },
                    linger = Linger.Bounded(100.milliseconds),
                ) { outgoing ->
                    stall.await()
                    ledger.record(outgoing)
                    TransmitOutcome.Written
                }
            val accepted = (0 until 3).map { "m$it" }
            accepted.forEach { writer.send(it) }

            writer.close()
            assertTrue(ledger.transmitted.isEmpty(), "a stalled peer wrote nothing")
            assertEquals(accepted, ledger.lost, "the in-flight frame and the remainder are all reported")
            assertTrue(ledger.reasons.all { it == NotSentReason.ConnectionClosed(CloseCause.Aborted) })
            assertEquals(ConnectionPhase.Closed(CloseCause.Aborted), writer.phase.value)
            assertExactlyOnce(accepted, ledger)
        }

    @Test
    fun abortReportsEveryQueuedMessageExactlyOnce() =
        runTest {
            val ledger = Ledger()
            val stall = CompletableDeferred<Unit>()
            val writer =
                handoffWriter(capacity = 8, onCapacity = CapacityBehavior.Suspend, onNotSent = { m, r ->
                    ledger.notSent += m to r
                }) { outgoing ->
                    stall.await()
                    ledger.record(outgoing)
                    TransmitOutcome.Written
                }
            val accepted = (0 until 3).map { "m$it" }
            accepted.forEach { writer.send(it) }
            advanceUntilIdle()

            writer.abort()
            assertEquals(accepted, ledger.lost)
            assertTrue(ledger.reasons.all { it == NotSentReason.ConnectionClosed(CloseCause.Aborted) })
            assertEquals(ConnectionPhase.Closed(CloseCause.Aborted), writer.phase.value)

            // Idempotent and convergent: a second abort reports nothing twice.
            writer.abort()
            assertEquals(accepted, ledger.lost)
            assertExactlyOnce(accepted, ledger)
        }

    @Test
    fun doubleCloseIsIdempotent() =
        runTest {
            val ledger = Ledger()
            val writer =
                handoffWriter(capacity = 4, onCapacity = CapacityBehavior.Suspend, onNotSent = { m, r ->
                    ledger.notSent += m to r
                }) { outgoing ->
                    delay(1)
                    ledger.record(outgoing)
                    TransmitOutcome.Written
                }
            writer.send("m0")

            val closers = (0 until 2).map { launch { writer.close() } }
            advanceUntilIdle()
            closers.forEach { it.join() }

            assertEquals(listOf("m0"), ledger.transmitted)
            assertEquals(ConnectionPhase.Closed(CloseCause.Graceful), writer.phase.value)
            writer.close()
            assertEquals(ConnectionPhase.Closed(CloseCause.Graceful), writer.phase.value)
            assertExactlyOnce(listOf("m0"), ledger)
        }

    @Test
    fun closeRacingAbortConvergesOnOneTerminalCause() =
        runTest {
            val ledger = Ledger()
            val stall = CompletableDeferred<Unit>()
            val writer =
                handoffWriter(capacity = 8, onCapacity = CapacityBehavior.Suspend, onNotSent = { m, r ->
                    ledger.notSent += m to r
                }) { outgoing ->
                    stall.await()
                    ledger.record(outgoing)
                    TransmitOutcome.Written
                }
            val accepted = listOf("m0", "m1")
            accepted.forEach { writer.send(it) }
            advanceUntilIdle()

            val closing = launch { writer.close() }
            advanceUntilIdle()
            assertEquals(ConnectionPhase.Draining, writer.phase.value, "an unbounded linger waits on the stalled peer")

            val aborting = launch { writer.abort() }
            advanceUntilIdle()
            closing.join()
            aborting.join()

            assertEquals(ConnectionPhase.Closed(CloseCause.Aborted), writer.phase.value, "the first terminal cause wins")
            assertEquals(accepted, ledger.lost)
            assertExactlyOnce(accepted, ledger)
        }

    @Test
    fun sendAfterCloseThrowsOutboundClosed() =
        runTest {
            val ledger = Ledger()
            val handoff =
                handoffWriter(capacity = 4, onCapacity = CapacityBehavior.Suspend, onNotSent = { m, r ->
                    ledger.notSent += m to r
                }) { outgoing ->
                    ledger.record(outgoing)
                    TransmitOutcome.Written
                }
            handoff.close()
            assertEquals(CloseCause.Graceful, assertFailsWith<OutboundClosedException> { handoff.send("x") }.closeCause)
            assertTrue(ledger.notSent.isEmpty(), "a refused send was never accepted, so it is reported nowhere")

            val awaited = awaitWrittenWriter { TransmitOutcome.Written }
            awaited.close()
            assertEquals(CloseCause.Graceful, assertFailsWith<OutboundClosedException> { awaited.send("x") }.closeCause)

            val aborted = awaitWrittenWriter { TransmitOutcome.Written }
            aborted.abort()
            assertEquals(CloseCause.Aborted, assertFailsWith<OutboundClosedException> { aborted.send("x") }.closeCause)
        }

    // ----- Writer failure ----------------------------------------------------------------------

    @Test
    fun writerFailureReportsTheRemainingQueueExactlyOnce() =
        runTest {
            val ledger = Ledger()
            val boom = TransmitBoom("transport died")
            val writer =
                handoffWriter(capacity = 8, onCapacity = CapacityBehavior.Suspend, onNotSent = { m, r ->
                    ledger.notSent += m to r
                }) { outgoing ->
                    if (originOf(outgoing) == "poison") throw boom
                    ledger.record(outgoing)
                    TransmitOutcome.Written
                }
            val accepted = listOf("m0", "poison", "m1", "m2")
            accepted.forEach { writer.send(it) }
            advanceUntilIdle()

            assertEquals(listOf("m0"), ledger.transmitted)
            assertEquals(listOf("poison", "m1", "m2"), ledger.lost)
            val phase = writer.phase.value as ConnectionPhase.Closed
            assertSame(boom, (phase.cause as CloseCause.Failed).cause)
            assertTrue(ledger.reasons.all { it == NotSentReason.ConnectionClosed(phase.cause) })

            val refused = assertFailsWith<OutboundClosedException> { writer.send("later") }
            assertSame(phase.cause, refused.closeCause, "the sender sees the same terminal cause instance")
            assertExactlyOnce(accepted, ledger)
        }

    // ----- Exactly-once sweep ------------------------------------------------------------------

    @Test
    fun exactlyOnceSweepAcrossEveryLossPath() =
        runTest {
            // Eviction under DropOldest, then a graceful close of what survived.
            val evicting = Ledger()
            val evictingAccepted = mutableListOf<String>()
            val evictingWriter =
                handoffWriter(capacity = 2, onCapacity = CapacityBehavior.DropOldest, onNotSent = { m, r ->
                    evicting.notSent += m to r
                }) { outgoing ->
                    evicting.record(outgoing)
                    TransmitOutcome.Written
                }
            repeat(6) { index ->
                evictingWriter.send("e$index")
                evictingAccepted += "e$index"
            }
            evictingWriter.close()
            assertExactlyOnce(evictingAccepted, evicting)

            // Abort with a stalled peer: nothing on the wire, everything reported.
            val aborting = Ledger()
            val stall = CompletableDeferred<Unit>()
            val abortingAccepted = mutableListOf<String>()
            val abortingWriter =
                handoffWriter(capacity = 8, onCapacity = CapacityBehavior.Suspend, onNotSent = { m, r ->
                    aborting.notSent += m to r
                }) { outgoing ->
                    stall.await()
                    aborting.record(outgoing)
                    TransmitOutcome.Written
                }
            repeat(4) { index ->
                abortingWriter.send("a$index")
                abortingAccepted += "a$index"
            }
            advanceUntilIdle()
            abortingWriter.abort()
            assertExactlyOnce(abortingAccepted, aborting)

            // Writer failure mid-queue.
            val failing = Ledger()
            val failingAccepted = mutableListOf<String>()
            val failingWriter =
                handoffWriter(capacity = 8, onCapacity = CapacityBehavior.Suspend, onNotSent = { m, r ->
                    failing.notSent += m to r
                }) { outgoing ->
                    if (originOf(outgoing) == "f2") throw TransmitBoom("transport died")
                    failing.record(outgoing)
                    TransmitOutcome.Written
                }
            repeat(5) { index ->
                failingWriter.send("f$index")
                failingAccepted += "f$index"
            }
            advanceUntilIdle()
            failingWriter.close()
            assertExactlyOnce(failingAccepted, failing)

            // Encode failure: reported, connection survives, later frames still land.
            val encoding = Ledger()
            val encodingAccepted = mutableListOf<String>()
            val encodingWriter =
                handoffWriter(capacity = 8, onCapacity = CapacityBehavior.Suspend, onNotSent = { m, r ->
                    encoding.notSent += m to r
                }) { outgoing ->
                    if (originOf(outgoing) == "c1") {
                        TransmitOutcome.EncodeFailed(TransmitBoom("cannot encode"))
                    } else {
                        encoding.record(outgoing)
                        TransmitOutcome.Written
                    }
                }
            repeat(3) { index ->
                encodingWriter.send("c$index")
                encodingAccepted += "c$index"
            }
            encodingWriter.close()
            assertEquals(listOf("c1"), encoding.lost)
            assertExactlyOnce(encodingAccepted, encoding)
        }
}

/**
 * The handler-contract hardening: `onNotSent` must not throw, and when it does anyway the writer
 * fails LOUDLY — `Closed(Failed(handlerError))`, senders refused — instead of dying under an Open
 * phase with senders still filling a queue nobody drains (the silent hang the component exists to
 * kill).
 */
@OptIn(ExperimentalFanoutApi::class, ExperimentalCoroutinesApi::class)
class OutboundWriterHandlerContractTests {
    @Test
    fun throwingOnNotSentFailsTheWriterInsteadOfHangingIt() =
        runTest {
            val handlerBoom = TransmitBoom("handler threw")
            val writer =
                handoffWriter(capacity = 4, onCapacity = CapacityBehavior.Suspend, onNotSent = { _, _ ->
                    throw handlerBoom
                }) { outgoing ->
                    // First element hits the writer's reporting path via a per-message encode
                    // failure; the throwing handler then escapes drive() itself.
                    if (originOf(outgoing) == "poison") {
                        TransmitOutcome.EncodeFailed(TransmitBoom("cannot encode"))
                    } else {
                        TransmitOutcome.Written
                    }
                }
            writer.send("poison")
            advanceUntilIdle()

            val settled = writer.phase.value
            assertTrue(settled is ConnectionPhase.Closed, "phase must not stay Open under a dead writer, was $settled")
            val cause = settled.cause
            assertTrue(cause is CloseCause.Failed, "handler error must surface as Failed, was $cause")
            assertSame(handlerBoom, cause.cause)

            val refused = assertFailsWith<OutboundClosedException> { writer.send("after") }
            assertSame(handlerBoom, (refused.closeCause as CloseCause.Failed).cause)
        }
}
