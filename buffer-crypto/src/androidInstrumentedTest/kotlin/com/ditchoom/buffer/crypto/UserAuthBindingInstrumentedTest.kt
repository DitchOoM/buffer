package com.ditchoom.buffer.crypto

import android.app.KeyguardManager
import android.content.Context
import androidx.biometric.BiometricPrompt
import androidx.test.platform.app.InstrumentationRegistry
import com.ditchoom.buffer.crypto.CryptoTestVectors.ascii
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds

/**
 * Tier-2 security-binding negative tests for user-authenticated keystore keys — proving the OS
 * binding is *real*, not advisory, without any human interaction (no prompt is ever shown, so
 * these run headless under `connectedCheck`).
 *
 *  - A [UserAuthenticationPolicy.Session]-bound key used without any recent device unlock must be
 *    refused **by the keystore** (`UserNotAuthenticatedException` →
 *    [HardwareKeyException.UserAuthenticationRequired]) — even though the prompt host grants
 *    unconditionally. If this test fails, the key was generated without
 *    `setUserAuthenticationRequired` and the binding is advisory again.
 *  - A [UserAuthenticationPolicy.PerUse] key must be refused **by the keystore** when the prompt
 *    host claims success without actually authorizing the `CryptoObject` through a real
 *    BiometricPrompt — the element only honors an authorization it saw itself.
 *
 * Both tests need a secure lock screen (the keystore rejects auth-bound generation outright
 * without one) — skipped via `assumeTrue` on unconfigured emulators. Tier-3 (prompt-and-match
 * with a real finger) stays device/human-gated outside CI.
 */
class UserAuthBindingInstrumentedTest {
    private val keyguard =
        InstrumentationRegistry
            .getInstrumentation()
            .targetContext
            .getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

    /** Grants everything but never shows a prompt — the keystore must still refuse on its own. */
    private class LyingPrompt : BiometricAuthorization {
        override suspend fun authorize(): Boolean = true

        override suspend fun authenticate(
            method: UserAuthenticationMethod,
            cryptoObject: BiometricPrompt.CryptoObject?,
        ): Boolean = true
    }

    private fun authenticatedProvider(): UserAuthenticatedKeyProvider {
        val hw = CryptoCapabilities.hardware
        assertIs<HardwareSupport.Available>(hw, "a device wires the Android Keystore provider")
        return assertNotNull(
            hw.provider.userAuthenticated(LyingPrompt()),
            "the platform provider must accept a BiometricAuthorization prompt host",
        )
    }

    @Test
    fun sessionBoundKeyIsRefusedByTheKeystoreWithoutAuthentication() =
        runTest {
            assumeTrue("requires a secure lock screen", keyguard.isDeviceSecure)
            val key =
                authenticatedProvider()
                    .generateAesGcm(UserAuthenticationPolicy.Session(5.seconds))
            try {
                assertFailsWith<HardwareKeyException.UserAuthenticationRequired>(
                    "an auth-bound key must be unusable without a recent device unlock",
                ) {
                    aesGcmAsyncOps().seal(key, ascii("must not encrypt"))
                }
            } finally {
                key.close()
            }
        }

    @Test
    fun perUseKeyIsRefusedWhenTheCryptoObjectWasNeverAuthorized() =
        runTest {
            assumeTrue("requires a secure lock screen", keyguard.isDeviceSecure)
            val key =
                authenticatedProvider()
                    .generateAesGcm(UserAuthenticationPolicy.PerUse())
            try {
                // LyingPrompt said yes without driving BiometricPrompt over the CryptoObject, so
                // the keystore itself must refuse the un-authorized cipher — any sealed
                // HardwareKeyException state is a pass; a successful seal means the binding is fake.
                assertFailsWith<HardwareKeyException>(
                    "a per-use key must be unusable without a real CryptoObject authorization",
                ) {
                    aesGcmAsyncOps().seal(key, ascii("must not encrypt"))
                }
            } finally {
                key.close()
            }
        }
}
