@file:Suppress("MatchingDeclarationName") // MPP platform-suffixed actual file

package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.PlatformBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.Provider
import java.security.ProviderException
import java.security.Security
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec

/*
 * Desktop-JVM non-exportable key backend: a TPM 2.0 reached through the standard `tpm2-pkcs11`
 * module via the JCA `SunPKCS11` provider.
 *
 * Keys are *generated inside* the TPM-backed PKCS#11 token and never leave it: the gated closures
 * drive a JCA `Signature` / `KeyAgreement` over the token key handle, so the private scalar is never
 * in process memory. This is the same shape as the Android Keystore backend - advisory-gated ops,
 * serialized off the calling thread, failures normalized to [HardwareKeyException] - with PKCS#11 in
 * place of the keystore.
 *
 * **Resolution is probed end-to-end, never inferred** (see [platformProtectedKeyResolution]): the
 * module must exist, the token must accept a login, and a full generate -> sign -> software-verify
 * round-trip must succeed before [ProtectedKeyResolution.Available] is reported. Every refusal is a
 * typed [CapabilityFinding.Tpm2] - module missing vs. PIN unconfigured vs. token rejection are
 * distinct states, not one `null`.
 *
 * **Configuration** (system property, falling back to the environment variable):
 *  - `buffer.crypto.tpm2.pkcs11.module` / `BUFFER_CRYPTO_TPM2_PKCS11_MODULE` - module path;
 *    otherwise the well-known distro locations are tried.
 *  - `buffer.crypto.tpm2.pkcs11.pin` / `BUFFER_CRYPTO_TPM2_PKCS11_PIN` - the token user PIN
 *    (required; without it the resolution is [CapabilityFinding.Tpm2.AuthNotConfigured]).
 *  - `buffer.crypto.tpm2.pkcs11.slotIndex` / `BUFFER_CRYPTO_TPM2_PKCS11_SLOT_INDEX` - the
 *    `slotListIndex` handed to SunPKCS11 (default 0, the first initialized token).
 *
 * **Eligibility mirrors what the stack actually backs.** ECDSA P-256 is the proven baseline (it is
 * the resolution probe). ECDH P-256 is probed lazily end-to-end - as of tpm2-pkcs11 1.9 the module
 * does not advertise `CKM_ECDH1_DERIVE`, so the probe honestly fails and agreement routes to the
 * software floor; when the module gains the mechanism, eligibility lights up with no library change.
 * AES-GCM is not offered (the SunPKCS11 + tpm2-pkcs11 combination does not usably back it).
 *
 * **Custody honesty:** the provider reports `dedicatedSecureElement = false`. PKCS#11 exposes no
 * reliable way to distinguish a discrete TPM from a firmware TPM, so the claim stays at
 * "hardware-isolated, non-exportable" - the same conservative posture as a TEE-only Android device.
 *
 * `ByteArray` appears at the unavoidable JCA seam (X509EncodedKeySpec / generateSecret / BigInteger),
 * never as a library data structure; secret-bearing arrays are wiped after use.
 */
