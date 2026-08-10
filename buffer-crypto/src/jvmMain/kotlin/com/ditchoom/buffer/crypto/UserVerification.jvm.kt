@file:Suppress("MatchingDeclarationName") // MPP platform-suffixed boundary file

package com.ditchoom.buffer.crypto

/**
 * The desktop JVM's [UserVerification] — always
 * [UserVerificationAvailability.Unsupported].
 *
 * A JVM process has no OS facility for verifying a *human at the point of use*: there is no
 * biometric prompt a plain `java` launcher may raise, and no way to probe enrollment or lockout.
 * The authentication that does exist on this platform is the PKCS#11 token PIN
 * (`C_Login`, configured through `BUFFER_CRYPTO_PKCS11_PIN`) — real authentication, but
 * process-to-token and fixed by deployment configuration rather than interactive; it is reported by
 * [ProtectedKeyResolution] / [CapabilityFinding], never here. `Unsupported` therefore says nothing
 * about this host's key custody: a JVM holding TPM-backed, non-exportable keys still reports it.
 */
fun userVerification(): UserVerification = UnsupportedUserVerification
