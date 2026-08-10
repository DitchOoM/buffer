@file:Suppress("MatchingDeclarationName") // MPP platform-suffixed boundary file

package com.ditchoom.buffer.crypto

/**
 * Linux's [UserVerification] — always [UserVerificationAvailability.Unsupported].
 *
 * Linux has no OS-owned interactive verification facility a library can drive: what exists
 * (`fprintd`, `polkit`, a desktop session's own lock screen) belongs to a particular desktop stack,
 * cannot be probed for key-capable enrollment or lockout, and is absent entirely on the headless
 * server targets this platform mostly serves. Where a Linux deployment does authenticate, it does so
 * to a PKCS#11 token by configured PIN — process-to-token, not a human verified at the point of use
 * — which is reported by [ProtectedKeyResolution] / [CapabilityFinding], never here.
 */
fun userVerification(): UserVerification = UnsupportedUserVerification
