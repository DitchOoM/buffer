@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.crypto.cinterop.pkcs11.BCP11_C_CloseSession
import com.ditchoom.buffer.crypto.cinterop.pkcs11.BCP11_C_DeriveKey
import com.ditchoom.buffer.crypto.cinterop.pkcs11.BCP11_C_DestroyObject
import com.ditchoom.buffer.crypto.cinterop.pkcs11.BCP11_C_GenerateKeyPair
import com.ditchoom.buffer.crypto.cinterop.pkcs11.BCP11_C_GetAttributeValue
import com.ditchoom.buffer.crypto.cinterop.pkcs11.BCP11_C_GetSlotList
import com.ditchoom.buffer.crypto.cinterop.pkcs11.BCP11_C_Initialize
import com.ditchoom.buffer.crypto.cinterop.pkcs11.BCP11_C_Login
import com.ditchoom.buffer.crypto.cinterop.pkcs11.BCP11_C_OpenSession
import com.ditchoom.buffer.crypto.cinterop.pkcs11.BCP11_C_Sign
import com.ditchoom.buffer.crypto.cinterop.pkcs11.BCP11_C_SignInit
import com.ditchoom.buffer.crypto.cinterop.pkcs11.CK_ATTRIBUTE
import com.ditchoom.buffer.crypto.cinterop.pkcs11.CK_C_INITIALIZE_ARGS
import com.ditchoom.buffer.crypto.cinterop.pkcs11.CK_ECDH1_DERIVE_PARAMS
import com.ditchoom.buffer.crypto.cinterop.pkcs11.CK_MECHANISM
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import platform.posix.RTLD_NOW
import platform.posix.dlopen
import platform.posix.dlsym

/*
 * Low-level PKCS#11 session over a runtime-dlopen'd module (see tpm2pkcs11.def for the binding
 * approach). One process-lifetime session, opened and logged-in by [Pkcs11Token.open]; every op is
 * synchronous and the provider above serializes them. Failures carry the raw `CKR_*` code as a typed
 * [Ckr] inside [Pkcs11Failure] - the native bridge sees the numeric code directly, so unlike the JVM
 * side there is no exception-message parsing at this seam.
 */

/** A token call failed with [ckr]; classified into typed findings/exceptions at the seams above. */
internal class Pkcs11Failure(
    val ckr: Ckr,
) : Exception("PKCS#11 operation failed (CKR 0x${ckr.raw.toString(HEX_RADIX)})")

/** How [Pkcs11Token.open] failed, when it did - each is a distinct typed refusal, never a null. */
internal sealed interface Pkcs11OpenResult {
    /** The session is up and logged in. */
    data class Opened(
        val token: Pkcs11Token,
    ) : Pkcs11OpenResult

    /** dlopen failed or a `C_*` entry point is not exported - the module is unusable as built. */
    data object ModuleUnloadable : Pkcs11OpenResult

    /** The module loaded but a bootstrap call refused with [ckr]. */
    data class Rejected(
        val ckr: Ckr,
    ) : Pkcs11OpenResult
}

