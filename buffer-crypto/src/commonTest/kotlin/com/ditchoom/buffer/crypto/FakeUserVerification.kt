package com.ditchoom.buffer.crypto

/*
 * Test doubles for the [UserVerification] vocabulary.
 *
 * [UserVerification.availability] answers a *device* question, so no real platform can be asked to
 * produce a chosen state on demand: a CI runner has whatever enrollment, permission, and lockout it
 * has, and eight of the fourteen [BiometricAvailability] variants are unreachable on any single one
 * of them. What is testable everywhere — and is the part consumers actually depend on — is the
 * *vocabulary*: that every state is reachable as a value, that a consumer's `when` over it is
 * compile-time exhaustive, and that a remedy is present exactly where the contract says it is.
 *
 * These fakes therefore stand in for the platform implementations the same way [FakeHardware] stands
 * in for a secure element: the fake supplies the state, the *contract* under test is the shape.
 *
 * Only the three action-carrying members need faking. The contract deliberately makes them
 * non-`sealed` interfaces so each platform source set can supply an `internal` implementation closing
 * over its captured host (`FragmentActivity` / `LAContext`); commonTest supplies its own for exactly
 * the same reason, closing over a scripted outcome instead. Every other state is a `data object` or a
 * `data class` the contract already hands out, and is used directly.
 */

/**
 * A [UserVerification] that reports whatever state a test scripts.
 *
 * [scripted] is a `var` on purpose: the contract's headline rule is *never cache the result*, and the
 * way to exercise that is to change the device's answer between two probes and prove a consumer sees
 * the new one. [probes] counts calls so a test can show a consumer re-queried rather than reused.
 */
internal class FakeUserVerification(
    var scripted: UserVerificationAvailability,
) : UserVerification {
    var probes: Int = 0
        private set

    override fun availability(): UserVerificationAvailability {
        probes++
        return scripted
    }
}

/**
 * [BiometricAvailability.Actionable.NotEnrolled] with a scripted enrollment route.
 *
 * [presents] models the two shapes the contract admits: a non-`null` value is a platform that
 * publishes an enrollment route (Android), where `true` means the screen was presented and `false`
 * means the Intent did not launch at all; `null` is a platform with **no route** (Apple, whose
 * enrollment deep links are private API), where [openEnrollment] itself must be `null` so the app
 * falls back to prose.
 */
internal class FakeNotEnrolled(
    override val modality: BiometricModality = BiometricModality.Unspecified,
    presents: Boolean? = true,
) : BiometricAvailability.Actionable.NotEnrolled {
    /** How many times the remedy was actually invoked — `0` proves the "no OS route" path skipped it. */
    var enrollmentLaunches: Int = 0
        private set

    override val openEnrollment: (suspend () -> Boolean)? =
        presents?.let { result -> suspend { recordLaunch(result) } }

    private fun recordLaunch(result: Boolean): Boolean {
        enrollmentLaunches++
        return result
    }
}

/**
 * [BiometricAvailability.Actionable.PermissionDenied] whose settings launch reports [presents]
 * (`false` is the honest answer on the Apple platforms that publish no per-app settings URL).
 */
internal class FakePermissionDenied(
    private val presents: Boolean = true,
) : BiometricAvailability.Actionable.PermissionDenied {
    var settingsLaunches: Int = 0
        private set

    override suspend fun openAppSettings(): Boolean {
        settingsLaunches++
        return presents
    }
}

/**
 * [BiometricAvailability.Actionable.LockedOutUntilCredential] whose credential prompt resolves to a
 * scripted [outcome] — the one place a [PromptOutcome] reaches a consumer at all.
 */
internal class FakeLockedOutUntilCredential(
    private val outcome: PromptOutcome = PromptOutcome.Succeeded,
) : BiometricAvailability.Actionable.LockedOutUntilCredential {
    var prompts: Int = 0
        private set

    override suspend fun unlockWithDeviceCredential(): PromptOutcome {
        prompts++
        return outcome
    }
}
