package com.ditchoom.buffer.flow

import com.ditchoom.buffer.ExperimentalFanoutApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** A handler failure with a specific type, so the throw sites are not generic exceptions. */
private class HandlerBoom : IllegalStateException("handler boom")

@OptIn(ExperimentalFanoutApi::class)
private typealias Transmitting = suspend (Outgoing<String>) -> TransmitOutcome

/**
 * Regressions for the cancellation-boundary defects found reviewing the send-ownership PR.
 *
 * They all share one root cause: a cleanup or reporting obligation placed *after* a cancellable
 * suspension point, so a caller (or writer) that is already unwinding drops it silently. That is
 * the same failure class this component exists to eliminate on the wire — a cancelled participant
 * leaving a job half-done — which is why each of these is a regression test and not a doc note.
 */
@OptIn(ExperimentalFanoutApi::class, ExperimentalCoroutinesApi::class)
class OutboundWriterCancellationTests {
    private fun TestScope.awaitWrittenWriter(transmit: Transmitting): OutboundWriter<String> =
        OutboundWriter(SendMode.AwaitWritten, transmit, StandardTestDispatcher(testScheduler))

    private fun TestScope.handoffWriter(
        capacity: Int,
        onCapacity: CapacityBehavior,
        onNotSent: suspend (String, NotSentReason) -> Unit,
        transmit: Transmitting,
    ): OutboundWriter<String> =
        OutboundWriter(
            SendMode.Handoff(OutboundCapacity(capacity), onCapacity, Linger.UntilDrained, onNotSent),
            transmit,
            StandardTestDispatcher(testScheduler),
        )

    // ----- The adopter's own cancellation is not our abort ------------------------------------

    /**
     * A `withTimeout` *inside* the adopter's transmit raises a CancellationException that has
     * nothing to do with `abort()`. Reading it as one left the writer dead under an `Open` phase:
     * senders kept queueing onto a rendezvous nobody read, and a later `close()` joined the corpse,
     * settled `Graceful`, and silently discarded the backlog.
     */
    @Test
    fun adopterTimeoutInsideTransmitFailsTheWriterInsteadOfPassingForAbort() =
        runTest {
            val writer =
                awaitWrittenWriter {
                    // The adopter bounding its own sink write — the documented, expected pattern.
                    withTimeout(50.milliseconds) { delay(1.seconds) }
                    TransmitOutcome.Written
                }
            val failure = CompletableDeferred<Throwable>()
            launch {
                runCatching { writer.send("a") }.onFailure { failure.complete(it) }
            }
            advanceUntilIdle()

            val phase = writer.phase.value
            assertIs<ConnectionPhase.Closed>(phase, "an adopter-raised timeout must terminate the writer")
            assertIs<CloseCause.Failed>(phase.cause, "it is a transport failure, not an abort")
            assertTrue(failure.isCompleted, "the sender must hear it rather than park forever")

            // The decisive part: the phase left Open, so the next sender is refused instead of
            // queueing onto a writer that will never read again.
            val refused = CompletableDeferred<Throwable>()
            launch { runCatching { writer.send("b") }.onFailure { refused.complete(it) } }
            advanceUntilIdle()
            assertIs<OutboundClosedException>(refused.getCompleted())
        }

    // ----- close()/abort() from an already-cancelled caller -----------------------------------

    /**
     * `finally { close() }` from a cancelled scope is *the* teardown idiom. The drain's `join()`
     * checks the invoker's cancellation, so it threw immediately and the ladder stopped there:
     * phase stuck at `Draining`, the queue never discarded, its references never released.
     */
    @Test
    fun closeFromACancelledCallerEscalatesInsteadOfStrandingTheQueue() =
        runTest {
            val stall = CompletableDeferred<Unit>()
            val writer =
                awaitWrittenWriter {
                    stall.await()
                    TransmitOutcome.Written
                }
            launch { runCatching { writer.send("in-flight") } }
            advanceUntilIdle()

            val owner =
                launch {
                    try {
                        awaitCancellation()
                    } finally {
                        // Cancelled at this point: every suspension here throws.
                        runCatching { writer.close() }
                    }
                }
            advanceUntilIdle()
            owner.cancelAndJoin()
            advanceUntilIdle()

            val phase = writer.phase.value
            assertIs<ConnectionPhase.Closed>(phase, "close() from a cancelled caller must still settle")
            assertIs<CloseCause.Aborted>(phase.cause, "an un-waitable drain escalates to the last rung")
        }

    /**
     * The Handoff twin, which is where the stranding is observable: every queued message owes
     * exactly one `onNotSent`, and a cancelled `close()` used to drop all of them.
     */
    @Test
    fun closeFromACancelledCallerStillReportsEveryQueuedMessage() =
        runTest {
            val stall = CompletableDeferred<Unit>()
            val lost = mutableListOf<String>()
            val writer =
                handoffWriter(capacity = 8, onCapacity = CapacityBehavior.Suspend, onNotSent = { m, _ -> lost += m }) {
                    stall.await()
                    TransmitOutcome.Written
                }
            listOf("a", "b", "c").forEach { writer.send(it) }
            advanceUntilIdle()

            val owner =
                launch {
                    try {
                        awaitCancellation()
                    } finally {
                        runCatching { writer.close() }
                    }
                }
            advanceUntilIdle()
            owner.cancelAndJoin()
            advanceUntilIdle()

            assertIs<ConnectionPhase.Closed>(writer.phase.value)
            assertEquals(
                setOf("a", "b", "c"),
                lost.toSet(),
                "a cancelled close() must not swallow the exactly-once loss reports it owes",
            )
        }

