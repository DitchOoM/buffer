package com.ditchoom.buffer.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runtime guard for the Apple [UserVerification] probe — the LocalAuthentication half that needs no
 * entitled Secure Enclave, so it runs on the macOS CI test binary rather than being device-gated.
 *
 * A CI runner has whatever enrollment, permission, and lockout state it has, so the assertions are
 * about the *shape* of the answer rather than a particular state: the probe returns a state this
 * platform is documented to be able to produce, and it keeps returning the same one. Nothing here
 * invokes a remedy — [BiometricAvailability.Actionable.PermissionDenied.openAppSettings] and
 * [BiometricAvailability.Actionable.LockedOutUntilCredential.unlockWithDeviceCredential] put system
 * UI on screen, which an unattended runner cannot dismiss.
 */
class UserVerificationAppleTest {
    @Test
    fun availabilityReportsAStateThisPlatformCanProduce() {
        val auth = LocalAuthAuthenticator(reason = "test reason")
        try {
            // Skip only where LocalAuthentication itself is absent (no context handle minted); on any
            // real macOS runner the context is created and the probe below runs for real.
            if (!auth.available) return
            when (val availability = userVerification(auth).availability()) {
                // tvOS ships no usable LocalAuthentication; the probe says so rather than guessing.
                UserVerificationAvailability.Unsupported -> Unit
                is UserVerificationAvailability.Supported ->
                    assertTrue(
                        appleCanProduce(availability.biometric),
                        "the Apple probe must not report a state it has no construction site for",
                    )
            }
        } finally {
            auth.close()
        }
    }

    @Test
    fun repeatedProbesReportTheSameState() {
        val auth = LocalAuthAuthenticator(reason = "test reason")
        try {
            if (!auth.available) return
            val verification = userVerification(auth)
            // Stability, not caching: the implementation re-probes with a fresh context every call
            // (that is the contract), and on an unattended machine nothing changes between the two —
            // so a differing variant would mean the probe is not deterministic in the state it maps.
            val first = verification.availability()
            val second = verification.availability()
            assertEquals(variantOf(first), variantOf(second), "two probes of an idle device must agree")
        } finally {
            auth.close()
        }
    }

    /**
     * Whether `UserVerification.apple.kt` has a construction site for [state].
     *
     * The four `false` arms are the ones its file header lists as never produced here:
     * [BiometricAvailability.Actionable.SecurityUpdateRequired] and
     * [BiometricAvailability.Unavailable.OnlyWeakBiometrics] (Android strength classes have no Apple
     * analogue), [BiometricAvailability.Indeterminate] (`canEvaluatePolicy` always answers), and
     * [BiometricAvailability.Actionable.LockedOutTemporarily] (Apple's probe reports lockout as the
     * until-credential kind). The `when` is `else`-free so a state added later must be classified
     * here rather than silently accepted.
     */
    @Suppress("CyclomaticComplexMethod")
    private fun appleCanProduce(state: BiometricAvailability): Boolean =
        when (state) {
            is BiometricAvailability.Ready -> true
            is BiometricAvailability.Actionable.NotEnrolled -> true
            is BiometricAvailability.Actionable.DeviceLockNotSet -> true
            is BiometricAvailability.Actionable.PermissionDenied -> true
            is BiometricAvailability.Actionable.LockedOutUntilCredential -> true
            // macOS external Touch ID keyboards — Apple-only, never produced on Android.
            BiometricAvailability.Actionable.SensorDisconnected -> true
            BiometricAvailability.Actionable.SensorNotPaired -> true
            BiometricAvailability.Unavailable.NoHardware -> true
            BiometricAvailability.Unavailable.TemporarilyUnavailable -> true
            BiometricAvailability.Unavailable.NotSupportedByOs -> true
            // Android-only, per the file header on UserVerification.apple.kt:
            BiometricAvailability.Actionable.SecurityUpdateRequired -> false
            BiometricAvailability.Actionable.LockedOutTemporarily -> false
            BiometricAvailability.Unavailable.OnlyWeakBiometrics -> false
            BiometricAvailability.Indeterminate -> false
        }

    /** A stable name for the variant, ignoring the payload [BiometricAvailability.Ready] carries. */
    private fun variantOf(availability: UserVerificationAvailability): String =
        when (availability) {
            UserVerificationAvailability.Unsupported -> "unsupported"
            is UserVerificationAvailability.Supported ->
                "${variantOf(availability.biometric)}/${availability.deviceCredential}"
        }

    // Exhaustive over the full 14-leaf vocabulary — the branch count IS the assertion.
    @Suppress("CyclomaticComplexMethod")
    private fun variantOf(state: BiometricAvailability): String =
        when (state) {
            is BiometricAvailability.Ready -> "ready:${state.modality}"
            is BiometricAvailability.Actionable.NotEnrolled -> "not-enrolled"
            is BiometricAvailability.Actionable.DeviceLockNotSet -> "device-lock-not-set"
            is BiometricAvailability.Actionable.PermissionDenied -> "permission-denied"
            BiometricAvailability.Actionable.SecurityUpdateRequired -> "security-update-required"
            BiometricAvailability.Actionable.SensorDisconnected -> "sensor-disconnected"
            BiometricAvailability.Actionable.SensorNotPaired -> "sensor-not-paired"
            is BiometricAvailability.Actionable.LockedOutUntilCredential -> "locked-out-until-credential"
            BiometricAvailability.Actionable.LockedOutTemporarily -> "locked-out-temporarily"
            BiometricAvailability.Unavailable.NoHardware -> "no-hardware"
            BiometricAvailability.Unavailable.OnlyWeakBiometrics -> "only-weak-biometrics"
            BiometricAvailability.Unavailable.TemporarilyUnavailable -> "temporarily-unavailable"
            BiometricAvailability.Unavailable.NotSupportedByOs -> "not-supported-by-os"
            BiometricAvailability.Indeterminate -> "indeterminate"
        }
}
