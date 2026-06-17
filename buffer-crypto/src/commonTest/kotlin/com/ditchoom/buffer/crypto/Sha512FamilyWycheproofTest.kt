package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Runs curated real Wycheproof vectors (C2SP `testvectors_v1`) through the implementation:
 * `valid` tags/derivations must be accepted, `invalid` (modified tag/msg, oversized output)
 * must be rejected. This is the negative/tamper discipline for a MAC/KDF producer — the
 * modified-tag cases are exactly the "single-byte flip must not be accepted" contract.
 */
class Sha512FamilyWycheproofTest {
    @Test
    fun hmacSha512Vectors() {
        // Curated subset is restricted to full-length (512-bit) tag groups, so the comparison
        // is an exact constant-time match against the library's full 64-byte tag.
        val summary =
            Wycheproof.run(WycheproofVectorsHmacSha512.JSON) { case ->
                val tag = hmacSha512(case.testHex("key"), case.testHex("msg"))
                tag.constantTimeEquals(case.testHex("tag"))
            }
        assertTrue(summary.total > 0, "expected curated HMAC-SHA512 vectors to run")
    }

    @Test
    fun hkdfSha512Vectors() {
        val summary =
            Wycheproof.run(WycheproofVectorsHkdfSha512.JSON) { case ->
                // Oversized `size` (> 255*HashLen) makes derive throw IllegalArgumentException,
                // which the runner treats as rejection — matching the `invalid` verdict.
                val okm =
                    HkdfSha512.derive(
                        salt = case.testHexOrNull("salt"),
                        ikm = case.testHex("ikm"),
                        info = case.testHexOrNull("info"),
                        length = case.testInt("size"),
                        factory = BufferFactory.Default,
                    )
                okm.constantTimeEquals(case.testHex("okm"))
            }
        assertTrue(summary.total > 0, "expected curated HKDF-SHA512 vectors to run")
    }
}
