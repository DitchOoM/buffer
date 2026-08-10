@file:OptIn(ExperimentalForeignApi::class)
@file:Suppress("MatchingDeclarationName") // MPP platform-suffixed boundary file; also holds the state impls

package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_BIO_DISCONNECTED
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_BIO_LOCKOUT
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_BIO_NOT_ENROLLED
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_BIO_NOT_PAIRED
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_BIO_PASSCODE_NOT_SET
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_BIO_READY
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_BIO_UNSUPPORTED
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_ERR_CANCELED
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_OK
import com.ditchoom.buffer.crypto.cinterop.cryptokit.bcks_la_biometric_availability
import com.ditchoom.buffer.crypto.cinterop.cryptokit.bcks_la_device_credential_available
import com.ditchoom.buffer.crypto.cinterop.cryptokit.bcks_la_unlock_with_credential
import com.ditchoom.buffer.crypto.cinterop.cryptokit.bcks_open_app_settings
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * Apple [UserVerification] — LocalAuthentication `canEvaluatePolicy` as a UX probe.
 *
 * The probe never touches the [LocalAuthAuthenticator]'s long-lived LAContext: the shim builds a
 * throwaway context per call, so asking "may this screen draw a Face ID button?" can never disturb
 * an evaluated session context that is authorizing Secure Enclave signs. The authenticator is
 * captured for exactly one reason — it owns the localized prompt reason, which the *remedy*
 * ([BiometricAvailability.Actionable.LockedOutUntilCredential.unlockWithDeviceCredential]) needs
 * because that one does put a system prompt on screen.
 *
 * Which states this platform can produce:
 *  - Apple-only: [BiometricAvailability.Actionable.SensorDisconnected] and
 *    [BiometricAvailability.Actionable.SensorNotPaired] (external Touch ID keyboards on macOS);
 *    Android never produces either.
 *  - Never produced here: [BiometricAvailability.Actionable.SecurityUpdateRequired] and
 *    [BiometricAvailability.Unavailable.OnlyWeakBiometrics] (Android strength classes have no Apple
 *    analogue — every biometry Apple exposes is key-capable), [BiometricAvailability.Indeterminate]
 *    (`canEvaluatePolicy` always answers), and
 *    [BiometricAvailability.Actionable.LockedOutTemporarily] — Apple's probe reports a lockout as
 *    `LAError.biometryLockout`, which is the *until-credential* kind, so the temporary variant has
 *    no Apple entry point.
 *  - [UserVerificationAvailability.Unsupported] is reported on tvOS alone, where the OS ships no
 *    usable LocalAuthentication at all. watchOS is [UserVerificationAvailability.Supported] with
 *    [BiometricAvailability.Unavailable.NoHardware]: wrist detection is not discrete biometry an app
 *    can name or gate a key on, but the passcode is a real credential the OS can verify.
 */

/**
 * The Apple [UserVerification], probing this device's LocalAuthentication facility.
 *
 * [authenticator] supplies the localized reason shown by the one member that prompts,
 * [BiometricAvailability.Actionable.LockedOutUntilCredential.unlockWithDeviceCredential]; probing
 * itself puts nothing on screen and holds no context. Constructed at the platform boundary — the
 * same seam as [userAuthenticated] — because the reason string is a UI concern common code cannot
 * name. The result is **not** cacheable: see [UserVerification].
 */
fun userVerification(authenticator: LocalAuthAuthenticator): UserVerification = AppleUserVerification(authenticator)

/** Biometry-type codes the shim writes out (`LABiometryType`), mirrored from CryptoKitShim.h. */
private const val BIOMETRY_NONE = 0
private const val BIOMETRY_TOUCH_ID = 1
private const val BIOMETRY_FACE_ID = 2
private const val BIOMETRY_OPTIC_ID = 3

/** The shim's "a device credential exists" answer for [bcks_la_device_credential_available]. */
private const val CREDENTIAL_AVAILABLE = 1

/**
 * Probes LocalAuthentication through the CryptoKit shim. Stateless: every call re-probes with a
 * fresh context, which is exactly the contract [UserVerification.availability] asks for.
 */
