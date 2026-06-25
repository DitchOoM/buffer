package com.ditchoom.buffer.crypto

import java.security.Signature

/**
 * On the JVM, Ed25519 is the `Ed25519` JCA algorithm added in JDK 15. Rather than parse the JDK
 * version string we probe the provider directly: if `Signature.getInstance("Ed25519")` resolves,
 * the algorithm is present (JDK 15+ with the SunEC provider, or any provider that supplies it).
 * Probed once and cached; a `false` here resolves the Ed25519 witness to
 * [SignatureSupport.Unavailable], so no Ed25519 op is reachable.
 */
internal actual val ed25519RuntimeSupported: Boolean by lazy {
    try {
        Signature.getInstance("Ed25519")
        true
    } catch (_: Throwable) {
        false
    }
}
