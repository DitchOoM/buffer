@file:Suppress("MatchingDeclarationName") // MPP platform-suffixed boundary file

package com.ditchoom.buffer.crypto

import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.biometric.BiometricManager
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/*
 * Android's [UserVerification] — a thin, honest projection of `BiometricManager.canAuthenticate`
 * plus `KeyguardManager` onto the module's shared vocabulary.
 *
 * Four things shape this file:
 *
 *  - **Probe only, never prompt.** [availability] is one `canAuthenticate` status query (plus a
 *    second, narrower one to tell weak biometry from no biometry) and one `KeyguardManager` read.
 *    Nothing here puts UI on screen; prompting lives in [BiometricPromptAuthenticator] and is bound
 *    to a key operation via [androidx.biometric.BiometricPrompt.CryptoObject]. The probe is also
 *    **re-run on every call and never cached** — enrollment, hardware availability, and the screen
 *    lock all change while the app is backgrounded, and a stale `Ready` is a broken prompt.
 *
 *  - **Class 3 only.** The probe asks for `BIOMETRIC_STRONG` because the contract's scope is
 *    *key-capable* biometry ([BiometricAvailability]). Class 2 biometry is real and works for screen
 *    unlock but can never gate a keystore key, so it is deliberately reported as
 *    [BiometricAvailability.Unavailable.OnlyWeakBiometrics] — the one place `BIOMETRIC_WEAK` is
 *    probed at all, purely to distinguish "a sensor you can see but we cannot use" from "no sensor".
 *
 *  - **This probe cannot see lockout, so this file never reports it.** `canAuthenticate` keeps
 *    returning `BIOMETRIC_SUCCESS` while biometry is locked out; lockout surfaces only *after* a
 *    prompt attempt, as `ERROR_LOCKOUT` / `ERROR_LOCKOUT_PERMANENT`. Reporting
 *    [BiometricAvailability.Actionable.LockedOutTemporarily] or
 *    [BiometricAvailability.Actionable.LockedOutUntilCredential] from here would be a guess, so
 *    androidMain constructs neither. That is the contract's "one vocabulary, two entry points":
 *    Apple reaches those states from its probe, Android would reach them by mapping a prompt error —
 *    and wiring the post-prompt vocabulary is deferred by design, not missing by accident.
 *
 *  - **Variants Android never constructs.** Beyond the two lockout states:
 *    [BiometricAvailability.Actionable.PermissionDenied] has no Android equivalent (there is no
 *    per-app biometry consent to revoke — it is Apple-produced), and
 *    [BiometricAvailability.Actionable.SensorDisconnected] /
 *    [BiometricAvailability.Actionable.SensorNotPaired] describe external Touch ID keyboards and are
 *    macOS-produced. So androidMain implements exactly **one** of the contract's action-carrying
 *    interfaces: [BiometricAvailability.Actionable.NotEnrolled]. Every other Android state is a
 *    `data object` the contract already supplies.
 *
 * [UserVerificationAvailability.Unsupported] is likewise never returned: Android always has a
 * verification facility, and the device's actual condition is described by the two nested statuses.
 */

/**
 * The Android [UserVerification], reading the live status of this device's key-capable biometry and
 * screen lock.
 *
 * [activity] is captured because both halves of the API need it: the probe needs a `Context` to
 * reach `BiometricManager` / `KeyguardManager`, and the enrollment remedy
 * ([BiometricAvailability.Actionable.NotEnrolled.openEnrollment]) starts a system `Activity` from
 * it. This is the same platform-boundary shape as [userAuthenticated] — a plain Android-typed
 * function, not an `expect`/`actual`, constructed by the app where a `FragmentActivity` is already
 * in hand and then passed into common code as a [UserVerification].
 *
 * ```kotlin
 * // In the Android app module, at the UI boundary:
 * val verification = userVerification(activity)
 * MyFeature(verification) // common code sees only the interface
 * ```
 *
 * The returned instance holds no cached status: every [UserVerification.availability] call re-probes
 * the OS.
 */
fun userVerification(activity: FragmentActivity): UserVerification = AndroidUserVerification(activity)

