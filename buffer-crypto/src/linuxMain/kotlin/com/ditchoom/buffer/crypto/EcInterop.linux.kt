@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.crypto.cinterop.boringssl.BCL_OK
import com.ditchoom.buffer.crypto.cinterop.boringssl.bcl_ec_decompress
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import platform.posix.size_tVar

/*
 * Linux EC point decompression via BoringSSL (EC_POINT_oct2point recovers Y from the compressed
 * encoding and validates on-curve — see bcl_ec_decompress in boringsslcrypto.def). The peer X is
 * public, so no secret material is involved.
 */

private const val P256_CURVE_CODE = 256
private const val P384_CURVE_CODE = 384
private const val P521_CURVE_CODE = 521

private fun nistCurveCode(curve: KeyAgreementCurve): Int =
    when (curve) {
        KeyAgreementCurve.P256 -> P256_CURVE_CODE
        KeyAgreementCurve.P384 -> P384_CURVE_CODE
        KeyAgreementCurve.P521 -> P521_CURVE_CODE
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
        var status = -1
        compressedPoint.withRemainingBytes { ptr, len ->
            status =
                bcl_ec_decompress(
                    nistCurveCode(curve),
                    ptr.reinterpret(),
                    len.convert(),
                    outBuf.reinterpret(),
                    outCap.convert(),
                    outLen.ptr,
                )
        }
        if (status != BCL_OK || outLen.value.toInt() != outCap) failPointNotOnCurve()
        val dest = factory.allocate(outCap)
        for (i in 0 until outCap) dest.writeByte(outBuf[i])
        dest.resetForRead()
        dest
    }
}
