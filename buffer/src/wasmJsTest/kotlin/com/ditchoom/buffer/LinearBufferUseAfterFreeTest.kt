@file:Suppress("MagicNumber")

package com.ditchoom.buffer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A released [LinearBuffer] must refuse access, the way every other platform's does.
 *
 * [CloseableBuffer.isFreed] states that "once true, all read/write operations on this buffer will
 * throw". Linux enforces it with `checkOpen()`, the JVM's deterministic buffers throw from their
 * accessors, and `FfmBuffer` inherits it from the JDK arena — wasmJs set the flag and never read it,
 * so a read after release returned whatever the allocator had since put in that block and a write
 * corrupted it, silently, on the one platform where releasing is mandatory rather than optional.
 *
 * The failure is worse here than the silence suggests. The free list is intrusive, so a stale write
 * into a released block also rewrites its next-link, and the resulting `memory access out of bounds`
 * lands on an unrelated allocation an arbitrary time later.
 */
class LinearBufferUseAfterFreeTest {
    private fun freedBuffer(size: Int = 64): PlatformBuffer {
        val buffer = BufferFactory.Default.allocate(size)
        buffer.writeInt(0x1234_5678)
        buffer.resetForRead()
        buffer.freeNativeMemory()
        return buffer
    }

    @Test
    fun readingAfterFreeThrows() {
        val buffer = freedBuffer()
        assertTrue(buffer.isFreedOrFail(), "the buffer should report itself freed")
        assertFailsWith<IllegalStateException> { buffer.readInt() }
        assertFailsWith<IllegalStateException> { buffer.readByte() }
        assertFailsWith<IllegalStateException> { buffer[0] }
    }

    @Test
    fun writingAfterFreeThrows() {
        val buffer = freedBuffer()
        assertFailsWith<IllegalStateException> { buffer.writeInt(1) }
        assertFailsWith<IllegalStateException> { buffer.writeByte(1) }
        assertFailsWith<IllegalStateException> { buffer[0] = 1.toByte() }
    }

    /**
     * The address is how a stale view is built in the first place, so handing it out after release
     * is the beginning of the bug rather than a harmless read of a number.
     */
    @Test
    fun handingOutTheNativeAddressAfterFreeThrows() {
        val buffer = freedBuffer()
        val access =
            checkNotNull(buffer.nativeMemoryAccess) {
                "BufferFactory.Default must yield native memory on wasmJs"
            }
        assertFailsWith<IllegalStateException> { access.nativeAddress }
    }

    @Test
    fun slicingAfterFreeThrows() {
        val buffer = freedBuffer()
        assertFailsWith<IllegalStateException> { buffer.slice() }
    }

    /**
     * Release stays idempotent. It is called from `use { }` on every path, including ones that have
     * already released explicitly, so making the second call throw would break ordinary code — this
     * check is about *access* after release, not about counting releases. Detecting a genuine double
     * free is issue #362's opt-in factory.
     */
    @Test
    fun releasingTwiceIsStillANoOp() {
        val buffer = BufferFactory.Default.allocate(64)
        buffer.freeNativeMemory()
        buffer.freeNativeMemory()
        assertTrue(buffer.isFreedOrFail())
    }

    /**
     * A live buffer is unaffected — the guard must not be a blanket refusal. Worth pinning because
     * the check sits on `ptr()`, the choke point every scalar load and store goes through.
     */
    @Test
    fun aLiveBufferIsUntouchedByTheGuard() {
        val buffer = BufferFactory.Default.allocate(64)
        try {
            buffer.writeInt(0x0BAD_F00D)
            buffer.resetForRead()
            assertEquals(0x0BAD_F00D, buffer.readInt())
            buffer.slice()
        } finally {
            buffer.freeNativeMemory()
        }
    }

    /**
     * A slice of a *released* parent is deliberately NOT covered. Non-owning views never set the
     * flag, so catching this needs the parent's liveness rather than the view's — the propagation
     * issue #362 proposes, and out of scope for a check that only fixes wasmJs's divergence from
     * every other platform's contract. Pinned so the gap is a recorded decision rather than an
     * assumption someone makes later.
     */
    @Test
    fun aSliceOfAReleasedParentIsNotCaughtYet() {
        val parent = BufferFactory.Default.allocate(64)
        val slice = parent.slice()
        parent.freeNativeMemory()
        assertTrue(!slice.isFreedOrFail(), "a non-owning view does not carry its parent's flag")
    }

    private fun PlatformBuffer.isFreedOrFail(): Boolean = (this as CloseableBuffer).isFreed
}
