package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.crypto.CryptoTestVectors.hexBuffer
import com.ditchoom.buffer.crypto.CryptoTestVectors.toHex
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests the boundary transcoders in EcInterop: ECDSA signature DER <-> P1363. Three angles:
 *  - against the real native stack (sign -> DER -> P1363 -> DER -> verify round-trips),
 *  - pure inverse round-trips exercising the INTEGER padding edges (high bit set, leading zeros),
 *  - strict parsing rejects malformed DER / wrong-length P1363.
 */
class EcInteropTest {
    private val ecdsaSchemes =
        listOf(SignatureScheme.EcdsaP256, SignatureScheme.EcdsaP384, SignatureScheme.EcdsaP521)

    private fun field(scheme: SignatureScheme) =
        when (scheme) {
            SignatureScheme.EcdsaP256 -> 32
            SignatureScheme.EcdsaP384 -> 48
            SignatureScheme.EcdsaP521 -> 66
            SignatureScheme.Ed25519 -> error("n/a")
        }

    // --- against the real stack -------------------------------------------------

    @Test
    fun derToP1363ToDerVerifiesOnNativeStack() =
        runTest {
            // Native platforms produce DER; the transcoded-and-restored signature must still verify.
            if (ecdsaSignatureEncoding != EcdsaSignatureEncoding.Der) return@runTest
            for (scheme in ecdsaSchemes) {
                val ops = signatureBlockingOrNull(scheme) ?: continue
                ops.generateSigningKeyBlocking().use { key ->
                    val msg = hexBuffer("00112233445566778899aabbccddeeff")
                    val der = ops.signBlocking(key, msg)
                    val p1363 = ecdsaSignatureToP1363(scheme, der)
                    assertEquals(field(scheme) * 2, p1363.remaining(), "${scheme.schemeName} P1363 width")
                    val restoredDer = ecdsaSignatureToDer(scheme, p1363)
                    assertTrue(
                        ops.verifyBlocking(key.verifyKey, msg, restoredDer),
                        "${scheme.schemeName} der->p1363->der must still verify",
                    )
                }
            }
        }

    // --- pure inverse round-trips (no crypto) ----------------------------------

    @Test
    fun p1363ToDerToP1363IsIdentityAcrossPaddingEdges() {
        for (scheme in ecdsaSchemes) {
            val f = field(scheme)
            // r: high bit set in the top byte (DER must add a 0x00 sign byte).
            // s: several leading zero bytes (DER must strip them; P1363 must re-pad).
            val r = ByteArray(f) { if (it == 0) 0xF1.toByte() else (it and 0xFF).toByte() }
            val s = ByteArray(f) { if (it < 3) 0 else (0x80 or it).toByte() }
            val p1363 = bytes(r + s, BufferFactory.Default)
            val der = ecdsaSignatureToDer(scheme, p1363)
            val back = ecdsaSignatureToP1363(scheme, der)
            assertEquals((r + s).toHexString(), back.toHex(), "${scheme.schemeName} p1363->der->p1363 identity")
        }
    }

    @Test
    fun derToP1363ToDerReproducesCanonicalDer() {
        // A hand-built canonical P-256 DER signature (r high-bit-set so it carries a 0x00 pad).
        val derHex =
            "3046" +
                "022100" + "f1" + "1e".repeat(31) +
                "0221008e" + "2d".repeat(31)
        val der = hexBuffer(derHex)
        val p1363 = ecdsaSignatureToP1363(SignatureScheme.EcdsaP256, der)
        assertEquals(64, p1363.remaining())
        val restored = ecdsaSignatureToDer(SignatureScheme.EcdsaP256, p1363)
        assertEquals(derHex, restored.toHex(), "canonical DER reproduced")
    }

    // --- strict parsing ---------------------------------------------------------

    @Test
    fun rejectsTrailingBytesInDer() {
        val good = ecdsaSignatureToDer(SignatureScheme.EcdsaP256, bytes(ByteArray(64) { 1 }, BufferFactory.Default))
        val withTrailer = bytes(readBytes(good) + byteArrayOf(0), BufferFactory.Default)
        val e = assertFailsWith<EcEncodingException> { ecdsaSignatureToP1363(SignatureScheme.EcdsaP256, withTrailer) }
        assertEquals(EcEncodingError.MalformedDer, e.error)
    }

    @Test
    fun rejectsNonMinimalInteger() {
        // SEQUENCE(len 8) { INTEGER 00 00 01, INTEGER 01 } — superfluous leading zero in r.
        val der = hexBuffer("3008" + "0203000001" + "020101")
        val e = assertFailsWith<EcEncodingException> { ecdsaSignatureToP1363(SignatureScheme.EcdsaP256, der) }
        assertEquals(EcEncodingError.NonMinimalInteger, e.error)
    }

    @Test
    fun rejectsWrongLengthP1363() {
        val e =
            assertFailsWith<EcEncodingException> {
                ecdsaSignatureToDer(SignatureScheme.EcdsaP256, bytes(ByteArray(63) { 1 }, BufferFactory.Default))
            }
        assertEquals(EcEncodingError.WrongSignatureLength, e.error)
    }

    @Test
    fun rejectsEd25519() {
        val e =
            assertFailsWith<EcEncodingException> {
                ecdsaSignatureToP1363(SignatureScheme.Ed25519, bytes(ByteArray(64) { 1 }, BufferFactory.Default))
            }
        assertEquals(EcEncodingError.UnsupportedScheme, e.error)
    }

    private fun bytes(
        b: ByteArray,
        factory: BufferFactory,
    ): ReadBuffer {
        val out = factory.allocate(b.size)
        for (x in b) out.writeByte(x)
        out.resetForRead()
        return out
    }

    private fun readBytes(buffer: ReadBuffer): ByteArray {
        val start = buffer.position()
        return ByteArray(buffer.remaining()) { buffer.get(start + it) }
    }

    private fun ByteArray.toHexString(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}
