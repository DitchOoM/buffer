package com.ditchoom.buffer.crypto

/*
 * Interactive user verification — "can this device ask a human to prove they are here, right now,
 * and if not, what exactly should the app tell them to do about it?"
 *
 * This family answers a *UX* question, not a custody question. The custody surfaces already in this
 * module ([ProtectedKeyResolution], [CryptoCapabilities.hardware], [KeyProvider.custodyFor]) say
 * where key material lives and whether an operation can be bound to authentication. Neither of them
 * can tell an app whether it is allowed to *draw a "Sign in with Face ID" button* — that needs the
 * enrollment/lockout/permission state of the device's own verification facility, which is exactly
 * what [UserVerification.availability] reports.
 *
 * Three decisions shape the whole hierarchy:
 *
 *  - **Probing is separate from prompting.** [UserVerification.availability] never puts UI on
 *    screen; it is a cheap OS query (Android `BiometricManager.canAuthenticate`, Apple
 *    `LAContext.canEvaluatePolicy`). There is deliberately **no** bare `authenticate()` here at all
 *    — see the note on [BiometricAvailability.Actionable].
 *  - **A remedy exists iff the state admits one.** Rather than a flat enum plus a grab-bag of
 *    "maybe this helps" helpers, the actions are `suspend` members *on the states that have them*
 *    ([BiometricAvailability.Actionable.NotEnrolled.openEnrollment],
 *    [BiometricAvailability.Actionable.PermissionDenied.openAppSettings],
 *    [BiometricAvailability.Actionable.LockedOutUntilCredential.unlockWithDeviceCredential]). An
 *    unreachable remedy is not representable, so no consumer can call one into a no-op.
 *  - **The first branch a consumer writes is CTA-vs-hide.** [BiometricAvailability.Actionable] means
 *    "the user can fix this — show a call to action"; [BiometricAvailability.Unavailable] means
 *    "nothing the user does here will help — hide the option". [BiometricAvailability.Ready] and
 *    [BiometricAvailability.Indeterminate] are the two states where a prompt is worth attempting.
 *
 * Construction is a **platform-boundary** concern: an implementation captures live UI context
 * (Android a `FragmentActivity`, Apple a `LocalAuthAuthenticator`), which common code cannot name.
 * Each platform therefore exposes its own `userVerification(...)` function — plain functions with
 * platform-shaped signatures, not `expect`/`actual` — exactly as the existing
 * `userAuthenticated(...)` seam does for [UserAuthenticatedKeyProvider]. Common code takes a
 * [UserVerification] as an injected parameter and never constructs one.
 */

/**
 * The device's interactive user-verification facility: the OS asking a human to prove presence with
 * a biometric or the device credential.
 *
 * **Re-query at the moment of need; never cache the result.** Enrollment, permission, and lockout
 * all change while the app is backgrounded — a user can delete their last fingerprint, revoke Face
 * ID for the app, or lock biometry out with failed attempts between two screens. Call
 * [availability] when a screen is about to render its authentication affordance, and again after a
 * remedy suspends and returns. The call is a cheap OS status query, so re-querying is not a cost
 * worth optimizing away; a stale cached [BiometricAvailability.Ready] is a broken prompt.
 *
 * ```kotlin
 * // `verification` was injected from the platform boundary; probe fresh on every render.
 * when (val availability = verification.availability()) {
 *     UserVerificationAvailability.Unsupported -> hideBiometricOption()
 *     is UserVerificationAvailability.Supported -> when (val biometric = availability.biometric) {
 *         is BiometricAvailability.Ready -> offerBiometricUnlock(biometric.modality)
 *         is BiometricAvailability.Actionable.NotEnrolled ->
 *             showCta(enrollLabel(biometric.modality), action = biometric.openEnrollment)
 *         is BiometricAvailability.Actionable.PermissionDenied ->
 *             showCta("Allow biometrics for this app", action = biometric::openAppSettings)
 *         is BiometricAvailability.Actionable.LockedOutUntilCredential ->
 *             showCta("Unlock with your device passcode") { biometric.unlockWithDeviceCredential() }
 *         is BiometricAvailability.Actionable.DeviceLockNotSet ->
 *             showCta("Set a screen lock to use biometrics", action = biometric.openDeviceLockSetup)
 *         is BiometricAvailability.Actionable -> showGenericCta(biometric)
 *         is BiometricAvailability.Unavailable -> hideBiometricOption()
 *         // Status could not be determined — attempting the prompt is legitimate.
 *         BiometricAvailability.Indeterminate -> offerBiometricUnlock(BiometricModality.Unspecified)
 *     }
 * }
 * ```
 *
 * **Scope.** This reports *interactive* verification only. It is **not** "can a key be gated behind
 * authentication": a PKCS#11 token PIN on the desktop JVM / Linux (`C_Login`, configured via
 * `BUFFER_CRYPTO_PKCS11_PIN`) is genuine authentication, but it is process-to-token and set by
 * deployment configuration — no human is verified at the point of use. That capability is reported
 * by [ProtectedKeyResolution] / [CapabilityFinding], never here. Correspondingly,
 * [UserVerificationAvailability.Unsupported] means *no interactive facility on this platform* and
 * says nothing at all about token authentication or key custody.
 */
