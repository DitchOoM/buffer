package com.ditchoom.buffer.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Conformance for the typed backend resolution ([CryptoCapabilities.protectedKeyResolution]):
 * three states that must never be conflated (Available / Refused-with-typed-finding / None), no
 * `null` anywhere, and strict agreement with the frozen witnesses it derives. Runs on every target —
 * each platform lands in a different arm, and all arms are compile-time exhaustive.
 */
class ProtectedKeyResolutionTest {
    @Test
    fun resolutionAndFrozenWitnessesAgree() {
        when (val resolution = CryptoCapabilities.protectedKeyResolution) {
            is ProtectedKeyResolution.Available -> {
                val support = CryptoCapabilities.protectedKeys
                assertTrue(support is ProtectedKeySupport.Available, "Available resolution must surface in the witness")
                assertSame(resolution.provider, support.provider, "the witness must expose the resolved provider")
            }
            is ProtectedKeyResolution.Refused ->
                assertEquals(ProtectedKeySupport.Unavailable, CryptoCapabilities.protectedKeys)
            ProtectedKeyResolution.None ->
                assertEquals(ProtectedKeySupport.Unavailable, CryptoCapabilities.protectedKeys)
        }
    }

    @Test
    fun refusalFindingStaysInItsBackendBranch() {
        val resolution = CryptoCapabilities.protectedKeyResolution
        if (resolution !is ProtectedKeyResolution.Refused) return
        val matches =
            when (resolution.backend) {
                ProtectedKeyBackend.AndroidKeystore -> resolution.finding is CapabilityFinding.Keystore
                ProtectedKeyBackend.AppleSecureEnclave -> resolution.finding is CapabilityFinding.Enclave
                ProtectedKeyBackend.WebCrypto -> resolution.finding is CapabilityFinding.Web
                ProtectedKeyBackend.Tpm2Pkcs11 -> resolution.finding is CapabilityFinding.Tpm2
            }
        assertTrue(matches, "a refusal's finding must come from its own backend's branch, got ${resolution.finding}")
    }

    @Test
    fun hardwareWitnessOnlySurfacesHardwareBackends() {
        // The secure-element refinement: hardware Available implies an Available resolution whose
        // provider IS the hardware provider; a software non-exportable backend (WebCrypto) must not
        // surface there.
        when (val hw = CryptoCapabilities.hardware) {
            is HardwareSupport.Unavailable -> Unit
            is HardwareSupport.Available -> {
                val resolution = CryptoCapabilities.protectedKeyResolution
                assertTrue(resolution is ProtectedKeyResolution.Available)
                assertSame(resolution.provider, hw.provider)
            }
        }
    }

    @Test
    fun ckrClassificationIsTypedWithAnExplicitOpenTail() {
        assertEquals(CkrClass.PinIncorrect, Ckr(0xA0u).classify())
        assertEquals(CkrClass.PinLocked, Ckr(0xA4u).classify())
        assertEquals(CkrClass.TokenNotPresent, Ckr(0xE0u).classify())
        assertEquals(CkrClass.MechanismInvalid, Ckr(0x70u).classify())
        assertEquals(CkrClass.DeviceError, Ckr(0x30u).classify())
        // The open ranges are reified, not nullable and not collapsed: vendor codes and
        // standard-range codes outside the classified set each carry their raw value.
        assertEquals(CkrClass.VendorDefined(Ckr(0x80000001u)), Ckr(0x80000001u).classify())
        assertEquals(CkrClass.Unrecognized(Ckr(0x191u)), Ckr(0x191u).classify())
    }
}
