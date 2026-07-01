package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.crypto.CryptoTestVectors.ascii
import com.ditchoom.buffer.crypto.CryptoTestVectors.repeatedByte
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * The `close()` half of the streaming digest/MAC lifecycle: a never-finalized instance releases
 * its state via [AutoCloseable.close] (on Apple/Linux that frees — and zeroes — the native ctx
 * that would otherwise leak). This pins, on EVERY platform: close bars further use, close is
 * idempotent, and close after finalize is a no-op. The finalize-bars-reuse half of the contract
 * is pinned by [DigestFinalizeContractTest].
 */
class DigestOneShotContractTest {
    private val hmacKey = repeatedByte(0x0B, 20)

    /** After close: update and finalize throw; close stays idempotent. */
    private fun closeBarsUse(
        update: (ReadBuffer) -> Unit,
        finalize: (WriteBuffer) -> Unit,
        close: () -> Unit,
        outBytes: Int,
    ) {
        update(ascii("abc"))
        close()
        close() // idempotent — must not double-free the native ctx
        assertFailsWith<IllegalStateException> { update(ascii("x")) }
        assertFailsWith<IllegalStateException> { finalize(BufferFactory.Default.allocate(outBytes)) }
    }

    /** After a successful finalize (which released the state itself), close is a no-op. */
    private fun closeAfterFinalizeIsNoOp(
        update: (ReadBuffer) -> Unit,
        finalize: (WriteBuffer) -> Unit,
        close: () -> Unit,
        outBytes: Int,
    ) {
        update(ascii("abc"))
        finalize(BufferFactory.Default.allocate(outBytes))
        close() // no-op after finalize — must not free the already-freed ctx
        close() // idempotent
    }

    @Test
    fun sha256CloseBarsUse() {
        val d = Sha256Digest()
        closeBarsUse({ d.update(it) }, { d.digestInto(it) }, { d.close() }, SHA256_DIGEST_BYTES)
    }

    @Test
    fun sha256CloseAfterFinalizeIsNoOp() {
        val d = Sha256Digest()
        closeAfterFinalizeIsNoOp({ d.update(it) }, { d.digestInto(it) }, { d.close() }, SHA256_DIGEST_BYTES)
    }

    @Test
    fun sha384CloseBarsUse() {
        val d = Sha384Digest()
        closeBarsUse({ d.update(it) }, { d.digestInto(it) }, { d.close() }, SHA384_DIGEST_BYTES)
    }

    @Test
    fun sha384CloseAfterFinalizeIsNoOp() {
        val d = Sha384Digest()
        closeAfterFinalizeIsNoOp({ d.update(it) }, { d.digestInto(it) }, { d.close() }, SHA384_DIGEST_BYTES)
    }

    @Test
    fun sha512CloseBarsUse() {
        val d = Sha512Digest()
        closeBarsUse({ d.update(it) }, { d.digestInto(it) }, { d.close() }, SHA512_DIGEST_BYTES)
    }

    @Test
    fun sha512CloseAfterFinalizeIsNoOp() {
        val d = Sha512Digest()
        closeAfterFinalizeIsNoOp({ d.update(it) }, { d.digestInto(it) }, { d.close() }, SHA512_DIGEST_BYTES)
    }

    @Test
    fun hmacSha256CloseBarsUse() {
        val m = HmacSha256Mac(hmacKey)
        closeBarsUse({ m.update(it) }, { m.doFinalInto(it) }, { m.close() }, HMAC_SHA256_BYTES)
    }

    @Test
    fun hmacSha256CloseAfterFinalizeIsNoOp() {
        val m = HmacSha256Mac(hmacKey)
        closeAfterFinalizeIsNoOp({ m.update(it) }, { m.doFinalInto(it) }, { m.close() }, HMAC_SHA256_BYTES)
    }

    @Test
    fun hmacSha384CloseBarsUse() {
        val m = HmacSha384Mac(hmacKey)
        closeBarsUse({ m.update(it) }, { m.doFinalInto(it) }, { m.close() }, HMAC_SHA384_BYTES)
    }

    @Test
    fun hmacSha384CloseAfterFinalizeIsNoOp() {
        val m = HmacSha384Mac(hmacKey)
        closeAfterFinalizeIsNoOp({ m.update(it) }, { m.doFinalInto(it) }, { m.close() }, HMAC_SHA384_BYTES)
    }

    @Test
    fun hmacSha512CloseBarsUse() {
        val m = HmacSha512Mac(hmacKey)
        closeBarsUse({ m.update(it) }, { m.doFinalInto(it) }, { m.close() }, HMAC_SHA512_BYTES)
    }

    @Test
    fun hmacSha512CloseAfterFinalizeIsNoOp() {
        val m = HmacSha512Mac(hmacKey)
        closeAfterFinalizeIsNoOp({ m.update(it) }, { m.doFinalInto(it) }, { m.close() }, HMAC_SHA512_BYTES)
    }
}