/**
 * Projects `BiometricManager` / `KeyguardManager` onto [UserVerificationAvailability]. `internal`:
 * consumers see only the [UserVerification] interface and the shared result types.
 */
internal class AndroidUserVerification(
    private val activity: FragmentActivity,
) : UserVerification {
    /** Fresh OS probe on every call — see the file header on why this is never cached. */
    override fun availability(): UserVerificationAvailability =
        UserVerificationAvailability.Supported(
            biometric = probeBiometric(),
            deviceCredential = probeDeviceCredential(),
        )

    /**
     * Maps `canAuthenticate(BIOMETRIC_STRONG)` onto [BiometricAvailability].
     *
     * The `BIOMETRIC_SUCCESS` case reports [BiometricModality.Unspecified] because `BiometricManager`
     * genuinely does not say which sensor will be used. The only alternative — inferring it from
     * `PackageManager` features (`FEATURE_FINGERPRINT` / `FEATURE_FACE` / `FEATURE_IRIS`) — reports
     * which sensors *exist*, not which is enrolled or which the prompt will pick, so it misreports
     * every dual-sensor device. Since [BiometricModality] is explicitly for labelling and never for a
     * security decision, an honest "unspecified" beats a confident wrong icon.
     */
    private fun probeBiometric(): BiometricAvailability {
        val manager = BiometricManager.from(activity)
        return when (manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricAvailability.Ready(BiometricModality.Unspecified)

            // Key-capable hardware exists but holds no enrollment. Enrollment requires a screen lock,
            // so without one the honest CTA is "set a passcode" — sending the user to the enrollment
            // screen from that state dead-ends them.
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
                if (isDeviceSecure()) {
                    AndroidNotEnrolled(activity)
                } else {
                    BiometricAvailability.Actionable.DeviceLockNotSet
                }

            // "No Class 3 hardware" and "no hardware at all" are different UX stories: a device whose
            // only sensor is Class 2 has visible, working biometry this API can never use for a key.
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE ->
                if (manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
                    BiometricManager.BIOMETRIC_SUCCESS
                ) {
                    BiometricAvailability.Unavailable.OnlyWeakBiometrics
                } else {
                    BiometricAvailability.Unavailable.NoHardware
                }

            // Sensor busy / held by another app / transient service failure: no CTA, may work later.
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> BiometricAvailability.Unavailable.TemporarilyUnavailable

            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED ->
                BiometricAvailability.Actionable.SecurityUpdateRequired

            BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED -> BiometricAvailability.Unavailable.NotSupportedByOs

            // BIOMETRIC_STATUS_UNKNOWN, plus any future/OEM code we do not recognize: neither a
            // remedy to offer nor a basis for hiding the feature, so attempting the prompt is
            // legitimate and the prompt reports the real outcome.
            else -> BiometricAvailability.Indeterminate
        }
    }

    /**
     * Reports whether the device has a credential the OS can verify.
     *
     * Deliberately `KeyguardManager.isDeviceSecure` rather than
     * `canAuthenticate(DEVICE_CREDENTIAL)`: the credential-only `canAuthenticate` query is not
     * supported below API 30 and misreports there (it can answer `BIOMETRIC_ERROR_UNSUPPORTED` on a
     * device that plainly has a PIN), whereas `isDeviceSecure` is accurate on every supported API
     * level, needs no permission, and is exactly the fact the contract asks for — a PIN / pattern /
     * password is set. It is also the same underlying fact as
     * [BiometricAvailability.Actionable.DeviceLockNotSet], read from the credential side, so both
     * fields stay consistent by construction.
     */
    private fun probeDeviceCredential(): DeviceCredentialAvailability =
        if (isDeviceSecure()) DeviceCredentialAvailability.Available else DeviceCredentialAvailability.NotSet

    /** `true` when a PIN / pattern / password is set; `false` if the service is somehow absent. */
    private fun isDeviceSecure(): Boolean {
        val keyguard = ContextCompat.getSystemService(activity, KeyguardManager::class.java)
        return keyguard?.isDeviceSecure == true
    }
}

