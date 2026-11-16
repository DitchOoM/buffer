package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.crypto.CryptoTestVectors.ascii
import com.ditchoom.buffer.crypto.CryptoTestVectors.hexBuffer
import com.ditchoom.buffer.crypto.CryptoTestVectors.repeatedByte
import com.ditchoom.buffer.crypto.CryptoTestVectors.toHex
import com.ditchoom.buffer.pool.BufferPool
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.minutes

/**
 * PBKDF2-HMAC-SHA256 / -SHA512 (RFC 8018) known answers, run through the async witness ops so the
 * same vectors check every platform, plus the blocking ops where the platform has them.
 *
 * Sources: RFC 7914 §11 (PBKDF2-HMAC-SHA256, including c = 80 000); the RFC 6070 inputs with SHA-256
 * and SHA-512 substituted for SHA-1 (the widely republished SHA-2 extension of RFC 6070). The
 * remaining cases (a non-UTF-8 password, a multi-block partial output, an over-block-size password,
 * c = 210 000, empty password/salt) were computed with OpenSSL's PKCS5_PBKDF2_HMAC via Python
 * `hashlib.pbkdf2_hmac`.
 */
class Pbkdf2Test {
    private class Vector(
        val prf: Pbkdf2Prf,
        val password: () -> ReadBuffer,
        val salt: () -> ReadBuffer,
        val iterations: Int,
        val expected: String,
    ) {
        val length get() = expected.length / 2
    }

    private val rfc6070Password = "passwordPASSWORDpassword"
    private val rfc6070Salt = "saltSALTsaltSALTsaltSALTsaltSALTsalt"

