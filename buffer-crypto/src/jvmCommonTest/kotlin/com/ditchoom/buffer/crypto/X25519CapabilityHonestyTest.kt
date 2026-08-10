package com.ditchoom.buffer.crypto

import java.security.KeyPairGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import javax.crypto.KeyAgreement as JcaKeyAgreement

/**
 * The capability flag must not disagree with the provider.
 *
 * **Why this is not covered by [KeyAgreementTest].** That suite *skips* any curve the target reports as
 * unavailable — a sound design for a genuinely absent primitive, but it makes the suite structurally
 * blind to the failure where the primitive is present and the flag says otherwise. Every X25519 test
 * passed on Android for exactly that reason while X25519 was reported `Unavailable` on every API level,
 * including those where it works. A green run said nothing.
 *
 * The bug had two independent causes, both from writing Conscrypt code to the JDK's shape:
 *  - the probe called `KeyPairGenerator.getInstance("XDH").initialize(NamedParameterSpec.X25519)`, and
 *    Conscrypt's X25519-only generator supports no `AlgorithmParameterSpec` at all; and
 *  - `rawX25519` cast the public key to `XECPublicKey`, which `OpenSSLX25519PublicKey` does not
 *    implement, so even a corrected probe would have thrown at the first key generation.
 *
 * So this asserts the **agreement between the two**, in the only direction that can be checked
 * portably: where the JCA can really produce an X25519 keypair, the capability must say so — and then
 * the whole documented path (generate → encode → agree) must actually run.
 */
class X25519CapabilityHonestyTest {
    /** Whether this runtime's JCA can genuinely produce an X25519 keypair, asked without our own flags. */
    private fun jcaCanReallyDoX25519(): Boolean =
        try {
            // Deliberately mirrors neither branch of the production helper: no AlgorithmParameterSpec at
            // all, which both the JDK (defaults to X25519) and Conscrypt (X25519-only) accept.
            KeyPairGenerator.getInstance("XDH").generateKeyPair()
            JcaKeyAgreement.getInstance("XDH")
            true
        } catch (_: Throwable) {
            false
        }

    @Test
    fun a_provider_that_can_do_x25519_is_reported_as_able_to_do_x25519() {
        if (!jcaCanReallyDoX25519()) return // genuinely absent (e.g. Android < 34); nothing to contradict
        assertTrue(
            CryptoCapabilities.keyAgreement(KeyAgreementCurve.X25519) is KeyAgreementSupport.Blocking,
            "the JCA produced an X25519 keypair, so the capability flag claiming Unavailable is a lie — " +
                "this is the Conscrypt regression: an AlgorithmParameterSpec-shaped probe reporting a " +
                "working primitive as missing",
        )
    }

    @Test
    fun the_reported_capability_can_complete_a_real_agreement() {
        val support = CryptoCapabilities.keyAgreement(KeyAgreementCurve.X25519)
        if (support !is KeyAgreementSupport.Blocking) return
        // Not merely "the flag is true": generate two pairs through our own ops and agree, so a flag that
        // is honest about the probe but wrong about the key encoding (the `XECPublicKey` half of the bug)
        // still fails here rather than at a consumer.
        val a = support.ops.generateKeyPairBlocking()
        val b = support.ops.generateKeyPairBlocking()
        try {
            assertEquals(
                32,
                a.publicKey.encoded.remaining(),
                "X25519 raw public point is 32 bytes; a different width means the SPKI tail was misread",
            )
            val ab =
                runBlockingCompat {
                    support.ops.deriveTlsPremasterSecret(
                        a.privateKey,
                        KeyAgreementPublicKey.of(KeyAgreementCurve.X25519, b.publicKey.encoded),
                    )
                }
            val ba =
                runBlockingCompat {
                    support.ops.deriveTlsPremasterSecret(
                        b.privateKey,
                        KeyAgreementPublicKey.of(KeyAgreementCurve.X25519, a.publicKey.encoded),
                    )
                }
            try {
                assertEquals(hex(ab), hex(ba), "both sides must derive the same X25519 secret")
                assertTrue(hex(ab).any { it != '0' }, "the shared secret must not be all-zero")
            } finally {
                ab.freeNativeMemory()
                ba.freeNativeMemory()
            }
        } finally {
            a.close()
            b.close()
        }
    }

    private fun hex(buf: com.ditchoom.buffer.ReadBuffer): String {
        val start = buf.position()
        val sb = StringBuilder()
        while (buf.remaining() > 0) {
            val v = buf.readByte().toInt() and 0xFF
            sb.append("0123456789abcdef"[v ushr 4]).append("0123456789abcdef"[v and 0xF])
        }
        buf.position(start)
        return sb.toString()
    }

    private fun <T> runBlockingCompat(block: suspend () -> T): T = kotlinx.coroutines.runBlocking { block() }
}