interface UserVerification {
    /**
     * Probes the current interactive-verification status. Cheap, non-prompting, and safe to call on
     * a UI thread. **Never cache the returned value** — see the note on [UserVerification].
     */
    fun availability(): UserVerificationAvailability
}

/**
 * Whether this platform has an interactive user-verification facility at all, and if so its two
 * independent statuses.
 *
 * The split is a platform fact, not a device fact: [Unsupported] is reported where the OS ships no
 * verification framework this library can drive (a desktop JVM, Linux, browsers/WASM engines,
 * tvOS — where `LAContext` is marked unavailable). Everything else reports [Supported] and
 * lets the two nested statuses describe the device. watchOS is the illustrative case: it is
 * [Supported] with [BiometricAvailability.Unavailable.NoHardware] and
 * [DeviceCredentialAvailability.Available] — no biometric sensor, but a real passcode the OS can
 * verify.
 */
sealed interface UserVerificationAvailability {
    /**
     * No interactive user-verification facility on this platform. Says nothing about key custody or
     * token authentication (see the scope note on [UserVerification]) — a JVM host holding
     * TPM-backed PKCS#11 keys still reports [Unsupported] here.
     */
    data object Unsupported : UserVerificationAvailability

    /**
     * The platform has a verification facility; [biometric] and [deviceCredential] report its two
     * halves independently. They compose: a [BiometricAvailability.Actionable] biometric state with
     * [DeviceCredentialAvailability.Available] means the app can offer credential fallback *now*
     * while surfacing the biometric remedy as a secondary CTA.
     */
    data class Supported(
        val biometric: BiometricAvailability,
        val deviceCredential: DeviceCredentialAvailability,
    ) : UserVerificationAvailability
}

/**
 * The status of **key-capable** biometry on this device.
 *
 * "Key-capable" is the load-bearing scope: only biometry strong enough to protect a hardware-backed
 * key is considered (Android Class 3 / `BIOMETRIC_STRONG`; on Apple every biometry the OS exposes
 * qualifies). So [Ready] carries a single, unqualified promise — *this biometry can gate a
 * hardware-backed key* — and never needs a second strength check at the call site. A device whose
 * only biometry is Class 2 (a weak face unlock) reports
 * [Unavailable.OnlyWeakBiometrics]: real, working biometry that can nonetheless never protect a
 * key, kept distinct from [Unavailable.NoHardware] precisely so the UX can say *why* the option is
 * missing instead of claiming a sensor that visibly exists is absent.
 *
 * The consumer's first branch is [Actionable] (show a call to action) versus [Unavailable] (hide
 * the option). [Indeterminate] sits outside both.
 */
sealed interface BiometricAvailability {
    /**
     * Key-capable biometry is enrolled and usable right now; [modality] is what the OS reports the
     * user will see (fingerprint, face, iris, or [BiometricModality.Unspecified] where the platform
     * declines to say). Still not a cached fact — re-probe before each use.
     */
    data class Ready(
        val modality: BiometricModality,
    ) : BiometricAvailability

    /**
     * States the **user can fix** — render a call to action rather than hiding the feature.
     *
     * Where a route to the fix exists, it rides on the state as a `suspend` member. There is
     * deliberately **no** bare `authenticate()` / `prompt()` anywhere in this API: an authentication
     * result that is not bound to a key operation is bypassable theater — code that skips the check
     * gets the same crypto — so authentication lives *inside* key use, on
     * [UserAuthenticatedKeyProvider], where the OS binds it to the operation itself
     * (`BiometricPrompt.CryptoObject`, `SecAccessControl`). Remedies here fix *states*;
     * authentication stays in *key use*.
     *
     * The action-carrying members are non-`sealed` interfaces on purpose: each platform source set
     * supplies an `internal` implementation closing over its captured host (activity / `LAContext`),
     * which a `sealed` supertype could not permit from outside this file. They are **implemented by
     * the library, not by consumers** — treat them as closed and branch on them exhaustively; the
     * same "sealed contract with internal implementations" posture the rest of this module uses.
     */
    sealed interface Actionable : BiometricAvailability {
        /**
         * The hardware is present and key-capable but nothing is enrolled — [modality] is what the
         * user would be enrolling.
         *
         * [openEnrollment] launches the OS enrollment surface and returns `true` when it was
         * actually presented. It is `null` **iff the OS offers no route at all**: Apple publishes no
         * enrollment deep link (`App-Prefs:` URLs are private API and reject an App Store binary),
         * so an Apple implementation supplies `null` and the app must fall back to prose ("Set up
         * Face ID in Settings"). A `null` here is therefore information, not a missing feature.
         * Returning from [openEnrollment] does not imply the user enrolled — re-probe with
         * [UserVerification.availability].
         */
        interface NotEnrolled : Actionable {
            /** Which biometry the user would enroll. */
            val modality: BiometricModality

