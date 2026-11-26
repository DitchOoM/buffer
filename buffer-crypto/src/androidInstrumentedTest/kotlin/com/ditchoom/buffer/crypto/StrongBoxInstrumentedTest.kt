package com.ditchoom.buffer.crypto

import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import com.ditchoom.buffer.crypto.CryptoTestVectors.ascii
import com.ditchoom.buffer.crypto.CryptoTestVectors.toHex
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * On-device guard for the Android Keystore hardware backend — the tier no host-JVM or emulator run
 * can exercise. The emulator keystore is TEE-only (no StrongBox), and a host-JVM unit test has no
 * `AndroidKeyStore` at all, so [HardwareKeyConformanceTest]'s `Available` branch never fires there.
 * This `androidInstrumentedTest` runs under `connectedCheck` on a real device and pins the one thing
 * CI is otherwise blind to: that a key's own [KeyCustody] matches what the device actually advertises
 * via `FEATURE_STRONGBOX_KEYSTORE`, and that the real provider round-trips.
 *
 * On a StrongBox device (Pixel 3+, recent Samsung, …) an ECDSA P-256 key — which every StrongBox must
 * hold — reports `dedicatedSecureElement == true`; on a TEE-only device `false`. The provider's custody
 * reports the TEE floor either way, because which element holds a key is known only per key; the
 * deprecated provider-wide `dedicatedSecureElement` keeps its StrongBox probe.
 */
class StrongBoxInstrumentedTest {
    private val hasStrongBox: Boolean =
        InstrumentationRegistry
            .getInstrumentation()
            .context.packageManager
            .hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)

    private fun provider(): HardwareKeyProvider {
        val hw = CryptoCapabilities.hardware
        assertIs<HardwareSupport.Available>(hw, "a device wires the Android Keystore provider")
        return hw.provider
    }

    @Test
    fun keyCustodyMatchesDeviceStrongBox() =
        runTest {
            val provider = provider()
            val signing = provider.generateSigning(SignatureScheme.EcdsaP256, ProtectedKeySpec())
            try {
                assertEquals(KeyProvenance.Hardware, signing.provenance)
                // The key's own custody must equal the device's StrongBox capability.
                assertEquals(
                    KeyCustody.NonExportable.Hardware(dedicatedSecureElement = hasStrongBox),
                    signing.custody,
                    "an ECDSA P-256 key's custody must equal the device StrongBox feature ($hasStrongBox)",
                )
                assertEquals(false, provider.custody.dedicatedSecureElement, "the provider reports the TEE floor")
                @Suppress("DEPRECATION")
                val probed = provider.dedicatedSecureElement
                assertEquals(hasStrongBox, probed, "the deprecated probe still answers whether StrongBox is there")
                // The provider-minted key publishes its public key and produces a verifiable signature.
                val vk = signing.verifyKey
                val ops = signatureAsyncOrNull(SignatureScheme.EcdsaP256)
                if (ops != null) {
                    val message = ascii("strongbox device signature")
                    val signature = ops.sign(signing, message)
                    assertTrue(ops.verify(vk, message, signature), "device hw-sign verifies under its public key")
                }
            } finally {
                signing.close()
            }
        }

    @Test
    fun aesGcmRoundTripsOnDevice() =
        runTest {
            val provider = provider()
            val key = provider.generateAesGcm(ProtectedKeySpec())
            try {
                assertEquals(KeyProvenance.Hardware, key.provenance)
                val ops = aesGcmAsyncOps()
                val plaintext = ascii("strongbox device AES-GCM payload")
                val sealed = ops.seal(key, plaintext, Aad.Of(ascii("ctx")))
                val opened = ops.open(sealed, key, Aad.Of(ascii("ctx")))
                assertEquals(plaintext.toHex(), opened.toHex(), "real device hw-seal → hw-open must round-trip")
            } finally {
                key.close()
            }
        }
}
