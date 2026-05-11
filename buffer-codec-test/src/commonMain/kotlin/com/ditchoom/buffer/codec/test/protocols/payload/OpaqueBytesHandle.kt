package com.ditchoom.buffer.codec.test.protocols.payload

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer

/**
 * Opaque, platform-typed handle for "this Payload carries some opaque bytes
 * — IPC forwarding, persistence, debug capture — and decode-side code
 * shouldn't know the byte layout". Used by [BinaryData] (PNG chunks, TLS
 * handshake tails, MQTT v5 BinaryData properties — all spec'd as "some
 * bytes, opaque to the framework").
 *
 * Same shielding trick as [PlatformBitmap]: KSP's transitive Payload-shape
 * walk descends into the Payload, sees the property typed as
 * [OpaqueBytesHandle], finds the type is not forbidden, not Payload, not a
 * value class — and stops. The handle's actual stores a [PlatformBuffer]
 * internally on every platform — the canonical Pattern #2 from the
 * buffer-codec lockdown plan (consumer-owned `PlatformBuffer` allocated via
 * `factory.allocate(...).write(source)`).
 *
 * Distinct from [PlatformBitmap] only for clarity — the underlying mechanism
 * is identical. Two named types keep the semantic intent visible at the call
 * site (Bitmap is a *bitmap*; OpaqueBytesHandle is *opaque bytes*).
 */
expect class OpaqueBytesHandle

/**
 * Construct from a consumer-owned [PlatformBuffer]. The platform actual
 * takes ownership of [bytes] and is responsible for its lifetime.
 *
 * Named `opaqueBytesFrom` rather than `OpaqueBytesHandle` to avoid a
 * constructor / top-level-fun ambiguity in the actuals.
 */
expect fun opaqueBytesFrom(bytes: PlatformBuffer): OpaqueBytesHandle

/**
 * Returns a [ReadBuffer] view over the bytes storage, position reset to 0.
 */
expect fun OpaqueBytesHandle.asReadBuffer(): ReadBuffer

expect fun OpaqueBytesHandle.byteSize(): Int

expect fun OpaqueBytesHandle.handleEquals(other: OpaqueBytesHandle): Boolean

expect fun OpaqueBytesHandle.handleHashCode(): Int

/**
 * Test convenience: wraps [bytes] into a fresh buffer via
 * [testFixtureFactory] and constructs the handle.
 */
fun opaqueBytesOf(bytes: ByteArray): OpaqueBytesHandle {
    val buf = testFixtureFactory.allocate(bytes.size)
    buf.writeBytes(bytes)
    buf.resetForRead()
    return opaqueBytesFrom(buf)
}

/**
 * Test convenience: materializes the handle's bytes as a heap `ByteArray`
 * via `copyToByteArray`. The `copy` in the called primitive's name makes
 * the cost visible.
 */
fun OpaqueBytesHandle.toBytes(): ByteArray {
    val buf = asReadBuffer()
    return buf.copyToByteArray(buf.remaining())
}

/**
 * Test convenience: same as [toBytes] but as a property for ergonomic test
 * call sites (`handle.bytes` → materialized `ByteArray`). Allocates on each
 * read — production code should prefer [asReadBuffer] for the zero-copy
 * view.
 */
val OpaqueBytesHandle.bytes: ByteArray get() = toBytes()
