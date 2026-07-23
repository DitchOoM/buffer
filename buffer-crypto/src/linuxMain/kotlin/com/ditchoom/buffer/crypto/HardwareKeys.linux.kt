@file:OptIn(ExperimentalForeignApi::class)
@file:Suppress("MatchingDeclarationName") // MPP platform-suffixed actual file

package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.crypto.cinterop.boringssl.BCL_OK
import com.ditchoom.buffer.crypto.cinterop.boringssl.bcl_ec_generate
import com.ditchoom.buffer.crypto.cinterop.boringssl.bcl_ecdh
import com.ditchoom.buffer.crypto.cinterop.boringssl.bcl_ecdsa_verify
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import platform.posix.R_OK
import platform.posix.access
import platform.posix.getenv
import platform.posix.size_tVar

/*
 * Kotlin/Native Linux non-exportable key backend: a TPM 2.0 reached through the standard
 * `tpm2-pkcs11` module, dlopen'd at runtime and driven directly over the PKCS#11 C API
 * (see Pkcs11Token.linux.kt) - the native sibling of the JVM SunPKCS11 backend, sharing its typed
 * findings, its probe-not-infer resolution discipline, and its custody posture.
 *
 * **Resolution is probed end-to-end, never inferred**: the module must dlopen, the token must accept
 * a login, and a full generate -> sign-on-token -> BoringSSL-software-verify round-trip must succeed
 * before [ProtectedKeyResolution.Available] is reported. Every refusal is a typed
 * [CapabilityFinding.Tpm2]; the native bridge sees each `CKR_*` code numerically, so classification
 * needs no message parsing.
 *
 * **Configuration** (environment only - Kotlin/Native has no system properties):
 *  - `BUFFER_CRYPTO_TPM2_PKCS11_MODULE` - module path; otherwise well-known distro locations.
 *  - `BUFFER_CRYPTO_TPM2_PKCS11_PIN` - token user PIN (required; absent is a typed refusal).
 *  - `BUFFER_CRYPTO_TPM2_PKCS11_SLOT_INDEX` - index into the present-token slot list (default 0).
 *
 * **Eligibility mirrors what the stack actually backs.** ECDSA P-256 is the proven baseline (the
 * resolution probe). ECDH P-256 is probed lazily end-to-end against a BoringSSL software peer - as
 * of tpm2-pkcs11 1.9 `CKM_ECDH1_DERIVE` is absent and the probe honestly fails; a derive-capable
 * module (verified against SoftHSM) lights it up with no library change. AES-GCM is not offered.
 * Unlike the SunPKCS11 bridge, key templates are per-call here, so signing keys carry `CKA_SIGN`
 * only and agreement keys `CKA_DERIVE` only - object-level key separation without a second
 * provider configuration.
 *
 * **Custody honesty / trust boundary:** identical to the JVM backend - `dedicatedSecureElement` is
 * `false` (PKCS#11 cannot distinguish discrete from firmware TPMs), and the hardware-custody claim
 * is exactly as trustworthy as the configured module path/PIN, which deployments MUST treat as
 * same-integrity-domain state.
 */
