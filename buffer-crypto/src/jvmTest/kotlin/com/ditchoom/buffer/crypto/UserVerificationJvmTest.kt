package com.ditchoom.buffer.crypto

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The desktop JVM boundary reports [UserVerificationAvailability.Unsupported]: a `java` launcher has
 * no OS facility for verifying a *human at the point of use*, and nothing about this host's key
 * custody changes that — a JVM holding TPM-backed PKCS#11 keys still has no biometric prompt to
 * raise, and its token PIN is reported by [ProtectedKeyResolution], never here.
 */
class UserVerificationJvmTest {
    @Test
    fun jvmHasNoInteractiveVerificationFacility() {
        assertEquals(UserVerificationAvailability.Unsupported, userVerification().availability())
    }
}
