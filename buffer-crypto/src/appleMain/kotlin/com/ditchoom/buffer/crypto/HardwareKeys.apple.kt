@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_OK
import com.ditchoom.buffer.crypto.cinterop.cryptokit.bcks_secure_enclave_available
import com.ditchoom.buffer.crypto.cinterop.cryptokit.bcks_secure_enclave_p256_generate
import com.ditchoom.buffer.crypto.cinterop.cryptokit.bcks_secure_enclave_p256_sign
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.posix.size_tVar

/*
 * Apple Secure Enclave hardware-backed key provider (CryptoKit `SecureEnclave.P256.Signing`, via the
 * CryptoKitShim).
 *
 * The Enclave generates and holds a P-256 private key that never leaves the element; signing routes
 * through the shim, which reconstructs the key from its opaque encrypted blob and signs *inside* the
 * Enclave (DER output, matching [ecdsaSignatureEncoding] == Der and the JVM/Android contract). The
 * public key the Enclave produced is captured into the returned [SigningKey] so the verifier can be
 * published.
 *
 * **Only ECDSA P-256 is backed.** AES-GCM is not eligible: CryptoKit exposes no symmetric Secure
 * Enclave key, and the only "Enclave-tied" AES one could build (ECDH a P-256 Enclave key → derive a
 * symmetric key → run AES.GCM in software) would put the AES key in process memory, violating the
 * non-exportable hardware-key contract. (The Enclave *does* contain an AES engine, but Apple exposes
 * no developer API to drive it as an app-controlled non-exportable AEAD key.)
 *
 * The per-use gate is [HardwareKeySpec.authorization], evaluated in Kotlin before each op. Binding
 * the Enclave key to a biometric `SecAccessControl` is a future enhancement layered on the gate.
 */
internal class SecureEnclaveHardwareKeyProvider : HardwareKeyProvider {
    override val dedicatedSecureElement: Boolean get() = true

    override fun eligible(alg: HardwareAlgorithm): Boolean = alg == HardwareAlgorithm.EcdsaP256

    override suspend fun generateAesGcm(spec: HardwareKeySpec): AesGcmKey =
        throw IllegalArgumentException(
            "AES-GCM is not eligible for Secure Enclave backing (no app-controlled non-exportable " +
                "symmetric Enclave key exists on Apple)",
        )

    override suspend fun generateSigning(
        scheme: SignatureScheme,
        spec: HardwareKeySpec,
    ): SigningKey {
        require(eligible(scheme.toHardwareAlgorithm())) {
            "${scheme.schemeName} is not eligible for hardware backing"
        }
        val generated = enclaveGenerateP256()
        val verifyKey = VerifyKey.ecdsaP256(BufferFactory.Default.wrap(generated.point))
        val blob = generated.blob
        val auth = spec.authorization
        return HardwareSigningKey(
            scheme = SignatureScheme.EcdsaP256,
            gatedSign = { message, factory ->
                if (!auth.authorize()) throw AuthorizationFailed()
                val der = enclaveSignP256(blob, message)
                val out: PlatformBuffer = factory.allocate(der.size)
                out.writeBytes(der)
                out.resetForRead()
                out
            },
            verifyKey = verifyKey,
        )
    }
}

/** A freshly generated Enclave key: the opaque restore [blob] and its uncompressed SEC1 [point]. */
private class EnclaveP256Key(
    val blob: ByteArray,
    val point: ByteArray,
)

private const val ENCLAVE_BLOB_CAP = 256
private const val P256_POINT_BYTES = 65

private fun enclaveGenerateP256(): EnclaveP256Key =
    memScoped {
        val blobOut = allocArray<ByteVar>(ENCLAVE_BLOB_CAP)
        val blobLen = alloc<size_tVar>()
        val pointOut = allocArray<ByteVar>(P256_POINT_BYTES)
        val pointLen = alloc<size_tVar>()
        val status =
            bcks_secure_enclave_p256_generate(
                blobOut.reinterpret(),
                ENCLAVE_BLOB_CAP.convert(),
                blobLen.ptr,
                pointOut.reinterpret(),
                P256_POINT_BYTES.convert(),
                pointLen.ptr,
            )
        check(status == BCKS_OK) { "Secure Enclave P-256 key generation failed (status $status)" }
        val blob = ByteArray(blobLen.value.toInt()) { blobOut[it] }
        val point = ByteArray(pointLen.value.toInt()) { pointOut[it] }
        EnclaveP256Key(blob, point)
    }

private fun enclaveSignP256(
    blob: ByteArray,
    message: ReadBuffer,
): ByteArray {
    val cap = maxSignatureBytes(SignatureScheme.EcdsaP256)
    return memScoped {
        val sigOut = allocArray<ByteVar>(cap)
        val sigLen = alloc<size_tVar>()
        var status = -1
        val msgLen = message.remaining()
        blob.usePinned { blobPin ->
            message.withRemainingBytes2(msgLen) { msgPtr ->
                status =
                    bcks_secure_enclave_p256_sign(
                        blobPin.addressOf(0).reinterpret(),
                        blob.size.convert(),
                        msgPtr.reinterpret(),
                        msgLen.convert(),
                        sigOut.reinterpret(),
                        cap.convert(),
                        sigLen.ptr,
                    )
            }
        }
        require(status == BCKS_OK) { "Secure Enclave signing failed (status $status)" }
        ByteArray(sigLen.value.toInt()) { sigOut[it] }
    }
}

/**
 * The Secure Enclave provider, resolved once. Returned only when the Enclave is both present
 * ([bcks_secure_enclave_available]) and actually usable — probed by a generate-and-discard, which
 * fails closed on an unentitled/unsigned binary (e.g. the macOS CLI test runner) or the simulator,
 * keeping [CryptoCapabilities.hardware] honestly [HardwareSupport.Unavailable] there.
 */
private val appleProvider: HardwareKeyProvider? by lazy {
    if (bcks_secure_enclave_available() == 0) {
        null
    } else {
        val usable =
            try {
                enclaveGenerateP256()
                true
            } catch (_: Throwable) {
                false
            }
        if (usable) SecureEnclaveHardwareKeyProvider() else null
    }
}

internal actual fun platformHardwareKeyProvider(): HardwareKeyProvider? = appleProvider
