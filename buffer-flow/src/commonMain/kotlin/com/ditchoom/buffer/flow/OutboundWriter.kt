package com.ditchoom.buffer.flow

import com.ditchoom.buffer.ExperimentalFanoutApi
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.pool.SharedBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * The element the writer hands to [OutboundWriter]'s `transmit` stage: either a message still to
 * be encoded (encode-on-writer keeps buffer lifetime and pool discipline inside the adopter's
 * transmit closure), or bytes already encoded once for fan-out. "Pre-encoded" is a queue-element
 * arm, not a connection flag — both send modes carry both arms.
 */
@ExperimentalFanoutApi
sealed interface Outgoing<out T> {
    /** Encode [message] on the writer, then write the frame fully. */
    @ExperimentalFanoutApi
    data class Encode<T>(
        val message: T,
    ) : Outgoing<T>

    /**
     * Write [view] fully — it is a private-cursor slice of shared, already-encoded bytes.
     * The writer owns the underlying reference; the transmit stage only writes.
     */
    @ExperimentalFanoutApi
    class Prewritten<T>(
        val view: ReadBuffer,
        /** The message the bytes encode, for loss reporting. */
        val origin: T,
    ) : Outgoing<T>
}

/** What the adopter's transmit stage reports back to the writer, per element. */
@ExperimentalFanoutApi
sealed interface TransmitOutcome {
    /** The frame reached the wire whole. */
    @ExperimentalFanoutApi
    data object Written : TransmitOutcome

    /**
     * The element failed to **encode** — deterministic, per-message, no bytes written. The
     * connection survives: in `AwaitWritten` the cause throws at the sender's call site; in
     * `Handoff` it reports through `onNotSent` as [NotSentReason.EncodeFailed].
     * Transport/write failures are different: transmit *throws* those, and they fail the
     * connection.
     */
    @ExperimentalFanoutApi
    data class EncodeFailed(
        val cause: Throwable,
    ) : TransmitOutcome
}

/** Thrown by [OutboundWriter.send]/[OutboundWriter.sendShared] once the writer is closed. */
@ExperimentalFanoutApi
class OutboundClosedException(
    /** Why the writer closed — the same cause carried by [ConnectionPhase.Closed]. */
    val closeCause: CloseCause,
) : IllegalStateException(
        "outbound writer is closed: $closeCause",
        (closeCause as? CloseCause.Failed)?.cause,
    )