internal class AppleUserVerification(
    private val authenticator: LocalAuthAuthenticator,
) : UserVerification {
    override fun availability(): UserVerificationAvailability =
        memScoped {
            // The shim writes biometryType on every path, including the unsupported one, so the
            // uninitialized native slot is never read back.
            val biometryType = alloc<IntVar>()
            val status = bcks_la_biometric_availability(biometryType.ptr)
            if (status == BCKS_BIO_UNSUPPORTED) {
                UserVerificationAvailability.Unsupported
            } else {
                UserVerificationAvailability.Supported(
                    biometric = biometricAvailability(status, biometryType.value),
                    deviceCredential = deviceCredentialAvailability(),
                )
            }
        }

    /**
     * Maps a `BCKS_BIO_*` probe result plus the reported `LABiometryType` onto the contract's
     * states. `BCKS_BIO_NOT_AVAILABLE` (the `else` branch, which also absorbs any code a future
     * shim adds) is the one ambiguous result: a reported biometry type means the sensor is there
     * and the app has been denied it, no type means there is no sensor at all.
     */
    private fun biometricAvailability(
        status: Int,
        biometryType: Int,
    ): BiometricAvailability =
        when (status) {
            BCKS_BIO_READY -> BiometricAvailability.Ready(biometryType.toModality())
            BCKS_BIO_NOT_ENROLLED -> AppleNotEnrolled(biometryType.toModality())
            BCKS_BIO_PASSCODE_NOT_SET -> BiometricAvailability.Actionable.DeviceLockNotSet
            BCKS_BIO_LOCKOUT -> AppleLockedOutUntilCredential(authenticator)
            BCKS_BIO_DISCONNECTED -> BiometricAvailability.Actionable.SensorDisconnected
            BCKS_BIO_NOT_PAIRED -> BiometricAvailability.Actionable.SensorNotPaired
            else ->
                if (biometryType != BIOMETRY_NONE) {
                    AppleBiometryPermissionDenied
                } else {
                    BiometricAvailability.Unavailable.NoHardware
                }
        }

    /**
     * The credential half. The shim's third answer (`-1`, "no LocalAuthentication on this
     * platform") cannot reach here: it can only occur where the biometric probe already returned
     * `BCKS_BIO_UNSUPPORTED`, and that path returns [UserVerificationAvailability.Unsupported]
     * without asking. Anything other than a plain "yes" is therefore
     * [DeviceCredentialAvailability.NotSet].
     */
    private fun deviceCredentialAvailability(): DeviceCredentialAvailability =
        if (bcks_la_device_credential_available() == CREDENTIAL_AVAILABLE) {
            DeviceCredentialAvailability.Available
        } else {
            DeviceCredentialAvailability.NotSet
        }
}

/**
 * `LABiometryType` → [BiometricModality]. Optic ID is reported as [BiometricModality.Iris]: it is
 * iris recognition, and the contract's modality is for labelling only, never a security decision.
 */
private fun Int.toModality(): BiometricModality =
    when (this) {
        BIOMETRY_TOUCH_ID -> BiometricModality.Fingerprint
        BIOMETRY_FACE_ID -> BiometricModality.Face
        BIOMETRY_OPTIC_ID -> BiometricModality.Iris
        else -> BiometricModality.Unspecified
    }

/**
 * Apple's [BiometricAvailability.Actionable.NotEnrolled]: hardware present, nothing enrolled.
 *
 * [openEnrollment] is `null` because Apple publishes **no** enrollment route — the `App-Prefs:`
 * URLs that reach the Touch ID / Face ID pane are private API and get an App Store binary rejected
 * — and the contract spells `null` as "no route exists", so the app falls back to prose.
 */
internal class AppleNotEnrolled(
    override val modality: BiometricModality,
) : BiometricAvailability.Actionable.NotEnrolled {
    override val openEnrollment: (suspend () -> Boolean)? = null

    /**
     * Value equality over the reported state, not instance identity: two probes of an unchanged
     * device must compare equal or `distinctUntilChanged`-style consumers re-render every probe.
     */
    override fun equals(other: Any?): Boolean = other is AppleNotEnrolled && other.modality == modality

    override fun hashCode(): Int = modality.hashCode()
}

/**
 * Apple's [BiometricAvailability.Actionable.PermissionDenied]: the sensor exists (the probe saw a
 * non-`none` `LABiometryType`) but the user turned biometry off for this app in Settings.
 *
 * Stateless, hence an object: the settings deep link addresses the app itself.
 */
internal object AppleBiometryPermissionDenied : BiometricAvailability.Actionable.PermissionDenied {
    /**
     * Dispatches the OS settings-page launch. `true` means the launch was handed to the system, not
     * that the page appeared or that consent was granted — re-probe with
     * [UserVerification.availability]. `false` on macOS, which publishes no supported per-app
     * settings URL, and on watchOS.
     */
    override suspend fun openAppSettings(): Boolean = withContext(Dispatchers.Default) { bcks_open_app_settings() == 1 }
}

/**
 * Apple's [BiometricAvailability.Actionable.LockedOutUntilCredential]: `LAError.biometryLockout`
 * from the probe. A successful device-credential evaluation is what re-enables biometry.
 */
internal class AppleLockedOutUntilCredential(
    private val authenticator: LocalAuthAuthenticator,
) : BiometricAvailability.Actionable.LockedOutUntilCredential {
    /** Value equality over the state (see [AppleNotEnrolled.equals]): all lockouts read the same. */
    override fun equals(other: Any?): Boolean = other is AppleLockedOutUntilCredential

    override fun hashCode(): Int = this::class.hashCode()

    /**
     * Prompts for the device credential on a **fresh** context carrying the authenticator's reason,
     * released as soon as the prompt resolves — the unlock authorizes nothing by itself, so there is
     * no reason to keep an evaluated context alive past it. The shim call blocks until the user
     * responds, hence [Dispatchers.Default]. A `0` handle means LocalAuthentication is absent on
     * this platform, which is a [PromptOutcome.Failed], not a cancellation. (A *closed*
     * authenticator still mints live handles — [LocalAuthAuthenticator.newContextHandle] is the
     * per-use factory and deliberately ignores `closed`, matching the enclave sign path.)
     */
    override suspend fun unlockWithDeviceCredential(): PromptOutcome {
        val handle = authenticator.newContextHandle()
        if (handle == 0L) return PromptOutcome.Failed
        return try {
            when (withContext(Dispatchers.Default) { bcks_la_unlock_with_credential(handle) }) {
                BCKS_OK -> PromptOutcome.Succeeded
                BCKS_ERR_CANCELED -> PromptOutcome.UserCancelled
                else -> PromptOutcome.Failed
            }
        } finally {
            releaseContextHandle(handle)
        }
    }
}
