package com.ditchoom.buffer.codec.test.protocols.payload

import com.ditchoom.buffer.codec.OpaqueBytesHandle
import com.ditchoom.buffer.codec.asReadBuffer
import com.ditchoom.buffer.codec.opaqueBytesFrom

/**
 * Test convenience: wraps [bytes] into a fresh buffer via
 * [testFixtureFactory] and constructs the handle. Production code constructs
 * via [opaqueBytesFrom] with an explicit `PlatformBuffer` allocator.
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
