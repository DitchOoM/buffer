package com.ditchoom.buffer.crypto

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The browser / WASM boundary reports [UserVerificationAvailability.Unsupported]. The web platform
 * exposes no biometric *status* by design — enrollment, permission, and lockout are fingerprintable
 * device state — so verification only ever happens inside a WebAuthn ceremony, which is a prompt and
 * not a probe. None of [BiometricAvailability]'s states could be answered honestly here.
 */
class UserVerificationWebTest {
    @Test
    fun theWebHasNoInteractiveVerificationFacility() {
        assertEquals(UserVerificationAvailability.Unsupported, userVerification().availability())
    }
}