/**
 * A connection-owned single writer: the component that makes send atomicity and serialization
 * structural rather than by-discipline.
 *
 * One writer coroutine — owned by this component, living on an internal scope whose lifetime
 * equals the component's ([close]/[abort] end it) — performs every transmit. Callers hand
 * elements off; they never run the write themselves, so cancelling a caller can never truncate a
 * frame, and concurrent senders cannot interleave. The [mode] decides how `send` completes; see
 * [SendMode].
 *
 * ## Contract with the adopter's [transmit]
 *
 * - Called serially, only from the writer coroutine, one [Outgoing] element at a time.
 * - Must write the frame **fully or throw** (`writeFully` semantics). A throw is a transport
 *   failure: the writer fails with [CloseCause.Failed], suspended senders and future sends get
 *   [OutboundClosedException], and remaining queued elements report not-sent.
 * - Encode failures are not throws — return [TransmitOutcome.EncodeFailed] instead; the
 *   connection survives them (see [TransmitOutcome]).
 *
 * ## Exactly-once accounting (the load-bearing invariant)
 *
 * Every accepted element leaves through exactly one of: transmit-completed, or the loss path
 * (mode-appropriate: `onNotSent` in Handoff, a thrown cause in AwaitWritten). Shared-bytes
 * references transferred via [sendShared] are released by this component exactly once, on
 * whichever path the element takes. Capacity eviction, linger expiry, `close()` racing
 * `abort()`, and writer failure are all instances of the same rule, not special cases.
 *
 * "Accepted" is the pivot: a message is accepted the instant it enters the writer's queue (in
 * `Handoff`) or reaches the writer's hands (in `AwaitWritten`). A sender cancelled *before* that
 * instant never sent anything and is reported nowhere — its `send` simply unwinds. A frame the
 * writer took and then could not finish (an [abort] mid-transmit, a linger expiry) *is* accepted,
 * so it takes the loss path like any other.
 *
 * ## Close ladder
 *
 * [close] is graceful: the phase moves to [ConnectionPhase.Draining] (the phase transition and
 * the enqueue check are one atomic step — no send can slip into a drained queue), queued frames
 * flush (bounded by the Handoff `linger`, which escalates to abort on expiry; AwaitWritten
 * finishes the in-flight frame and fails the waiting senders), the writer joins, and the phase
 * reaches [ConnectionPhase.Closed]. [abort] is immediate: cancel the writer wherever it is —
 * truncating a frame on a dying connection harms nobody, which is the whole point of the
 * ownership design — and report the queue not-sent. Both are idempotent and converge under
 * concurrent calls: the **first** terminal cause to settle wins and is never overwritten, so a
 * writer failure racing a `close()` reports [CloseCause.Failed], not [CloseCause.Graceful].
 *
 * Senders that arrive during [ConnectionPhase.Draining] are refused with
 * [OutboundClosedException] carrying [CloseCause.Graceful]: draining is closed to senders, and
 * the graceful cause is the one the phase is on its way to.
 *
 * The prompt-cancellation edge, stated once: in `AwaitWritten`, a sender whose handoff succeeded
 * may still unwind with `CancellationException` while the writer finishes the frame — "cancelled"
 * does not imply "not sent". The writer completes every taken element's acknowledgement exactly
 * once regardless of whether anyone is still listening. `Handoff`'s parked senders have the same
 * edge at the same boundary: a sender cancelled after a freed slot admitted its message loses the
 * *wait*, not the message.
 */