            /** Opens OS enrollment, `true` if presented; `null` when the platform has no route. */
            val openEnrollment: (suspend () -> Boolean)?
        }

        /**
         * No screen lock is set, so biometric enrollment is impossible in the first place. The
         * correct CTA is **"set a passcode"**, not "enroll a fingerprint" — sending the user to
         * enrollment from this state dead-ends them. Setting a device credential also moves
         * [DeviceCredentialAvailability] to [DeviceCredentialAvailability.Available].
         */
        interface DeviceLockNotSet : Actionable {
            /**
             * Launches the OS flow that sets a device credential; `null` ⇔ the OS has no route
             * (Apple — passcode setup has no public deep link). On Android API 30+ this is the
             * biometric-enroll flow itself, which walks the user through setting the lock *and*
             * enrolling in one pass, so a single tap can carry this state all the way to [Ready].
             * `true` means the flow was launched, not completed — re-probe with
             * [UserVerification.availability] on resume.
             */
            val openDeviceLockSetup: (suspend () -> Boolean)?
        }

        /**
         * The user revoked this app's permission to use biometry (Apple: Face ID toggled off for
         * the app in Settings). Apple-produced: the implementation distinguishes it from
         * [Unavailable.NoHardware] by checking that `LAContext.biometryType` is not `.none` — the
         * sensor exists, the app just may not use it.
         */
        interface PermissionDenied : Actionable {
            /**
             * Asks the OS to open this app's settings page so the user can re-grant biometry;
             * `true` means the launch was **dispatched** (the OS accepted the request), not that
             * the page appeared — iOS offers no completion signal a library could trust. `false`
             * means this platform has no route (macOS has no supported per-app settings URL).
             * Returning does not imply consent was granted — re-probe with
             * [UserVerification.availability] on resume.
             */
            suspend fun openAppSettings(): Boolean
        }

        /**
         * Biometry is unusable until a pending OS/security update is installed (Android
         * `BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED`). Android-produced; the CTA points at system
         * updates.
         */
        data object SecurityUpdateRequired : Actionable

        /**
         * The biometric sensor is an external device that is currently disconnected (macOS external
         * Touch ID keyboard; Apple `biometryDisconnected`). The CTA is "reconnect it" — the
         * capability returns on its own once the hardware is back.
         */
        data object SensorDisconnected : Actionable

        /**
         * An external biometric sensor is present but not paired with this Mac (Apple
         * `biometryNotPaired`). The CTA is to complete pairing in system settings.
         */
        data object SensorNotPaired : Actionable

        /**
         * Biometry is locked out until the user proves themselves with the **device credential**;
         * repeated failures got it there. [unlockWithDeviceCredential] drives the system credential
         * prompt, and on success biometry is re-enabled on both platforms — so the natural UX is to
         * compose this with the sibling [UserVerificationAvailability.Supported.deviceCredential]
         * field and offer credential entry as the fallback path.
         *
         * **Entry points differ by platform, vocabulary does not.** On Apple this state is visible
         * to the *probe* (`LAError.biometryLockout` from `canEvaluatePolicy`). On Android the probe
         * cannot see lockout at all — `canAuthenticate` still reports success, and lockout surfaces
         * only *after* a prompt attempt (`ERROR_LOCKOUT_PERMANENT`). This release therefore never
         * produces this state on Android; the vocabulary is defined platform-neutrally so a later
         * minor can map Android's post-prompt errors onto it without inventing a second type.
         * One vocabulary, two entry points; see also [LockedOutTemporarily].
         */
        interface LockedOutUntilCredential : Actionable {
            /**
             * Presents the system device-credential prompt; on [PromptOutcome.Succeeded] biometry is
             * re-enabled. Re-probe with [UserVerification.availability] afterwards rather than
             * assuming [Ready].
             */
            suspend fun unlockWithDeviceCredential(): PromptOutcome
        }

