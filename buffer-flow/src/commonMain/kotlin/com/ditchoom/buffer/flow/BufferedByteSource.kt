package com.ditchoom.buffer.flow

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * A [ByteSource] wrapper that adds a **non-consuming [peek]** by owning a small look-ahead buffer.
 *
 * Heterogeneous stream demux needs to *classify* an accepted stream before choosing how to decode
 * it — e.g. an HTTP/3 unidirectional stream is self-describing via a leading stream-type varint
 * (RFC 9114 §6.2). Classification must not consume the prefix (some layers re-parse it) and must
 * not copy the payload. [peek] reads ahead without consuming: the peeked bytes are retained in a
 * look-ahead queue and re-delivered, unchanged and uncopied, by subsequent [read] calls.
 *
 * ### Zero-copy contract
 * - When the first buffered chunk already covers the requested [peek] length, the returned buffer
 *   is a zero-copy *view* of that chunk — no allocation at all.
 * - Only when the requested range spans multiple upstream chunks is a staging buffer allocated,
 *   sized to the requested length (a classification prefix is ≤ 8 bytes — never the payload).
 *   The staging buffer comes from [factory], so an accounting test can pin this with
 *   `BufferFactory.counting()`.
 * - [read] hands back the buffered chunks themselves (ownership transfer), never a copy.
 *
 * ### Aliasing
 * A buffer returned by [peek] may share storage with a buffer later returned by [read] (the
 * zero-copy view case). Consume the peeked view before mutating or releasing the read buffer.
 *
 * **Thread safety:** NOT thread-safe. Confine [peek]/[read] to a single coroutine, matching the
 * [ByteSource] contract.
 *
 * @param upstream the raw source to buffer; [readPolicy] and liveness are inherited from it
 * @param factory allocator for the (header-sized) staging buffer in the chunk-spanning peek case
 */
class BufferedByteSource(
    private val upstream: ByteSource,
    private val factory: BufferFactory = BufferFactory.Default,
) : ByteSource {
    private val lookAhead = ArrayDeque<ReadBuffer>()

    /** Terminal result ([ReadResult.End] or [ReadResult.Reset]) seen while filling; sticky. */
    private var terminal: ReadResult? = null

    override val isOpen: Boolean get() = lookAhead.isNotEmpty() || upstream.isOpen

    override val readPolicy: ReadPolicy get() = upstream.readPolicy

    /** Total bytes currently held in the look-ahead queue. */
    fun buffered(): Int = lookAhead.sumOf { it.remaining() }

    /**
     * Reads **up to** [n] bytes without consuming them, waiting at most [deadline] in total.
     *
     * Fills the look-ahead queue from [upstream] until [n] bytes are buffered, the stream ends,
     * or the deadline is exhausted. Returns:
     * - [ReadResult.Data] with `min(n, available)` bytes if any bytes are buffered — check
     *   `buffer.remaining()`, a clean EOF after fewer than [n] bytes yields a short peek;
     * - [ReadResult.End] / [ReadResult.Reset] if the stream terminated with nothing buffered.
     *
     * A later [read] re-delivers the same bytes; peeking is idempotent.
     */
    suspend fun peek(
        n: Int,
        deadline: Duration,
    ): ReadResult {
        require(n > 0) { "peek length must be positive, was $n" }
        fillLookAhead(n, deadline)
        val available = buffered()
        if (available == 0) return terminal ?: ReadResult.End
        val length = minOf(n, available)
        val head = lookAhead.first()
        return if (head.remaining() >= length) {
            // Zero-copy: a view over the head chunk, limited to the requested length.
            ReadResult.Data(head.slice().readBytes(length))
        } else {
            ReadResult.Data(stageSpanningPeek(length))
        }
    }

    /** Peeks up to [n] bytes using the inherited [readPolicy] deadline. */
    suspend fun peek(n: Int): ReadResult = peek(n, readPolicy.toDeadline())

    override suspend fun read(deadline: Duration): ReadResult {
        val head = lookAhead.removeFirstOrNull()
        return if (head != null) ReadResult.Data(head) else terminal ?: upstream.read(deadline)
    }

    /**
     * Spanning-peek case: assembles a header-sized staging buffer via absolute reads —
     * the positions of the buffered chunks are untouched.
     */
    private fun stageSpanningPeek(length: Int): ReadBuffer {
        val staging = factory.allocate(length)
        var copied = 0
        for (chunk in lookAhead) {
            var index = chunk.position()
            while (index < chunk.limit() && copied < length) {
                staging.writeByte(chunk[index])
                index++
                copied++
            }
            if (copied == length) break
        }
        staging.resetForRead()
        return staging
    }

    private suspend fun fillLookAhead(
        n: Int,
        deadline: Duration,
    ) {
        val start = if (deadline.isInfinite()) null else TimeSource.Monotonic.markNow()
        while (buffered() < n && terminal == null) {
            val budget =
                if (start == null) {
                    deadline
                } else {
                    val left = deadline - start.elapsedNow()
                    if (left <= Duration.ZERO) return
                    left
                }
            when (val result = upstream.read(budget)) {
                is ReadResult.Data -> if (result.buffer.hasRemaining()) lookAhead.addLast(result.buffer)
                ReadResult.End, ReadResult.Reset -> terminal = result
            }
        }
    }
}
