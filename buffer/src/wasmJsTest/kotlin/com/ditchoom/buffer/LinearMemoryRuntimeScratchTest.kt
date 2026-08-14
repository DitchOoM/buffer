// Sizes and offsets here are deliberately literal, matching LinearMemoryReclamationTest: each test
// picks its own byte count so the exact-fit free list keeps them independent of execution order.
@file:Suppress("MagicNumber")
@file:OptIn(UnsafeWasmMemoryApi::class)

package com.ditchoom.buffer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.wasm.unsafe.Pointer
import kotlin.wasm.unsafe.UnsafeWasmMemoryApi
import kotlin.wasm.unsafe.withScopedMemoryAllocator

/**
 * The pool must not be based in the region the Kotlin/Wasm runtime allocates its own scratch from.
 *
 * `memory.grow()` returns the previous page count, which reads like a watermark above which the
 * address space is exclusively the caller's. It is not one. The stdlib's own allocator — the one
 * behind `kotlin.wasm.unsafe.withScopedMemoryAllocator`, which every byte-exchanging piece of interop
 * goes through — opens each top-level scope as `ScopedMemoryAllocator(startAddress = 0)` and bumps
 * upward from address 0 every time, growing memory only when its own bump pointer would run past the
 * current size. It cannot be told that somebody else grew memory first.
 *
 * [theRuntimeAllocatesItsScratchFromAddressZero] pins that premise against the stdlib, so the rest of
 * this class does not rest on an inferred claim. The other two are the consequences: the pool must
 * start above the runtime's floor, and a scoped allocation inside that floor must leave live buffer
 * bytes — and the free list's next-links, which live inside released blocks — untouched.
 *
 * Before the fix `heapBase` was whatever `memory.grow` happened to return, which is 0 pages for a
 * WasmGC module that has not yet needed linear memory: the pool then sat directly on top of the
 * runtime's scratch and every scoped allocation corrupted it.
 */
class LinearMemoryRuntimeScratchTest {
    /**
     * The premise, taken from the stdlib rather than assumed: every top-level scope starts at 0, and
     * it stays 0 no matter how much memory the buffer pool has already grown.
     */
    @Test
    fun theRuntimeAllocatesItsScratchFromAddressZero() {
        BufferFactory.Default.allocate(1024).freeNativeMemory()
        val first = withScopedMemoryAllocator { it.allocate(64).address.toInt() }
        val second = withScopedMemoryAllocator { it.allocate(64).address.toInt() }
        assertEquals(0, first, "the stdlib's top-level scope is documented to start at address 0")
        assertEquals(0, second, "and it resets to 0 for every scope, so the region is reused forever")
    }

    @Test
    fun theBufferPoolIsBasedAboveTheRuntimeScratchReserve() {
        BufferFactory.Default.allocate(1032).freeNativeMemory()
        val heapBase = LinearMemoryAllocator.getAllocationStats().heapBase
        assertTrue(
            heapBase >= LinearMemoryAllocator.runtimeScratchReserveBytes,
            "the pool is based at $heapBase, inside the runtime's scratch region " +
                "[0, ${LinearMemoryAllocator.runtimeScratchReserveBytes}) — every " +
                "withScopedMemoryAllocator scope writes there",
        )
    }

