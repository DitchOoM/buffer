package com.ditchoom.buffer.crypto

import android.app.KeyguardManager
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.ditchoom.buffer.crypto.CryptoTestVectors.ascii
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * Tier-2 security-binding negative tests for user-authenticated keystore keys — proving the OS
 * binding is *real*, not advisory, without any human interaction (no prompt is ever shown, so
 * these run headless under `connectedCheck`).
 *
 *  - A [UserAuthenticationRequirement.Session]-bound key used without any recent device unlock
 *    must be refused **by the keystore** (`UserNotAuthenticatedException` →
 *    [HardwareKeyException.UserAuthenticationRequired]) — even though the SPI gate said yes. If
 *    this test fails, the key was generated without `setUserAuthenticationRequired` and the gate
 *    is advisory again.
 *  - A [UserAuthenticationRequirement.PerUse] key with a plain closure gate must be refused at
 *    *generation* ([HardwareKeyException.UserAuthenticatorRequired]): Android only honors
 *    auth-per-use through a `BiometricPrompt.CryptoObject`, which a closure cannot drive.
 *
 * The session test needs a secure lock screen (the keystore rejects auth-bound generation
 * outright without one) — skipped via `assumeTrue` on unconfigured emulators. Tier-3
 * (prompt-and-match with a real finger) stays device/human-gated outside CI.
 */
class UserAuthBindingInstrumentedTest {
    private val keyguard =
        InstrumentationRegistry
            .getInstrumentation()
            .targetContext
            .getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

    private fun provider(): HardwareKeyProvider {
        val hw = CryptoCapabilities.hardware
        assertIs<HardwareSupport.Available>(hw, "a device wires the Android Keystore provider")
        return hw.provider
    }

    @Test
    fun sessionBoundKeyIsRefusedByTheKeystoreWithoutAuthentication() =
        runTest {
            assumeTrue("requires a secure lock screen", keyguard.isDeviceSecure)
            val provider = provider()
            // The gate grants unconditionally — if the op still fails, the *keystore* enforced the
            // binding, which is exactly what this tier pins.
            val key =
                provider.generateAesGcm(
                    HardwareKeySpec(
                        userAuthentication = UserAuthenticationRequirement.Session(5.seconds),
                    ),
                )
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
    fun perUseGenerationRequiresACryptoObjectCapableAuthenticator() =
        runTest {
            val provider = provider()
            // Thrown by policy resolution before the keystore is touched, so no lock screen needed.
            assertFailsWith<HardwareKeyException.UserAuthenticatorRequired> {
                provider.generateAesGcm(
                    HardwareKeySpec(
                        userAuthentication = UserAuthenticationRequirement.PerUse(),
                    ),
                )
            }
            assertFailsWith<HardwareKeyException.UserAuthenticatorRequired> {
                provider.generateSigning(
                    SignatureScheme.EcdsaP256,
                    HardwareKeySpec(userAuthentication = UserAuthenticationRequirement.PerUse()),
                )
            }
        }
}
