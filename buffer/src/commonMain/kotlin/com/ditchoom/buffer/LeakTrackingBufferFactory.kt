package com.ditchoom.buffer

/**
 * Where [LeakTrackingBufferFactory] sends leak reports. Swap to capture
 * output in tests, forward to a logger, or raise as an assertion.
 */
fun interface LeakSink {
    fun report(leaks: List<TrackedAllocation>)

    companion object {
        /**
         * Default sink — prints a multi-line report to [println]. Useful
         * for test harnesses that want to see leak stacks inline with
         * test output without wiring a logger.
         */
        val Stdout: LeakSink =
            LeakSink { leaks ->
                println("---- BufferFactory leak report: ${leaks.size} live allocation(s) ----")
                for (alloc in leaks) {
                    println("  #${alloc.id}  ${alloc.kind}(${alloc.size})  at:")
                    for (line in alloc.stack.lineSequence()) {
                        if (line.isNotBlank()) println("    $line")
                    }
                }
                println("--------")
            }

        /**
         * Throws an [AssertionError] listing every leak — useful for a
         * test that wants "no buffer left behind" as a hard contract.
         */
        val Asserting: LeakSink =
            LeakSink { leaks ->
                val msg =
                    buildString {
                        appendLine("${leaks.size} buffer allocation(s) were never freed:")
                        for (alloc in leaks) {
                            appendLine("  #${alloc.id}  ${alloc.kind}(${alloc.size})")
                            for (line in alloc.stack.lineSequence()) {
                                if (line.isNotBlank()) appendLine("    $line")
                            }
                        }
                    }
                throw AssertionError(msg)
            }
    }
}

/**
 * One entry in [LeakTrackingBufferFactory]'s tracking table.
 *
 * @property id      Monotonic id, unique within the factory instance.
 * @property size    The `size` argument (for `allocate`) or the array
 *                   length (for `wrap`) — the requested capacity at
 *                   allocation time.
 * @property kind    Which factory method produced this buffer:
 *                   `"allocate"` or `"wrap"`. Some leak patterns only
 *                   hit one of the two paths (e.g. `wrap`-source leaks
 *                   are rarer), so reports surface this.
 * @property stack   Capture-site stack trace, as a plain multi-line
 *                   string (output of `Throwable().stackTraceToString()`).
 */
class TrackedAllocation(
    val id: Int,
    val size: Int,
    val kind: String,
    val stack: String,
)

/**
 * A [BufferFactory] decorator that records every allocation and surfaces
 * the ones that were never freed. Wraps **any** [BufferFactory], not just
 * [BufferFactory.Default] — use it on top of `deterministic()`, pooled
 * factories, fault-injecting test factories, etc.
 *
 * Intended as a diagnostic tool, not a production component. Flip it on
 * when chasing a leak (the Autobahn DirectByteBuffer exhaustion was the
 * original motivator); swap it back out for production code so the
 * `freeNativeMemory` indirection doesn't land on the hot path.
 *
 * Not thread-safe. Allocate/free calls from multiple coroutines/threads
 * on the same decorator instance race the internal live-allocations
 * table. Wrap with external synchronization if the diagnostic scope
 * spans multiple threads.
 *
 * Typical usage:
 * ```kotlin
 * val tracking = LeakTrackingBufferFactory(BufferFactory.Default)
 * try {
 *     runWebSocketTest(tracking)
 * } finally {
 *     tracking.reportLeaks()   // prints any un-freed allocations
 * }
 * ```
 */
class LeakTrackingBufferFactory(
    private val delegate: BufferFactory,
    private val sink: LeakSink = LeakSink.Stdout,
) : BufferFactory {
    private val live: MutableMap<Int, TrackedAllocation> = mutableMapOf()
    private var nextId: Int = 0

    override fun allocate(
        size: Int,
        byteOrder: ByteOrder,
    ): PlatformBuffer {
        val inner = delegate.allocate(size, byteOrder)
        return wrapTracked(inner, size, "allocate")
    }

    override fun wrap(
        array: ByteArray,
        byteOrder: ByteOrder,
    ): PlatformBuffer {
        val inner = delegate.wrap(array, byteOrder)
        return wrapTracked(inner, array.size, "wrap")
    }

    private fun wrapTracked(
        inner: PlatformBuffer,
        size: Int,
        kind: String,
    ): PlatformBuffer {
        val id = ++nextId
        live[id] =
            TrackedAllocation(
                id = id,
                size = size,
                kind = kind,
                stack = Throwable("alloc site").stackTraceToString(),
            )
        return LeakTrackingBuffer(inner, this, id)
    }

    internal fun notifyFreed(id: Int) {
        live.remove(id)
    }

    /**
     * Returns a snapshot of currently-live allocations. Does not emit to
     * the sink.
     */
    fun snapshot(): List<TrackedAllocation> = live.values.toList()

    /**
     * Emits any currently-live allocations to [sink]. A no-op when there
     * are no leaks.
     */
    fun reportLeaks() {
        val leaks = snapshot()
        if (leaks.isNotEmpty()) sink.report(leaks)
    }
}

/**
 * Thin wrapper that delegates every [PlatformBuffer] operation to [inner]
 * and notifies its tracking factory on [freeNativeMemory]. Participates
 * in [unwrapFully] so consumers that need the concrete buffer type
 * (`buffer.unwrapFully() as BaseJvmBuffer`, etc.) see straight through
 * the wrapper.
 */
internal class LeakTrackingBuffer(
    internal val inner: PlatformBuffer,
    private val factory: LeakTrackingBufferFactory,
    private val id: Int,
) : PlatformBuffer by inner,
    BufferWrapper {
    private var freed: Boolean = false

    override fun unwrapOnce(): ReadBuffer = inner

    override fun freeNativeMemory() {
        if (freed) return
        freed = true
        factory.notifyFreed(id)
        inner.freeNativeMemory()
    }

    override fun equals(other: Any?): Boolean = bufferEquals(this, other)

    override fun hashCode(): Int = bufferHashCode(this)
}
