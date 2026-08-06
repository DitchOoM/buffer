package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.CountingBufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.counting
import com.ditchoom.buffer.crypto.CryptoTestVectors.hexBuffer
import com.ditchoom.buffer.crypto.CryptoTestVectors.toHex
import com.ditchoom.buffer.managed
import com.ditchoom.buffer.pool.BufferPool
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private const val ITERATIONS = 8
private const val POOL_CHUNK_BYTES = 64
private const val POOL_MAX_BUFFERS = 4
private const val FLIP_MASK = 0x01
private const val PAD_BYTES = 5

/**
 * An AEAD `open` must not retain a reference to the caller's ciphertext buffer.
 *
 * The Apple backend used to take two `slice()` views of `ciphertextAndTag` (one for the
 * ciphertext, one for the tag) and never hand them back. On a pooled input every `open` therefore
 * left the chunk's refcount above zero, so `freeNativeMemory()` never returned it to the pool: the
 * hit rate collapsed to zero and the process allocated a fresh chunk per record — including on the
 * attacker-reachable rejection path. See issue #332.
 *
 * The fixture lives in commonTest on purpose: `aesGcmOpen` / `chaChaPolyOpen` are `expect` funs, so
 * one suite gates every platform's actual against this bug class rather than only the platform that
 * carried it.
 *
 * It drives the explicit-nonce primitives rather than the framed `ops.open`, because
 * `splitFramed` slices the caller's frame the same way on every platform — an out-of-scope leak
 * that would make this fixture fail everywhere for the wrong reason.
 */
class AeadPoolLifecycleTest {
    // NIST AES-128-GCM vector inputs (see AeadTest / AeadBackingTests), truncated to a whole
    // number of plaintext bytes. The ciphertext length is constant across iterations so the pool
    // hands back the same size class every time.
    private val keyHex = "feffe9928665731c6d6a8f9467308308"
    private val ivHex = "cafebabefacedbaddecaf888"
    private val aadHex = "feedfacedeadbeeffeedfacedeadbeefabaddad2"
    private val ptHex = "d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a72"
    private val chaChaKeyHex = "808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f"

    @Test
    fun aesGcmSyncOpenSuccessDrainsPool() =
        runTest {
            if (!aesGcmBlockingAvailable) return@runTest
            val key = AesGcmKey.of(hexBuffer(keyHex))
            val sealed = sealAesGcm(key)
            val (pool, backing) = countedPool()
            repeat(ITERATIONS) {
                val chunk = pooledCopy(sealed, pool)
                try {
                    val dest = BufferFactory.Default.allocate(ptHex.length / 2)
                    aesGcmOpen(key, hexBuffer(ivHex), hexBuffer(aadHex), chunk, dest)
                    dest.resetForRead()
                    assertEquals(ptHex, dest.toHex(), "AES-GCM sync open must round-trip")
                } finally {
                    chunk.freeNativeMemory()
                }
            }
            assertPoolDrained(pool, backing, "aesGcmOpen success")
            assertEquals(1L, backing.allocationCount, "pool must reuse one chunk across $ITERATIONS opens")
            pool.clear()
        }

    @Test
    fun aesGcmSyncOpenVerificationFailedDrainsPool() =
        runTest {
            if (!aesGcmBlockingAvailable) return@runTest
            val key = AesGcmKey.of(hexBuffer(keyHex))
            val sealed = sealAesGcm(key)
            val tagByte = sealed.remaining() - 1
            val (pool, backing) = countedPool()
            repeat(ITERATIONS) {
                val chunk = pooledCopy(sealed, pool, flipIndex = tagByte)
                try {
                    val dest = BufferFactory.Default.allocate(ptHex.length / 2)
                    assertFailsWith<VerificationFailed>("flipped tag byte must reject") {
                        aesGcmOpen(key, hexBuffer(ivHex), hexBuffer(aadHex), chunk, dest)
                    }
                } finally {
                    chunk.freeNativeMemory()
                }
            }
            assertPoolDrained(pool, backing, "aesGcmOpen rejection")
            assertEquals(1L, backing.allocationCount, "rejection path must reuse one chunk")
            pool.clear()
        }

