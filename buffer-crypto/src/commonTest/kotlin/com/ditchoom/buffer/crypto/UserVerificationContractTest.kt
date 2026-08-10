package com.ditchoom.buffer.crypto

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Conformance for the interactive user-verification vocabulary ([UserVerification] and the
 * [UserVerificationAvailability] / [BiometricAvailability] hierarchy it reports). Runs on every
 * target — the vocabulary is common, only the states a given device produces are not.
 *
 * The states are supplied by [FakeUserVerification] and friends rather than by a platform probe,
 * because no runner can be asked to be locked out, unenrolled, and permission-denied in one test
 * process. What is under test is therefore the part that *is* platform-independent and the part
 * consumers actually compile against:
 *
 *  - every `when` a consumer writes over these types is **compile-time exhaustive with no `else`** —
 *    the flat 14-branch form, the 4-branch CTA-vs-hide form, and the two-state top-level form. An
 *    `else`-free `when` here is not a style preference: it is what makes a state added in a later
 *    minor a compile error at every consumer instead of a silently-hidden feature;
 *  - [BiometricAvailability.Actionable] and [BiometricAvailability.Unavailable] are **disjoint**, so
 *    "show a CTA" and "hide the option" can never both be true of one state;
 *  - a remedy exists **iff** the state admits one, and a `null`
 *    [BiometricAvailability.Actionable.NotEnrolled.openEnrollment] is information ("no OS route"), not
 *    a missing feature — the consumer's `?.let` simply does not run;
 *  - the composition the design is shaped around: a lockout plus a device credential is a remedy, a
 *    lockout without one is prose.
 */
class UserVerificationContractTest {
    // ---------------------------------------------------------------------------------------------
    // Exhaustiveness: each helper below is a consumer's `when`, written without `else` on purpose.
    // ---------------------------------------------------------------------------------------------

    /** What a screen does with a state: prompt, render a call to action, or hide the affordance. */
    private enum class Ux { Prompt, Cta, Hide }

    /**
     * The **first** branch a consumer writes, over the four direct children of the sealed parent.
     * Needs no `else`, which is the proof that [BiometricAvailability.Actionable] and
     * [BiometricAvailability.Unavailable] partition the actionable/unactionable states between them
     * with [BiometricAvailability.Ready] and [BiometricAvailability.Indeterminate] outside both.
     */
    private fun ux(state: BiometricAvailability): Ux =
        when (state) {
            is BiometricAvailability.Ready -> Ux.Prompt
            is BiometricAvailability.Actionable -> Ux.Cta
            is BiometricAvailability.Unavailable -> Ux.Hide
            // Status unknown: no remedy to offer and no basis for hiding, so attempting is legitimate.
            BiometricAvailability.Indeterminate -> Ux.Prompt
        }