@Suppress("TooManyFunctions") // one member per PKCS#11 operation the backend performs
internal class Pkcs11Token private constructor(
    private val fGenerateKeyPair: BCP11_C_GenerateKeyPair,
    private val fGetAttributeValue: BCP11_C_GetAttributeValue,
    private val fSignInit: BCP11_C_SignInit,
    private val fSign: BCP11_C_Sign,
    private val fDeriveKey: BCP11_C_DeriveKey,
    private val fDestroyObject: BCP11_C_DestroyObject,
    private val session: ULong,
) {
    /**
     * Generates a session-object P-256 keypair whose private key is single-purpose by template:
     * `CKA_SIGN` for [signing]`= true`, `CKA_DERIVE` otherwise - never both (unlike the SunPKCS11
     * bridge, this binding controls the template per call). Sensitive and non-extractable always.
     */
    fun generateP256KeyPair(signing: Boolean): Pkcs11KeyPair =
        memScoped {
            val mech = mechanism(CKM_EC_KEY_PAIR_GEN)
            val ecParams = allocArrayOf(P256_OID_DER)
            val yes = alloc<UByteVar> { value = CK_TRUE }
            val no = alloc<UByteVar> { value = CK_FALSE }

            val pubTemplate = allocArray<CK_ATTRIBUTE>(PUB_TEMPLATE_ATTRS.toInt())
            pubTemplate.setAttr(0, CKA_EC_PARAMS, ecParams, P256_OID_DER.size)
            pubTemplate.setAttr(1, CKA_VERIFY, if (signing) yes.ptr else no.ptr, 1)

            val privTemplate = allocArray<CK_ATTRIBUTE>(PRIV_TEMPLATE_ATTRS.toInt())
            privTemplate.setAttr(0, CKA_SENSITIVE, yes.ptr, 1)
            privTemplate.setAttr(1, CKA_EXTRACTABLE, no.ptr, 1)
            privTemplate.setAttr(2, if (signing) CKA_SIGN else CKA_DERIVE, yes.ptr, 1)

            val hPub = alloc<ULongVar>()
            val hPriv = alloc<ULongVar>()
            checkRv(
                fGenerateKeyPair(
                    session,
                    mech.ptr,
                    pubTemplate,
                    PUB_TEMPLATE_ATTRS,
                    privTemplate,
                    PRIV_TEMPLATE_ATTRS,
                    hPub.ptr,
                    hPriv.ptr,
                ),
            )
            Pkcs11KeyPair(publicHandle = hPub.value, privateHandle = hPriv.value)
        }

    /**
     * The public key of [handle] as an uncompressed SEC1 point (`04 || X || Y`, 65 bytes,
     * read-ready). `CKA_EC_POINT` is spec'd as a DER OCTET STRING around the point; some modules
     * return the bare point, so both encodings are accepted.
     */
    fun ecPoint(handle: ULong): ReadBuffer =
        memScoped {
            val raw = readAttribute(this, handle, CKA_EC_POINT)
            val n = raw.remaining()
            when {
                n == P256_POINT_BYTES + 2 &&
                    raw.get(raw.position()).toInt() == DER_OCTET_STRING &&
                    (raw.get(raw.position() + 1).toInt() and BYTE_MASK) == P256_POINT_BYTES -> {
                    raw.position(raw.position() + 2)
                    raw
                }
                n == P256_POINT_BYTES -> raw
                else -> throw Pkcs11Failure(Ckr(CKR_GENERAL_ERROR_CODE))
            }
        }

    /** `ECDSA(digest)` on the token: raw `r || s` (64 bytes for P-256), read-ready. */
    fun signDigest(
        privateHandle: ULong,
        digest: ReadBuffer,
    ): ReadBuffer =
        memScoped {
            val mech = mechanism(CKM_ECDSA)
            checkRv(fSignInit(session, mech.ptr, privateHandle))
            val digestLen = digest.remaining()
            val out = allocArray<UByteVar>(SIGNATURE_CAP)
            val outLen = alloc<ULongVar> { value = SIGNATURE_CAP.convert() }
            digest.withRemainingBytes2(digestLen) { digestPtr ->
                checkRv(fSign(session, digestPtr.reinterpret(), digestLen.convert(), out, outLen.ptr))
            }
            val n = outLen.value.toInt()
            val result = BufferFactory.Default.allocate(n)
            for (i in 0 until n) result.writeByte(out[i].toByte())
            result.resetForRead()
            result
        }

    /**
     * `DH(privateHandle, peerPoint)` inside the token via `CKM_ECDH1_DERIVE` / `CKD_NULL`: derives a
     * readable generic-secret session object, copies its 32-byte X-coordinate value into a wiped
     * secure buffer, and destroys the derived object before returning.
     */
    fun deriveEcdh(
        privateHandle: ULong,
        peerPoint: ReadBuffer,
    ): PlatformBuffer =
        memScoped {
            val peerLen = peerPoint.remaining()
            val derived = alloc<ULongVar>()
            peerPoint.withRemainingBytes2(peerLen) { peerPtr ->
                val params =
                    alloc<CK_ECDH1_DERIVE_PARAMS> {
                        kdf = CKD_NULL
                        ulSharedDataLen = 0u
                        pSharedData = null
                        ulPublicDataLen = peerLen.convert()
                        pPublicData = peerPtr.reinterpret()
                    }
                val mech = alloc<CK_MECHANISM>()
                mech.mechanism = CKM_ECDH1_DERIVE
                mech.pParameter = params.ptr
                mech.ulParameterLen = sizeOf<CK_ECDH1_DERIVE_PARAMS>().convert()

                val classVal = alloc<ULongVar> { value = CKO_SECRET_KEY }
                val keyType = alloc<ULongVar> { value = CKK_GENERIC_SECRET }
                val valueLen = alloc<ULongVar> { value = P256_FIELD_BYTES.convert() }
                val yes = alloc<UByteVar> { value = CK_TRUE }
                val no = alloc<UByteVar> { value = CK_FALSE }
                val template = allocArray<CK_ATTRIBUTE>(DERIVE_TEMPLATE_ATTRS.toInt())
                template.setAttr(0, CKA_CLASS, classVal.ptr, ULONG_BYTES)
                template.setAttr(1, CKA_KEY_TYPE, keyType.ptr, ULONG_BYTES)
                template.setAttr(2, CKA_VALUE_LEN, valueLen.ptr, ULONG_BYTES)
                template.setAttr(SENSITIVE_ATTR_INDEX, CKA_SENSITIVE, no.ptr, 1)
                template.setAttr(EXTRACTABLE_ATTR_INDEX, CKA_EXTRACTABLE, yes.ptr, 1)

                checkRv(fDeriveKey(session, mech.ptr, privateHandle, template, DERIVE_TEMPLATE_ATTRS, derived.ptr))
            }
            try {
                val value = readAttribute(this, derived.value, CKA_VALUE)
                if (value.remaining() != P256_FIELD_BYTES) throw Pkcs11Failure(Ckr(CKR_GENERAL_ERROR_CODE))
                val secret = secureScratch.allocate(P256_FIELD_BYTES)
                secret.write(value)
                secret.resetForRead()
                secret
            } finally {
                destroy(derived.value)
            }
        }

    /** Destroys a session object; best-effort (a failed destroy cannot un-happen the op above it). */
    fun destroy(handle: ULong) {
        fDestroyObject(session, handle)
    }

    /** Two-phase `C_GetAttributeValue`: length query, then fetch into a fresh read-ready buffer. */
    private fun readAttribute(
        scope: MemScope,
        handle: ULong,
        attribute: ULong,
    ): PlatformBuffer =
        with(scope) {
            val probe = allocArray<CK_ATTRIBUTE>(1)
            probe.setAttr(0, attribute, null, 0)
            checkRv(fGetAttributeValue(session, handle, probe, 1u))
            val n = probe[0].ulValueLen.toInt()
            if (n <= 0) throw Pkcs11Failure(Ckr(CKR_GENERAL_ERROR_CODE))
            val storage = allocArray<ByteVar>(n)
            probe.setAttr(0, attribute, storage, n)
            checkRv(fGetAttributeValue(session, handle, probe, 1u))
            val out = BufferFactory.Default.allocate(n)
            for (i in 0 until n) out.writeByte(storage[i])
            out.resetForRead()
            out
        }

    private fun MemScope.mechanism(type: ULong): CK_MECHANISM =
        alloc<CK_MECHANISM> {
            mechanism = type
            pParameter = null
            ulParameterLen = 0u
        }

    private fun checkRv(rv: ULong) {
        if (rv != CKR_OK) throw Pkcs11Failure(Ckr(rv))
    }

    companion object {
        /**
         * dlopens [modulePath], resolves the entry points, and bootstraps `C_Initialize` ->
         * `C_GetSlotList` -> `C_OpenSession` -> `C_Login(CKU_USER)`. Already-initialized and
         * already-logged-in answers are tolerated (another library in-process may own the module).
         */
        @Suppress("ReturnCount") // each typed refusal exits as soon as it is known
        fun open(
            modulePath: String,
            pin: String,
            slotIndex: Int,
        ): Pkcs11OpenResult {
            val handle = dlopen(modulePath, RTLD_NOW) ?: return Pkcs11OpenResult.ModuleUnloadable
            val entry = resolveEntryPoints(handle) ?: return Pkcs11OpenResult.ModuleUnloadable
            return memScoped {
                // CKF_OS_LOCKING_OK: the module locks internally with OS primitives, so the one
                // non-serialized caller (the lazy eligibility probe) is safe alongside token ops.
                val initArgs =
                    alloc<CK_C_INITIALIZE_ARGS> {
                        CreateMutex = null
                        DestroyMutex = null
                        LockMutex = null
                        UnlockMutex = null
                        flags = CKF_OS_LOCKING_OK
                        pReserved = null
                    }
                val initRv = entry.initialize(initArgs.ptr)
                if (initRv != CKR_OK && initRv != CKR_ALREADY_INITIALIZED) {
                    return@memScoped Pkcs11OpenResult.Rejected(Ckr(initRv))
                }
                val session =
                    when (val bootstrap = openAndLogin(entry, pin, slotIndex)) {
                        is SessionResult.Live -> bootstrap.session
                        is SessionResult.Refused -> return@memScoped Pkcs11OpenResult.Rejected(bootstrap.ckr)
                    }
                Pkcs11OpenResult.Opened(
                    Pkcs11Token(
                        fGenerateKeyPair = entry.generateKeyPair,
                        fGetAttributeValue = entry.getAttributeValue,
                        fSignInit = entry.signInit,
                        fSign = entry.sign,
                        fDeriveKey = entry.deriveKey,
                        fDestroyObject = entry.destroyObject,
                        session = session,
                    ),
                )
            }
        }

        /** Every `C_*` entry point this backend uses, dlsym-resolved; `null` when any is missing. */
        @Suppress("ReturnCount") // one exit per missing symbol keeps the resolution flat
        private fun resolveEntryPoints(handle: COpaquePointer): EntryPoints? {
            fun sym(name: String): COpaquePointer? = dlsym(handle, name)
            return EntryPoints(
                initialize = sym("C_Initialize")?.reinterpret() ?: return null,
                getSlotList = sym("C_GetSlotList")?.reinterpret() ?: return null,
                openSession = sym("C_OpenSession")?.reinterpret() ?: return null,
                closeSession = sym("C_CloseSession")?.reinterpret() ?: return null,
                login = sym("C_Login")?.reinterpret() ?: return null,
                generateKeyPair = sym("C_GenerateKeyPair")?.reinterpret() ?: return null,
                getAttributeValue = sym("C_GetAttributeValue")?.reinterpret() ?: return null,
                signInit = sym("C_SignInit")?.reinterpret() ?: return null,
                sign = sym("C_Sign")?.reinterpret() ?: return null,
                deriveKey = sym("C_DeriveKey")?.reinterpret() ?: return null,
                destroyObject = sym("C_DestroyObject")?.reinterpret() ?: return null,
            )
        }

        /** Slot lookup, session open, and login - the CKR-refusable half of the bootstrap. */
        @Suppress("ReturnCount") // each typed refusal exits as soon as it is known
        private fun openAndLogin(
            entry: EntryPoints,
            pin: String,
            slotIndex: Int,
        ): SessionResult =
            memScoped {
                val count = alloc<ULongVar>()
                val listRv = entry.getSlotList(CK_TRUE, null, count.ptr)
                if (listRv != CKR_OK) return@memScoped SessionResult.Refused(Ckr(listRv))
                val n = count.value.toInt()
                if (slotIndex >= n) return@memScoped SessionResult.Refused(Ckr(CKR_SLOT_ID_INVALID_CODE))
                val slots = allocArray<ULongVar>(n)
                val fillRv = entry.getSlotList(CK_TRUE, slots, count.ptr)
                if (fillRv != CKR_OK) return@memScoped SessionResult.Refused(Ckr(fillRv))

                val session = alloc<ULongVar>()
                val openRv =
                    entry.openSession(slots[slotIndex], CKF_SERIAL_SESSION or CKF_RW_SESSION, null, null, session.ptr)
                if (openRv != CKR_OK) return@memScoped SessionResult.Refused(Ckr(openRv))

                val pinBytes = pin.encodeToByteArray()
                val pinNative = allocArray<ByteVar>(pinBytes.size)
                for (i in pinBytes.indices) pinNative[i] = pinBytes[i]
                val loginRv = entry.login(session.value, CKU_USER, pinNative.reinterpret(), pinBytes.size.convert())
                for (i in pinBytes.indices) {
                    pinBytes[i] = 0
                    pinNative[i] = 0
                }
                if (loginRv != CKR_OK && loginRv != CKR_USER_ALREADY_LOGGED_IN) {
                    entry.closeSession(session.value)
                    return@memScoped SessionResult.Refused(Ckr(loginRv))
                }
                SessionResult.Live(session.value)
            }
    }
}

