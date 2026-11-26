package com.ditchoom.buffer.crypto

import android.os.Build
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.ProviderException
import java.security.spec.ECGenParameterSpec
import javax.crypto.KeyAgreement

/**
 * The Android Keystore provider's two device probes, each a throwaway key generated and deleted under [monitor], the
 * provider's keystore monitor, so a probe never touches the keystore while a live operation does.
 */
internal class KeystoreProbes(
    private val keyStore: KeyStore,
    private val monitor: Any,
) {
    /**
     * Probes whether the keystore actually backs ECDH P-256 agreement keys in secure hardware:
     * generates a throwaway `PURPOSE_AGREE_KEY` key, confirms its `KeyInfo` level, and runs a full
     * keystore ECDH against a fresh software peer. The end-to-end agreement matters — a device may
     * accept the generation spec and still fail the `KeyAgreement` op, or place the key in a software
     * KeyMint — so generation alone would over-promise. Pre-31 the purpose does not exist. A transient
     * keystore refusal is [Settlement.Unsettled], so a busy keystore is not taken for one without
     * agreement.
     */
    fun agreement(): Settlement<Boolean> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return Settlement.Settled(false)
        return synchronized(monitor) {
            val alias = newAlias("probe-ecdh")
            try {
                val keyPair =
                    KeyPairGenerator
                        .getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEY_STORE)
                        .apply {
                            initialize(agreementSpec(alias, StrongBoxRequest.Tee, probeAdvisoryPolicy))
                        }.generateKeyPair()
                when (placementOfSecurityLevel(keyInfoOf(keyPair.private).securityLevel)) {
                    is KeystorePlacement.NotSecureHardware -> Settlement.Settled(false)
                    is KeystorePlacement.SecureHardware -> Settlement.Settled(agreesWithSoftwarePeer(keyPair.private))
                }
            } catch (e: GeneralSecurityException) {
                probeRefusal(e)
            } catch (e: ProviderException) {
                probeRefusal(e)
            } finally {
                runCatching { keyStore.deleteEntry(alias) }
            }
        }
    }

    /**
     * Generates a throwaway StrongBox key and reports whether StrongBox holds it. Before API 31 the level cannot be
     * read, so a generation that does not throw counts as StrongBox. Any refusal is `false`.
     */
    fun strongBox(): Boolean =
        synchronized(monitor) {
            val alias = newAlias("probe")
            try {
                val keyPair =
                    KeyPairGenerator
                        .getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEY_STORE)
                        .apply {
                            initialize(ecSpec(alias, StrongBoxRequest.StrongBox, probeAdvisoryPolicy))
                        }.generateKeyPair()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    placementOfSecurityLevel(keyInfoOf(keyPair.private).securityLevel).isStrongBox()
                } else {
                    true
                }
            } catch (_: GeneralSecurityException) {
                false
            } catch (
                @Suppress("TooGenericExceptionCaught") _: RuntimeException,
            ) {
                // A ProviderException (StrongBox unavailable, provisioning) or any other unchecked refusal.
                false
            } finally {
                runCatching { keyStore.deleteEntry(alias) }
            }
        }

    private fun agreesWithSoftwarePeer(privateKey: PrivateKey): Boolean {
        val peer =
            KeyPairGenerator
                .getInstance("EC")
                .apply { initialize(ECGenParameterSpec(SECP256R1)) }
                .generateKeyPair()
        val ka = KeyAgreement.getInstance(ECDH, ANDROID_KEY_STORE)
        ka.init(privateKey)
        ka.doPhase(peer.public, true)
        val secret = ka.generateSecret()
        val ok = secret.size == P256_FIELD_BYTES
        secret.fill(0)
        return ok
    }

    /** A refused probe: transient ⇒ ask again next time; anything else ⇒ no hardware agreement on this device. */
    private fun probeRefusal(failure: Throwable): Settlement<Boolean> =
        if (keystoreFailureFacts(failure).any { it is KeystoreFailureFact.Coded && it.transientFailure }) {
            Settlement.Unsettled(false)
        } else {
            Settlement.Settled(false)
        }
}

/** Unbound throwaway-key policy for the probes. */
private val probeAdvisoryPolicy = ResolvedAndroidPolicy.Advisory(HardwareAuthorization { true })

private fun KeystorePlacement.isStrongBox(): Boolean =
    when (this) {
        is KeystorePlacement.SecureHardware -> custody.dedicatedSecureElement
        is KeystorePlacement.NotSecureHardware -> false
    }

private const val P256_FIELD_BYTES = 32
