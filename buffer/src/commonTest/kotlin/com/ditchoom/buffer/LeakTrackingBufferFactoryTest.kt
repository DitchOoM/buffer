package com.ditchoom.buffer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LeakTrackingBufferFactoryTest {
    private fun captureSink(): Pair<LeakSink, () -> List<TrackedAllocation>> {
        var captured: List<TrackedAllocation> = emptyList()
        val sink = LeakSink { leaks -> captured = leaks }
        return sink to { captured }
    }

    @Test
    fun snapshotIsEmptyWhenAllFreed() {
        val tracker = LeakTrackingBufferFactory(BufferFactory.Default)
        val buf = tracker.allocate(64)
        buf.freeNativeMemory()
        assertTrue(tracker.snapshot().isEmpty())
    }

    @Test
    fun snapshotShowsUnfreedAllocations() {
        val tracker = LeakTrackingBufferFactory(BufferFactory.Default)
        val keep1 = tracker.allocate(64)
        val keep2 = tracker.allocate(128)
        tracker.allocate(256).freeNativeMemory() // this one released
        val live = tracker.snapshot()
        assertEquals(2, live.size)
        assertEquals(setOf(64, 128), live.map { it.size }.toSet())
        // keep references alive
        keep1.position()
        keep2.position()
    }

    @Test
    fun reportLeaksInvokesSinkWithLiveAllocationsOnly() {
        val (sink, captured) = captureSink()
        val tracker = LeakTrackingBufferFactory(BufferFactory.Default, sink)
        tracker.allocate(32).freeNativeMemory()
        val leaked = tracker.allocate(1024) // never freed
        tracker.reportLeaks()
        assertEquals(1, captured().size)
        assertEquals(1024, captured().single().size)
        leaked.position() // keep ref
    }

    @Test
    fun reportLeaksIsNoOpWhenCleanShutdown() {
        val (sink, captured) = captureSink()
        val tracker = LeakTrackingBufferFactory(BufferFactory.Default, sink)
        tracker.allocate(64).freeNativeMemory()
        tracker.allocate(64).freeNativeMemory()
        tracker.reportLeaks()
        assertEquals(0, captured().size)
    }

    @Test
    fun assertingSinkRaisesOnLeak() {
        val tracker = LeakTrackingBufferFactory(BufferFactory.Default, LeakSink.Asserting)
        val leaked = tracker.allocate(42)
        val err = assertFailsWith<AssertionError> { tracker.reportLeaks() }
        assertTrue(
            err.message?.contains("42") == true,
            "Expected alloc size 42 in leak message, got: ${err.message}",
        )
        leaked.position()
    }

    @Test
    fun wrappedBufferDelegatesIO() {
        val tracker = LeakTrackingBufferFactory(BufferFactory.Default)
        val buf = tracker.allocate(16)
        buf.writeInt(0xCAFEBABE.toInt())
        buf.writeInt(0x12345678)
        buf.resetForRead()
        assertEquals(0xCAFEBABE.toInt(), buf.readInt())
        assertEquals(0x12345678, buf.readInt())
        buf.freeNativeMemory()
    }

    @Test
    fun wrappedBufferUnwrapFullyReachesInner() {
        val tracker = LeakTrackingBufferFactory(BufferFactory.Default)
        val buf = tracker.allocate(16)
        val inner = buf.unwrapFully()
        // Fully unwrapped buffer must NOT be the LeakTrackingBuffer wrapper
        assertTrue(inner !is LeakTrackingBuffer, "unwrapFully must strip the tracking wrapper")
        buf.freeNativeMemory()
    }

    @Test
    fun doubleFreeIsIdempotent() {
        val tracker = LeakTrackingBufferFactory(BufferFactory.Default)
        val buf = tracker.allocate(16)
        buf.freeNativeMemory()
        buf.freeNativeMemory() // must not corrupt the tracking table
        assertTrue(tracker.snapshot().isEmpty())
    }

    @Test
    fun freeIfNeededRoutesThroughWrapper() {
        val tracker = LeakTrackingBufferFactory(BufferFactory.Default)
        val buf = tracker.allocate(32)
        (buf as ReadBuffer).freeIfNeeded() // common extension path
        assertTrue(tracker.snapshot().isEmpty())
    }

    @Test
    fun wrapPathIsTrackedToo() {
        val tracker = LeakTrackingBufferFactory(BufferFactory.Default)
        val arr = ByteArray(8)
        val buf = tracker.wrap(arr)
        assertEquals(1, tracker.snapshot().size)
        assertEquals("wrap", tracker.snapshot().single().kind)
        buf.freeNativeMemory()
        assertTrue(tracker.snapshot().isEmpty())
    }

    @Test
    fun stackTraceIncludesCallerFrame() {
        val tracker = LeakTrackingBufferFactory(BufferFactory.Default)
        val leaked = tracker.allocate(64)
        val stack = tracker.snapshot().single().stack
        assertTrue(
            stack.contains("stackTraceIncludesCallerFrame") || stack.lines().size > 2,
            "Stack trace should have more than 2 frames, got:\n$stack",
        )
        leaked.position()
    }

    @Test
    fun decoratorStackingWorks() {
        // Verify the decorator composes with other factory decorators (the
        // whole reason LeakTrackingBufferFactory takes a generic delegate).
        val limited = BufferFactory.Default.withSizeLimit(1024)
        val tracker = LeakTrackingBufferFactory(limited)
        val buf = tracker.allocate(512)
        assertEquals(1, tracker.snapshot().size)
        assertFailsWith<IllegalArgumentException> { tracker.allocate(2048) }
        // Failing allocation must NOT create a leak entry
        assertEquals(1, tracker.snapshot().size)
        buf.freeNativeMemory()
        assertTrue(tracker.snapshot().isEmpty())
    }
}