/** The dlsym-resolved `C_*` entry points, typed via the .def's function-pointer typedefs. */
private class EntryPoints(
    val initialize: BCP11_C_Initialize,
    val getSlotList: BCP11_C_GetSlotList,
    val openSession: BCP11_C_OpenSession,
    val closeSession: BCP11_C_CloseSession,
    val login: BCP11_C_Login,
    val generateKeyPair: BCP11_C_GenerateKeyPair,
    val getAttributeValue: BCP11_C_GetAttributeValue,
    val signInit: BCP11_C_SignInit,
    val sign: BCP11_C_Sign,
    val deriveKey: BCP11_C_DeriveKey,
    val destroyObject: BCP11_C_DestroyObject,
)

/** A live logged-in session handle, or the CKR that refused the bootstrap. */
private sealed interface SessionResult {
    data class Live(
        val session: ULong,
    ) : SessionResult

    data class Refused(
        val ckr: Ckr,
    ) : SessionResult
}

/** A generated keypair's object handles. */
internal class Pkcs11KeyPair(
    val publicHandle: ULong,
    val privateHandle: ULong,
)

private fun CPointer<CK_ATTRIBUTE>.setAttr(
    index: Int,
    type: ULong,
    value: COpaquePointer?,
    length: Int,
) {
    this[index].type = type
    this[index].pValue = value
    this[index].ulValueLen = length.convert()
}