@ExperimentalFanoutApi
class OutboundWriter<T>(
    private val mode: SendMode<T>,
    private val transmit: suspend (Outgoing<T>) -> TransmitOutcome,
    /**
     * Where the writer coroutine runs. Public on purpose: an adopter's virtual-time test can only
     * observe "send completed" deterministically if the writer itself runs under the test
     * scheduler, so the seam must reach adopters, not just this module's own tests. Production
     * code leaves the default.
     */
    writerContext: CoroutineContext = Dispatchers.Default,
) {
    private val currentPhase = MutableStateFlow<ConnectionPhase>(ConnectionPhase.Open)

    /** The send/close lifecycle, reactively. Single source of truth — there is no separate flag. */
    val phase: StateFlow<ConnectionPhase>
        get() = currentPhase

    /**
     * Guards the phase *and* the queue, so "is this writer still open?" and "is this message in
     * the queue?" are decided in one step and the send-after-close TOCTOU cannot exist. User code
     * ([SendMode.Handoff.onNotSent]) and [transmit] are never invoked while it is held.
     */
    private val lock = Mutex()

    /** Identity marker for re-entrancy detection; see [insideWriter]. */
    private val mark = WriterMark(this)

    private val scope = CoroutineScope(writerContext + Job())

    private val strategy: OutboundStrategy<T> =
        when (mode) {
            SendMode.AwaitWritten -> AwaitWrittenStrategy()
            is SendMode.Handoff -> HandoffStrategy(mode)
        }

    /** How long a graceful close may drain. Only `Handoff` has an unattended queue to bound. */
    private val linger: Linger =
        when (mode) {
            SendMode.AwaitWritten -> Linger.UntilDrained
            is SendMode.Handoff -> mode.linger
        }

    // Broad catches below are the component boundary: ANY non-cancellation throwable from user
    // code or transport must fail the writer with its cause, never escape uncategorized.
    @Suppress("TooGenericExceptionCaught")
    private val writerJob: Job =
        scope.launch(mark) {
            try {
                strategy.drive()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                // Only a throwing user handler (onNotSent) reaches here — drive() settles its own
                // transmit failures. The writer cannot continue, but it must not die with the
                // phase still Open: senders would keep filling a queue nobody drains, the silent
                // hang this component exists to kill. Surface the error through the API — phase
                // Closed(Failed(t)), every subsequent send refused with the same cause — and
                // best-effort the owed reports. Deliberately NOT rethrown: the error is already
                // loud where consumers look, and an uncaught writer exception would only reach
                // the process-level CoroutineExceptionHandler (a crash on some platforms).
                failWriter(t)
                runCatching { strategy.discardQueued(terminalCause()) }
            }
            // A normal loop exit means the queue drained after close() opened the window. Whoever
            // settled first keeps their cause; this only fills in the graceful case.
            settle(CloseCause.Graceful)
        }

    /**
     * Sends [message] per the [mode]'s completion semantics. Throws [OutboundClosedException]
     * once the writer is not [ConnectionPhase.Open].
     */
    suspend fun send(message: T): Unit = strategy.send(MessageSend(message))

    /**
     * Sends already-encoded shared bytes. **Transfers one [SharedBytes] reference** — the caller
     * retains for this call, and this component releases exactly once on whichever path the
     * element takes. [origin] is the message the bytes encode, for loss reporting.
     *
     * The transfer is unconditional: the reference is released even when this call throws
     * [OutboundClosedException], because a refused send is one of the paths the element can take.
     */
    suspend fun sendShared(
        bytes: SharedBytes,
        origin: T,
    ): Unit = strategy.send(SharedSend(bytes, origin))

    /**
     * Graceful close: drain per mode, join the writer, settle at [ConnectionPhase.Closed].
     *
     * Cancellation-safe by construction. The canonical teardown is `finally { close() }` from a
     * scope that is *already* cancelled, so every step that must happen — leaving [Open],
     * refusing senders, and escalating to [abort] when the drain cannot be waited on — runs under
     * [NonCancellable]. Only the *waiting* is cancellable, and losing the wait escalates rather
     * than abandoning the ladder half-climbed.
     */
    suspend fun close() {
        withContext(NonCancellable) {
            lock.withLock {
                if (currentPhase.value === ConnectionPhase.Open) {
                    // One atomic step: the phase leaves Open and the strategy stops accepting in
                    // the same critical section, so no send can slip into a draining queue.
                    currentPhase.value = ConnectionPhase.Draining
                    strategy.refuseSends()
                }
            }
        }
        if (insideWriter()) {
            // Re-entrant close from a handler running on the writer coroutine: the drain *is* this
            // coroutine, so opening the window is the whole job. Joining here would join ourselves.
            return
        }
        var lingerExpired = false
        try {
            when (val bound = linger) {
                Linger.UntilDrained -> writerJob.join()
                is Linger.Bounded ->
                    lingerExpired = withTimeoutOrNull(bound.timeout) { writerJob.join() } == null
            }
        } catch (cancellation: CancellationException) {
            // The *caller* was cancelled mid-drain (`finally { close() }` from a dead scope). The
            // drain can no longer be waited on, but the queue's exactly-once obligations are not
            // the caller's to drop: escalate to the ladder's last rung, then unwind as asked.
            abort()
            throw cancellation
        }
        if (lingerExpired) {
            // Linger expired with the queue unflushed: the ladder's last rung.
            abort()
            return
        }
        settle(CloseCause.Graceful)
        scope.cancel()
    }

    /**
     * Immediate close: cancel the writer, report the queue not-sent, settle at
     * [ConnectionPhase.Closed].
     *
     * The **entire** body is [NonCancellable]. Everything inside is bounded, and this is the API's
     * escape hatch: a caller reaching for it is usually already being cancelled (a watchdog, a
     * `finally`), and an abort that silently no-ops because its caller was cancelled first is
     * worse than no escape hatch at all.
     */
    suspend fun abort() {
        // Whether we are the writer's own lineage decides only whether we may *join* — see
        // [insideWriter]. Read it outside NonCancellable so it reflects the caller's context.
        val reentrant = insideWriter()
        withContext(NonCancellable) {
            lock.withLock {
                if (currentPhase.value !is ConnectionPhase.Closed) {
                    currentPhase.value = ConnectionPhase.Closed(CloseCause.Aborted)
                }
                strategy.refuseSends()
            }
            val cause = terminalCause()
            // Always cancel, even from the writer's own lineage: an abort that does not stop the
            // writer is not an abort. Only the join is lineage-conditional — joining a job we are
            // (or descend from) would deadlock.
            writerJob.cancel()
            if (!reentrant) writerJob.join()
            // The discard must complete even if the caller is itself being cancelled — a dropped
            // discard is a lost report, which is exactly what exactly-once forbids.
            strategy.discardQueued(cause)
        }
        if (!reentrant) scope.cancel()
    }

    /** Settles the terminal phase. The first cause wins; later ones are dropped, never overwritten. */
    private suspend fun settle(cause: CloseCause) {
        lock.withLock {
            if (currentPhase.value !is ConnectionPhase.Closed) {
                currentPhase.value = ConnectionPhase.Closed(cause)
            }
        }
    }

    /** Terminal-phase cause, for reporting. Callers settle first, so the fallback is unreachable. */
    private fun terminalCause(): CloseCause {
        val settled = currentPhase.value as? ConnectionPhase.Closed
        return settled?.cause ?: CloseCause.Aborted
    }

    /**
     * The cause a refused sender sees. `Draining` is closed to senders and its eventual cause is
     * graceful, so a send racing a graceful close reports [CloseCause.Graceful] rather than
     * inventing a phase-specific one.
     */
    private fun senderCloseCause(): CloseCause =
        when (val observed = currentPhase.value) {
            is ConnectionPhase.Closed -> observed.cause
            else -> CloseCause.Graceful
        }

    /**
     * True when the caller is the writer coroutine **or a coroutine launched from it** — the
     * marker is a context element, so it is inherited by children of `transmit`/`onNotSent`.
     * Lineage is deliberately the right question for the only thing this gates: whether [abort]
     * may `join` the writer. A child of the writer joining it deadlocks exactly as the writer
     * itself would. It must never gate whether the writer is *cancelled* — see [abort].
     */
    private suspend fun insideWriter(): Boolean = currentCoroutineContext()[WriterMark]?.owner === this

    /**
     * Fails the writer: terminal phase first, then refusal, so anyone who observes the failure
     * already sees the terminal cause. Loss reporting is the caller's (mode-specific) job.
     *
     * [NonCancellable]: this runs on paths that are already unwinding (a transmit throw racing an
     * [abort]), and a phase transition lost to the caller's cancellation leaves a dead writer
     * under an [ConnectionPhase.Open] phase — the silent hang this component exists to kill.
     */
    private suspend fun failWriter(cause: Throwable) {
        withContext(NonCancellable) {
            lock.withLock {
                if (currentPhase.value !is ConnectionPhase.Closed) {
                    currentPhase.value = ConnectionPhase.Closed(CloseCause.Failed(cause))
                }
                strategy.refuseSends()
            }
        }
    }

    /**
     * `AwaitWritten`: a rendezvous handoff whose queue is its suspended senders.
     *
     * kotlinx's rendezvous channel is used here — and only here — because its semantics *are* the
     * contract: an element is either taken by the writer or it never happened, and
     * `onUndeliveredElement` fires on exactly the paths where it never happened (sender cancelled
     * before the rendezvous, or the channel closed under a parked sender). No buffering, hence
     * none of the `BufferOverflow` gaps that force [HandoffStrategy] to hand-roll its deque.
     */
    private inner class AwaitWrittenStrategy : OutboundStrategy<T> {
        private val handoffs =
            Channel<AckedSend<T>>(
                capacity = Channel.RENDEZVOUS,
                onUndeliveredElement = { undelivered -> undelivered.discard(senderCloseCause()) },
            )

        override suspend fun send(payload: OutboundPayload<T>) {
            val element = AckedSend(payload, CompletableDeferred())
            try {
                handoffs.send(element)
            } catch (closed: ClosedSendChannelException) {
                // The element never reached the writer; `onUndeliveredElement` already released the
                // transferred reference, so this path only reports.
                throw OutboundClosedException(senderCloseCause()).apply { addSuppressed(closed) }
            }
            // Past this point the writer owns the element: cancelling here abandons the wait, not
            // the write, and the ack is completed exactly once whether or not anyone is listening.
            element.ack.await()
        }

        @Suppress("TooGenericExceptionCaught") // any transmit throwable = transport failure, fails the writer
        override suspend fun drive() {
            for (element in handoffs) {
                try {
                    val outcome =
                        try {
                            element.payload.withOutgoing { transmit(it) }
                        } catch (cancellation: CancellationException) {
                            if (currentCoroutineContext().isActive) {
                                // NOT our cancellation: the adopter's transmit raised it itself (a
                                // `withTimeout` around the sink write). That is a transport
                                // failure, and misreading it as an abort would leave a dead writer
                                // under an Open phase.
                                element.ack.completeExceptionally(cancellation)
                                failWriter(cancellation)
                                return
                            }
                            // abort() cancelled us mid-frame. The frame may be truncated on a dying
                            // connection (by design); the sender still gets its exactly-one answer.
                            element.ack.completeExceptionally(OutboundClosedException(terminalCause()))
                            throw cancellation
                        } catch (failure: Throwable) {
                            // The sender's answer is settled BEFORE any cancellable suspension: an
                            // abort racing this throw must not be able to strand it in await().
                            element.ack.completeExceptionally(failure)
                            failWriter(failure)
                            return
                        }
                    when (outcome) {
                        TransmitOutcome.Written -> element.ack.complete(Unit)
                        // Encode failure is per-message: the sender hears it, the connection lives.
                        is TransmitOutcome.EncodeFailed -> element.ack.completeExceptionally(outcome.cause)
                    }
                } finally {
                    // The two obligations every taken element carries, on every exit path — which
                    // is what makes "released exactly once" and "never hangs its sender"
                    // structural rather than a property of every branch remembering to spell
                    // them. The `isCompleted` guard is not correctness (completeExceptionally is
                    // already a no-op on a settled ack) but allocation: this is the hot path, and
                    // the success case must not build an exception it will immediately discard.
                    element.payload.release()
                    if (!element.ack.isCompleted) {
                        element.ack.completeExceptionally(OutboundClosedException(terminalCause()))
                    }
                }
            }
        }

        override fun refuseSends() {
            handoffs.close()
        }

        override suspend fun discardQueued(cause: CloseCause) {
            // Nothing is ever buffered here: the queue is the suspended senders, and closing for
            // send hands every one of them back through `onUndeliveredElement`.
            handoffs.close()
        }
    }

    /**
     * `Handoff`: a hand-rolled bounded deque plus parked senders, drained by the writer.
     *
     * Hand-rolled on purpose. `onNotSent` is a *suspend* handler that must fire for evictions, and
     * kotlinx's only loss hook (`onUndeliveredElement`) is non-suspend *and* skipped entirely for
     * `DROP_OLDEST`, so this mode's promised contract is inexpressible on a `BufferedChannel`.
     *
     * There are no permits: the deque's own size is the accounting, and a slot is handed to a
     * parked sender under [lock] in the same step that enqueues its message. A sender cancelled
     * between grant and enqueue therefore cannot leak capacity — the two are not separable.
     */
    private inner class HandoffStrategy(
        private val handoff: SendMode.Handoff<T>,
    ) : OutboundStrategy<T> {
        private val capacity = handoff.capacity.messages

        /** Accepted-but-unwritten messages. Guarded by [lock]. */
        private val queue = ArrayDeque<OutboundPayload<T>>()

        /** Senders parked for space, in arrival order. Guarded by [lock]. */
        private val parked = ArrayDeque<ParkedSend<T>>()

        /** Reactive writer wakeup. Conflated: a wakeup is never lost and never accumulates. */
        private val wakeup = Channel<Unit>(Channel.CONFLATED)

        @Suppress("TooGenericExceptionCaught") // the transferred reference is owed a release on every path
        override suspend fun send(payload: OutboundPayload<T>) {
            val admission =
                try {
                    lock.withLock { admit(payload) }
                } catch (t: Throwable) {
                    // Cancelled (or failed) while suspended on the contended lock: nothing was ever
                    // queued, so nothing is reported — but the reference this call took ownership of
                    // still owes its single release. `AwaitWritten` covers the identical window via
                    // `onUndeliveredElement`; this is Handoff's equivalent.
                    payload.release()
                    throw t
                }
            when (admission) {
                Admission.Accepted -> Unit
                Admission.Refused -> {
                    payload.release()
                    throw OutboundClosedException(senderCloseCause())
                }
                // User code, deliberately outside the lock: re-entrant send/close is legal. The
                // victim was *already accepted*, so its exactly-once report is not this sender's
                // to drop — [reportLost] is NonCancellable and routes a throwing handler to the
                // writer rather than to the unrelated call site that triggered the eviction.
                is Admission.Displaced -> {
                    admission.victim.release()
                    reportLost(admission.victim, NotSentReason.CapacityExceeded)
                }
                is Admission.Parked -> awaitSlot(admission.sender, payload)
            }
        }

        /** Decides the fate of [payload] under [lock] — phase check and enqueue in one step. */
        private fun admit(payload: OutboundPayload<T>): Admission<T> =
            if (currentPhase.value !== ConnectionPhase.Open) {
                Admission.Refused
            } else if (queue.size < capacity) {
                enqueue(payload)
                Admission.Accepted
            } else {
                when (handoff.onCapacity) {
                    CapacityBehavior.Suspend -> {
                        val sender = ParkedSend(payload)
                        parked.addLast(sender)
                        Admission.Parked(sender)
                    }
                    CapacityBehavior.DropOldest -> {
                        val victim = queue.removeFirst()
                        enqueue(payload)
                        Admission.Displaced(victim)
                    }
                    CapacityBehavior.DropNewest -> Admission.Displaced(payload)
                }
            }

        /** Under [lock]. */
        private fun enqueue(payload: OutboundPayload<T>) {
            queue.addLast(payload)
            wakeup.trySend(Unit)
        }

        /**
         * Waits for a slot. Ownership of [payload] is settled once, under [lock], by asking
         * whether an admitter already moved it into the queue — the only question that matters on
         * every exit path (admitted, refused, or cancelled).
         */
        @Suppress("TooGenericExceptionCaught") // the await outcome is re-thrown after ownership settles
        private suspend fun awaitSlot(
            sender: ParkedSend<T>,
            payload: OutboundPayload<T>,
        ) {
            var failure: Throwable? = null
            try {
                sender.slot.await()
            } catch (t: Throwable) {
                failure = t
            }
            val ours =
                withContext(NonCancellable) {
                    lock.withLock {
                        parked.remove(sender)
                        !sender.enqueued
                    }
                }
            // Never accepted: the transferred reference dies with the send, unreported (nothing
            // was ever queued to lose).
            if (ours) payload.release()
            if (failure != null) throw failure
        }

        override suspend fun drive() {
            var alive = true
            while (alive) {
                val next = lock.withLock { dequeue() }
                if (next == null) {
                    // Drained. Only close()/abort()/failure move the phase off Open, so this is
                    // the graceful exit; otherwise park until there is work or the phase moves.
                    if (currentPhase.value !== ConnectionPhase.Open) return
                    wakeup.receive()
                } else {
                    alive = transmitOne(next)
                }
            }
        }

        /**
         * Transmits one element and discharges its obligations. Returns `false` when the writer
         * must stop — a transport failure, whose queue this has already discarded.
         */
        @Suppress("TooGenericExceptionCaught") // any transmit throwable = transport failure, fails the writer
        private suspend fun transmitOne(next: OutboundPayload<T>): Boolean {
            var alive = true
            try {
                val outcome =
                    try {
                        next.withOutgoing { transmit(it) }
                    } catch (cancellation: CancellationException) {
                        if (!currentCoroutineContext().isActive) {
                            // Accepted but unfinished: it owes exactly one report even though we
                            // are being torn down mid-frame.
                            reportLost(next, NotSentReason.ConnectionClosed(terminalCause()))
                            throw cancellation
                        }
                        // NOT our cancellation — the adopter's own transmit raised it. A transport
                        // failure, not an abort; see the AwaitWritten twin.
                        failWriter(cancellation)
                        alive = false
                        null
                    } catch (failure: Throwable) {
                        failWriter(failure)
                        alive = false
                        null
                    }
                when {
                    !alive -> {
                        val cause = terminalCause()
                        reportLost(next, NotSentReason.ConnectionClosed(cause))
                        discardQueued(cause)
                    }
                    outcome is TransmitOutcome.EncodeFailed ->
                        reportLost(next, NotSentReason.EncodeFailed(outcome.cause))
                }
            } finally {
                // One release site per strategy: the load-bearing exactly-once-release invariant
                // is structural, not a property of every exit path remembering to spell it.
                next.release()
            }
            return alive
        }

        /**
         * The writer's loss-report path: [NonCancellable] so a teardown cannot swallow an owed
         * report, and guarded so a throwing handler fails the writer rather than unwinding the
         * loop past the releases it still owes.
         */
        @Suppress("TooGenericExceptionCaught") // handler contract: a throw fails the writer, never escapes
        private suspend fun reportLost(
            payload: OutboundPayload<T>,
            reason: NotSentReason,
        ) {
            withContext(NonCancellable) {
                try {
                    handoff.onNotSent(payload.origin, reason)
                } catch (t: Throwable) {
                    if (t !is CancellationException) failWriter(t)
                }
            }
        }

        /** Under [lock]: take the head and hand the freed slot straight to the longest-parked sender. */
        private fun dequeue(): OutboundPayload<T>? {
            val next = queue.removeFirstOrNull() ?: return null
            admitParked()
            return next
        }

        /** Under [lock]: FIFO slot handoff. Grant and enqueue are the same step, so nothing leaks. */
        private fun admitParked() {
            while (queue.size < capacity) {
                val sender = parked.removeFirstOrNull() ?: return
                sender.enqueued = true
                enqueue(sender.payload)
                sender.slot.complete(Unit)
            }
        }

        override fun refuseSends() {
            // Parked senders were never accepted, so they leave through their own call site with
            // OutboundClosedException — not through onNotSent.
            while (true) {
                val sender = parked.removeFirstOrNull() ?: break
                sender.slot.completeExceptionally(OutboundClosedException(senderCloseCause()))
            }
            wakeup.trySend(Unit)
        }

        /**
         * Drains the queue's obligations. Every element is released unconditionally and reported
         * under its own guard, so one throwing handler degrades *its own* report to best-effort
         * (as documented) without stranding the references and reports of everything behind it.
         */
        override suspend fun discardQueued(cause: CloseCause) {
            withContext(NonCancellable) {
                while (true) {
                    val lost = lock.withLock { queue.removeFirstOrNull() } ?: break
                    try {
                        runCatching { handoff.onNotSent(lost.origin, NotSentReason.ConnectionClosed(cause)) }
                    } finally {
                        lost.release()
                    }
                }
            }
        }
    }
}

