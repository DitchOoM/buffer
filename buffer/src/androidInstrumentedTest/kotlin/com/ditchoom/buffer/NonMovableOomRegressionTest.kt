package com.ditchoom.buffer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.ThreadingMode
import org.junit.FixMethodOrder
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Regression tests for the Android large-message OOM caused by non-moving-space
 * fragmentation (Autobahn case 9.2.5, CI run 28618587934).
 *
 * On ART, `ByteBuffer.allocateDirect` is backed by `VMRuntime.newNonMovableArray` in the
 * non-moving heap space — a fixed-capacity region the GC never compacts. Mixed-size churn
 * of MB-scale direct buffers fragments it until a large contiguous allocation fails even
 * with tens of MB free ("malloc_space fragmentation").
 *
 * case1 drives the failure through [BufferPool] with the acquire/release pattern the
 * websocket read path produces: large assembled-message buffers of unique odd sizes
 * interleaved with small chop buffers held mid-frame. It goes green if either the pool
 * stops churning (size-class reuse) or the factory stops allocating large buffers in the
 * non-moving space. The size-class pool fix (BufferSizeClass) is what keeps it green.
 *
 * case2 is IGNORED: it asserts that a raw [BufferFactory.Default] allocation succeeds
 * with both ART spaces already fragmented by held pins — something only a factory-level
 * fix (routing large allocations to owned native malloc) can guarantee. That fix was
 * implemented and then rejected: it changes the Default-factory lifecycle contract
 * (buffers become leak-on-drop CloseableBuffers, and a GC safety net is unsound on ART
 * because derived ByteBuffer views do not retain the original buffer), which is worse
 * than the narrow affected window (stock non-Mainline ART + largeHeap budget + hours of
 * mixed-size churn — see ANDROID_ART_ALLOCATOR.md). The test is kept as the repro
 * recipe should the factory-level route ever be revisited.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class NonMovableOomRegressionTest {
    private companion object {
        const val KIB = 1024
        const val MIB = 1024 * KIB

        /** 8 MiB + 29 — the exact allocation size that failed in CI (8,388,637 bytes). */
        const val FAILING_SIZE = 8 * MIB + 29
    }

    @Test
    fun case1_pooledLargeMessageChurn_finalLargeAcquireSucceeds() {
        val pool =
            BufferPool(
                threadingMode = ThreadingMode.MultiThreaded,
                maxPoolSize = 8,
                defaultBufferSize = 8 * KIB,
                factory = BufferFactory.Default,
            )
        try {
            // Consecutive Autobahn-style cases: each case acquires one large
            // assembled-message buffer of a unique odd size while small chop buffers are
            // held mid-frame. Sizes grow monotonically per round so no dead hole in the
            // non-moving space exactly fits a later request.
            repeat(6) { round ->
                for (mb in intArrayOf(1, 2, 4, 8)) {
                    val chops = ArrayList<ReadWriteBuffer>()
                    repeat(8) { chops += pool.acquire(8 * KIB) }
                    val big = pool.acquire(mb * MIB + 29 + round * 8 * KIB)
                    big.resetForWrite()
                    big.writeByte(0)
                    chops.forEach { pool.release(it) }
                    pool.release(big)
                }
            }
            val final = pool.acquire(FAILING_SIZE) as PlatformBuffer
            assertTrue(final.capacity >= FAILING_SIZE)
            pool.release(final)
        } catch (e: OutOfMemoryError) {
            fail("non-moving space fragmentation reproduced through the pool: ${e.message}")
        } finally {
            pool.clear()
        }
    }

    @Test
    @Ignore(
        "Requires factory-level rerouting of large Default allocations to owned native " +
            "malloc, which was evaluated and rejected (leak-on-drop lifecycle contract; " +
            "see class KDoc). Kept as the deterministic two-front fragmentation repro.",
    )
    // The explicit gc() calls are load-bearing: this repro must force reclamation of the
    // dropped 1 MiB churn between the two fragmentation fronts, not wait for a background GC.
    @Suppress("ExplicitGarbageCollectionCall")
    fun case2_fragmentedSpaces_largeDefaultAllocationSucceeds() {
        // ART routes newNonMovableArray >= 12 KiB to the Large Object Space and smaller
        // arrays to the non-moving malloc space; an allocation that fails in the LOS
        // falls back to the non-moving space. The CI crash needed BOTH fronts degraded:
        // the LOS freelist region fragmented by large-message churn, and the non-moving
        // space fragmented by 8 KiB stream-processor churn (its OOM message is the one
        // reported: 47 MB in use, largest contiguous 516800 bytes). Reproduce both.
        //
        // Requires a CI-like heap budget (dalvik.vm.heapgrowthlimit=576m); under the
        // stock 192m emulator limit the LOS region can never be driven to exhaustion,
        // so this test cannot go red there.
        // The freeNativeMemory() calls are no-ops on GC-managed allocateDirect buffers;
        // they are kept so the cleanup stays correct if an owning factory route returns.
        val nmPins = ArrayList<PlatformBuffer>() // 8 KiB < LOS threshold -> non-moving space
        val losPins = ArrayList<PlatformBuffer>() // 16 KiB >= threshold -> LOS
        var bigs = ArrayList<PlatformBuffer>() // ~1 MiB -> LOS
        var result: PlatformBuffer? = null
        try {
            // Front 1 — non-moving space: alternate pin/drop 8 KiB buffers (~20 MiB
            // pinned) so every hole between live pins is <= 8 KiB.
            try {
                repeat(5000) { i ->
                    val b = BufferFactory.Default.allocate(8 * KIB)
                    if (i % 2 == 0) nmPins += b
                }
            } catch (expected: OutOfMemoryError) {
            }
            // Front 2 — LOS: alternate ~1 MiB churn with 16 KiB pins in address order,
            // then drop the churn. Freed 1 MiB holes stay bounded by live pins, so no
            // contiguous run in the freelist region can serve an 8 MiB request.
            try {
                repeat(480) {
                    bigs += BufferFactory.Default.allocate(1 * MIB + 61)
                    losPins += BufferFactory.Default.allocate(16 * KIB)
                }
            } catch (expected: OutOfMemoryError) {
            }
            bigs.forEach { it.freeNativeMemory() }
            bigs = ArrayList()
            Runtime.getRuntime().gc()

            result =
                try {
                    BufferFactory.Default.allocate(FAILING_SIZE)
                } catch (e: OutOfMemoryError) {
                    fail(
                        "large direct allocation failed with fragmented LOS + non-moving space " +
                            "(nmPins=${nmPins.size}, losPins=${losPins.size}): ${e.message}",
                    )
                }
            assertTrue(result.capacity >= FAILING_SIZE)
        } finally {
            bigs.forEach { it.freeNativeMemory() }
            losPins.forEach { it.freeNativeMemory() }
            result?.freeNativeMemory()
            nmPins.clear()
            losPins.clear()
            Runtime.getRuntime().gc()
        }
    }
}