// Cryptoki constants (PKCS#11 v2.40) - only what this backend uses.
private const val CKR_OK = 0uL
private const val CKR_ALREADY_INITIALIZED = 0x191uL
private const val CKR_USER_ALREADY_LOGGED_IN = 0x100uL
private const val CKR_SLOT_ID_INVALID_CODE = 0x3uL
private const val CKR_GENERAL_ERROR_CODE = 0x5uL
private const val CKM_EC_KEY_PAIR_GEN = 0x1040uL
private const val CKM_ECDSA = 0x1041uL
private const val CKM_ECDH1_DERIVE = 0x1050uL
private const val CKA_CLASS = 0x0uL
private const val CKA_VALUE = 0x11uL
private const val CKA_KEY_TYPE = 0x100uL
private const val CKA_SENSITIVE = 0x103uL
private const val CKA_SIGN = 0x108uL
private const val CKA_VERIFY = 0x10AuL
private const val CKA_DERIVE = 0x10CuL
private const val CKA_VALUE_LEN = 0x161uL
private const val CKA_EXTRACTABLE = 0x162uL
private const val CKA_EC_PARAMS = 0x180uL
private const val CKA_EC_POINT = 0x181uL
private const val CKO_SECRET_KEY = 0x4uL
private const val CKK_GENERIC_SECRET = 0x10uL
private const val CKD_NULL = 0x1uL
private const val CKU_USER = 1uL
private const val CKF_SERIAL_SESSION = 0x4uL
private const val CKF_RW_SESSION = 0x2uL
private const val CKF_OS_LOCKING_OK = 0x2uL
private const val CK_TRUE: UByte = 1u
private const val CK_FALSE: UByte = 0u

private const val ULONG_BYTES = 8
private const val PUB_TEMPLATE_ATTRS = 2uL
private const val PRIV_TEMPLATE_ATTRS = 3uL
private const val DERIVE_TEMPLATE_ATTRS = 5uL
private const val SENSITIVE_ATTR_INDEX = 3
private const val EXTRACTABLE_ATTR_INDEX = 4
private const val P256_POINT_BYTES = 65
private const val P256_FIELD_BYTES = 32
private const val SIGNATURE_CAP = 144
private const val DER_OCTET_STRING = 0x04
private const val BYTE_MASK = 0xFF
private const val HEX_RADIX = 16

/** DER OID for prime256v1 / secp256r1 (1.2.840.10045.3.1.7) - the `CKA_EC_PARAMS` value. */
private val P256_OID_DER =
    byteArrayOf(
        0x06,
        0x08,
        0x2A,
        0x86.toByte(),
        0x48,
        0xCE.toByte(),
        0x3D,
        0x03,
        0x01,
        0x07,
    )