internal class Tpm2Pkcs11NativeKeyProvider(
    private val token: Pkcs11Token,
) : HardwareKeyProvider {
    /** PKCS#11 cannot confirm a discrete element, so this never over-claims. See the file KDoc. */
    override val dedicatedSecureElement: Boolean get() = false

    /**
     * Serializes suspend token ops off the calling thread. The lazy [agreementSupported] probe is
     * the one exception ([eligible] is non-suspend); safe because `C_Initialize` requested
     * `CKF_OS_LOCKING_OK`, so the module itself locks at the PKCS#11 boundary.
     */
    private val tokenLock = Mutex()

    /**
     * `true` once a full token ECDH against a BoringSSL software peer matches the software-side
     * derivation in both directions. tpm2-pkcs11 1.9 lacks `CKM_ECDH1_DERIVE`, so this honestly
     * fails there; a derive-capable module (verified against SoftHSM) passes with no library change.
     */
    private val agreementSupported: Boolean by lazy { probeNativeAgreement(token) }

    override fun eligible(alg: ProtectedKeyAlgorithm): Boolean =
        when (alg) {
            ProtectedKeyAlgorithm.EcdsaP256 -> true
            ProtectedKeyAlgorithm.EcdhP256 -> agreementSupported
            else -> false
        }

    override suspend fun generateSigning(
        scheme: SignatureScheme,
        spec: ProtectedKeySpec,
    ): SigningKey {
        if (!eligible(scheme.toProtectedKeyAlgorithm())) throw HardwareKeyException.AlgorithmNotEligible()
        val (pair, point) =
            tokenOp {
                val kp = token.generateP256KeyPair(signing = true)
                kp to token.ecPoint(kp.publicHandle)
            }
        val verifyKey = VerifyKey.ecdsaP256(point)
        val gate = spec.authorization
        return ProtectedSigningKey(
            scheme = SignatureScheme.EcdsaP256,
            custody = custody,
            gatedSign = { message, factory ->
                if (!gate.authorize()) throw AuthorizationFailed()
                tokenOp {
                    // CKM_ECDSA signs a caller-supplied digest; hash here, then DER-wrap the raw r||s.
                    val digest = sha256(message.slice())
                    rawEcdsaToDer(token.signDigest(pair.privateHandle, digest), factory)
                }
            },
            verifyKey = verifyKey,
            // Session objects die with the process anyway; destroying early frees token memory.
            onClose = {
                token.destroy(pair.privateHandle)
                token.destroy(pair.publicHandle)
            },
        )
    }

    override suspend fun generateAesGcm(spec: ProtectedKeySpec): AesGcmKey =
        // The tpm2-pkcs11 stack does not usably back AES-GCM; see eligible().
        throw HardwareKeyException.AlgorithmNotEligible()

    // A denied gate / curve clash / peer rejection are distinct typed outcomes; the swallowed
    // UnsupportedHardwareKey is deliberately replaced by the uniform InvalidPublicKey (no oracle).
    @Suppress("ThrowsCount", "SwallowedException")
    override suspend fun generateKeyAgreement(
        curve: KeyAgreementCurve,
        spec: ProtectedKeySpec,
    ): KeyAgreementKeyPair {
        // Only ECDH P-256, and only where the end-to-end probe holds (see agreementSupported).
        if (curve != KeyAgreementCurve.P256 || !eligible(ProtectedKeyAlgorithm.EcdhP256)) {
            throw HardwareKeyException.AlgorithmNotEligible()
        }
        val (pair, point) =
            tokenOp {
                val kp = token.generateP256KeyPair(signing = false)
                kp to token.ecPoint(kp.publicHandle)
            }
        val publicKey = KeyAgreementPublicKey.of(curve, point)
        val gate = spec.authorization
        val privateKey =
            ProtectedKeyAgreementPrivateKey(
                curve = curve,
                custody = custody,
                gatedDh = { peer ->
                    if (!gate.authorize()) throw AuthorizationFailed()
                    require(peer.curve == curve) { "private/public key curve mismatch" }
                    val peerPoint = structurallyValidPoint(peer.encoded)
                    try {
                        tokenOp { token.deriveEcdh(pair.privateHandle, peerPoint) }
                    } catch (e: HardwareKeyException.UnsupportedHardwareKey) {
                        // Uniform peer-attributable rejection - no oracle, matching the JVM backend.
                        throw InvalidPublicKey(curve)
                    }
                },
                onClose = {
                    token.destroy(pair.privateHandle)
                    token.destroy(pair.publicHandle)
                },
            )
        return keyAgreementKeyPairOf(curve, privateKey, publicKey)
    }

    /**
     * Runs a token [block] serialized and off the calling thread, normalizing [Pkcs11Failure] to a
     * [HardwareKeyException]. A [CryptoException] thrown inside passes through untouched.
     */
    @Suppress("SwallowedException")
    private suspend fun <T> tokenOp(block: () -> T): T =
        tokenLock.withLock {
            withContext(Dispatchers.Default) {
                try {
                    block()
                } catch (e: CryptoException) {
                    throw e
                } catch (e: Pkcs11Failure) {
                    throw HardwareKeyException.UnsupportedHardwareKey()
                }
            }
        }
}

