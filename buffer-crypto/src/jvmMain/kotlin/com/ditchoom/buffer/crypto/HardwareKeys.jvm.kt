@file:Suppress("MatchingDeclarationName") // MPP platform-suffixed actual file

package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.PlatformBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Provider
import java.security.ProviderException
import java.security.Security
import java.security.Signature
import java.security.cert.X509Certificate
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
 * the resolution probe). ECDH P-256 is probed lazily end-to-end on a second, derive-capable provider
 * configuration (see the agreement-side note below) - as of tpm2-pkcs11 1.9 the module does not
 * advertise `CKM_ECDH1_DERIVE`, so the probe honestly fails and agreement routes to the software
 * floor; a module that backs the mechanism (verified against SoftHSM) lights eligibility up with no
 * library change. AES-GCM is not offered (the SunPKCS11 + tpm2-pkcs11 combination does not usably
 * back it).
 *
 * **Agreement runs on a second provider instance** over the same module/slot. SunPKCS11's default EC
 * keygen template omits `CKA_DERIVE` (a derive attempt fails `CKR_KEY_FUNCTION_NOT_PERMITTED` even on
 * a mechanism-capable module) and its derived-secret template must be readable, so the agreement side
 * configures both via `attributes` blocks. Keeping that template off the signing provider is
 * deliberate key separation at the PKCS#11 object layer: an identity signing key can never be driven
 * as an ECDH oracle, and an agreement key never signs (its wrapper certificate is software-signed).
 *
 * **Persistence** (see [Tpm2Pkcs11KeyStore]): the SunPKCS11 `KeyStore` only addresses private keys
 * paired with a certificate object, so persistent entries are wrapped in a minimal kind-tagged
 * certificate (built in Tpm2Pkcs11Persistence.jvm.kt) and stored via `setKeyEntry`, which copies the
 * session key into a token object (`C_CopyObject` - verified against tpm2-pkcs11 1.9 and SoftHSM).
 * Ephemeral keys from the plain generate methods stay session objects and die with the process.
 *
 * **Custody honesty:** the provider reports `dedicatedSecureElement = false`. PKCS#11 exposes no
 * reliable way to distinguish a discrete TPM from a firmware TPM, so the claim stays at
 * "hardware-isolated, non-exportable" - the same conservative posture as a TEE-only Android device.
 *
 * **Custody trust boundary (operator-delegated):** unlike the Android/Apple backends, where the OS
 * pins which element backs a key, the hardware-custody claim here is exactly as trustworthy as the
 * *configured module*. Pointing the module path at a software PKCS#11 implementation (e.g. SoftHSM)
 * yields software keys labeled [KeyCustody.NonExportable.Hardware] - the JCA bridge cannot read
 * `CK_TOKEN_INFO` to verify the token is a TPM. Deployments that rely on the custody tier MUST
 * treat the module path/PIN configuration as security-sensitive, same-integrity-domain state.
 * The PIN additionally arrives as an immutable system-property/environment `String`; only the
 * `char[]` copy handed to the login is wiped - an inherent limit of env-based configuration.
 *
 * `ByteArray` appears at the unavoidable JCA seam (X509EncodedKeySpec / generateSecret / BigInteger),
 * never as a library data structure; secret-bearing arrays are wiped after use.
 */