    /**
     * The behavioural half. Scribble over the whole reserve through the runtime's own allocator —
     * exactly what interop does, only bigger — and require that a live buffer and a released block's
     * free-list link both survive it.
     *
     * The released block matters as much as the live one: the next-link lives in the freed block's
     * first four bytes, so a stray write there is not noticed until the *next* allocation of that
     * size class loads it and dereferences a wild offset.
     */
    @Test
    fun aRuntimeScopedAllocationInsideTheReserveLeavesThePoolIntact() {
        val live = BufferFactory.Default.allocate(1040)
        repeat(260) { live.writeInt(0x5EED_1234) }

        // Two same-class blocks: releasing the lower one parks it on the free list (the upper one is
        // still live, so it is not a top-of-heap rewind) with its next-link written into its bytes.
        val parked = BufferFactory.Default.allocate(1048)
        val keepAbove = BufferFactory.Default.allocate(1048)
        parked.freeNativeMemory()

        withScopedMemoryAllocator { allocator ->
            val reserve = LinearMemoryAllocator.runtimeScratchReserveBytes
            val base = allocator.allocate(reserve).address.toInt()
            var at = 0
            while (at < reserve) {
                Pointer((base + at).toUInt()).storeInt(0x5A5A_5A5A)
                at += Int.SIZE_BYTES
            }
        }

        live.resetForRead()
        repeat(260) {
            assertEquals(0x5EED_1234, live.readInt(), "runtime scratch overwrote live buffer bytes")
        }

        // Popping the parked block dereferences the link that lived in the scribbled region.
        val reusedBefore = LinearMemoryAllocator.getAllocationStats().reusedBlocks
        val reused = BufferFactory.Default.allocate(1048)
        assertTrue(
            LinearMemoryAllocator.getAllocationStats().reusedBlocks > reusedBefore,
            "the parked block should have been reused, exercising its next-link",
        )
        reused.freeNativeMemory()
        keepAbove.freeNativeMemory()
        live.freeNativeMemory()
    }

    /**
     * The tripwire, which is what makes a residual breach diagnosable: corrupt a parked block's
     * next-link by hand and require the next allocation of that class to report it, rather than trap
     * inside `Pointer.loadInt` with a stack that names nothing.
     */
    @Test
    fun aCorruptedNextLinkIsReportedInsteadOfTrapping() {
        assertCorruptLinkIsReportedAndRecovers(sizeBytes = 1056, corruptLink = Int.MIN_VALUE)
    }

    /**
     * The same tripwire with a link near `Int.MAX_VALUE`, which is the value class the bound has to
     * be written carefully to catch: `offset + aligned > nextOffset` overflows to a negative for
     * anything up there and silently passes, so the guard would wave through exactly the wild
     * offsets it exists to reject and the allocation would trap in `Pointer.loadInt` after all.
     * Fails against the `+ aligned >` form, passes against `> nextOffset - aligned`.
     */
    @Test
    fun aCorruptedNextLinkNearIntMaxIsReportedInsteadOfTrapping() {
        assertCorruptLinkIsReportedAndRecovers(sizeBytes = 1064, corruptLink = Int.MAX_VALUE - 255)
    }

    /**
     * Park a block of [sizeBytes], scribble [corruptLink] over its intrusive next-link exactly as a
     * stale write through a released view would, and require the next allocation of that size class
     * to report it rather than trap.
     *
     * Then require the class to still be usable. The allocator drops the corrupt chain before
     * throwing precisely so one bad link is a single reported incident and not a permanent one — if
     * the head were left installed, every later allocation of this class would re-read it and throw
     * for the life of the process. That recovery is asserted here rather than papered over by
     * repairing linear memory by hand, which would also have to guess the link it overwrote.
     */
    private fun assertCorruptLinkIsReportedAndRecovers(
        sizeBytes: Int,
        corruptLink: Int,
    ) {
        val parked = BufferFactory.Default.allocate(sizeBytes)
        val keepAbove = BufferFactory.Default.allocate(sizeBytes)
        val access =
            checkNotNull(parked.nativeMemoryAccess) {
                "BufferFactory.Default must yield native memory on wasmJs"
            }
        val parkedAddress = access.nativeAddress.toInt()
        parked.freeNativeMemory()

        Pointer(parkedAddress.toUInt()).storeInt(corruptLink)

        val failure =
            assertFailsWith<IllegalStateException> {
                BufferFactory.Default.allocate(sizeBytes)
            }
        assertTrue(
            failure.message!!.contains("free list is corrupt"),
            "expected a diagnosable message, got: ${failure.message}",
        )

        // The chain was dropped on the way out of the throw, so the class allocates again.
        val recovered = BufferFactory.Default.allocate(sizeBytes)
        recovered.freeNativeMemory()
        keepAbove.freeNativeMemory()
    }
}