// =============================================================================
// Resolution - every step's refusal is a distinct typed finding, never a bare null
// =============================================================================

private val linuxResolution: ProtectedKeyResolution by lazy { resolveNativeTpm2Pkcs11() }

internal actual fun platformProtectedKeyResolution(): ProtectedKeyResolution = linuxResolution

@Suppress("ReturnCount", "SwallowedException", "TooGenericExceptionCaught") // typed refusals; probe fails closed
private fun resolveNativeTpm2Pkcs11(): ProtectedKeyResolution {
    val modulePath =
        env(MODULE_ENV) ?: WELL_KNOWN_MODULE_PATHS.firstOrNull { access(it, R_OK) == 0 }
            ?: return refused(CapabilityFinding.Tpm2.ModuleNotFound)
    val pin = env(PIN_ENV) ?: return refused(CapabilityFinding.Tpm2.AuthNotConfigured)
    val slotIndex = env(SLOT_ENV)?.toIntOrNull() ?: 0

    val token =
        when (val opened = Pkcs11Token.open(modulePath, pin, slotIndex)) {
            is Pkcs11OpenResult.Opened -> opened.token
            is Pkcs11OpenResult.ModuleUnloadable -> return refused(CapabilityFinding.Tpm2.TokenRejectedOpaque)
            is Pkcs11OpenResult.Rejected ->
                return refused(CapabilityFinding.Tpm2.TokenRejected(opened.ckr.classify()))
        }

    // End-to-end probe: generate -> sign on token -> verify in software (BoringSSL). Only a full
    // round-trip upgrades the resolution to Available; anything less would over-promise.
    return try {
        val pair = token.generateP256KeyPair(signing = true)
        try {
            val point = token.ecPoint(pair.publicHandle)
            val message = BufferFactory.Default.wrap(PROBE_MESSAGE.encodeToByteArray())
            val digest = sha256(message.slice())
            val der = rawEcdsaToDer(token.signDigest(pair.privateHandle, digest), BufferFactory.Default)
            if (softwareVerifiesP256(point, message, der)) {
                ProtectedKeyResolution.Available(
                    ProtectedKeyBackend.Tpm2Pkcs11,
                    Tpm2Pkcs11NativeKeyProvider(token),
                )
            } else {
                refused(CapabilityFinding.Tpm2.ProbeOpFailedOpaque(ProtectedKeyAlgorithm.EcdsaP256))
            }
        } finally {
            token.destroy(pair.privateHandle)
            token.destroy(pair.publicHandle)
        }
    } catch (e: Pkcs11Failure) {
        refused(CapabilityFinding.Tpm2.ProbeOpFailed(ProtectedKeyAlgorithm.EcdsaP256, e.ckr.classify()))
    } catch (e: Throwable) {
        refused(CapabilityFinding.Tpm2.ProbeOpFailedOpaque(ProtectedKeyAlgorithm.EcdsaP256))
    }
}

private fun refused(finding: CapabilityFinding.Tpm2): ProtectedKeyResolution =
    ProtectedKeyResolution.Refused(ProtectedKeyBackend.Tpm2Pkcs11, finding)

/**
 * Full agreement probe against a BoringSSL software peer, both directions matching - the same
 * evidence bar as the JVM backend's probe. Any failure (mechanism absent, template refused, secret
 * mismatch) answers `false`: agreement routes to the software floor instead of over-promising.
 */
@Suppress("SwallowedException", "TooGenericExceptionCaught")
private fun probeNativeAgreement(token: Pkcs11Token): Boolean =
    try {
        val pair = token.generateP256KeyPair(signing = false)
        try {
            val tokenPoint = token.ecPoint(pair.publicHandle)
            probeAgainstSoftwarePeer(token, pair, tokenPoint)
        } finally {
            token.destroy(pair.privateHandle)
            token.destroy(pair.publicHandle)
        }
    } catch (e: Throwable) {
        false
    }

