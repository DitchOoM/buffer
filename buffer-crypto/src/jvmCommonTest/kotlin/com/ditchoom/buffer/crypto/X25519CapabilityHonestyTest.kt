package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.toHexString
import kotlinx.coroutines.runBlocking
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
    fun providerThatCanDoX25519IsReportedAsAbleToDoX25519() {
        if (!jcaCanReallyDoX25519()) return // genuinely absent (e.g. Android < 34); nothing to contradict
        assertTrue(
            CryptoCapabilities.keyAgreement(KeyAgreementCurve.X25519) is KeyAgreementSupport.Blocking,
            "the JCA produced an X25519 keypair, so the capability flag claiming Unavailable is a lie — " +
                "this is the Conscrypt regression: an AlgorithmParameterSpec-shaped probe reporting a " +
                "working primitive as missing",
        )
    }

    @Test
    fun reportedCapabilityCanCompleteARealAgreement() {
        val support = CryptoCapabilities.keyAgreement(KeyAgreementCurve.X25519)
        if (support !is KeyAgreementSupport.Blocking) return
        // Not merely "the flag is true": generate two pairs through our own ops and agree, so a flag that
        // is honest about the probe but wrong about the key encoding (the `XECPublicKey` half of the bug)
        // still fails here rather than at a consumer.
        val a = support.ops.generateKeyPairBlocking()
        val b = support.ops.generateKeyPairBlocking()
        try {
            assertEquals(
                X25519_RAW_PUBLIC_BYTES,
                a.publicKey.encoded.remaining(),
                "X25519 raw public point is $X25519_RAW_PUBLIC_BYTES bytes; a different width means the " +
                    "SPKI tail was misread",
            )
            val ab = agree(support, a, b)
            val ba = agree(support, b, a)
            try {
                assertEquals(ab.toHexString(), ba.toHexString(), "both sides must derive the same X25519 secret")
                assertTrue(ab.toHexString().any { it != '0' }, "the shared secret must not be all-zero")
            } finally {
                ab.freeNativeMemory()
                ba.freeNativeMemory()
            }
        } finally {
            a.close()
            b.close()
        }
    }

    /** [own]'s private key agreed against [peer]'s public point, through the reported blocking ops. */
    private fun agree(
        support: KeyAgreementSupport.Blocking,
        own: KeyAgreementKeyPair,
        peer: KeyAgreementKeyPair,
    ) = runBlocking {
        support.ops.deriveTlsPremasterSecret(
            own.privateKey,
            KeyAgreementPublicKey.of(KeyAgreementCurve.X25519, peer.publicKey.encoded),
        )
    }

    private companion object {
        /** RFC 7748 X25519 public keys are a 32-byte u-coordinate. */
        const val X25519_RAW_PUBLIC_BYTES = 32
    }
}
