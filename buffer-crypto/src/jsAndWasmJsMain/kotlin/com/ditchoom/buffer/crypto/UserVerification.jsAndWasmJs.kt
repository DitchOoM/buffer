@file:Suppress("MatchingDeclarationName") // MPP platform-suffixed boundary file

package com.ditchoom.buffer.crypto

/**
 * The browser / WASM engine [UserVerification] — always
 * [UserVerificationAvailability.Unsupported].
 *
 * The web platform deliberately exposes no biometric *status*: a page cannot ask whether a
 * fingerprint is enrolled, whether the user revoked a sensor, or whether biometry is locked out,
 * because that is fingerprintable device state. User verification on the web only happens *inside* a
 * WebAuthn ceremony, which is a prompt and not a probe — so none of [BiometricAvailability]'s states
 * can be answered honestly here, and reporting `Unsupported` is truthful where guessing would not
 * be. Key custody is unaffected: WebCrypto keys with `extractable:false` remain
 * [KeyCustody.NonExportable.Software], reported through [ProtectedKeyResolution].
 */
fun userVerification(): UserVerification = UnsupportedUserVerification