private fun probeAgainstSoftwarePeer(
    token: Pkcs11Token,
    pair: Pkcs11KeyPair,
    tokenPoint: ReadBuffer,
): Boolean =
    memScoped {
        // Software peer via BoringSSL: raw scalar + uncompressed point.
        val priv = allocArray<ByteVar>(P256_SCALAR_CAP)
        val privLen = alloc<size_tVar>()
        val pub = allocArray<ByteVar>(P256_POINT_CAP)
        val pubLen = alloc<size_tVar>()
        val generated =
            bcl_ec_generate(
                P256_CURVE_CODE,
                priv.reinterpret(),
                P256_SCALAR_CAP.convert(),
                privLen.ptr,
                pub.reinterpret(),
                P256_POINT_CAP.convert(),
                pubLen.ptr,
            )
        if (generated != BCL_OK) return@memScoped false

        val peerPoint = BufferFactory.Default.allocate(pubLen.value.toInt())
        for (i in 0 until pubLen.value.toInt()) peerPoint.writeByte(pub[i])
        peerPoint.resetForRead()

        // Token side: DH(tokenPriv, softwarePub).
        val tokenSecret = token.deriveEcdh(pair.privateHandle, peerPoint)

        // Software side: DH(softwarePriv, tokenPub) via BoringSSL.
        val softwareSecret = allocArray<ByteVar>(P256_SCALAR_CAP)
        val softwareSecretLen = alloc<size_tVar>()
        val tokenPointLen = tokenPoint.remaining()
        var agreed = -1
        tokenPoint.withRemainingBytes2(tokenPointLen) { pointPtr ->
            agreed =
                bcl_ecdh(
                    P256_CURVE_CODE,
                    priv.reinterpret(),
                    privLen.value,
                    pointPtr.reinterpret(),
                    tokenPointLen.convert(),
                    softwareSecret.reinterpret(),
                    P256_SCALAR_CAP.convert(),
                    softwareSecretLen.ptr,
                )
        }
        for (i in 0 until P256_SCALAR_CAP) priv[i] = 0.toByte()

        val match =
            agreed == BCL_OK &&
                softwareSecretLen.value.toInt() == tokenSecret.remaining() &&
                (0 until tokenSecret.remaining()).all {
                    tokenSecret.get(tokenSecret.position() + it) == softwareSecret[it]
                }
        for (i in 0 until P256_SCALAR_CAP) softwareSecret[i] = 0.toByte()
        tokenSecret.freeNativeMemory()
        match
    }

/** Verifies a DER [signature] over [message] under the uncompressed [point] via BoringSSL. */
private fun softwareVerifiesP256(
    point: ReadBuffer,
    message: ReadBuffer,
    signature: ReadBuffer,
): Boolean {
    val pointLen = point.remaining()
    val messageLen = message.remaining()
    val signatureLen = signature.remaining()
    var status = -1
    point.withRemainingBytes2(pointLen) { pointPtr ->
        message.withRemainingBytes2(messageLen) { msgPtr ->
            signature.withRemainingBytes2(signatureLen) { sigPtr ->
                status =
                    bcl_ecdsa_verify(
                        P256_CURVE_CODE,
                        pointPtr.reinterpret(),
                        pointLen.convert(),
                        msgPtr.reinterpret(),
                        messageLen.convert(),
                        sigPtr.reinterpret(),
                        signatureLen.convert(),
                    )
            }
        }
    }
    return status == BCL_OK
}

/**
 * Wraps a raw `r || s` token signature (64 bytes) as a canonical DER `ECDSA-Sig-Value` - the
 * cross-platform wire format every backend emits. Leading zeros are stripped to the minimal
 * magnitude; a sign-padding octet is added when the leading bit is set.
 */