internal class Tpm2Pkcs11HardwareKeyProvider(
    private val p11: Provider,
) : HardwareKeyProvider {
    /** PKCS#11 cannot confirm a discrete element, so this never over-claims. See the file KDoc. */
    override val dedicatedSecureElement: Boolean get() = false

    /** Serializes token ops (TPM round-trips are slow and strictly ordered) off the calling thread. */
    private val tokenLock = Mutex()

    /**
     * `true` once a full token ECDH against a software peer matches the software-side derivation.
     * Lazily probed: tpm2-pkcs11 1.9 lacks `CKM_ECDH1_DERIVE` (SunPKCS11 then registers no `ECDH`
     * service, and the probe fails on `NoSuchAlgorithmException`), but a module that gains the
     * mechanism passes with no library change.
     */
    private val agreementSupported: Boolean by lazy { probeAgreement(p11) }

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
        val keyPair = tokenOp { generateP256KeyPair(p11) }
        val verifyKey = VerifyKey.ecdsaP256(uncompressedP256Point(keyPair.public as ECPublicKey))
        val gate = spec.authorization
        return ProtectedSigningKey(
            scheme = SignatureScheme.EcdsaP256,
            custody = custody,
            gatedSign = { message, factory ->
                if (!gate.authorize()) throw AuthorizationFailed()
                tokenOp {
                    val signer = Signature.getInstance(ECDSA_SHA256, p11).apply { initSign(keyPair.private) }
                    signer.update(message.slice().remainingBytes())
                    val der = signer.sign()
                    val out: PlatformBuffer = factory.allocate(der.size)
                    out.writeBytes(der)
                    out.resetForRead()
                    out
                }
            },
            verifyKey = verifyKey,
            // Session objects: the token drops the key with the process; nothing to delete here.
            onClose = {},
        )
    }

    override suspend fun generateAesGcm(spec: ProtectedKeySpec): AesGcmKey =
        // The SunPKCS11 + tpm2-pkcs11 combination does not usably back AES-GCM; see eligible().
        throw HardwareKeyException.AlgorithmNotEligible()

    override suspend fun generateKeyAgreement(
        curve: KeyAgreementCurve,
        spec: ProtectedKeySpec,
    ): KeyAgreementKeyPair {
        // Only ECDH P-256, and only where the end-to-end probe holds (see agreementSupported).
        if (curve != KeyAgreementCurve.P256 || !eligible(ProtectedKeyAlgorithm.EcdhP256)) {
            throw HardwareKeyException.AlgorithmNotEligible()
        }
        val keyPair = tokenOp { generateP256KeyPair(p11) }
        val publicKey = KeyAgreementPublicKey.of(curve, uncompressedP256Point(keyPair.public as ECPublicKey))
        val gate = spec.authorization
        val privateKey =
            ProtectedKeyAgreementPrivateKey(
                curve = curve,
                custody = custody,
                gatedDh = { peer ->
                    if (!gate.authorize()) throw AuthorizationFailed()
                    require(peer.curve == curve) { "private/public key curve mismatch" }
                    // Peer-point conversion happens outside the token lock; a malformed or
                    // off-curve point is rejected before the TPM is ever touched.
                    val jcaPeer = p256PublicKeyFromSec1(peer.encoded)
                    tokenOp { agreeP256OnToken(p11, keyPair.private, jcaPeer) }
                },
                onClose = {},
            )
        return keyAgreementKeyPairOf(curve, privateKey, publicKey)
    }

    /**
     * Runs a token [block] serialized and on [Dispatchers.IO] (PKCS#11 calls block), normalizing any
     * provider-originated failure to a [HardwareKeyException]. A [CryptoException] thrown inside
     * (e.g. [AuthorizationFailed], [InvalidPublicKey]) passes through untouched.
     */
    @Suppress("SwallowedException")
    private suspend fun <T> tokenOp(block: () -> T): T =
        tokenLock.withLock {
            withContext(Dispatchers.IO) {
                try {
                    block()
                } catch (e: CryptoException) {
                    throw e
                } catch (e: GeneralSecurityException) {
                    throw HardwareKeyException.UnsupportedHardwareKey()
                } catch (e: ProviderException) {
                    throw HardwareKeyException.UnsupportedHardwareKey()
                }
            }
        }
}

// =============================================================================
// Resolution - every step's refusal is a distinct typed finding, never a bare null
// =============================================================================

private val jvmResolution: ProtectedKeyResolution by lazy { resolveTpm2Pkcs11() }

internal actual fun platformProtectedKeyResolution(): ProtectedKeyResolution = jvmResolution

@Suppress("SwallowedException", "TooGenericExceptionCaught", "ReturnCount")
private fun resolveTpm2Pkcs11(): ProtectedKeyResolution {
    val explicitModule = configured(MODULE_PROP, MODULE_ENV)
    val os = System.getProperty("os.name")?.lowercase() ?: ""
    // Without an explicit module, only Linux is worth probing (the module is a Linux stack); a
    // macOS/Windows JVM wires no backend in this version - None, a build fact, not a device refusal.
    if (explicitModule == null && !os.contains("linux")) return ProtectedKeyResolution.None

    val modulePath =
        explicitModule ?: WELL_KNOWN_MODULE_PATHS.firstOrNull { java.io.File(it).exists() }
            ?: return refused(CapabilityFinding.Tpm2.ModuleNotFound)
    if (!java.io.File(modulePath).exists()) return refused(CapabilityFinding.Tpm2.ModuleNotFound)

    val pin = configured(PIN_PROP, PIN_ENV) ?: return refused(CapabilityFinding.Tpm2.AuthNotConfigured)
    val slotIndex = configured(SLOT_PROP, SLOT_ENV)?.toIntOrNull() ?: 0

    // Configure SunPKCS11 + login. Provider.configure is JVM 9+; a JVM 8 runtime is a typed refusal.
    val p11 =
        try {
            val base =
                Security.getProvider(SUN_PKCS11)
                    ?: return refused(CapabilityFinding.Tpm2.TokenRejectedOpaque)
            val conf = "--name=$PROVIDER_NAME\nlibrary=$modulePath\nslotListIndex=$slotIndex\n"
            val configured = base.configure(conf)
            val keyStore = java.security.KeyStore.getInstance(PKCS11_KEYSTORE, configured)
            val pinChars = pin.toCharArray()
            try {
                keyStore.load(null, pinChars)
            } finally {
                pinChars.fill(' ')
            }
            configured
        } catch (e: NoSuchMethodError) {
            return refused(CapabilityFinding.Tpm2.RuntimeUnsupported)
        } catch (e: Throwable) {
            return refused(tokenRejection(e))
        }

    // End-to-end probe: generate -> sign on token -> verify in software. Only a full round-trip
    // upgrades the resolution to Available; anything less would over-promise.
    return try {
        val keyPair = generateP256KeyPair(p11)
        val message = PROBE_MESSAGE.encodeToByteArray()
        val signer = Signature.getInstance(ECDSA_SHA256, p11).apply { initSign(keyPair.private) }
        signer.update(message)
        val der = signer.sign()
        val softPub =
            KeyFactory.getInstance(EC).generatePublic(X509EncodedKeySpec(keyPair.public.encoded))
        val verifier = Signature.getInstance(ECDSA_SHA256).apply { initVerify(softPub) }
        verifier.update(message)
        if (verifier.verify(der)) {
            ProtectedKeyResolution.Available(ProtectedKeyBackend.Tpm2Pkcs11, Tpm2Pkcs11HardwareKeyProvider(p11))
        } else {
            refused(probeFailure(ProtectedKeyAlgorithm.EcdsaP256, cause = null))
        }
    } catch (e: Throwable) {
        refused(probeFailure(ProtectedKeyAlgorithm.EcdsaP256, cause = e))
    }
}

