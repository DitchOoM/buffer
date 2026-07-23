package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.crypto.CryptoTestVectors.ascii
import com.ditchoom.buffer.crypto.CryptoTestVectors.toHex
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Runtime contract for Secure Enclave ECDH P-256 — the Apple half of hardware-backed key agreement.
 *
 * Every test here is **capability-gated, not device-gated by hand**: each asks the provider whether
 * its generate-and-agree probe held, so an entitled Mac / device runs the real element while the iOS
 * simulator and an unentitled binary skip to [EnclaveAgreementEligibilityTest] instead. Nothing here
 * mocks the Enclave: where the gate opens, every derive below is a real in-element Diffie–Hellman.
 *
 * Each run uses a random Keychain service name so it never reads an item another binary created
 * (which would prompt for keychain access), and deletes what it wrote.
 */
class EnclaveKeyAgreementTest {
    private val storeName = "bcks-kex-test-${Random.nextInt(Int.MAX_VALUE)}"

    private fun store(): KeyStore = CryptoCapabilities.keyStore(KeyStoreConfig(name = storeName))

    /** The Enclave provider, but only when its probed ECDH support actually holds. */
    private fun agreeingEnclave(): SecureEnclaveHardwareKeyProvider? =
        appleEnclaveProviderOrNull()?.takeIf { it.eligible(ProtectedKeyAlgorithm.EcdhP256) }

    private fun ops() = assertNotNull(keyAgreementAsyncOrNull(KeyAgreementCurve.P256))

    // ---- Persistence: get-or-generate / reload / derive ------------------------------------------

    @Test
    fun keyAgreementIsIdempotentReloadsAcrossRestartAndDerives() =
        runTest {
            if (agreeingEnclave() == null) return@runTest
            val s = store()
            val alias = "device-kex"
            try {
                val first = s.getOrGenerateKeyAgreement(alias, KeyAgreementCurve.P256)
                assertEquals(KeyProvenance.Hardware, first.privateKey.provenance)
                assertEquals(CustodyTier.Hardware, s.custodyFor(ProtectedKeyAlgorithm.EcdhP256).tier)
                assertFailsWith<UnsupportedOperationException>("an Enclave scalar is never exportable") {
                    first.privateKey.exportEncoded()
                }
                val pub = first.publicKey.exportSpki().toHex()

                // A second get-or-generate over a fresh store returns the SAME key, not a new one.
                val again = store().getOrGenerateKeyAgreement(alias, KeyAgreementCurve.P256)
                assertEquals(pub, again.publicKey.exportSpki().toHex())

                // Simulated restart: a brand-new store re-attaches from the Keychain record, and the
                // reloaded handle derives what a software peer derives against the persisted public
                // key — the in-Enclave DH is real, standard ECDH, and stable across reloads.
                val reloaded = assertNotNull(store().loadKeyAgreement(alias))
                assertEquals(pub, reloaded.publicKey.exportSpki().toHex())
                val ops = ops()
                val peer = ops.generateKeyPair()
                try {
                    val info = Info.Of(ascii("enclave-kex"))
                    val viaFirst = ops.deriveSharedSecret(first.privateKey, peer.publicKey, info, length = 32)
                    val viaReloaded = ops.deriveSharedSecret(reloaded.privateKey, peer.publicKey, info, length = 32)
                    val viaPeer = ops.deriveSharedSecret(peer.privateKey, reloaded.publicKey, info, length = 32)
                    assertEquals(viaPeer.toHex(), viaFirst.toHex(), "hw derive must match the peer's derivation")
                    assertEquals(viaPeer.toHex(), viaReloaded.toHex(), "reloaded hw key must derive the same secret")
                } finally {
                    peer.close()
                }
            } finally {
                s.delete(alias)
            }
        }

    @Test
    fun agreementAliasNeverSilentlyReplacesAnotherKind() =
        runTest {
            if (agreeingEnclave() == null) return@runTest
            val s = store()
            val signAlias = "mixed-sign"
            val kexAlias = "mixed-kex"
            try {
                // Both kinds are (public point + Enclave blob) records — only the record's kind byte
                // tells them apart, so this is the test that the byte is load-bearing.
                s.getOrGenerateSigning(signAlias, SignatureScheme.EcdsaP256)
                val clash =
                    assertFailsWith<KeyStoreException.AliasMismatch> {
                        s.getOrGenerateKeyAgreement(signAlias, KeyAgreementCurve.P256)
                    }
                assertEquals(ProtectedKeyAlgorithm.EcdsaP256, clash.stored)
                assertEquals(ProtectedKeyAlgorithm.EcdhP256, clash.requested)
                assertNull(s.loadKeyAgreement(signAlias), "a signing alias must not load as an agreement key")

                s.getOrGenerateKeyAgreement(kexAlias, KeyAgreementCurve.P256)
                val reverse =
                    assertFailsWith<KeyStoreException.AliasMismatch> {
                        s.getOrGenerateSigning(kexAlias, SignatureScheme.EcdsaP256)
                    }
                assertEquals(ProtectedKeyAlgorithm.EcdhP256, reverse.stored)
                assertEquals(ProtectedKeyAlgorithm.EcdsaP256, reverse.requested)
                assertNull(s.loadSigning(kexAlias), "an agreement alias must not load as a signing key")
            } finally {
                s.delete(signAlias)
                s.delete(kexAlias)
            }
        }

