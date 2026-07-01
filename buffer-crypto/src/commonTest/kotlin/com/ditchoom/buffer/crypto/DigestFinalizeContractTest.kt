package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.crypto.CryptoTestVectors.ascii
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * The incremental digest/MAC classes are one-shot on every platform: after `digestInto` /
 * `doFinalInto`, both a second finalize and a further `update` throw [IllegalStateException].
 * The contract matters for uniformity (JCA would silently reset for reuse) and for safety on
 * the native backends, where finalize releases the underlying context — reuse there would
 * otherwise touch freed memory.
 */
class DigestFinalizeContractTest {
    private val key = ascii("contract test key")

    private fun out(bytes: Int): WriteBuffer = BufferFactory.Default.allocate(bytes)

    @Test
    fun sha256DoubleFinalizeThrows() {
        val digest = Sha256Digest().update(ascii("abc"))
        digest.digestInto(out(SHA256_DIGEST_BYTES))
        assertFailsWith<IllegalStateException> { digest.digestInto(out(SHA256_DIGEST_BYTES)) }
    }

    @Test
    fun sha256UpdateAfterFinalizeThrows() {
        val digest = Sha256Digest().update(ascii("abc"))
        digest.digestInto(out(SHA256_DIGEST_BYTES))
        assertFailsWith<IllegalStateException> { digest.update(ascii("more")) }
    }

    @Test
    fun sha384DoubleFinalizeThrows() {
        val digest = Sha384Digest().update(ascii("abc"))
        digest.digestInto(out(SHA384_DIGEST_BYTES))
        assertFailsWith<IllegalStateException> { digest.digestInto(out(SHA384_DIGEST_BYTES)) }
    }

    @Test
    fun sha384UpdateAfterFinalizeThrows() {
        val digest = Sha384Digest().update(ascii("abc"))
        digest.digestInto(out(SHA384_DIGEST_BYTES))
        assertFailsWith<IllegalStateException> { digest.update(ascii("more")) }
    }

    @Test
    fun sha512DoubleFinalizeThrows() {
        val digest = Sha512Digest().update(ascii("abc"))
        digest.digestInto(out(SHA512_DIGEST_BYTES))
        assertFailsWith<IllegalStateException> { digest.digestInto(out(SHA512_DIGEST_BYTES)) }
    }

    @Test
    fun sha512UpdateAfterFinalizeThrows() {
        val digest = Sha512Digest().update(ascii("abc"))
        digest.digestInto(out(SHA512_DIGEST_BYTES))
        assertFailsWith<IllegalStateException> { digest.update(ascii("more")) }
    }

    @Test
    fun hmacSha256DoubleFinalizeThrows() {
        val mac = HmacSha256Mac(key).update(ascii("abc"))
        mac.doFinalInto(out(HMAC_SHA256_BYTES))
        assertFailsWith<IllegalStateException> { mac.doFinalInto(out(HMAC_SHA256_BYTES)) }
    }

    @Test
    fun hmacSha256UpdateAfterFinalizeThrows() {
        val mac = HmacSha256Mac(key).update(ascii("abc"))
        mac.doFinalInto(out(HMAC_SHA256_BYTES))
        assertFailsWith<IllegalStateException> { mac.update(ascii("more")) }
    }

    @Test
    fun hmacSha384DoubleFinalizeThrows() {
        val mac = HmacSha384Mac(key).update(ascii("abc"))
        mac.doFinalInto(out(HMAC_SHA384_BYTES))
        assertFailsWith<IllegalStateException> { mac.doFinalInto(out(HMAC_SHA384_BYTES)) }
    }

    @Test
    fun hmacSha384UpdateAfterFinalizeThrows() {
        val mac = HmacSha384Mac(key).update(ascii("abc"))
        mac.doFinalInto(out(HMAC_SHA384_BYTES))
        assertFailsWith<IllegalStateException> { mac.update(ascii("more")) }
    }

    @Test
    fun hmacSha512DoubleFinalizeThrows() {
        val mac = HmacSha512Mac(key).update(ascii("abc"))
        mac.doFinalInto(out(HMAC_SHA512_BYTES))
        assertFailsWith<IllegalStateException> { mac.doFinalInto(out(HMAC_SHA512_BYTES)) }
    }

    @Test
    fun hmacSha512UpdateAfterFinalizeThrows() {
        val mac = HmacSha512Mac(key).update(ascii("abc"))
        mac.doFinalInto(out(HMAC_SHA512_BYTES))
        assertFailsWith<IllegalStateException> { mac.update(ascii("more")) }
    }
}