/**
 * The per-mode machinery behind [OutboundWriter]. Each arm owns its own element type and queue,
 * so the states the other arm would make possible — an acknowledgement with no waiter, a bounded
 * deque with no loss handler — are unconstructible rather than merely unused.
 */
@ExperimentalFanoutApi
private interface OutboundStrategy<T> {
    /** The caller's side of the mode's completion semantics. */
    suspend fun send(payload: OutboundPayload<T>)

    /** The writer coroutine's loop. Returns when the queue is drained and the phase left Open. */
    suspend fun drive()

    /** Stop accepting. Called under the writer's lock, atomically with the phase transition. */
    fun refuseSends()

    /** Report every still-queued element as lost, exactly once, with user code outside the lock. */
    suspend fun discardQueued(cause: CloseCause)
}

/**
 * What the writer holds per accepted message: the message itself plus, for `sendShared`, the one
 * transferred [SharedBytes] reference this component owes exactly one [release] to.
 */
@ExperimentalFanoutApi
private sealed interface OutboundPayload<T> {
    val origin: T

    /**
     * Lends this payload's [Outgoing] to [block] for exactly the duration of the write.
     *
     * Scoped rather than returned because `sendShared`'s outgoing borrows a view of shared bytes:
     * a returned view would outlive the reference that keeps its storage alive, which is a
     * use-after-free the moment the last reference drops mid-write. The borrow ends when the
     * write does.
     */
    suspend fun <R> withOutgoing(block: suspend (Outgoing<T>) -> R): R

