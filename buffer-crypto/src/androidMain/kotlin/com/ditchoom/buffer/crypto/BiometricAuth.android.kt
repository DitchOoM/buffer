package com.ditchoom.buffer.crypto

import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/*
 * Android biometric authenticator for hardware-backed keys.
 *
 * [HardwareAuthorization] is the common gate; this file is the Android refinement that can put a
 * real prompt on screen. Two layers:
 *
 *  - [BiometricAuthorization] — the *capability* interface the Android keystore provider dispatches
 *    to. It exists because Android's auth-per-use keys ([UserAuthenticationRequirement.PerUse])
 *    require the OS to see the exact `Cipher` / `Signature` being authorized
 *    ([BiometricPrompt.CryptoObject]); a plain `suspend () -> Boolean` closure cannot express that.
 *    Generation of a PerUse key with a non-[BiometricAuthorization] gate throws
 *    [HardwareKeyException.UserAuthenticatorRequired] — at generation, so a misconfigured key never
 *    exists.
 *
 *  - [BiometricPromptAuthenticator] — the library-shipped implementation over androidx.biometric.
 *    The UI host (a [FragmentActivity]) and the prompt strings cannot be expressed in common code,
 *    so the app constructs this and injects it as [HardwareKeySpec.authorization] — the same
 *    inversion-of-control pattern as `BufferFactory`.
 */

/**
 * An [HardwareAuthorization] that can bind an authentication to the exact keystore operation via
 * [BiometricPrompt.CryptoObject]. Required for [UserAuthenticationRequirement.PerUse] keys on
 * Android; also used (without a crypto object) to unlock a
 * [UserAuthenticationRequirement.Session] window.
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
 * val spec = HardwareKeySpec(
 *     authorization = BiometricPromptAuthenticator(activity, title = "Unlock signing key"),
 *     userAuthentication = UserAuthenticationRequirement.PerUse(),
 * )
 * val key = provider.generateSigning(SignatureScheme.EcdsaP256, spec)
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
