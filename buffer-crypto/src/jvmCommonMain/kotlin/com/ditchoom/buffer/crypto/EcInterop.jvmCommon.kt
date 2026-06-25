package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import java.math.BigInteger

/*
 * JVM/Android EC point decompression via java.math.BigInteger (the JDK's vetted big-integer, the same
 * one RSA/DSA use). SunEC rejects compressed points and Conscrypt's support varies by API level, so
 * neither native provider is a portable basis; the math is done here instead. It runs only on the
 * *public* X coordinate (no secret material), so BigInteger's variable-time modPow leaks nothing.
 *
 * The fixed-width `ByteArray` here is the unavoidable BigInteger constructor seam (the same one
 * KeyAgreement.jvmCommon.kt documents); the bytes are the public coordinate, never library data flow.
 */

private val THREE = BigInteger.valueOf(3)
private const val SEC1_UNCOMPRESSED_BYTE: Byte = 0x04

actual fun ecPublicKeyDecompress(
    curve: KeyAgreementCurve,
    compressedPoint: ReadBuffer,
    factory: BufferFactory,
): ReadBuffer {
    val cp = requireCompressedPoint(curve, compressedPoint)
    val field = cp.field
    val p = BigInteger(ecPrimeHex(curve), 16)
    val b = BigInteger(ecBHex(curve), 16)
    val x = readBigEndian(compressedPoint, cp.xStart, field)
    if (x >= p) failPointNotOnCurve() // X must be a reduced field element (matches the native stacks)
    // y^2 = x^3 - 3x + b (mod p); a = -3 on every NIST prime curve.
    val rhs =
        x
            .modPow(THREE, p)
            .subtract(THREE.multiply(x))
            .add(b)
            .mod(p)
    // p ≡ 3 (mod 4) => sqrt(v) = v^((p+1)/4) mod p (a single modular exponentiation).
    val y = rhs.modPow(p.add(BigInteger.ONE).shiftRight(2), p)
    if (y.multiply(y).mod(p) != rhs) failPointNotOnCurve() // x had no square root => not on the curve
    val chosenY = if (y.testBit(0) == cp.wantOdd) y else p.subtract(y)

    val dest = factory.allocate(1 + 2 * field)
    dest.writeByte(SEC1_UNCOMPRESSED_BYTE)
    for (i in 0 until field) dest.writeByte(compressedPoint.get(cp.xStart + i)) // X verbatim
    writeFixedBigEndian(dest, chosenY, field)
    dest.resetForRead()
    return dest
}

/** Positive [BigInteger] from [len] big-endian bytes of [buf] starting at absolute [start]. */
private fun readBigEndian(
    buf: ReadBuffer,
    start: Int,
    len: Int,
): BigInteger = BigInteger(1, ByteArray(len) { buf.get(start + it) })

/** Writes [value] as [len] big-endian bytes into [dest], left-zero-padded (value known to fit). */
private fun writeFixedBigEndian(
    dest: WriteBuffer,
    value: BigInteger,
    len: Int,
) {
    val mag = value.toByteArray() // big-endian, may carry a leading 0x00 sign byte or be shorter
    var offset = 0
    var size = mag.size
    if (size > len && mag[0].toInt() == 0) {
        offset = 1
        size -= 1
    }
    repeat(len - size) { dest.writeByte(0) }
    for (i in 0 until size) dest.writeByte(mag[offset + i])
}