    private val vectors =
        listOf(
            // RFC 7914 §11.
            Vector(
                Pbkdf2Prf.HmacSha256,
                { ascii("passwd") },
                { ascii("salt") },
                1,
                "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc" +
                    "49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783",
            ),
            Vector(
                Pbkdf2Prf.HmacSha256,
                { ascii("Password") },
                { ascii("NaCl") },
                80_000,
                "4ddcd8f60b98be21830cee5ef22701f9641a4418d04c0414aeff08876b34ab56" +
                    "a1d425a1225833549adb841b51c9b3176a272bdebba1d078478f62b397f33c8d",
            ),
            // RFC 6070 inputs, HMAC-SHA256.
            Vector(
                Pbkdf2Prf.HmacSha256,
                { ascii("password") },
                { ascii("salt") },
                1,
                "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b",
            ),
            Vector(
                Pbkdf2Prf.HmacSha256,
                { ascii("password") },
                { ascii("salt") },
                2,
                "ae4d0c95af6b46d32d0adff928f06dd02a303f8ef3c251dfd6e2d85a95474c43",
            ),
            Vector(
                Pbkdf2Prf.HmacSha256,
                { ascii("password") },
                { ascii("salt") },
                4096,
                "c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a",
            ),
            Vector(
                Pbkdf2Prf.HmacSha256,
                { ascii(rfc6070Password) },
                { ascii(rfc6070Salt) },
                4096,
                "348c89dbcbd32b2f32d814b8116e84cf2b17347ebc1800181c4e2a1fb8dd53e1c635518c7dac47e9",
            ),
            Vector(
                Pbkdf2Prf.HmacSha256,
                { hexBuffer("7061737300776f7264") },
                { hexBuffer("7361006c74") },
                4096,
                "89b69d0516f829893c696226650a8687",
            ),
            // RFC 6070 inputs, HMAC-SHA512.
            Vector(
                Pbkdf2Prf.HmacSha512,
                { ascii("password") },
                { ascii("salt") },
                1,
                "867f70cf1ade02cff3752599a3a53dc4af34c7a669815ae5d513554e1c8cf252" +
                    "c02d470a285a0501bad999bfe943c08f050235d7d68b1da55e63f73b60a57fce",
            ),
            Vector(
                Pbkdf2Prf.HmacSha512,
                { ascii("password") },
                { ascii("salt") },
                2,
                "e1d9c16aa681708a45f5c7c4e215ceb66e011a2e9f0040713f18aefdb866d53c" +
                    "f76cab2868a39b9f7840edce4fef5a82be67335c77a6068e04112754f27ccf4e",
            ),
            Vector(
                Pbkdf2Prf.HmacSha512,
                { ascii("password") },
                { ascii("salt") },
                4096,
                "d197b1b33db0143e018b12f3d1d1479e6cdebdcc97c5c0f87f6902e072f457b5" +
                    "143f30602641b3d55cd335988cb36b84376060ecd532e039b742a239434af2d5",
            ),
            Vector(
                Pbkdf2Prf.HmacSha512,
                { ascii(rfc6070Password) },
                { ascii(rfc6070Salt) },
                4096,
                "8c0511f4c6e597c6ac6315d8f0362e225f3c501495ba23b868c005174dc4ee71" +
                    "115b59f9e60cd9532fa33e0f75aefe30225c583a186cd82bd4daea9724a3d3b8",
            ),
            Vector(
                Pbkdf2Prf.HmacSha512,
                { hexBuffer("7061737300776f7264") },
                { hexBuffer("7361006c74") },
                4096,
                "9d9e9c4cd21fe4be24d5b8244c759665",
            ),
            // High iteration count (the OWASP floor for PBKDF2-HMAC-SHA512).
            Vector(
                Pbkdf2Prf.HmacSha512,
                { ascii("password") },
                { ascii("salt") },
                210_000,
                "afd45a832dc9e7f4d2c43bf634617930f239154a76eea9309db685a8514d553c" +
                    "6c2f0a2ac004af746e32aa8df0d3060280e398e926d07d3bf5e9cdc60fc4eb23",
            ),
            // Three blocks, the last one partial: block indices 2 and 3 and the final truncation.
            Vector(
                Pbkdf2Prf.HmacSha512,
                { ascii("password") },
                { ascii("salt") },
                1,
                "867f70cf1ade02cff3752599a3a53dc4af34c7a669815ae5d513554e1c8cf252" +
                    "c02d470a285a0501bad999bfe943c08f050235d7d68b1da55e63f73b60a57fce" +
                    "7b532e206c2967d4c7d2ffa460539fc4d4e5eec70125d74c6c7cf86d25284f29" +
                    "7907fcea1ad214effdbea23e1312084eabb180ab72edbac45ea2a53f5f5b9fe1" +
                    "ec722ddabc96fa0d0bad974b1a0f706d9a7f08ec7f79",
            ),
            // A password that is not valid UTF-8: proves the bytes are used as-is.
            Vector(
                Pbkdf2Prf.HmacSha256,
                { hexBuffer("fffe8000c3") },
                { ascii("salt") },
                1000,
                "1bf5389643d27d1b15234ce0283b7d88e7cfab1b4d94a4e2a69107bd2f43c25e",
            ),
            Vector(
                Pbkdf2Prf.HmacSha512,
                { hexBuffer("fffe8000c3") },
                { ascii("salt") },
                1000,
                "ae94f4037c2a5b971810eea5a279ae21bcb18aade1e35c94432184587d484c16",
            ),
            // A password longer than the HMAC block, which HMAC hashes down first.
            Vector(
                Pbkdf2Prf.HmacSha256,
                { repeatedByte('k'.code, 200) },
                { ascii("salt") },
                2,
                "3a77b5f88a50ea2fcf86bd76c5cb5eb640eb6e6fb6ce5987b6696ca4a81fc949",
            ),
            Vector(
                Pbkdf2Prf.HmacSha512,
                { repeatedByte('k'.code, 200) },
                { ascii("salt") },
                2,
                "e9fdd588175bf1523fceae5699825fa91b8e5c2b796e192cc57063f74429c5a2" +
                    "fe28280f3ccd306ea19f4156e7cbaff46548d06a47969f58d1e933f7957829fc",
            ),
        )

    /** Empty inputs are valid in RFC 8018; WebCrypto engines disagree on them, so only native paths pin them. */
    private val emptyInputVectors =
        listOf(
            Vector(
                Pbkdf2Prf.HmacSha256,
                { hexBuffer("") },
                { ascii("salt") },
                1000,
                "94fb56af3ea22e5d3ed1b054085b136ca301b75d8b406c802c489479f27387c6",
            ),
            Vector(
                Pbkdf2Prf.HmacSha512,
                { hexBuffer("") },
                { ascii("salt") },
                1000,
                "f930bd1aa48d2e83b0202406030ed64b16161c4e252b08d19b54531e72b17dc3",
            ),
            Vector(
                Pbkdf2Prf.HmacSha512,
                { ascii("password") },
                { hexBuffer("") },
                1000,
                "037e94baa9506c5ba26bd3bbaa3684b933192040620ebce309ff1e8442d5463c",
            ),
        )

