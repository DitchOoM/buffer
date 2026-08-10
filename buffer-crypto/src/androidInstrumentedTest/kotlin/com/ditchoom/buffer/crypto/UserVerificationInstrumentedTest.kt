package com.ditchoom.buffer.crypto

import android.app.KeyguardManager
import android.content.Context
import android.util.Log
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Runs the REAL Android probe ([AndroidUserVerification]) on whatever device or emulator is
 * attached — the common contract suite only exercises fakes, so this is the only place the
 * `canAuthenticate` mapping, the `KeyguardManager` read, and the enrollment remedy execute against
 * an actual OS. The assertions are device-state-agnostic: they hold on an enrolled Pixel, a
 * lock-screen-less emulator, and an OEM build alike, because they check *internal consistency* and
 * *platform-impossibility* rather than any particular state.
 */
@RunWith(AndroidJUnit4::class)
class UserVerificationInstrumentedTest {
    private fun <T> withVerification(block: (FragmentActivity, UserVerification) -> T): T {
        ActivityScenario.launch(TestHostActivity::class.java).use { scenario ->
            var result: T? = null
            scenario.onActivity { activity ->
                result = block(activity, userVerification(activity))
            }
            @Suppress("UNCHECKED_CAST")
            return result as T
        }
    }

    /** Android always has a verification facility: the probe must report [Supported], never throw. */
    @Test
    fun probeReportsSupported() {
        val availability = withVerification { _, v -> v.availability() }
        assertTrue(availability is UserVerificationAvailability.Supported, "Android is never Unsupported")
        // The state itself is device-dependent; log it so a per-device run reports what this
        // OS/OEM/enrollment combination actually produced.
        Log.i("UserVerificationTest", "probe -> ${availability.biometric} / ${availability.deviceCredential}")
    }

    /**
     * The probe must never report a state Android cannot produce: the Apple-only variants, or the
     * two lockout states `canAuthenticate` is blind to (they surface post-prompt only, and this
     * release wires no post-prompt mapping).
     */
    @Test
    fun probeNeverReportsAndroidImpossibleStates() {
        val supported = withVerification { _, v -> v.availability() } as UserVerificationAvailability.Supported
        val impossible =
            when (supported.biometric) {
                is BiometricAvailability.Actionable.PermissionDenied,
                BiometricAvailability.Actionable.SensorDisconnected,
                BiometricAvailability.Actionable.SensorNotPaired,
                is BiometricAvailability.Actionable.LockedOutUntilCredential,
                BiometricAvailability.Actionable.LockedOutTemporarily,
                -> true
                else -> false
            }
        assertTrue(!impossible, "Android probe reported an Android-impossible state: ${supported.biometric}")
    }

    /** The credential field and the OS keyguard must agree — they are reads of the same fact. */
    @Test
    fun deviceCredentialAgreesWithKeyguard() {
        val (supported, secure) =
            withVerification { activity, v ->
                val keyguard = activity.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                (v.availability() as UserVerificationAvailability.Supported) to keyguard.isDeviceSecure
            }
        val expected =
            if (secure) DeviceCredentialAvailability.Available else DeviceCredentialAvailability.NotSet
        assertEquals(expected, supported.deviceCredential)
    }

    /**
     * Two probes of an unchanged device compare EQUAL — the value-equality contract
     * `distinctUntilChanged`-style consumers rely on. This exercises the real impls' overridden
     * `equals` (the action-carrying states are classes, not data objects).
     */
    @Test
    fun reProbeOfUnchangedDeviceComparesEqual() {
        val (first, second) = withVerification { _, v -> v.availability() to v.availability() }
        assertEquals(first, second)
    }

    /**
     * If this device is in [BiometricAvailability.Actionable.NotEnrolled], invoke the real
     * enrollment remedy and record which way the OEM answered — `true` = the system enrollment
     * screen resolved and launched, `false` = this OEM stripped/renamed it and consumers get the
     * prose fallback. BOTH are contract-conformant; the assertion is only that the remedy returns
     * instead of crashing or hanging. The printed line is the datum this test exists to collect
     * (run it per OEM: stock, One UI, ColorOS...). Skips (trivially passes) on devices in any
     * other state.
     */
    @Test
    fun enrollmentRemedyReturnsHonestlyWhenNotEnrolled() {
        val state = withVerification { _, v -> v.availability() } as UserVerificationAvailability.Supported
        val notEnrolled = state.biometric as? BiometricAvailability.Actionable.NotEnrolled ?: return
        val remedy = assertNotNull(notEnrolled.openEnrollment, "Android always publishes an enrollment route")
        val launched = runBlocking { remedy() }
        // Log, not println: instrumentation stdout is not reliably forwarded to logcat, and this
        // line IS the test's product.
        Log.i("UserVerificationTest", "openEnrollment -> $launched on this OEM build")
    }

    /**
     * Same shape as the enrollment datum, for [BiometricAvailability.Actionable.DeviceLockNotSet]:
     * on a device with no screen lock, invoke the real lock-setup remedy and record whether this
     * OEM's `ACTION_BIOMETRIC_ENROLL`-or-`ACTION_SECURITY_SETTINGS` tiering launched. Skips
     * (trivially passes) in any other state.
     */
    @Test
    fun deviceLockSetupRemedyReturnsHonestlyWhenLockNotSet() {
        val state = withVerification { _, v -> v.availability() } as UserVerificationAvailability.Supported
        val lockNotSet =
            state.biometric as? BiometricAvailability.Actionable.DeviceLockNotSet ?: return
        val remedy = assertNotNull(lockNotSet.openDeviceLockSetup, "Android always publishes a lock-setup route")
        val launched = runBlocking { remedy() }
        Log.i("UserVerificationTest", "openDeviceLockSetup -> $launched on this OEM build")
    }
}
