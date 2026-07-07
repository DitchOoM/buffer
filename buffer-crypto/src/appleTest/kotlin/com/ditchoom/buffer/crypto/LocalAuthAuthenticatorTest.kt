package com.ditchoom.buffer.crypto

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Runtime guard for the [LocalAuthAuthenticator] lifecycle contract — the half of the Apple
 * user-auth path that needs only LocalAuthentication (no entitled Secure Enclave), so it runs on
 * the macOS CI test binary rather than being device-gated like the Enclave sign path.
 *
 * Pins the fix for the "closed authenticator still reports available" defect: [close] must flip
 * [LocalAuthAuthenticator.available] to `false` so [userAuthenticated] refuses it and a session
 * sign fails cleanly with [AuthorizationFailed] instead of handing the shim a released LAContext
 * handle (which the sign path would otherwise misreport as [HardwareKeyException.KeyInvalidated]).
 */
class LocalAuthAuthenticatorTest {
    @Test
    fun closeFlipsAvailableToFalse() {
        val auth = LocalAuthAuthenticator(reason = "unit test")
        // Skip only where LocalAuthentication itself is absent (no context handle minted); on any
        // real macOS runner the context is created and this exercises the close transition.
        if (!auth.available) return
        assertTrue(auth.available, "a freshly constructed authenticator with LA present is available")
        auth.close()
        assertFalse(auth.available, "a closed authenticator must report unavailable")
    }

    @Test
    fun closeIsIdempotent() {
        val auth = LocalAuthAuthenticator(reason = "unit test")
        if (!auth.available) return
        auth.close()
        // A second close must not double-release the native handle or crash.
        auth.close()
        assertFalse(auth.available)
    }

    @Test
    fun userAuthenticatedRefusesAClosedAuthenticator() {
        val provider = platformHardwareKeyProvider() ?: return // no Enclave provider on this runner
        val auth = LocalAuthAuthenticator(reason = "unit test")
        if (!auth.available) return
        auth.close()
        // A closed prompt host cannot drive OS auth, so the witness-style extension must decline it.
        assertTrue(
            provider.userAuthenticated(auth) == null,
            "userAuthenticated must return null for a closed authenticator",
        )
    }
}