    // ----- A throwing handler must not strand what is behind it -------------------------------

    /**
     * `discardQueued` released-then-reported per element with no per-element guard, so a handler
     * that threw at element k abandoned k+1..n — neither released nor reported. The documented
     * degradation was "reports become best-effort"; it never blessed leaked references.
     */
    @Test
    fun aThrowingOnNotSentDoesNotStrandTheRestOfTheQueue() =
        runTest {
            val stall = CompletableDeferred<Unit>()
            val seen = mutableListOf<String>()
            val writer =
                handoffWriter(
                    capacity = 8,
                    onCapacity = CapacityBehavior.Suspend,
                    onNotSent = { m, _ ->
                        seen += m
                        // "b" is a *queued* element, so the throw lands inside discardQueued with
                        // "c" still behind it — the exact shape that used to abandon the tail.
                        if (m == "b") throw HandlerBoom()
                    },
                ) {
                    stall.await()
                    TransmitOutcome.Written
                }
            listOf("a", "b", "c").forEach { writer.send(it) }
            advanceUntilIdle() // the writer takes "a" and stalls; "b" and "c" stay queued

            runCatching { writer.abort() }
            advanceUntilIdle()

            assertEquals(
                setOf("a", "b", "c"),
                seen.toSet(),
                "one throwing report must not cancel the reports owed to everything behind it",
            )
        }

    /**
     * A capacity eviction reports a *previously accepted* message. Running that report on the
     * evicting sender's coroutine with no protection meant a sender cancelled at the handler's
     * first suspension point silently dropped someone else's loss report — an under-counted
     * ledger, which adopters have no way to detect.
     */
    @Test
    fun anEvictionReportSurvivesTheEvictingSenderBeingCancelled() =
        runTest {
            val stall = CompletableDeferred<Unit>()
            val handlerEntered = CompletableDeferred<Unit>()
            val releaseHandler = CompletableDeferred<Unit>()
            val lost = mutableListOf<String>()
            val writer =
                handoffWriter(
                    capacity = 1,
                    onCapacity = CapacityBehavior.DropOldest,
                    onNotSent = { m, _ ->
                        handlerEntered.complete(Unit)
                        releaseHandler.await() // the suspension point the cancellation lands on
                        lost += m
                    },
                ) {
                    stall.await()
                    TransmitOutcome.Written
                }
            writer.send("first")
            advanceUntilIdle() // the writer takes "first" and stalls, leaving the slot free
            writer.send("victim") // fills the single queue slot
            advanceUntilIdle()

            // This sender evicts "victim" and then dies while its report is still suspended.
            val evictor = launch { writer.send("winner") }
            advanceUntilIdle()
            assertTrue(handlerEntered.isCompleted, "precondition: the eviction report is in flight")

            evictor.cancel()
            releaseHandler.complete(Unit)
            advanceUntilIdle()

            assertTrue(
                "victim" in lost,
                "the evicted message's exactly-once report is not the evicting sender's to drop",
            )
        }

    // ----- abort() must abort, even from the writer's own lineage ------------------------------

    /**
     * `WriterMark` is a context element, so it is inherited by anything transmit launches. A
     * watchdog child calling `abort()` therefore took the re-entrant branch and skipped cancelling
     * the writer entirely — an abort that discarded the queue, settled `Closed`, and left the stuck
     * transmit running. Lineage may gate the `join` (a child joining its parent deadlocks); it must
     * never gate the `cancel`.
     */
    @Test
    fun abortFromAWatchdogLaunchedInsideTransmitStillCancelsTheWriter() =
        runTest {
            val writerRef = CompletableDeferred<OutboundWriter<String>>()
            val transmitJob = CompletableDeferred<Job>()
            val writer =
                awaitWrittenWriter {
                    val writerContext = currentCoroutineContext()
                    transmitJob.complete(assertNotNull(writerContext[Job]))
                    // A watchdog *child of the writer* — which is what makes it inherit WriterMark
                    // and take the re-entrant branch. Launching from the enclosing TestScope would
                    // not reproduce the defect at all.
                    CoroutineScope(writerContext).launch { writerRef.await().abort() }
                    awaitCancellation() // the stuck write the watchdog exists to break
                }
            writerRef.complete(writer)
            launch { runCatching { writer.send("a") } }
            advanceUntilIdle()

            assertIs<ConnectionPhase.Closed>(writer.phase.value)
            assertTrue(
                transmitJob.getCompleted().isCancelled,
                "an abort that leaves the stuck writer running is not an abort",
            )
        }

    // ----- structural: a failed writer never leaves a sender suspended --------------------------

    /**
     * Whatever kills the writer, every element it already took must still receive exactly one
     * answer. This is the property the per-element `finally` makes structural rather than a
     * consequence of each exit path remembering to settle its ack.
     */
    @Test
    fun aWriterThatDiesMidFrameStillAnswersTheSenderItTookFrom() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            val writer =
                awaitWrittenWriter {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            val outcome = CompletableDeferred<Throwable>()
            launch { runCatching { writer.send("a") }.onFailure { outcome.complete(it) } }
            advanceUntilIdle()
            assertTrue(entered.isCompleted, "precondition: the writer took the element")

            writer.abort()
            advanceUntilIdle()

            assertTrue(outcome.isCompleted, "the sender must never be left suspended on its ack")
        }
}