internal class Tpm2Pkcs11HardwareKeyProvider(
    private val p11: Provider,
    private val tokenKeyStore: java.security.KeyStore,
    private val modulePath: String,
    private val slotIndex: Int,
) : HardwareKeyProvider {
    /** PKCS#11 cannot confirm a discrete element, so this never over-claims. See the file KDoc. */
    override val dedicatedSecureElement: Boolean get() = false

    /**
     * Serializes suspend token ops (TPM round-trips are slow) off the calling thread. The lazy
     * [agreementSupported] probe is the one exception: [eligible] is non-suspend so the probe cannot
     * take this Mutex and may overlap an in-flight op - safe because SunPKCS11 pools sessions and
     * the resource manager (tpm2-abrmd / kernel `/dev/tpmrm0`) serializes at the TPM boundary.
     */
    private val tokenLock = Mutex()

    /**
     * The derive-capable provider configuration over the same module/slot, plus its logged-in
     * keystore view - every agreement op (ephemeral, persistent, and the eligibility probe) runs
     * here, never on [p11] (see the file KDoc's key-separation note). `null` when the second
     * configure/login fails, which keeps agreement honestly ineligible.
     */
    private val agreementSide: AgreementSide? by lazy { configureAgreementSide() }

    /**
     * `true` once a full token ECDH against a software peer matches the software-side derivation.
     * Lazily probed on the agreement side: tpm2-pkcs11 1.9 lacks `CKM_ECDH1_DERIVE` (SunPKCS11 then
     * registers no `ECDH` service, and the probe fails on `NoSuchAlgorithmException`), but a module
     * that backs the mechanism passes with no library change (verified against SoftHSM).
     */
    private val agreementSupported: Boolean by lazy { agreementSide?.let { probeAgreement(it.provider) } ?: false }

    @Suppress("SwallowedException", "TooGenericExceptionCaught")
    private fun configureAgreementSide(): AgreementSide? =
        try {
            val base = Security.getProvider(SUN_PKCS11)
            val pin = configured(PIN_PROP, PIN_ENV)
            if (base == null || pin == null) {
                null
            } else {
                val conf =
                    "--name=$AGREEMENT_PROVIDER_NAME\nlibrary=$modulePath\nslotListIndex=$slotIndex\n" +
                        AGREEMENT_ATTRIBUTES
                val provider = base.configure(conf)
                val keyStore = java.security.KeyStore.getInstance(PKCS11_KEYSTORE, provider)
                val pinChars = pin.toCharArray()
                try {
                    keyStore.load(null, pinChars)
                } finally {
                    pinChars.fill(' ')
                }
                AgreementSide(provider, keyStore)
            }
        } catch (e: Throwable) {
            null
        }

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
        // Session objects: the token drops the key with the process; nothing to delete on close.
        val keyPair = tokenOp { generateP256KeyPair(p11) }
        val verifyKey = VerifyKey.ecdsaP256(uncompressedP256Point(keyPair.public as ECPublicKey))
        return tokenSigningKey(keyPair.private, verifyKey, spec.authorization)
    }

    /** A [SigningKey] whose gated sign drives [privateKey] on the token - shared by generate and reload. */
    private fun tokenSigningKey(
        privateKey: PrivateKey,
        verifyKey: VerifyKey,
        gate: HardwareAuthorization,
    ): SigningKey =
        ProtectedSigningKey(
            scheme = SignatureScheme.EcdsaP256,
            custody = custody,
            gatedSign = { message, factory ->
                if (!gate.authorize()) throw AuthorizationFailed()
                tokenOp {
                    val signer = Signature.getInstance(ECDSA_SHA256, p11).apply { initSign(privateKey) }
                    signer.update(message.slice().remainingBytes())
                    val der = signer.sign()
                    val out: PlatformBuffer = factory.allocate(der.size)
                    out.writeBytes(der)
                    out.resetForRead()
                    out
                }
            },
            verifyKey = verifyKey,
            // Persistent entries outlive close(); session objects die with the process either way.
            onClose = {},
        )

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
        // eligible() implies the side resolved; the fallback keeps this total rather than trusting it.
        val side = agreementSide ?: throw HardwareKeyException.AlgorithmNotEligible()
        val keyPair = tokenOp { generateP256KeyPair(side.provider) }
        val publicKey = KeyAgreementPublicKey.of(curve, uncompressedP256Point(keyPair.public as ECPublicKey))
        return tokenAgreementPair(side, keyPair.private, publicKey, spec.authorization)
    }

    /** A [KeyAgreementKeyPair] whose gated DH drives [privateKey] on the agreement side - generate and reload. */
    private fun tokenAgreementPair(
        side: AgreementSide,
        privateKey: PrivateKey,
        publicKey: KeyAgreementPublicKey,
        gate: HardwareAuthorization,
    ): KeyAgreementKeyPair {
        val privateHandle =
            ProtectedKeyAgreementPrivateKey(
                curve = KeyAgreementCurve.P256,
                custody = custody,
                gatedDh = { peer ->
                    if (!gate.authorize()) throw AuthorizationFailed()
                    require(peer.curve == KeyAgreementCurve.P256) { "private/public key curve mismatch" }
                    // Peer-point conversion happens outside the token lock; a malformed or
                    // off-curve point is rejected before the TPM is ever touched.
                    val jcaPeer = p256PublicKeyFromSec1(peer.encoded)
                    tokenOp { agreeP256OnToken(side.provider, privateKey, jcaPeer) }
                },
                onClose = {},
            )
        return keyAgreementKeyPairOf(KeyAgreementCurve.P256, privateHandle, publicKey)
    }

    // --- Persistent cert-wrapped entries (the [Tpm2Pkcs11KeyStore] seam) --------------------------
    //
    // Labels arrive fully namespaced from the store ("<name>:<alias>"). Reads go through
    // [tokenKeyStore]; agreement entries are written and reloaded through the agreement side so the
    // reloaded key handle is bound to the derive-capable provider. Both views enumerate the same
    // token objects. Concurrent get-or-generate races on one label are benign: the last setKeyEntry
    // wins and the loser's session key dies with the process.

    /**
     * The (kind, tag) wrapper-certificate record under [p11Label], `null` when the label is absent,
     * or [KeyStoreException.CorruptEntry] when a certificate exists but carries no kind tag (a
     * foreign token object squatting in our namespace is surfaced, never silently overwritten).
     */
    internal suspend fun storedEntryKind(p11Label: String): Pair<Int, Int>? =
        storeOp {
            when (val cert = refreshed(tokenKeyStore).getCertificate(p11Label) as? X509Certificate) {
                null -> null
                else -> storedKindOf(cert) ?: throw KeyStoreException.CorruptEntry()
            }
        }

    /**
     * Re-loads [ks]'s cached token-object view before a read. SunPKCS11's `KeyStore` maps the
     * token's objects at `load()` and only tracks its *own* writes afterwards, so entries written
     * through the other side's view (signing vs. agreement provider) stay invisible until a re-load
     * - verified empirically against SoftHSM.
     *
     * `engineLoad` wraps every internal failure (login, `PKCS11Exception`) in a bare [IOException] -
     * there is no more specific public type - so the mapping to the typed storage failure is caught
     * exactly here, never as a blanket handler over a whole store op.
     */
    @Suppress("SwallowedException")
    private fun refreshed(ks: java.security.KeyStore): java.security.KeyStore {
        try {
            ks.load(null, null)
        } catch (e: IOException) {
            throw KeyStoreException.StorageFailure(retryable = false)
        }
        return ks
    }

    /** Generates a signing key and persists it under [p11Label] - cert self-signed on the token. */
    internal suspend fun persistSigning(
        p11Label: String,
        spec: ProtectedKeySpec,
    ): SigningKey {
        val keyPair =
            storeOp {
                val kp = generateP256KeyPair(p11)
                val cert =
                    buildWrapperCertificate(
                        KIND_SIGNING,
                        signingTag(SignatureScheme.EcdsaP256),
                        p11Label,
                        kp.public,
                    ) { tbs ->
                        val signer = Signature.getInstance(ECDSA_SHA256, p11).apply { initSign(kp.private) }
                        signer.update(tbs)
                        signer.sign()
                    }
                tokenKeyStore.setKeyEntry(p11Label, kp.private, null, arrayOf(cert))
                kp
            }
        val verifyKey = VerifyKey.ecdsaP256(uncompressedP256Point(keyPair.public as ECPublicKey))
        return tokenSigningKey(keyPair.private, verifyKey, spec.authorization)
    }

    /** Re-attaches the signing entry under [p11Label], or `null` when the key or certificate is gone. */
    internal suspend fun signingFromEntry(
        p11Label: String,
        spec: ProtectedKeySpec,
    ): SigningKey? {
        val loaded =
            storeOp {
                val view = refreshed(tokenKeyStore)
                val key = view.getKey(p11Label, null) as? PrivateKey
                val cert = view.getCertificate(p11Label) as? X509Certificate
                if (key == null || cert == null) null else key to cert
            } ?: return null
        val verifyKey = VerifyKey.ecdsaP256(uncompressedP256Point(loaded.second.publicKey as ECPublicKey))
        return tokenSigningKey(loaded.first, verifyKey, spec.authorization)
    }

    /**
     * Generates an agreement key on the agreement side and persists it under [p11Label]. The wrapper
     * certificate is software-signed: the token object is derive-capable by design, and the wrapper
     * is packaging, not a trust assertion (see Tpm2Pkcs11Persistence.jvm.kt).
     */
    internal suspend fun persistAgreement(
        p11Label: String,
        spec: ProtectedKeySpec,
    ): KeyAgreementKeyPair {
        val side = agreementSide ?: throw HardwareKeyException.AlgorithmNotEligible()
        val keyPair =
            storeOp {
                val kp = generateP256KeyPair(side.provider)
                val cert =
                    buildWrapperCertificate(
                        KIND_AGREEMENT,
                        agreementTag(KeyAgreementCurve.P256),
                        p11Label,
                        kp.public,
                        ::softwareWrapperSignature,
                    )
                side.keyStore.setKeyEntry(p11Label, kp.private, null, arrayOf(cert))
                kp
            }
        val publicKey =
            KeyAgreementPublicKey.of(KeyAgreementCurve.P256, uncompressedP256Point(keyPair.public as ECPublicKey))
        return tokenAgreementPair(side, keyPair.private, publicKey, spec.authorization)
    }

    /** Re-attaches the agreement entry under [p11Label], `null` when gone or agreement is unavailable. */
    internal suspend fun agreementFromEntry(
        p11Label: String,
        spec: ProtectedKeySpec,
    ): KeyAgreementKeyPair? {
        val side = agreementSide ?: return null
        val loaded =
            storeOp {
                val view = refreshed(side.keyStore)
                val key = view.getKey(p11Label, null) as? PrivateKey
                val cert = view.getCertificate(p11Label) as? X509Certificate
                if (key == null || cert == null) null else key to cert
            }
        return loaded?.let { (key, cert) ->
            val publicKey =
                KeyAgreementPublicKey.of(
                    KeyAgreementCurve.P256,
                    uncompressedP256Point(cert.publicKey as ECPublicKey),
                )
            tokenAgreementPair(side, key, publicKey, spec.authorization)
        }
    }

    /** Every certificate-paired label on the token (all namespaces; the store filters its own). */
    internal suspend fun entryAliases(): Set<String> =
        storeOp {
            buildSet {
                val aliases = refreshed(tokenKeyStore).aliases()
                while (aliases.hasMoreElements()) add(aliases.nextElement())
            }
        }

    internal suspend fun containsEntry(p11Label: String): Boolean =
        storeOp {
            refreshed(tokenKeyStore).containsAlias(p11Label)
        }

    /** Destroys the token objects under [p11Label]; `false` when nothing was stored there. */
    internal suspend fun deleteEntry(p11Label: String): Boolean =
        storeOp {
            val view = refreshed(tokenKeyStore)
            if (!view.containsAlias(p11Label)) {
                false
            } else {
                view.deleteEntry(p11Label)
                true
            }
        }

    /**
     * Like [tokenOp], but normalizes provider failures to [KeyStoreException.StorageFailure] - these
     * are durable-entry operations, so the store-shaped error is the honest one. A [CryptoException]
     * (e.g. [KeyStoreException.CorruptEntry]) passes through untouched.
     */
    @Suppress("SwallowedException")
    private suspend fun <T> storeOp(block: () -> T): T =
        tokenLock.withLock {
            withContext(Dispatchers.IO) {
                try {
                    block()
                } catch (e: CryptoException) {
                    throw e
                } catch (e: GeneralSecurityException) {
                    throw KeyStoreException.StorageFailure(retryable = false)
                } catch (e: ProviderException) {
                    throw KeyStoreException.StorageFailure(retryable = false)
                }
            }
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

/** A provider configuration paired with its logged-in keystore view of the same token. */
private class AgreementSide(
    val provider: Provider,
    val keyStore: java.security.KeyStore,
)

// =============================================================================
// Resolution - every step's refusal is a distinct typed finding, never a bare null
// =============================================================================

private val jvmResolution: ProtectedKeyResolution by lazy { resolveTpm2Pkcs11() }

internal actual fun platformProtectedKeyResolution(): ProtectedKeyResolution = jvmResolution

/** The resolved TPM-backed provider for the platform key store, or `null` short of Available. */
internal fun tpm2Pkcs11ProviderOrNull(): Tpm2Pkcs11HardwareKeyProvider? =
    (jvmResolution as? ProtectedKeyResolution.Available)?.provider as? Tpm2Pkcs11HardwareKeyProvider

@Suppress("SwallowedException", "TooGenericExceptionCaught", "ReturnCount", "CyclomaticComplexMethod")
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
    // The logged-in keystore is retained: it is the persistence surface for Tpm2Pkcs11KeyStore.
    val (p11, tokenKeyStore) =
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
            configured to keyStore
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
            ProtectedKeyResolution.Available(
                ProtectedKeyBackend.Tpm2Pkcs11,
                Tpm2Pkcs11HardwareKeyProvider(p11, tokenKeyStore, modulePath, slotIndex),
            )
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
@Suppress("ReturnCount")
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
private const val AGREEMENT_PROVIDER_NAME = "buffer-crypto-tpm2-agree"

/**
 * The agreement side's template overrides: SunPKCS11's default EC keygen omits `CKA_DERIVE`, and the
 * ECDH-derived secret must be readable for `generateSecret` to return it (`CKR_ATTRIBUTE_SENSITIVE`
 * otherwise) - both verified empirically against SoftHSM. Scoped to this second provider so signing
 * keys never gain derive capability.
 */
private val AGREEMENT_ATTRIBUTES =
    """
    attributes(generate,CKO_PRIVATE_KEY,*) = {
      CKA_DERIVE = true
    }
    attributes(*,CKO_SECRET_KEY,*) = {
      CKA_SENSITIVE = false
      CKA_EXTRACTABLE = true
    }
    """.trimIndent() + "\n"
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
