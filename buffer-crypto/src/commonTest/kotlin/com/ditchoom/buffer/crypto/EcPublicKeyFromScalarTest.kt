package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.crypto.CryptoTestVectors.hexBuffer
import com.ditchoom.buffer.crypto.CryptoTestVectors.toHex
import com.ditchoom.buffer.pool.BufferPool
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * `ecPublicKeyFromScalar` known answers: the RFC 6979 Appendix A.2.5 / A.2.6 / A.2.7 key pairs
 * (P-256 / P-384 / P-521), plus the boundary scalars `1` (the generator, odd Y) and `n - 1` (its
 * negation, even Y), which pin both Y parities on every platform.
 */
class EcPublicKeyFromScalarTest {
    private class Vector(
        val curve: KeyAgreementCurve,
        val scalar: String,
        val point: String,
    )

    private val p256G =
        "6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296"

    private val vectors =
        listOf(
            Vector(
                KeyAgreementCurve.P256,
                "c9afa9d845ba75166b5c215767b1d6934e50c3db36e89b127b8a622b120f6721",
                "04" +
                    "60fed4ba255a9d31c961eb74c6356d68c049b8923b61fa6ce669622e60f29fb6" +
                    "7903fe1008b8bc99a41ae9e95628bc64f2f1b20c2d7e9f5177a3c294d4462299",
            ),
            Vector(
                KeyAgreementCurve.P384,
                "6b9d3dad2e1b8c1c05b19875b6659f4de23c3b667bf297ba9aa47740787137d8" +
                    "96d5724e4c70a825f872c9ea60d2edf5",
                "04" +
                    "ec3a4e415b4e19a4568618029f427fa5da9a8bc4ae92e02e06aae5286b300c64" +
                    "def8f0ea9055866064a254515480bc13" +
                    "8015d9b72d7d57244ea8ef9ac0c621896708a59367f9dfb9f54ca84b3f1c9db1" +
                    "288b231c3ae0d4fe7344fd2533264720",
            ),
            Vector(
                KeyAgreementCurve.P521,
                "00fad06daa62ba3b25d2fb40133da757205de67f5bb0018fee8c86e1b68c7e75" +
                    "caa896eb32f1f47c70855836a6d16fcc1466f6d8fbec67db89ec0c08b0e996b83538",
                "04" +
                    "01894550d0785932e00eaa23b694f213f8c3121f86dc97a04e5a7167db4e5bcd" +
                    "371123d46e45db6b5d5370a7f20fb633155d38ffa16d2bd761dcac474b9a2f5023a4" +
                    "00493101c962cd4d2fddf782285e64584139c2f91b47f87ff82354d6630f746a" +
                    "28a0db25741b5b34a828008b22acc23f924faafbd4d33f81ea66956dfeaa2bfdfcf5",
            ),
            Vector(
                KeyAgreementCurve.P256,
                "0000000000000000000000000000000000000000000000000000000000000001",
                "04" + p256G + "4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5",
            ),
            Vector(
                KeyAgreementCurve.P256,
                "ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632550",
                "04" + p256G + "b01cbd1c01e58065711814b583f061e9d431cca994cea1313449bf97c840ae0a",
            ),
        )

    @Test
    fun knownAnswers() =
        runTest {
            for (v in vectors) {
                val scalar = hexBuffer(v.scalar)
                assertEquals(v.point, ecPublicKeyFromScalar(v.curve, scalar).toHex(), "${v.curve} d=${v.scalar}")
                assertEquals(v.scalar.length / 2, scalar.remaining(), "scalar must be read non-destructively")
            }
        }

    @Test
    fun identicalOutputAcrossScalarBackings() =
        runTest {
            val pool = BufferPool()
            val v = vectors.first()
            for (kind in CryptoBackings.inputs) {
                val scalar = CryptoBackings.place(kind, hexBuffer(v.scalar), pool)
                assertEquals(v.point, ecPublicKeyFromScalar(v.curve, scalar).toHex(), "scalar=$kind")
            }
            pool.clear()
        }

    @Test
    fun rejectsScalarsOutsideOneToNMinusOne() =
        runTest {
            for (curve in listOf(KeyAgreementCurve.P256, KeyAgreementCurve.P384, KeyAgreementCurve.P521)) {
                val order = ecOrderHex(curve)
                val outOfRange =
                    listOf(
                        "00".repeat(order.length / 2),
                        order,
                        "ff".repeat(order.length / 2),
                    )
                for (scalar in outOfRange) {
                    val e = assertFailsWith<EcEncodingException> { ecPublicKeyFromScalar(curve, hexBuffer(scalar)) }
                    assertEquals(EcEncodingError.ScalarOutOfRange, e.error, "$curve d=$scalar")
                }
            }
        }

    @Test
    fun rejectsWrongWidthAndX25519() =
        runTest {
            val short =
                assertFailsWith<EcEncodingException> {
                    ecPublicKeyFromScalar(KeyAgreementCurve.P256, hexBuffer("01"))
                }
            assertEquals(EcEncodingError.WrongKeyLength, short.error)
            val long =
                assertFailsWith<EcEncodingException> {
                    ecPublicKeyFromScalar(KeyAgreementCurve.P256, hexBuffer("00" + vectors.first().scalar))
                }
            assertEquals(EcEncodingError.WrongKeyLength, long.error)
            val x25519 =
                assertFailsWith<EcEncodingException> {
                    ecPublicKeyFromScalar(KeyAgreementCurve.X25519, hexBuffer("01".repeat(32)))
                }
            assertEquals(EcEncodingError.UnsupportedCurve, x25519.error)
        }
}