        /**
         * Biometry is in a short cool-down after failed attempts and recovers by itself.
         *
         * It deliberately **carries no duration**: no platform discloses how long the cool-down has
         * left (Android's 30s is an implementation detail it does not publish, Apple exposes
         * nothing), so a field here could only ever be a guess rendered as a countdown the OS may
         * contradict. Re-probe on resume instead. As with [LockedOutUntilCredential], Apple could
         * report this from a probe while Android sees lockout only after a prompt attempt
         * (`ERROR_LOCKOUT`) — so this release produces the state on no platform yet (Apple's
         * probe-visible lockout is the until-credential kind); it names the transient cool-down so
         * the post-prompt mapping of a later minor has a home for it.
         */
        data object LockedOutTemporarily : Actionable
    }

    /**
     * States **no user action can fix** — hide the biometric option rather than offering a CTA that
     * leads nowhere. Fall back to [UserVerificationAvailability.Supported.deviceCredential] or to
     * the app's own authentication.
     */
    sealed interface Unavailable : BiometricAvailability {
        /** The device has no biometric sensor at all (watchOS, many low-end and desktop devices). */
        data object NoHardware : Unavailable

        /**
         * The device has biometry, but only weak biometry (Android Class 2 / `BIOMETRIC_WEAK`) —
         * real and working for screen unlock, yet never permitted to gate a hardware-backed key, so
         * it can never satisfy this API's key-capable scope. Distinct from [NoHardware] so the UX
         * can explain a visibly-present sensor it is not offering.
         */
        data object OnlyWeakBiometrics : Unavailable

        /**
         * The sensor exists and is enrolled but the OS reports it unavailable right now for a reason
         * outside the user's control (Android `BIOMETRIC_ERROR_HW_UNAVAILABLE` — hardware busy,
         * another app holding it, a transient service failure). Not [Actionable]: there is no CTA,
         * it simply may work later.
         */
        data object TemporarilyUnavailable : Unavailable

        /** The OS version predates key-capable biometric APIs, so this library cannot drive it. */
        data object NotSupportedByOs : Unavailable
    }

    /**
     * The platform could not determine biometric status (Android
     * `BIOMETRIC_STATUS_UNKNOWN`). Outside both [Actionable] and [Unavailable] on purpose: there is
     * no remedy to offer *and* no basis for hiding the feature. **Attempting the prompt is
     * legitimate** — the prompt itself will report the real outcome — so treat this like an
     * optimistic [Ready] with an unknown [BiometricModality], and handle the prompt's failure path.
     */
    data object Indeterminate : BiometricAvailability
}

/**
 * Which biometry the user will be shown, for labelling and iconography only — never for a security
 * decision (key-capability is already guaranteed by [BiometricAvailability.Ready]'s scope).
 */
enum class BiometricModality {
    /** Fingerprint (Touch ID, Android fingerprint sensors). */
    Fingerprint,

    /** Face recognition (Face ID, Android face unlock that meets Class 3). */
    Face,

    /** Iris recognition. */
    Iris,

    /** The platform reports key-capable biometry without naming the modality. */
    Unspecified,
}

/**
 * Whether the device has a credential (PIN / pattern / passcode) the OS can verify. Reported
 * alongside [BiometricAvailability] so an app can offer credential fallback while a biometric state
 * is being remedied — and because [DeviceCredentialAvailability.NotSet] is the same underlying fact
 * as [BiometricAvailability.Actionable.DeviceLockNotSet], seen from the credential side.
 */
enum class DeviceCredentialAvailability {
    /** A device credential is set and the OS can prompt for it. */
    Available,

    /** No screen lock is set; no credential prompt is possible (and biometry cannot be enrolled). */
    NotSet,
}

/**
 * The result of a system prompt this API drives — currently only
 * [BiometricAvailability.Actionable.LockedOutUntilCredential.unlockWithDeviceCredential].
 *
 * Three outcomes because they need three different app responses: proceed, respect an explicit
 * user decision, or report an error. It carries no error detail on purpose — the actionable
 * follow-up is always to re-probe with [UserVerification.availability], never to branch on an OS
 * error code.
 */
enum class PromptOutcome {
    /** The user authenticated successfully. */
    Succeeded,

    /** The user dismissed the prompt or chose a negative action; do not retry unprompted. */
    UserCancelled,

    /** Authentication did not succeed for any other reason (attempt exhausted, system error). */
    Failed,
}

/**
 * The shared [UserVerification] every platform without an interactive verification facility hands
 * out — a JVM host, Linux, and JS/WASM engines. Stateless and allocation-free, so a platform
 * boundary function can return it unconditionally. `internal`: consumers only ever see the
 * [UserVerification] interface and the [UserVerificationAvailability.Unsupported] value.
 */
internal object UnsupportedUserVerification : UserVerification {
    override fun availability(): UserVerificationAvailability = UserVerificationAvailability.Unsupported
}
