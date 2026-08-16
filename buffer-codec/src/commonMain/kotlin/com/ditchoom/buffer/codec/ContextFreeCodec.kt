package com.ditchoom.buffer.codec

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ExperimentalFanoutApi
import com.ditchoom.buffer.pool.SharedBytes
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * A [Codec] whose encoding is **connection-independent**: [encode] and [wireSize] must produce
 * identical bytes for identical values regardless of the [EncodeContext] they are given.
 *
 * This is a *capability declaration*, and it is what makes cross-connection encode sharing safe.
 * Encoding is not context-free in general — a codec carrying per-connection state (a QPACK-style
 * dynamic table, per-connection packet ids) encodes the same value to *different* bytes on
 * different connections, and sharing those bytes across connections is silent stream corruption.
 * By scoping [encodeShared] to this marker, sharing through a stateful codec is unrepresentable
 * rather than merely discouraged.
 *
 * Implementors: only declare this when the contract genuinely holds for every value of [T].
 * Generated codecs qualify whenever their schema reads no context keys in the encode direction.
 */
@ExperimentalFanoutApi
interface ContextFreeCodec<T> : Codec<T>

/**
 * One message encoded once, shareable across many connections.
 *
 * Pairs the encoded [bytes] with the [origin] message so loss reporting can hand back the
 * *message* (never a buffer someone must remember to free). Created by [encodeShared]; the frame
 * holds the creator's reference to [bytes] until [close].
 *
 * Reference flow for a fan-out loop: create once → each per-connection send **retains** and
 * transfers a reference into that connection's outbound path (released there exactly once,
 * write-complete or not-sent) → the creator calls [close] after the loop. N connections =
 * N + 1 references; the last release frees the storage.
 */
@ExperimentalFanoutApi
@OptIn(ExperimentalAtomicApi::class)
class SharedFrame<T>(
    /** The message these bytes encode — the value loss reporting hands back. */
    val origin: T,
    /** The encoded bytes; refcounted, read through per-consumer [SharedBytes.view] cursors. */
    val bytes: SharedBytes,
) {
    /**
     * Whether the creator's reference has already been dropped. Atomic because a frame is
     * distributed across connections on unrelated threads, and the whole point of the flag is to
     * make the second close a *diagnosis* rather than a race.
     */
    private val closed = AtomicBoolean(false)

    /**
     * Releases the creator's reference. Call **exactly once**, after distributing the frame.
     *
     * Deliberately NOT idempotent: a second close throws, because silently absorbing it would
     * mask the same accounting bug that silently absorbing an over-release would — the refcount
     * is strict everywhere or trustworthy nowhere. Don't pair with `use`-style helpers that may
     * close twice.
     *
     * The flag is what makes that promise true. Delegating straight to [SharedBytes.release]
     * would only throw when the count is already at zero — with consumer references still
     * outstanding, a double close would instead *steal one of theirs*, freeing the storage an
     * entire reference early and surfacing as another connection's bytes on the wire, far from
     * the buggy call.
     */
    fun close() {
        check(closed.compareAndSet(expectedValue = false, newValue = true)) {
            "SharedFrame.close() called more than once: the creator holds exactly one reference, " +
                "and a second close would consume a consumer's reference instead of its own."
        }
        bytes.release()
    }
}

/**
 * Encodes [message] exactly once into a [SharedFrame] for fan-out to multiple connections.
 *
 * Only available on [ContextFreeCodec] — see the marker's contract for why. The encode allocates
 * from [factory] ([BufferFactory.Default] unless the caller needs a specific allocation strategy)
 * and the returned frame owns the buffer via its [SharedBytes] refcount.
 */
@ExperimentalFanoutApi
fun <T> ContextFreeCodec<T>.encodeShared(
    message: T,
    factory: BufferFactory = BufferFactory.Default,
): SharedFrame<T> {
    // EncodeContext.Empty is the honest argument: a ContextFreeCodec encodes identically for every
    // context by contract, so threading a caller's context would only imply an influence that is
    // declared not to exist.
    //
    // encodeToPlatformBuffer already hands back a buffer positioned for reading (position = 0,
    // limit = encoded size) and frees it on any encode failure. Do NOT resetForRead() again —
    // a second reset flips limit to the current position (0) and would adopt an empty frame.
    val encoded = encodeToPlatformBuffer(message, factory, EncodeContext.Empty)
    return SharedFrame(message, SharedBytes.adopt(encoded))
}
