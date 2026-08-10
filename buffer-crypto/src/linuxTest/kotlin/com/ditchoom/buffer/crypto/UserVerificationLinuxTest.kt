package com.ditchoom.buffer.crypto

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Linux boundary reports [UserVerificationAvailability.Unsupported]: there is no OS facility this
 * library can drive to verify a human at the point of use. A TPM-backed PKCS#11 token PIN is real
 * authentication but process-to-token and set by deployment configuration, so it is reported by
 * [ProtectedKeyResolution] / [CapabilityFinding] rather than here.
 */
class UserVerificationLinuxTest {
    @Test
    fun linuxHasNoInteractiveVerificationFacility() {
        assertEquals(UserVerificationAvailability.Unsupported, userVerification().availability())
    }
}
