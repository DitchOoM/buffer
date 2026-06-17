package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.ReadWriteBuffer
import com.ditchoom.buffer.crypto.CryptoTestVectors.toHex
import com.ditchoom.buffer.managed
import com.ditchoom.buffer.pool.BufferPool
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The crypto primitives must produce identical output regardless of how the input and
 * destination buffers are backed. Each backing exercises a different branch of the platform
 * bridges:
 *  - heap (managed ByteArray) -> `managedMemoryAccess` array path / `SecretKeySpec(array, …)`
 *  - direct (native)          -> native-pointer / `ByteBuffer` path / `SecretKeySpec(copy)`
 *  - PooledBuffer / slice     -> wrapper delegation (the CLAUDE.md wrapper-transparency contract)
 *
 * A regression in any one branch (e.g. a broken direct-dest fast path) would otherwise pass
 * CI silently, because the existing KAT tests only hit one backing per platform incidentally.
 */
class BufferBackingTests {
    private enum class Backing { HEAP, DIRECT, POOLED, SLICE }

    // SHA-256("abc") and RFC 4231 Case 2 HMAC-SHA256(key="Jefe", msg="what do ya want for nothing?").
    private val sha256Abc = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    private val hmacJefe = "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843"

    private fun load(
        buf: ReadWriteBuffer,
        text: String,
    ): ReadWriteBuffer {
        for (c in text) buf.writeByte(c.code.toByte())
        buf.resetForRead()
        return buf
    }

    /** A read-ready [text] buffer with the requested [kind] backing. */
    private fun input(
        kind: Backing,
        text: String,
        pool: BufferPool,
    ): ReadBuffer =
        when (kind) {
            Backing.HEAP -> load(BufferFactory.managed().allocate(text.length), text)
            Backing.DIRECT -> load(BufferFactory.Default.allocate(text.length), text)
            Backing.POOLED -> load(pool.acquire(text.length), text)
            Backing.SLICE -> load(BufferFactory.managed().allocate(text.length), text).slice()
        }

    /** An empty writable destination of [size] bytes with the requested [kind] backing. */
    private fun dest(
        kind: Backing,
        size: Int,
        pool: BufferPool,
    ): ReadWriteBuffer =
        when (kind) {
            Backing.HEAP -> BufferFactory.managed().allocate(size)
            Backing.DIRECT -> BufferFactory.Default.allocate(size)
            Backing.POOLED -> pool.acquire(size)
            Backing.SLICE -> error("slice is read-only; not a valid destination")
        }

    @Test
    fun sha256AcrossInputAndDestBackings() {
        val pool = BufferPool()
        val inputs = listOf(Backing.HEAP, Backing.DIRECT, Backing.POOLED, Backing.SLICE)
        val dests = listOf(Backing.HEAP, Backing.DIRECT, Backing.POOLED)
        for (ik in inputs) {
            for (dk in dests) {
                val out = dest(dk, SHA256_DIGEST_BYTES, pool)
                sha256(input(ik, "abc", pool), out)
                out.resetForRead()
                assertEquals(sha256Abc, out.toHex(), "sha256 input=$ik dest=$dk")
            }
        }
        pool.clear()
    }

    @Test
    fun hmacAcrossKeyBackings() {
        // Varying the key backing toggles the SecretKeySpec(array, …) vs SecretKeySpec(copy)
        // branch on JVM and the managed/native pointer branch on Apple.
        val pool = BufferPool()
        for (keyKind in listOf(Backing.HEAP, Backing.DIRECT, Backing.POOLED, Backing.SLICE)) {
            val key = input(keyKind, "Jefe", pool)
            val message = input(Backing.DIRECT, "what do ya want for nothing?", pool)
            assertEquals(hmacJefe, hmacSha256(key, message).toHex(), "hmac key=$keyKind")
        }
        pool.clear()
    }
}
