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
import kotlin.coroutines.cancellation.CancellationException

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
     * KeyMint — so generation alone would over-promise. Pre-31 the purpose does not exist. How a failed
     * probe is read is [agreementProbeOutcome].
     */
    fun agreement(): Settlement<AgreementSupport> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return Settlement.Settled(AgreementSupport.NotSupported)
        return synchronized(monitor) {
            val alias = newAlias("probe-ecdh")
            try {
                agreementProbeOutcome {
                    val keyPair =
                        KeyPairGenerator
                            .getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEY_STORE)
                            .apply {
                                initialize(agreementSpec(alias, StrongBoxRequest.Tee, probeAdvisoryPolicy))
                            }.generateKeyPair()
                    when (placementOfSecurityLevel(keyInfoOf(keyPair.private).securityLevel)) {
                        is KeystorePlacement.NotSecureHardware -> AgreementSupport.NotSupported
                        KeystorePlacement.StrongBox, KeystorePlacement.Tee, KeystorePlacement.UnnamedSecure ->
                            if (agreesWithSoftwarePeer(keyPair.private)) {
                                AgreementSupport.Supported
                            } else {
                                AgreementSupport.NotSupported
                            }
                    }
                }
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
                    placementOfSecurityLevel(keyInfoOf(keyPair.private).securityLevel) == KeystorePlacement.StrongBox
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

}

/** Unbound throwaway-key policy for the probes. */
private val probeAdvisoryPolicy = ResolvedAndroidPolicy.Advisory(HardwareAuthorization { true })

private const val P256_FIELD_BYTES = 32

/**
 * How one agreement [probe] that answered, or failed, is held: the keystore's answer is settled; a transient keystore
 * refusal is [AgreementSupport.Undecided] for the retry window, so a busy keystore is not taken for one without
 * agreement; any other keystore refusal settles agreement off. A probe that fails in a way nothing here expects is
 * undecided too, never a crash of an eligibility question. Cancellation and errors pass through.
 */
internal inline fun agreementProbeOutcome(probe: () -> AgreementSupport): Settlement<AgreementSupport> =
    try {
        Settlement.Settled(probe())
    } catch (e: CancellationException) {
        throw e
    } catch (e: GeneralSecurityException) {
        keystoreProbeRefusal(e)
    } catch (e: ProviderException) {
        keystoreProbeRefusal(e)
    } catch (
        @Suppress("TooGenericExceptionCaught") _: Exception,
    ) {
        Settlement.Unsettled(AgreementSupport.Undecided)
    }

internal fun keystoreProbeRefusal(failure: Throwable): Settlement<AgreementSupport> =
    if (keystoreFailureFacts(failure).any { it is KeystoreFailureFact.Coded && it.transientFailure }) {
        Settlement.Unsettled(AgreementSupport.Undecided)
    } else {
        Settlement.Settled(AgreementSupport.NotSupported)
    }