    @Test
    fun knownAnswersThroughTheAsyncOps() =
        runTest(timeout = 5.minutes) {
            for (v in vectors) {
                val ops = CryptoCapabilities.pbkdf2(v.prf).ops
                val out = ops.derive(v.password(), v.salt(), v.iterations, v.length, BufferFactory.Default)
                assertEquals(v.expected, out.toHex(), "${v.prf} c=${v.iterations} dkLen=${v.length}")
            }
        }

    @Test
    fun knownAnswersThroughTheBlockingOps() {
        for (v in vectors + emptyInputVectors) {
            when (val support = CryptoCapabilities.pbkdf2(v.prf)) {
                is Pbkdf2Support.Blocking -> {
                    val out = support.ops.deriveBlocking(v.password(), v.salt(), v.iterations, v.length, BufferFactory.Default)
                    assertEquals(v.expected, out.toHex(), "${v.prf} c=${v.iterations} dkLen=${v.length}")
                }
                is Pbkdf2Support.AsyncOnly -> return
            }
        }
    }

    @Test
    fun deriveIntoWritesAtThePositionAndLeavesInputsUntouched() =
        runTest {
            val ops = CryptoCapabilities.pbkdf2(Pbkdf2Prf.HmacSha512).ops
            val password = ascii("password")
            val salt = ascii("salt")
            val dest = BufferFactory.Default.allocate(3 + 64 + 5)
            dest.writeByte(1)
            dest.writeByte(2)
            dest.writeByte(3)
            ops.deriveInto(password, salt, 2, 64, dest)
            assertEquals(3 + 64, dest.position())
            assertEquals(8, password.remaining())
            assertEquals(4, salt.remaining())
            dest.position(3)
            dest.setLimit(3 + 64)
            assertEquals(
                "e1d9c16aa681708a45f5c7c4e215ceb66e011a2e9f0040713f18aefdb866d53c" +
                    "f76cab2868a39b9f7840edce4fef5a82be67335c77a6068e04112754f27ccf4e",
                dest.toHex(),
            )
        }

    @Test
    fun identicalOutputAcrossBackings() =
        runTest {
            val pool = BufferPool()
            val expected = "d197b1b33db0143e"
            for (prf in listOf(Pbkdf2Prf.HmacSha512)) {
                val ops = CryptoCapabilities.pbkdf2(prf).ops
                for (pwKind in CryptoBackings.inputs) {
                    for (saltKind in CryptoBackings.inputs) {
                        for (destKind in CryptoBackings.dests) {
                            val dest = CryptoBackings.dest(destKind, 8, pool)
                            ops.deriveInto(
                                CryptoBackings.place(pwKind, ascii("password"), pool),
                                CryptoBackings.place(saltKind, ascii("salt"), pool),
                                4096,
                                8,
                                dest,
                            )
                            dest.resetForRead()
                            assertEquals(expected, dest.toHex(), "pw=$pwKind salt=$saltKind dest=$destKind")
                        }
                    }
                }
            }
            pool.clear()
        }

    @Test
    fun rejectsInvalidParametersBeforeDispatch() =
        runTest {
            for (prf in Pbkdf2Prf.entries) {
                val ops = CryptoCapabilities.pbkdf2(prf).ops
                assertFailsWith<IllegalArgumentException> {
                    ops.derive(ascii("p"), ascii("s"), 0, 32, BufferFactory.Default)
                }
                assertFailsWith<IllegalArgumentException> {
                    ops.derive(ascii("p"), ascii("s"), -1, 32, BufferFactory.Default)
                }
                assertFailsWith<IllegalArgumentException> {
                    ops.derive(ascii("p"), ascii("s"), 1, 0, BufferFactory.Default)
                }
                assertFailsWith<IllegalArgumentException> {
                    ops.derive(ascii("p"), ascii("s"), 1, PBKDF2_MAX_OUTPUT_BYTES + 1, BufferFactory.Default)
                }
                val shortDest = BufferFactory.Default.allocate(31)
                assertFailsWith<IllegalArgumentException> {
                    ops.deriveInto(ascii("p"), ascii("s"), 1, 32, shortDest)
                }
                assertEquals(0, shortDest.position())
            }
        }
}