    @Test
    fun persistedSigningKeysStillRoundTripUnderTheKindTaggedRecord() =
        runTest {
            if (appleEnclaveProviderOrNull() == null) return@runTest
            val s = store()
            val alias = "sign-roundtrip"
            try {
                val first = s.getOrGenerateSigning(alias, SignatureScheme.EcdsaP256)
                val pub = first.verifyKey.exportSpki().toHex()
                val reloaded = assertNotNull(store().loadSigning(alias))
                assertEquals(pub, reloaded.verifyKey.exportSpki().toHex())
                val ops = assertNotNull(signatureAsyncOrNull(SignatureScheme.EcdsaP256))
                val message = ascii("record format change must not break signing")
                val signature = ops.sign(reloaded, message)
                assertTrue(ops.verify(first.verifyKey, message, signature), "reloaded Enclave key must still sign")
            } finally {
                s.delete(alias)
            }
        }

    // ---- Ephemeral provider keys: real in-element DH, gate, peer rejection -----------------------

    @Test
    fun ephemeralEnclaveKeyAgreesWithASoftwarePeer() =
        runTest {
            val provider = agreeingEnclave() ?: return@runTest
            val pair = provider.generateKeyAgreement(KeyAgreementCurve.P256, ProtectedKeySpec())
            val ops = ops()
            val peer = ops.generateKeyPair()
            try {
                assertEquals(KeyProvenance.Hardware, pair.privateKey.provenance)
                val info = Info.Of(ascii("ephemeral-enclave-kex"))
                val viaHardware = ops.deriveSharedSecret(pair.privateKey, peer.publicKey, info, length = 32)
                val viaPeer = ops.deriveSharedSecret(peer.privateKey, pair.publicKey, info, length = 32)
                assertEquals(viaPeer.toHex(), viaHardware.toHex(), "in-Enclave DH must be standard ECDH")
            } finally {
                peer.close()
                pair.close()
            }
        }

    @Test
    fun offCurvePeerPointsAreRejectedUniformly() =
        runTest {
            val provider = agreeingEnclave() ?: return@runTest
            val pair = provider.generateKeyAgreement(KeyAgreementCurve.P256, ProtectedKeySpec())
            val ops = ops()
            val peer = ops.generateKeyPair()
            try {
                // A valid encoding whose X coordinate was perturbed: right length, right 0x04 prefix,
                // not on the curve. The element's rejection must arrive as InvalidPublicKey — the
                // same answer a malformed encoding gets, with no detail about which check failed.
                val bogus = KeyAgreementPublicKey.of(KeyAgreementCurve.P256, perturbed(peer.publicKey.encoded))
                assertFailsWith<InvalidPublicKey> {
                    ops.deriveSharedSecret(pair.privateKey, bogus, Info.Of(ascii("bad-peer")), length = 32)
                }
            } finally {
                peer.close()
                pair.close()
            }
        }

    @Test
    fun aDeniedAdvisoryGateRefusesTheDeriveBeforeTheElementIsTouched() =
        runTest {
            val provider = agreeingEnclave() ?: return@runTest
            val spec = ProtectedKeySpec(authorization = HardwareAuthorization { false })
            val pair = provider.generateKeyAgreement(KeyAgreementCurve.P256, spec)
            val ops = ops()
            val peer = ops.generateKeyPair()
            try {
                assertFailsWith<AuthorizationFailed> {
                    ops.deriveSharedSecret(pair.privateKey, peer.publicKey, Info.Of(ascii("denied")), length = 32)
                }
            } finally {
                peer.close()
                pair.close()
            }
        }

    /** A copy of [point] with one X-coordinate byte flipped — same length and 0x04 prefix, off-curve. */
    private fun perturbed(point: ReadBuffer): ReadBuffer {
        val out = copyBuffer(point, BufferFactory.Default)
        val target = out.position() + 1
        out.set(target, (out.get(target).toInt() xor X_FLIP_MASK).toByte())
        return out
    }

    private companion object {
        const val X_FLIP_MASK = 0x5A
    }
}
