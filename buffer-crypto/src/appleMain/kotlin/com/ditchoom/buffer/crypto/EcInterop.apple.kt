@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_ERR_INPUT
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_OK
import com.ditchoom.buffer.crypto.cinterop.cryptokit.bcks_ec_decompress
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import platform.posix.size_tVar

/*
 * Apple EC point decompression via the CryptoKit shim (P###.KeyAgreement.PublicKey
 * compressedRepresentation -> x963Representation). CryptoKit's compressed-point support starts at
 * macOS 13 / iOS 16 / watchOS 9 / tvOS 16; on an older OS the shim reports BCKS_ERR_INTERNAL and this
 * surfaces UnsupportedOperationException (the same capability-floor pattern the X25519 path uses on
 * older Android). The peer X is public — no secret material.
 */

private const val P256_BITS = 256
private const val P384_BITS = 384
private const val P521_BITS = 521

private fun curveBits(curve: KeyAgreementCurve): Int =
    when (curve) {
        KeyAgreementCurve.P256 -> P256_BITS
        KeyAgreementCurve.P384 -> P384_BITS
        KeyAgreementCurve.P521 -> P521_BITS
        KeyAgreementCurve.X25519 -> error("X25519 has no compressed point form") // requireCompressedPoint rejected it
    }

actual fun ecPublicKeyDecompress(
    curve: KeyAgreementCurve,
    compressedPoint: ReadBuffer,
    factory: BufferFactory,
): ReadBuffer {
    val cp = requireCompressedPoint(curve, compressedPoint)
    val outCap = 1 + 2 * cp.field
    return memScoped {
        val outBuf = allocArray<ByteVar>(outCap)
        val outLen = alloc<size_tVar>()
        var status = BCKS_ERR_INPUT
        compressedPoint.withRemainingBytes { ptr, len ->
            status =
                bcks_ec_decompress(
                    curveBits(curve),
                    ptr.reinterpret(),
                    len.convert(),
                    outBuf.reinterpret(),
                    outCap.convert(),
                    outLen.ptr,
                )
        }
        when (status) {
            BCKS_OK -> Unit
            BCKS_ERR_INPUT -> failPointNotOnCurve()
            else -> throw UnsupportedOperationException(
                "EC point decompression requires macOS 13 / iOS 16 / watchOS 9 / tvOS 16 or newer",
            )
        }
        val dest = factory.allocate(outCap)
        for (i in 0 until outCap) dest.writeByte(outBuf[i])
        dest.resetForRead()
        dest
    }
}