internal fun rawEcdsaToDer(
    raw: ReadBuffer,
    factory: BufferFactory,
): PlatformBuffer {
    if (raw.remaining() != 2 * P256_FIELD_BYTES) throw HardwareKeyException.UnsupportedHardwareKey()
    val base = raw.position()

    fun magnitudeStart(offset: Int): Int {
        var i = 0
        while (i < P256_FIELD_BYTES - 1 && raw.get(base + offset + i).toInt() == 0) i++
        return i
    }

    fun encodedLength(offset: Int): Int {
        val start = magnitudeStart(offset)
        val pad = if (raw.get(base + offset + start).toInt() and BYTE_MASK >= SIGN_BIT) 1 else 0
        return P256_FIELD_BYTES - start + pad
    }

    val rLen = encodedLength(0)
    val sLen = encodedLength(P256_FIELD_BYTES)
    val contentLength = 2 + rLen + 2 + sLen
    val out = factory.allocate(2 + contentLength)
    out.writeByte(DER_SEQUENCE_TAG.toByte())
    out.writeByte(contentLength.toByte())

    fun writeInteger(
        offset: Int,
        encoded: Int,
    ) {
        out.writeByte(DER_INTEGER_TAG.toByte())
        out.writeByte(encoded.toByte())
        var remaining = encoded
        if (encoded > P256_FIELD_BYTES - magnitudeStart(offset)) {
            out.writeByte(0)
            remaining--
        }
        for (i in P256_FIELD_BYTES - remaining until P256_FIELD_BYTES) {
            out.writeByte(raw.get(base + offset + i))
        }
    }
    writeInteger(0, rLen)
    writeInteger(P256_FIELD_BYTES, sLen)
    out.resetForRead()
    return out
}

/** Structural SEC1 check (65 bytes, `04` lead) before the token sees the point; on-curve validation
 *  happens inside the module/TPM (mandated by TPM 2.0 `ECDH_ZGen`), and any refusal maps uniformly
 *  to [InvalidPublicKey]. */
private fun structurallyValidPoint(encoded: ReadBuffer): ReadBuffer {
    val view = encoded.slice()
    if (view.remaining() != P256_POINT_BYTES ||
        (view.get(view.position()).toInt() and BYTE_MASK) != SEC1_UNCOMPRESSED
    ) {
        throw InvalidPublicKey(KeyAgreementCurve.P256)
    }
    return view
}

private fun env(name: String): String? = getenv(name)?.toKString()?.takeIf { it.isNotBlank() }

private const val MODULE_ENV = "BUFFER_CRYPTO_TPM2_PKCS11_MODULE"
private const val PIN_ENV = "BUFFER_CRYPTO_TPM2_PKCS11_PIN"
private const val SLOT_ENV = "BUFFER_CRYPTO_TPM2_PKCS11_SLOT_INDEX"

private val WELL_KNOWN_MODULE_PATHS =
    listOf(
        "/usr/lib/x86_64-linux-gnu/libtpm2_pkcs11.so.1",
        "/usr/lib/x86_64-linux-gnu/libtpm2_pkcs11.so",
        "/usr/lib/aarch64-linux-gnu/libtpm2_pkcs11.so.1",
        "/usr/lib64/libtpm2_pkcs11.so.1",
        "/usr/lib/libtpm2_pkcs11.so.1",
        "/usr/local/lib/libtpm2_pkcs11.so.1",
    )

private const val PROBE_MESSAGE = "buffer-crypto tpm2-pkcs11 native resolution probe"
private const val P256_CURVE_CODE = 256
private const val P256_FIELD_BYTES = 32
private const val P256_POINT_BYTES = 65
private const val P256_SCALAR_CAP = 72
private const val P256_POINT_CAP = 133
private const val SEC1_UNCOMPRESSED = 0x04
private const val BYTE_MASK = 0xFF
private const val SIGN_BIT = 0x80
private const val DER_SEQUENCE_TAG = 0x30
private const val DER_INTEGER_TAG = 0x02