private fun refused(finding: CapabilityFinding.Tpm2): ProtectedKeyResolution =
    ProtectedKeyResolution.Refused(ProtectedKeyBackend.Tpm2Pkcs11, finding)

/** A configure/login failure as a typed finding - classified when a CKR is recoverable, opaque otherwise. */
private fun tokenRejection(failure: Throwable): CapabilityFinding.Tpm2 =
    ckrOf(failure)?.let { CapabilityFinding.Tpm2.TokenRejected(it.classify()) }
        ?: CapabilityFinding.Tpm2.TokenRejectedOpaque

/** A probe-op failure as a typed finding - classified when a CKR is recoverable, opaque otherwise. */
private fun probeFailure(
    alg: ProtectedKeyAlgorithm,
    cause: Throwable?,
): CapabilityFinding.Tpm2 =
    cause?.let { ckrOf(it) }?.let { CapabilityFinding.Tpm2.ProbeOpFailed(alg, it.classify()) }
        ?: CapabilityFinding.Tpm2.ProbeOpFailedOpaque(alg)

/**
 * Recovers the PKCS#11 `CKR_*` code from a SunPKCS11 failure as a typed [Ckr], or `null` when none
 * was surfaced (the `*Opaque` findings make that absence a state, not a nullable field downstream).
 *
 * Bridge note: `PKCS11Exception.getErrorCode()` lives in the non-exported `sun.security.pkcs11`
 * package, unreachable under JPMS without `--add-opens`. The exception's *message* carries the code
 * (`"CKR_PIN_INCORRECT"` for named codes, `"0x..."` for unnamed ones), so this walks the cause chain
 * and converts that one JCA artifact into the typed value at the boundary - the string never leaves
 * this function, and consumers only ever see [Ckr]/[CkrClass].
 */
private fun ckrOf(failure: Throwable): Ckr? {
    var cur: Throwable? = failure
    while (cur != null) {
        if (cur.javaClass.name == PKCS11_EXCEPTION_CLASS) {
            val message = cur.message ?: return null
            CKR_NAME_TO_CODE[message.trim()]?.let { return Ckr(it) }
            val hex = HEX_CODE_REGEX.find(message)?.value ?: return null
            return hex.removePrefix("0x").toULongOrNull(HEX_RADIX)?.let { Ckr(it) }
        }
        cur = cur.cause
    }
    return null
}

/** System property first, then the environment; blank answers count as absent. */
private fun configured(
    prop: String,
    env: String,
): String? = System.getProperty(prop)?.takeIf { it.isNotBlank() } ?: System.getenv(env)?.takeIf { it.isNotBlank() }

private const val MODULE_PROP = "buffer.crypto.tpm2.pkcs11.module"
private const val MODULE_ENV = "BUFFER_CRYPTO_TPM2_PKCS11_MODULE"
private const val PIN_PROP = "buffer.crypto.tpm2.pkcs11.pin"
private const val PIN_ENV = "BUFFER_CRYPTO_TPM2_PKCS11_PIN"
private const val SLOT_PROP = "buffer.crypto.tpm2.pkcs11.slotIndex"
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

private const val SUN_PKCS11 = "SunPKCS11"
private const val PKCS11_KEYSTORE = "PKCS11"
private const val PROVIDER_NAME = "buffer-crypto-tpm2"
private const val PKCS11_EXCEPTION_CLASS = "sun.security.pkcs11.wrapper.PKCS11Exception"
private const val ECDSA_SHA256 = "SHA256withECDSA"
private const val EC = "EC"
private const val PROBE_MESSAGE = "buffer-crypto tpm2-pkcs11 resolution probe"
private const val HEX_RADIX = 16
private val HEX_CODE_REGEX = Regex("0x[0-9a-fA-F]+")

/** The named `CKR_*` codes SunPKCS11 prints, mapped back to their numeric values (typed at the boundary). */
private val CKR_NAME_TO_CODE: Map<String, ULong> =
    mapOf(
        "CKR_TOKEN_NOT_PRESENT" to 0xE0u,
        "CKR_PIN_INCORRECT" to 0xA0u,
        "CKR_PIN_LOCKED" to 0xA4u,
        "CKR_MECHANISM_INVALID" to 0x70u,
        "CKR_DEVICE_ERROR" to 0x30u,
        "CKR_SLOT_ID_INVALID" to 0x3u,
        "CKR_GENERAL_ERROR" to 0x5u,
        "CKR_FUNCTION_FAILED" to 0x6u,
    )