    /**
     * The **fully-resolved** branch, naming all fourteen leaves of the hierarchy with no `else`. The
     * three action-carrying leaves are matched with `is` because they are interfaces the library
     * implements per platform; the rest are the contract's own singletons.
     */
    @Suppress("CyclomaticComplexMethod")
    private fun label(state: BiometricAvailability): String =
        when (state) {
            is BiometricAvailability.Ready -> "ready"
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

    /** The top-level branch: a platform either has a verification facility or it does not. */
    private fun label(availability: UserVerificationAvailability): String =
        when (availability) {
            UserVerificationAvailability.Unsupported -> "unsupported"
            is UserVerificationAvailability.Supported ->
                "${label(availability.biometric)}/${availability.deviceCredential}"
        }

    /** Every [BiometricAvailability.Actionable] leaf, fakes standing in for the three interfaces. */
    private fun actionableStates(): List<BiometricAvailability.Actionable> =
        listOf(
            FakeNotEnrolled(),
            FakeDeviceLockNotSet(),
            FakePermissionDenied(),
            BiometricAvailability.Actionable.SecurityUpdateRequired,
            BiometricAvailability.Actionable.SensorDisconnected,
            BiometricAvailability.Actionable.SensorNotPaired,
            FakeLockedOutUntilCredential(),
            BiometricAvailability.Actionable.LockedOutTemporarily,
        )

    /** Every [BiometricAvailability.Unavailable] leaf. */
    private fun unavailableStates(): List<BiometricAvailability.Unavailable> =
        listOf(
            BiometricAvailability.Unavailable.NoHardware,
            BiometricAvailability.Unavailable.OnlyWeakBiometrics,
            BiometricAvailability.Unavailable.TemporarilyUnavailable,
            BiometricAvailability.Unavailable.NotSupportedByOs,
        )

    /** All fourteen states: [BiometricAvailability.Ready], 8 actionable, 4 unavailable, indeterminate. */
    private fun allBiometricStates(): List<BiometricAvailability> =
        buildList {
            BiometricModality.entries.forEach { add(BiometricAvailability.Ready(it)) }
            addAll(actionableStates())
            addAll(unavailableStates())
            add(BiometricAvailability.Indeterminate)
        }

    // ---------------------------------------------------------------------------------------------
    // Tests
    // ---------------------------------------------------------------------------------------------

    @Test
    fun topLevelAvailabilityHasExactlyTwoStates() {
        val probed = FakeUserVerification(UserVerificationAvailability.Unsupported).availability()
        assertEquals("unsupported", label(probed))
        val supported =
            UserVerificationAvailability.Supported(
                biometric = BiometricAvailability.Ready(BiometricModality.Face),
                deviceCredential = DeviceCredentialAvailability.Available,
            )
        assertEquals("ready/Available", label(FakeUserVerification(supported).availability()))
    }

    @Test
    fun everyBiometricStateIsNamedByAnElseFreeWhen() {
        // 4 Ready modalities + 8 Actionable + 4 Unavailable + Indeterminate.
        val states = allBiometricStates()
        assertEquals(BiometricModality.entries.size + 8 + 4 + 1, states.size, "every leaf must be represented")
        // Distinct labels: the flat `when` above resolves each leaf to its own branch, so no two
        // states collapse onto one another (a mis-ordered `is` branch would swallow a sibling).
        val labels = states.map { label(it) }
        assertEquals(14, labels.toSet().size, "the four Ready modalities share one label; all 14 leaves are distinct")
        assertTrue(labels.none { it.isEmpty() })
    }

    @Test
    fun actionableAndUnavailableAreDisjointBranchesOfTheSealedParent() {
        actionableStates().forEach { state ->
            assertEquals(Ux.Cta, ux(state), "an Actionable state must render a call to action: ${label(state)}")
            assertTrue(state !is BiometricAvailability.Unavailable, "Actionable must never also be Unavailable")
        }
        unavailableStates().forEach { state ->
            assertEquals(Ux.Hide, ux(state), "an Unavailable state must hide the option: ${label(state)}")
            assertTrue(state !is BiometricAvailability.Actionable, "Unavailable must never also be Actionable")
        }
        // The two states outside both: a prompt is worth attempting.
        assertEquals(Ux.Prompt, ux(BiometricAvailability.Ready(BiometricModality.Fingerprint)))
        assertEquals(Ux.Prompt, ux(BiometricAvailability.Indeterminate))
        assertOutsideBothBranches(BiometricAvailability.Ready(BiometricModality.Fingerprint))
        assertOutsideBothBranches(BiometricAvailability.Indeterminate)
    }

    /** [BiometricAvailability.Ready] and [BiometricAvailability.Indeterminate] sit outside both branches. */
    private fun assertOutsideBothBranches(state: BiometricAvailability) {
        assertTrue(state !is BiometricAvailability.Actionable, "$state has no remedy to offer")
        assertTrue(state !is BiometricAvailability.Unavailable, "$state is no basis for hiding the option")
    }

    @Test
    fun enumsRoundTripThroughTheirNames() {
        assertEquals(3, PromptOutcome.entries.size)
        assertEquals(4, BiometricModality.entries.size)
        assertEquals(2, DeviceCredentialAvailability.entries.size)
        PromptOutcome.entries.forEach { assertEquals(it, PromptOutcome.valueOf(it.name)) }
        BiometricModality.entries.forEach { assertEquals(it, BiometricModality.valueOf(it.name)) }
        DeviceCredentialAvailability.entries.forEach { assertEquals(it, DeviceCredentialAvailability.valueOf(it.name)) }
        // Ready carries the modality it was told, unchanged — it is a label, never a security decision.
        BiometricModality.entries.forEach { assertEquals(it, BiometricAvailability.Ready(it).modality) }
    }

    @Test
    fun platformsWithoutAFacilityShareOneStatelessInstance() {
        // The object every Unsupported platform boundary hands out (JVM, Linux, JS/WASM).
        assertEquals(UserVerificationAvailability.Unsupported, UnsupportedUserVerification.availability())
        assertEquals(UserVerificationAvailability.Unsupported, UnsupportedUserVerification.availability())
    }

    @Test
    fun aConsumerSeesTheStateChangeBetweenTwoProbes() {
        // The contract's headline rule is "re-query at the moment of need, never cache": a device can
        // lose its last enrollment between two screens, and a consumer that probes fresh must see it.
        val verification =
            FakeUserVerification(
                UserVerificationAvailability.Supported(
                    biometric = BiometricAvailability.Ready(BiometricModality.Fingerprint),
                    deviceCredential = DeviceCredentialAvailability.Available,
                ),
            )
        assertEquals("ready/Available", label(verification.availability()))
        verification.scripted =
            UserVerificationAvailability.Supported(
                biometric = FakeNotEnrolled(BiometricModality.Fingerprint),
                deviceCredential = DeviceCredentialAvailability.Available,
            )
        assertEquals("not-enrolled/Available", label(verification.availability()))
        assertEquals(2, verification.probes, "each render probes the OS again")
    }

    @Test
    fun notEnrolledWithoutAnOsRouteFallsBackToProse() {
        // Apple publishes no enrollment deep link, so `openEnrollment` is null and the consumer's
        // `?.let` never runs — the CTA becomes prose instead of a button.
        val state = FakeNotEnrolled(modality = BiometricModality.Face, presents = null)
        assertNull(state.openEnrollment, "a platform with no enrollment route reports null, not a no-op lambda")
        var cta: String? = null
        state.openEnrollment?.let { cta = "button" }
        assertNull(cta, "no route means no button")
        assertEquals(0, state.enrollmentLaunches, "nothing was launched")
        assertEquals(BiometricModality.Face, state.modality, "the modality still names what would be enrolled")
    }

    @Test
    fun notEnrolledWithAnOsRouteLaunchesEnrollment() =
        runTest {
            val presented = FakeNotEnrolled(modality = BiometricModality.Fingerprint)
            val open = assertNotNull(presented.openEnrollment, "a platform with a route supplies the remedy")
            assertTrue(open(), "true means the enrollment screen was presented")
            assertEquals(1, presented.enrollmentLaunches)

            // `false` is the OEM-stripped-Intent case: not a crash, a signal to fall back to prose.
            val stripped = FakeNotEnrolled(presents = false)
            val strippedOpen = assertNotNull(stripped.openEnrollment)
            assertFalse(strippedOpen(), "false means the Intent never launched")
            assertEquals(1, stripped.enrollmentLaunches)
        }

    @Test
    fun deviceLockNotSetCarriesTheSameNullableRemedyShape() =
        runTest {
            // Android: a route exists (API 30+ walks lock-setup AND enrollment in one flow).
            val launched = FakeDeviceLockNotSet()
            val open = assertNotNull(launched.openDeviceLockSetup, "Android supplies a lock-setup route")
            assertTrue(open(), "true means the flow was launched")
            assertEquals(1, launched.lockSetupLaunches)

            // Apple: passcode setup has no public deep link — null, prose fallback, remedy never runs.
            val prose = FakeDeviceLockNotSet(launches = null)
            assertNull(prose.openDeviceLockSetup, "no OS route reports null, not a no-op lambda")
            assertEquals(0, prose.lockSetupLaunches, "nothing was launched")
        }

    @Test
    fun permissionDeniedOpensThisAppsSettingsPage() =
        runTest {
            val granted = FakePermissionDenied()
            assertTrue(granted.openAppSettings(), "true means the settings page was presented")
            assertEquals(1, granted.settingsLaunches)
            // macOS/watchOS publish no supported per-app settings URL; the state stays Actionable but
            // the launch honestly reports false.
            val noRoute = FakePermissionDenied(presents = false)
            assertFalse(noRoute.openAppSettings())
            assertEquals(Ux.Cta, ux(noRoute), "a failed launch does not change the state's UX class")
        }

    @Test
    fun lockedOutReportsEveryPromptOutcome() =
        runTest {
            PromptOutcome.entries.forEach { scripted ->
                val state = FakeLockedOutUntilCredential(scripted)
                assertEquals(scripted, state.unlockWithDeviceCredential(), "the remedy surfaces $scripted verbatim")
                assertEquals(1, state.prompts)
            }
        }

    @Test
    fun lockoutRemedyIsOfferedOnlyWhenTheDeviceHasACredential() =
        runTest {
            // The design's UX story, both halves. With a credential, the credential prompt IS the
            // remedy that re-enables biometry; without one there is nothing to prompt for, so the app
            // falls back to prose and the remedy is never invoked.
            val withCredential = FakeLockedOutUntilCredential(PromptOutcome.Succeeded)
            assertEquals(
                "unlocked:Succeeded",
                resolveLockout(
                    UserVerificationAvailability.Supported(withCredential, DeviceCredentialAvailability.Available),
                ),
            )
            assertEquals(1, withCredential.prompts)

            val withoutCredential = FakeLockedOutUntilCredential(PromptOutcome.Succeeded)
            assertEquals(
                "set a screen lock to re-enable biometrics",
                resolveLockout(
                    UserVerificationAvailability.Supported(withoutCredential, DeviceCredentialAvailability.NotSet),
                ),
            )
            assertEquals(0, withoutCredential.prompts, "no credential means no prompt to drive")

            // A cancelled unlock is a distinct answer from a failed one: the app must not retry a
            // deliberate dismissal, and both leave biometry locked out.
            val cancelled = FakeLockedOutUntilCredential(PromptOutcome.UserCancelled)
            assertEquals(
                "unlocked:UserCancelled",
                resolveLockout(
                    UserVerificationAvailability.Supported(cancelled, DeviceCredentialAvailability.Available),
                ),
            )
        }

    /** The composed consumer path: lockout state × credential availability → what the app does. */
    private suspend fun resolveLockout(state: UserVerificationAvailability.Supported): String {
        val biometric = state.biometric
        if (biometric !is BiometricAvailability.Actionable.LockedOutUntilCredential) return label(biometric)
        return when (state.deviceCredential) {
            DeviceCredentialAvailability.Available -> "unlocked:${biometric.unlockWithDeviceCredential()}"
            DeviceCredentialAvailability.NotSet -> "set a screen lock to re-enable biometrics"
        }
    }
}