    @Test
    fun chaChaPolySyncOpenSuccessDrainsPool() =
        runTest {
            if (!chaChaPolyBlockingAvailable) return@runTest
            val key = ChaChaPolyKey.of(hexBuffer(chaChaKeyHex))
            val sealed = sealChaChaPoly(key)
            val (pool, backing) = countedPool()
            repeat(ITERATIONS) {
                val chunk = pooledCopy(sealed, pool)
                try {
                    val dest = BufferFactory.Default.allocate(ptHex.length / 2)
                    chaChaPolyOpen(key, hexBuffer(ivHex), hexBuffer(aadHex), chunk, dest)
                    dest.resetForRead()
                    assertEquals(ptHex, dest.toHex(), "ChaCha20-Poly1305 sync open must round-trip")
                } finally {
                    chunk.freeNativeMemory()
                }
            }
            assertPoolDrained(pool, backing, "chaChaPolyOpen success")
            assertEquals(1L, backing.allocationCount, "pool must reuse one chunk across $ITERATIONS opens")
            pool.clear()
        }

    @Test
    fun chaChaPolySyncOpenVerificationFailedDrainsPool() =
        runTest {
            if (!chaChaPolyBlockingAvailable) return@runTest
            val key = ChaChaPolyKey.of(hexBuffer(chaChaKeyHex))
            val sealed = sealChaChaPoly(key)
            val tagByte = sealed.remaining() - 1
            val (pool, backing) = countedPool()
            repeat(ITERATIONS) {
                val chunk = pooledCopy(sealed, pool, flipIndex = tagByte)
                try {
                    val dest = BufferFactory.Default.allocate(ptHex.length / 2)
                    assertFailsWith<VerificationFailed>("flipped tag byte must reject") {
                        chaChaPolyOpen(key, hexBuffer(ivHex), hexBuffer(aadHex), chunk, dest)
                    }
                } finally {
                    chunk.freeNativeMemory()
                }
            }
            assertPoolDrained(pool, backing, "chaChaPolyOpen rejection")
            assertEquals(1L, backing.allocationCount, "rejection path must reuse one chunk")
            pool.clear()
        }

    @Test
    fun aesGcmAsyncOpenSuccessDrainsPool() =
        runTest {
            // Ungated: covers the web targets, whose only AEAD surface is the async one.
            val key = AesGcmKey.of(hexBuffer(keyHex))
            val sealed = sealAesGcm(key)
            val (pool, backing) = countedPool()
            repeat(ITERATIONS) {
                val chunk = pooledCopy(sealed, pool)
                try {
                    // Plaintext-out uses Default, not the pool, so it cannot skew the chunk count.
                    val opened =
                        aesGcmOpenWithNonceAsync(
                            key,
                            hexBuffer(ivHex),
                            hexBuffer(aadHex),
                            chunk,
                            BufferFactory.Default,
                        )
                    assertEquals(ptHex, opened.toHex(), "AES-GCM async open must round-trip")
                } finally {
                    chunk.freeNativeMemory()
                }
            }
            assertPoolDrained(pool, backing, "aesGcmOpenWithNonceAsync success")
            assertEquals(1L, backing.allocationCount, "pool must reuse one chunk across $ITERATIONS opens")
            pool.clear()
        }

    @Test
    fun aesGcmAsyncOpenVerificationFailedDrainsPool() =
        runTest {
            val key = AesGcmKey.of(hexBuffer(keyHex))
            val sealed = sealAesGcm(key)
            val tagByte = sealed.remaining() - 1
            val (pool, backing) = countedPool()
            repeat(ITERATIONS) {
                val chunk = pooledCopy(sealed, pool, flipIndex = tagByte)
                try {
                    assertFailsWith<VerificationFailed>("flipped tag byte must reject") {
                        aesGcmOpenWithNonceAsync(
                            key,
                            hexBuffer(ivHex),
                            hexBuffer(aadHex),
                            chunk,
                            BufferFactory.Default,
                        )
                    }
                } finally {
                    chunk.freeNativeMemory()
                }
            }
            assertPoolDrained(pool, backing, "aesGcmOpenWithNonceAsync rejection")
            assertEquals(1L, backing.allocationCount, "rejection path must reuse one chunk")
            pool.clear()
        }

    /**
     * Every other case in this file hands `open` a buffer sitting at position 0, so the offset
     * arithmetic that replaced the slices is only ever exercised at `from == 0` — the one value
     * where it cannot be wrong. This drives a non-zero start by prefixing the sealed frame with
     * padding and positioning past it, which is the shape a real caller reading out of a framed
     * stream produces.
     */
    @Test
    fun aesGcmSyncOpenAtNonZeroPositionRoundTrips() =
        runTest {
            if (!aesGcmBlockingAvailable) return@runTest
            val key = AesGcmKey.of(hexBuffer(keyHex))
            val sealed = sealAesGcm(key)
            val sealedLen = sealed.remaining()

            val padded = BufferFactory.Default.allocate(PAD_BYTES + sealedLen)
            repeat(PAD_BYTES) { padded.writeByte(0xAB.toByte()) }
            padded.write(sealed)
            padded.resetForRead()
            padded.position(PAD_BYTES)
            assertEquals(sealedLen, padded.remaining(), "padding must not change the frame length")

            val out = BufferFactory.Default.allocate(sealedLen - AEAD_TAG_BYTES)
            aesGcmOpen(key, hexBuffer(ivHex), hexBuffer(aadHex), padded, out)
            out.resetForRead()
            assertEquals(ptHex, out.toHex(), "plaintext from a non-zero start must match")
        }

