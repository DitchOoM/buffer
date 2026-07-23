package com.ditchoom.buffer.crypto

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.time.Duration

/*
 * Android biometric authenticator for hardware-backed keys.
 *
 * Two layers:
 *
 *  - [BiometricAuthorization] — the prompt-host capability [userAuthenticated] demands at the type
 *    level to mint OS-bound keys. It exists because Android's auth-per-use keys
 *    ([UserAuthenticationPolicy.PerUse]) require the OS to see the exact `Cipher` / `Signature`
 *    being authorized ([BiometricPrompt.CryptoObject]); a plain `suspend () -> Boolean` closure
 *    cannot express that — so the requirement lives in the signature of [userAuthenticated], not
 *    in a runtime check.
 *
 *  - [BiometricPromptAuthenticator] — the library-shipped implementation over androidx.biometric.
 *    The UI host (a [FragmentActivity]) and the prompt strings cannot be expressed in common code,
 *    so the app constructs this at the platform boundary and hands it to [userAuthenticated].
 */

/**
 * A prompt host that can bind an authentication to the exact keystore operation via
 * [BiometricPrompt.CryptoObject]. Handed to [userAuthenticated] to obtain a
 * [UserAuthenticatedKeyProvider]; drives [UserAuthenticationPolicy.PerUse] ops and unlocks stale
 * [UserAuthenticationPolicy.Session] windows. Also usable as a plain advisory
 * [HardwareAuthorization] gate.
 */
interface BiometricAuthorization : HardwareAuthorization {
    /**
     * Prompts the user with authenticator classes matching [method]. A non-null [cryptoObject]
     * binds the authentication to that exact keystore `Cipher` / `Signature` (auth-per-use);
     * `null` performs a plain session-unlock authentication. Returns `true` on success, `false`
     * on denial / cancellation / lockout — the provider surfaces `false` as [AuthorizationFailed].
     */
    suspend fun authenticate(
        method: UserAuthenticationMethod,
        cryptoObject: BiometricPrompt.CryptoObject?,
    ): Boolean
}

/**
 * The library-shipped [BiometricAuthorization] over androidx.biometric's [BiometricPrompt].
 *
 * ```kotlin
 * val authed = provider.userAuthenticated(BiometricPromptAuthenticator(activity, title = "Unlock signing key"))
 * val key = authed?.generateSigning(SignatureScheme.EcdsaP256, UserAuthenticationPolicy.PerUse())
 * ```
 *
 * The prompt always runs on the main executor; [authenticate] suspends until the user completes
 * or dismisses it and never throws for a user-driven outcome (denial is `false`, not an
 * exception). Cancelling the calling coroutine dismisses the prompt.
 */
class BiometricPromptAuthenticator(
    private val activity: FragmentActivity,
    private val title: String,
    private val subtitle: String? = null,
    /** Shown only when the device credential is not an allowed fallback (biometric-only prompts). */
    private val negativeButtonText: String = "Cancel",
) : BiometricAuthorization {
    /** Plain gate use (advisory / session unlock): biometric or device credential. */
    override suspend fun authorize(): Boolean = authenticate(UserAuthenticationMethod.BiometricOrCredential, null)

    override suspend fun authenticate(
        method: UserAuthenticationMethod,
        cryptoObject: BiometricPrompt.CryptoObject?,
    ): Boolean =
        suspendCancellableCoroutine { cont ->
            val executor = ContextCompat.getMainExecutor(activity)
            executor.execute {
                val callback =
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            if (cont.isActive) cont.resume(true)
                        }

                        override fun onAuthenticationError(
                            errorCode: Int,
                            errString: CharSequence,
                        ) {
                            // Cancel / lockout / no-enrollment: a definitive non-success.
                            if (cont.isActive) cont.resume(false)
                        }
                        // onAuthenticationFailed (a non-matching attempt) keeps the prompt up;
                        // the flow is still pending, so it neither resumes nor fails here.
                    }
                val prompt = BiometricPrompt(activity, executor, callback)
                val info = promptInfo(method, forCryptoObject = cryptoObject != null)
                cont.invokeOnCancellation { executor.execute { prompt.cancelAuthentication() } }
                if (cryptoObject != null) {
                    prompt.authenticate(info, cryptoObject)
                } else {
                    prompt.authenticate(info)
                }
            }
        }

    private fun promptInfo(
        method: UserAuthenticationMethod,
        forCryptoObject: Boolean,
    ): BiometricPrompt.PromptInfo {
        // Crypto-object prompts may only offer the device-credential fallback on API 30+ (the
        // keystore cannot bind a credential confirmation to a Cipher before that), so older
        // devices degrade to a biometric-only prompt for per-use keys.
        val credentialAllowed =
            method == UserAuthenticationMethod.BiometricOrCredential &&
                (!forCryptoObject || Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        val allowed =
            if (credentialAllowed) {
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            } else {
                BiometricManager.Authenticators.BIOMETRIC_STRONG
            }
        return BiometricPrompt.PromptInfo
            .Builder()
            .setTitle(title)
            .apply { subtitle?.let { setSubtitle(it) } }
            .setAllowedAuthenticators(allowed)
            .apply {
                // A negative button is required exactly when the credential fallback is absent.
                if (!credentialAllowed) setNegativeButtonText(negativeButtonText)
            }.build()
    }
}

/**
 * The key's auth behavior, fixed at generation. Each variant carries exactly what its op path
 * needs — [Session]/[PerUse] hold the [BiometricAuthorization] the [UserAuthenticatedKeyProvider]
 * captured at construction, so "OS-bound key without a prompt host" is unrepresentable (no cast,
 * no nullable downstream, no runtime validation state).
 */
