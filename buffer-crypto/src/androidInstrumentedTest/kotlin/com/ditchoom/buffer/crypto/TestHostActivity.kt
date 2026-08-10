package com.ditchoom.buffer.crypto

import androidx.fragment.app.FragmentActivity

/**
 * Bare host for instrumented tests that need a real [FragmentActivity] — the platform boundary
 * `userVerification(activity)` requires one (mirroring `BiometricPromptAuthenticator`). Declared in
 * this source set's `AndroidManifest.xml`; launched with `ActivityScenario`. No UI, no theme
 * requirements — `FragmentActivity` inflates against the default platform theme.
 */
class TestHostActivity : FragmentActivity()