    /**
     * A zero-length plaintext against a heap destination. `BufferFactory.managed()` is a
     * first-class factory, and pairing it with an empty payload leaves the destination with
     * nothing remaining — which reached `addressOf(backingArray.size)` and threw before the
     * `count == 0` guard existed.
     */
    @Test
    fun aesGcmOpenEmptyPlaintextIntoManagedDestination() =
        runTest {
            if (!aesGcmBlockingAvailable) return@runTest
            val key = AesGcmKey.of(hexBuffer(keyHex))
            val sealedEmpty =
                aesGcmSealWithNonceAsync(
                    key,
                    hexBuffer(ivHex),
                    hexBuffer(aadHex),
                    BufferFactory.managed().allocate(0).also { it.resetForRead() },
                    BufferFactory.managed(),
                )
            assertEquals(AEAD_TAG_BYTES, sealedEmpty.remaining(), "an empty seal is tag-only")

            val out = BufferFactory.managed().allocate(0)
            aesGcmOpen(key, hexBuffer(ivHex), hexBuffer(aadHex), sealedEmpty, out)
            assertEquals(0, out.position(), "nothing to write for an empty plaintext")
        }

    /** One sealed `ciphertext‖tag` frame, built off the pool so only opens are counted. */
    private suspend fun sealAesGcm(key: AesGcmKey): ReadBuffer =
        aesGcmSealWithNonceAsync(
            key,
            hexBuffer(ivHex),
            hexBuffer(aadHex),
            hexBuffer(ptHex),
            BufferFactory.Default,
        )

    /** ChaCha20-Poly1305 mirror of [sealAesGcm]; only called when the blocking bridge exists. */
    private fun sealChaChaPoly(key: ChaChaPolyKey): ReadBuffer {
        val out = BufferFactory.Default.allocate(ptHex.length / 2 + AEAD_TAG_BYTES)
        chaChaPolySeal(key, hexBuffer(ivHex), hexBuffer(aadHex), hexBuffer(ptHex), out)
        out.resetForRead()
        return out
    }
}

/**
 * The only sound drain check: chunks the pool's BACKING factory ever created, minus the chunks
 * sitting in the pool right now. PoolStats alone cannot see this — poolHits/poolMisses count
 * acquires, and a chunk pinned by an unreleased slice reads identically to a chunk that was never
 * acquired (both leave currentPoolSize at 0). See #332.
 */
private fun assertPoolDrained(
    pool: BufferPool,
    backing: CountingBufferFactory,
    what: String,
) {
    val inPool = pool.stats().currentPoolSize
    val outstanding = backing.allocationCount - inPool
    assertEquals(
        0L,
        outstanding,
        "$what: $outstanding chunk(s) never returned " +
            "(backing allocated ${backing.allocationCount}, pool holds $inPool)",
    )
}

/** A pool plus the counting factory behind it, so allocations and returns can be reconciled. */
private fun countedPool(): Pair<BufferPool, CountingBufferFactory> {
    val backing = BufferFactory.Default.counting()
    val pool =
        BufferPool(
            maxPoolSize = POOL_MAX_BUFFERS,
            defaultBufferSize = POOL_CHUNK_BYTES,
            factory = backing,
        )
    return pool to backing
}

/**
 * Copies [source]'s remaining bytes into a chunk borrowed from [pool], read-ready. When
 * [flipIndex] is non-negative that byte is flipped, producing a frame that must fail verification.
 *
 * The chunk MUST be released with `freeNativeMemory()`, never `pool.release(...)`: release()
 * re-pools the inner buffer while ignoring the refcount, which would mask the very leak this
 * fixture exists to catch.
 */
private fun pooledCopy(
    source: ReadBuffer,
    pool: BufferPool,
    flipIndex: Int = -1,
): PlatformBuffer {
    val start = source.position()
    val n = source.remaining()
    val chunk = pool.acquire(n) as PlatformBuffer
    for (i in 0 until n) {
        val b = source.get(start + i)
        chunk.writeByte(if (i == flipIndex) (b.toInt() xor FLIP_MASK).toByte() else b)
    }
    chunk.resetForRead()
    return chunk
}