internal sealed interface ResolvedAndroidPolicy {
    /** No OS binding; [gate] is the advisory [ProtectedKeySpec.authorization]. */
    class Advisory(
        val gate: HardwareAuthorization,
    ) : ResolvedAndroidPolicy

    class Session(
        val validity: Duration,
        val method: UserAuthenticationMethod,
        val prompt: BiometricAuthorization,
    ) : ResolvedAndroidPolicy {
        suspend fun unlock(): Boolean = prompt.authenticate(method, cryptoObject = null)
    }

    class PerUse(
        val method: UserAuthenticationMethod,
        val prompt: BiometricAuthorization,
    ) : ResolvedAndroidPolicy
}

/**
 * The Android [UserAuthenticatedKeyProvider]: pairs the keystore provider with the
 * [BiometricAuthorization] prompt host captured at construction, and maps each pure-data
 * [UserAuthenticationPolicy] onto a [ResolvedAndroidPolicy] that carries it.
 */
internal class AndroidUserAuthenticatedKeyProvider(
    private val base: AndroidKeystoreHardwareKeyProvider,
    private val prompt: BiometricAuthorization,
) : UserAuthenticatedKeyProvider {
    override fun eligible(alg: ProtectedKeyAlgorithm): Boolean = base.eligible(alg)

    override suspend fun generateAesGcm(
        policy: UserAuthenticationPolicy,
        aesKeySizeBits: Int,
    ): AesGcmKey = base.generateAesGcmBound(policy.resolve(), aesKeySizeBits)

    override suspend fun generateSigning(
        scheme: SignatureScheme,
        policy: UserAuthenticationPolicy,
    ): SigningKey = base.generateSigningBound(policy.resolve(), scheme)

    // Deliberately NOT a PerUseAgreementCapable: Android's keystore has no CryptoObject overload for
    // KeyAgreement, so it can gate ECDH by the auth window (Session) but never per-derive (PerUse).
    // The Windowed parameter type makes that limit a compile-time fact rather than a runtime throw.
    override suspend fun generateKeyAgreement(
        curve: KeyAgreementCurve,
        policy: UserAuthenticationPolicy.Windowed,
    ): KeyAgreementKeyPair {
        if (curve != KeyAgreementCurve.P256 || !base.eligible(ProtectedKeyAlgorithm.EcdhP256)) {
            throw HardwareKeyException.AlgorithmNotEligible()
        }
        return base.generateKeyAgreementBound(policy.resolve())
    }

    private fun UserAuthenticationPolicy.resolve(): ResolvedAndroidPolicy =
        when (this) {
            is UserAuthenticationPolicy.Session -> ResolvedAndroidPolicy.Session(validity, method, prompt)
            is UserAuthenticationPolicy.PerUse -> ResolvedAndroidPolicy.PerUse(method, prompt)
        }
}

/**
 * Binds this provider to a [BiometricAuthorization] prompt host, unlocking generation of keys the
 * *keystore itself* refuses without user authentication ([UserAuthenticationPolicy.Session] /
 * [UserAuthenticationPolicy.PerUse]). The prompt host is captured here — at the platform boundary,
 * where it already had to be constructed — so the policy values stay pure data and a bound key
 * without a prompt is a compile error, not a runtime state.
 *
 * Returns `null` for a provider this platform did not mint (e.g. a test fake): only the Android
 * Keystore provider can honor OS-level binding.
 */
fun HardwareKeyProvider.userAuthenticated(prompt: BiometricAuthorization): UserAuthenticatedKeyProvider? =
    (this as? AndroidKeystoreHardwareKeyProvider)?.let { AndroidUserAuthenticatedKeyProvider(it, prompt) }

/**
 * Applies OS-level user-authentication binding to a keystore key at generation. On API 30+ the
 * typed `setUserAuthenticationParameters` expresses both the window and the allowed authenticator
 * classes; 28/29 only have the deprecated validity-duration seconds, where `-1` means
 * auth-per-use (biometric + `CryptoObject` only) and the authenticator classes cannot be narrowed.
 */
internal fun KeyGenParameterSpec.Builder.applyUserAuth(policy: ResolvedAndroidPolicy) {
    when (policy) {
        is ResolvedAndroidPolicy.Advisory -> return
        is ResolvedAndroidPolicy.Session -> {
            setUserAuthenticationRequired(true)
            val seconds =
                policy.validity.inWholeSeconds
                    .coerceIn(1L, Int.MAX_VALUE.toLong())
                    .toInt()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setUserAuthenticationParameters(seconds, keystoreAuthTypes(policy.method))
            } else {
                @Suppress("DEPRECATION")
                setUserAuthenticationValidityDurationSeconds(seconds)
            }
        }
        is ResolvedAndroidPolicy.PerUse -> {
            setUserAuthenticationRequired(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setUserAuthenticationParameters(0, keystoreAuthTypes(policy.method))
            } else {
                @Suppress("DEPRECATION")
                setUserAuthenticationValidityDurationSeconds(-1)
            }
        }
    }
}

/** [UserAuthenticationMethod] → the API 30+ keystore authenticator-class bitmask. */
internal fun keystoreAuthTypes(method: UserAuthenticationMethod): Int =
    when (method) {
        UserAuthenticationMethod.BiometricOnly -> KeyProperties.AUTH_BIOMETRIC_STRONG
        UserAuthenticationMethod.BiometricOrCredential ->
            KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
    }