    fun release()
}

@ExperimentalFanoutApi
private class MessageSend<T>(
    override val origin: T,
) : OutboundPayload<T> {
    override suspend fun <R> withOutgoing(block: suspend (Outgoing<T>) -> R): R = block(Outgoing.Encode(origin))

    override fun release() = Unit
}

@ExperimentalFanoutApi
private class SharedSend<T>(
    private val bytes: SharedBytes,
    override val origin: T,
) : OutboundPayload<T> {
    /** The private-cursor view is borrowed on the writer for exactly the span of the write. */
    override suspend fun <R> withOutgoing(block: suspend (Outgoing<T>) -> R): R =
        bytes.withView { view -> block(Outgoing.Prewritten(view, origin)) }

    override fun release() = bytes.release()
}

/** `AwaitWritten`'s element: a payload plus the acknowledgement its sender is waiting on. */
@ExperimentalFanoutApi
private class AckedSend<T>(
    val payload: OutboundPayload<T>,
    val ack: CompletableDeferred<Unit>,
) {
    /** The loss path: release the transferred reference, then answer the sender exactly once. */
    fun discard(cause: CloseCause) {
        payload.release()
        ack.completeExceptionally(OutboundClosedException(cause))
    }
}

/** `Handoff`'s parked sender: a payload waiting for a slot, plus the grant it is suspended on. */
@ExperimentalFanoutApi
private class ParkedSend<T>(
    val payload: OutboundPayload<T>,
) {
    /** Set under the writer's lock in the same step that moves [payload] into the queue. */
    var enqueued: Boolean = false

    /** Completes on admission, fails with [OutboundClosedException] on refusal. */
    val slot = CompletableDeferred<Unit>()
}

/** The fate `Handoff` decides for a message under the lock, acted on once the lock is released. */
@ExperimentalFanoutApi
private sealed interface Admission<out T> {
    /** Queued. */
    data object Accepted : Admission<Nothing>

    /** The writer is no longer open. */
    data object Refused : Admission<Nothing>

    /** Capacity policy chose a victim: the queue head (DropOldest) or the arrival (DropNewest). */
    class Displaced<T>(
        val victim: OutboundPayload<T>,
    ) : Admission<T>

    /** The sender waits for a slot. */
    class Parked<T>(
        val sender: ParkedSend<T>,
    ) : Admission<T>
}

/**
 * Identity marker installed in the writer coroutine's context so a re-entrant `close()`/`abort()`
 * from a user handler can tell "I *am* the writer" and initiate instead of joining itself. It
 * survives `withContext(NonCancellable)`, which replaces only the job.
 */
private class WriterMark(
    val owner: Any,
) : AbstractCoroutineContextElement(WriterMark) {
    companion object Key : CoroutineContext.Key<WriterMark>
}