/**
 * [BiometricAvailability.Actionable.NotEnrolled] for Android: key-capable hardware is present, a
 * screen lock is set, and nothing is enrolled — so the OS does have an enrollment screen to send the
 * user to. Closes over the [activity] the remedy launches from.
 */
internal class AndroidNotEnrolled(
    private val activity: FragmentActivity,
) : BiometricAvailability.Actionable.NotEnrolled {
    /** Always [BiometricModality.Unspecified] — see the mapping note on [AndroidUserVerification]. */
    override val modality: BiometricModality = BiometricModality.Unspecified

    /**
     * Value equality over the reported state, not the captured [activity]: two probes of an
     * unchanged device must compare equal or `distinctUntilChanged`-style consumers re-render
     * every probe. All Android [NotEnrolled][BiometricAvailability.Actionable.NotEnrolled] states
     * read the same ([modality] is constant).
     */
    override fun equals(other: Any?): Boolean = other is AndroidNotEnrolled

    override fun hashCode(): Int = this::class.hashCode()

    /**
     * Non-`null` on Android: unlike Apple, the platform publishes real enrollment deep links, so this
     * remedy is always reachable from this state.
     */
    override val openEnrollment: (suspend () -> Boolean)? = { launchEnrollment() }

    /**
     * Opens the system biometric-enrollment screen on the main thread.
     *
     * **`true` means the screen opened, not that the user enrolled.** Nothing in the Android
     * enrollment flow reports back a result this API could trust, so re-probe with
     * [UserVerification.availability] when the app resumes rather than treating `true` as
     * [BiometricAvailability.Ready].
     *
     * `false` means the Intent did not launch at all — some OEM builds strip or rename these
     * screens, which surfaces as `ActivityNotFoundException`. A `false` is a signal to fall back to
     * prose ("Set up a fingerprint in Settings"), never a crash.
     */
    private suspend fun launchEnrollment(): Boolean =
        // Main-executor dispatch (not Dispatchers.Main): the module depends on coroutines-core only,
        // and core's Main dispatcher throws without the kotlinx-coroutines-android artifact. Same
        // choice, for the same reason, as BiometricPromptAuthenticator.
        suspendCancellableCoroutine { cont ->
            ContextCompat.getMainExecutor(activity).execute {
                // RuntimeException, not just ActivityNotFoundException: OEM settings activities also
                // throw SecurityException (activity not exported to third-party callers) and bare
                // RuntimeExceptions. The contract promises launch-failure -> false, never a crash —
                // and an escaped throwable here would both kill the main thread AND leave the
                // continuation suspended forever.
                val launched =
                    try {
                        activity.startActivity(enrollmentIntent())
                        true
                    } catch (_: RuntimeException) {
                        false
                    }
                if (cont.isActive) cont.resume(launched)
            }
        }

    /**
     * The canonical enrollment Intent for this API level, centralized here so no consumer has to
     * rediscover the branching:
     *
     *  - **API 30+** — [Settings.ACTION_BIOMETRIC_ENROLL] with
     *    [Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED] set to
     *    [BiometricManager.Authenticators.BIOMETRIC_STRONG], so the user lands on enrollment for a
     *    biometric that can actually gate a key rather than a Class 2 one this API would then refuse.
     *  - **API 28-29** — `ACTION_FINGERPRINT_ENROLL`, the only enrollment action those releases
     *    expose (the authenticator-class extra does not exist yet).
     *  - **Below** — `ACTION_SECURITY_SETTINGS`, the nearest screen that exists. The module's
     *    `minSdk` is 28, so this branch is unreachable today; it is kept so the mapping stays whole
     *    if that floor ever moves.
     */
    private fun enrollmentIntent(): Intent =
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                Intent(Settings.ACTION_BIOMETRIC_ENROLL).putExtra(
                    Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,
                    BiometricManager.Authenticators.BIOMETRIC_STRONG,
                )

            // Deprecated as of API 30 in favor of ACTION_BIOMETRIC_ENROLL above; on 28/29 it is the
            // only enrollment action there is, which is precisely this branch.
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ->
                @Suppress("DEPRECATION")
                Intent(Settings.ACTION_FINGERPRINT_ENROLL)

            else -> Intent(Settings.ACTION_SECURITY_SETTINGS)
        }
}
