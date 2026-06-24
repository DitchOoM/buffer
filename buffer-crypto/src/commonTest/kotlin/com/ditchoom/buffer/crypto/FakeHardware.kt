package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.crypto.CryptoTestVectors.hexBuffer

/*
 * A test fake for [HardwareKeyProvider]. No platform ships a real secure-element backend in 6.0, so
 * this stands in to exercise the hardware-key *machinery*: the SPI, the internal Hardware* key impls,
 * the gated-op closures, the witness dispatch (async-only), and the [AuthorizationFailed] gate.
 *
 * It models a secure element with software crypto behind an auth gate:
 *  - The gated closures perform real AES-GCM / ECDSA via the module's own async primitives, so a
 *    successful op produces standard ciphertext / signatures (proving the seam wires through).
 *  - Each use is gated on [HardwareKeySpec.authorization]; a denying gate raises [AuthorizationFailed],
 *    exactly as a refused biometric / keystore unlock would.
 *  - Eligibility is a realistic subset: AES-GCM and ECDSA P-256 only. ChaCha20-Poly1305 and Ed25519
 *    are rejected (Enclave/StrongBox do not back them), matching the no-hardware-variant decision.
 *  - Keys are generated, never imported: AES material is fresh CSPRNG bytes; the ECDSA key uses a
 *    fixed NIST P-256 test keypair (buffer-crypto exposes no ECDSA keypair generation, so a known
 *    keypair is used so the test can build the matching public [VerifyKey] — verify keys are public).
 *
 * Because commonTest sees the module's `internal` declarations, it can construct [HardwareAesGcmKey]
 * / [HardwareSigningKey] directly — which is how a real provider in a later minor would build them.
 */
internal class FakeHardware(
    override val dedicatedSecureElement: Boolean = true,
) : HardwareKeyProvider {
    /** A hardware AES-GCM key plus the software twin (same material) the test opens with to cross-check. */
    class AesGcmPair(
        val hardware: AesGcmKey,
        val softwareTwin: AesGcmKey,
    )

    /** A hardware signing key plus the public [VerifyKey] the test verifies its signatures with. */
    class SigningPair(
        val hardware: SigningKey,
        val verifyKey: VerifyKey,
    )

    override fun eligible(alg: HardwareAlgorithm): Boolean = alg == HardwareAlgorithm.AesGcm || alg == HardwareAlgorithm.EcdsaP256

    override suspend fun generateAesGcm(spec: HardwareKeySpec): AesGcmKey = aesGcmPair(spec).hardware

    override suspend fun generateSigning(
        scheme: SignatureScheme,
        spec: HardwareKeySpec,
    ): SigningKey {
        require(eligible(scheme.toHardwareAlgorithm())) {
            "${scheme.schemeName} is not eligible for hardware backing"
        }
        return signingPair(spec).hardware
    }

    /** Richer result for the conformance test: the hardware key and a software twin with the same key. */
    fun aesGcmPair(spec: HardwareKeySpec): AesGcmPair {
        require(spec.aesKeySizeBits == AES_128_KEY_BYTES * Byte.SIZE_BITS || spec.aesKeySizeBits == AES_256_KEY_BYTES * Byte.SIZE_BITS) {
            "AES key size must be 128 or 256 bits, was ${spec.aesKeySizeBits}"
        }
        val material = cryptoRandom(spec.aesKeySizeBits / Byte.SIZE_BITS)
        // Two independent in-memory keys over the SAME bytes: one drives the gated closures (the
        // "secure element"), one is the software twin the test opens with. A real element exposes
        // neither — copying material out is exactly what a hardware key forbids; the fake peeks only
        // so the test can prove the hardware seal produced standard, openable AES-GCM.
        val inner = AesGcmKey.of(material)
        val twin = AesGcmKey.of(material)
        val auth = spec.authorization
        val hardware =
            HardwareAesGcmKey(
                sizeBits = spec.aesKeySizeBits,
                gatedSeal = { nonce, aad, plaintext, factory ->
                    if (!auth.authorize()) throw AuthorizationFailed()
                    aesGcmSealWithNonceAsync(inner, nonce, aad, plaintext, factory)
                },
                gatedOpen = { nonce, aad, ciphertextAndTag, factory ->
                    if (!auth.authorize()) throw AuthorizationFailed()
                    aesGcmOpenWithNonceAsync(inner, nonce, aad, ciphertextAndTag, factory)
                },
            )
        return AesGcmPair(hardware, twin)
    }

    /** Richer result for the conformance test: the hardware signing key and its public verify key. */
    fun signingPair(spec: HardwareKeySpec): SigningPair {
        val inner = SigningKey.ecdsaP256(hexBuffer(P256_SCALAR_HEX))
        val verifyKey = VerifyKey.ecdsaP256(hexBuffer(P256_POINT_HEX))
        val auth = spec.authorization
        val hardware =
            HardwareSigningKey(
                scheme = SignatureScheme.EcdsaP256,
                gatedSign = { message, factory ->
                    if (!auth.authorize()) throw AuthorizationFailed()
                    signAsyncPlatform(inner, message, factory)
                },
                verifyKey = verifyKey,
            )
        return SigningPair(hardware, verifyKey)
    }

    companion object {
        // A fixed NIST P-256 keypair (scalar `d`, uncompressed SEC1 point) reused from the signature
        // KAT suite; the fake signs from the scalar and the test verifies with the matching point.
        const val P256_SCALAR_HEX = "2021d664b9d4d3dee182cd2d00fae8814e1b9b5c7250813c1e1bd98c01862b87"
        const val P256_POINT_HEX =
            "040b06d55b786556284360597ea5543929c0fcd1148785094a7cd0d4888f40de8a" +
                "0beacfada1cbe2c031a3d578890ae80b7250144e30067ce85532028c2262bf7f"
    }
}
