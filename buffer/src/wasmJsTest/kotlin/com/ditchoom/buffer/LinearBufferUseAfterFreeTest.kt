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
     * A view of a released buffer refuses access, which is the case that actually corrupts memory:
     * the owner's block goes back to the allocator and is reissued, so a write through the stale
     * view lands on an unrelated buffer — or on a parked block's next-link, which is not noticed
     * until some later allocation of that size class dereferences it.
     *
     * The view's own flag stays false, because releasing a non-owning view is a no-op and that
     * remains true. Liveness is the owner's, and the view consults it.
     */
    @Test
    fun aViewOfAReleasedBufferRefusesAccess() {
        val parent = BufferFactory.Default.allocate(64)
        parent.writeInt(0x1234_5678)
        parent.resetForRead()
        val view = parent.slice()
        assertEquals(0x1234_5678, view.readInt(), "the view reads fine while its owner is live")
        view.resetForRead()

        parent.freeNativeMemory()

        assertTrue(!view.isFreedOrFail(), "releasing a non-owning view is still a no-op")
        assertFailsWith<IllegalStateException> { view.readInt() }
        assertFailsWith<IllegalStateException> { view.writeInt(1) }
        assertFailsWith<IllegalStateException> { view.slice() }
    }

    /**
     * A view of a view is linked to the owner, not to the buffer it was taken from — so the check
     * stays one branch however deep the slicing goes, and a released owner is still caught.
     */
    @Test
    fun aViewOfAViewIsLinkedToTheOwner() {
        val parent = BufferFactory.Default.allocate(64)
        val view = parent.slice().slice().slice()
        parent.freeNativeMemory()
        assertFailsWith<IllegalStateException> { view.readByte() }
    }

    /**
     * Releasing the *view* must not disturb the owner: a view has no block of its own to give back,
     * so this is the direction that would break ordinary `use { }` code if it were symmetric.
     */
    @Test
    fun releasingAViewLeavesItsOwnerUsable() {
        val parent = BufferFactory.Default.allocate(64)
        try {
            val view = parent.slice()
            view.freeNativeMemory()
            parent.writeInt(0x0BAD_F00D)
            parent.resetForRead()
            assertEquals(0x0BAD_F00D, parent.readInt())
        } finally {
            parent.freeNativeMemory()
        }
    }

    private fun PlatformBuffer.isFreedOrFail(): Boolean = (this as CloseableBuffer).isFreed
}
