package com.ditchoom.buffer.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.KeyAgreement

/*
 * ECDH P-256 agreement helpers for [AndroidKeystoreHardwareKeyProvider] — the stateless keystore
 * spec/op/conversion pieces of the `PURPOSE_AGREE_KEY` path (API 31+, probed). Split from
 * HardwareKeys.android.kt, which retains the provider, the eligibility probes, and the shared
 * signing/AES plumbing.
 */

/**
 * An ECDH P-256 agreement-key spec, mirroring the signing `ecSpec`. Only reachable on API 31+ (the
 * probe fails fast pre-31, and every generation path is gated on that eligibility);
 * `PURPOSE_AGREE_KEY` is a compile-time constant, so referencing it carries no runtime API
 * requirement of its own.
 */
internal fun agreementSpec(
    alias: String,
    strongBox: Boolean,
    policy: ResolvedAndroidPolicy,
): KeyGenParameterSpec =
    KeyGenParameterSpec
        .Builder(alias, KeyProperties.PURPOSE_AGREE_KEY)
        .setAlgorithmParameterSpec(ECGenParameterSpec(SECP256R1))
        .setIsStrongBoxBacked(strongBox)
        .apply { applyUserAuth(policy) }
        .build()

/**
 * Runs the keystore ECDH: `DH(privateKey, jcaPeer)` inside the element, returning the raw 32-byte
 * shared secret in a wiped [SecureBuffer] (the common seam applies the KDF / validation above it).
 * A peer-point rejection from the provider — checked or unchecked — maps to the uniform
 * [InvalidPublicKey], never distinguishable by exception type (oracle avoidance, matching the
 * software JCA glue). `init` stays outside that mapping: a failure there is a *key* problem
 * (invalidated, unsupported), which `mappingKeystoreFailures` normalizes to a [HardwareKeyException].
 */
@Suppress("SwallowedException", "TooGenericExceptionCaught", "ThrowsCount")
internal fun agreeP256(
    privateKey: PrivateKey,
    jcaPeer: PublicKey,
): PlatformBuffer {
    val ka = KeyAgreement.getInstance(ECDH, ANDROID_KEY_STORE)
    ka.init(privateKey)
    val rawSecretBytes =
        try {
            ka.doPhase(jcaPeer, true)
            ka.generateSecret()
        } catch (e: GeneralSecurityException) {
            throw InvalidPublicKey(KeyAgreementCurve.P256)
        } catch (e: RuntimeException) {
            throw InvalidPublicKey(KeyAgreementCurve.P256)
        }
    if (rawSecretBytes.size != P256_FIELD_BYTES) {
        rawSecretBytes.fill(0)
        throw HardwareKeyException.UnsupportedHardwareKey()
    }
    val raw = secureScratch.allocate(P256_FIELD_BYTES)
    raw.writeBytes(rawSecretBytes)
    rawSecretBytes.fill(0)
    raw.resetForRead()
    return raw
}

/**
 * Converts a peer's uncompressed SEC1 point (`04 ‖ X ‖ Y`, the pinned [KeyAgreementCurve] encoding)
 * to a JCA [PublicKey]. Malformed encodings and provider rejections surface as the uniform
 * [InvalidPublicKey] — same no-oracle contract as the software glue.
 */
@Suppress("SwallowedException", "TooGenericExceptionCaught", "ThrowsCount")
internal fun p256PublicKey(encoded: ReadBuffer): PublicKey {
    val curve = KeyAgreementCurve.P256
    val view = encoded.slice()
    val n = view.remaining()
    if (n != curve.publicKeyBytes || (view.get(view.position()).toInt() and UBYTE_MASK) != SEC1_UNCOMPRESSED) {
        throw InvalidPublicKey(curve)
    }
    // ByteArray at the unavoidable JCA seam (BigInteger/ECPoint), never a library data structure.
    val point = ByteArray(n)
    for (i in 0 until n) point[i] = view.get(view.position() + i)
    return try {
        val x = BigInteger(1, point.copyOfRange(1, 1 + P256_FIELD_BYTES))
        val y = BigInteger(1, point.copyOfRange(1 + P256_FIELD_BYTES, n))
        val params =
            AlgorithmParameters
                .getInstance("EC")
                .apply { init(ECGenParameterSpec(SECP256R1)) }
                .getParameterSpec(ECParameterSpec::class.java)
        KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), params))
    } catch (e: GeneralSecurityException) {
        throw InvalidPublicKey(curve)
    } catch (e: RuntimeException) {
        throw InvalidPublicKey(curve)
    }
}

/**
 * Distinguishes the two `PrivateKeyEntry` kinds a store can hold — ECDSA signing vs ECDH agreement —
 * by the keystore-recorded purposes ([KeyInfo.getPurposes], intrinsic to the entry, so no sidecar).
 * Callers already hold the keystore lock (`entryAlgorithm` / the reattach paths run this inside
 * `keystoreOp`).
 */
internal fun privateEntryAlgorithm(privateKey: PrivateKey): ProtectedKeyAlgorithm {
    val info =
        KeyFactory
            .getInstance(privateKey.algorithm, ANDROID_KEY_STORE)
            .getKeySpec(privateKey, KeyInfo::class.java) as KeyInfo
    return if (info.purposes and KeyProperties.PURPOSE_AGREE_KEY != 0) {
        ProtectedKeyAlgorithm.EcdhP256
    } else {
        ProtectedKeyAlgorithm.EcdsaP256
    }
}

internal const val ECDH = "ECDH"
private const val UBYTE_MASK = 0xFF

// File-private copies: same-named private constants exist in other files of this package, so these
// cannot be shared as internal without a redeclaration conflict.
private const val P256_FIELD_BYTES = 32
private const val SEC1_UNCOMPRESSED = 0x04
