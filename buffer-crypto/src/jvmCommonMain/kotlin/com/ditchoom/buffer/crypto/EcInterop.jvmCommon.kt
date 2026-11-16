package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import java.math.BigInteger
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import javax.crypto.KeyAgreement

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

/*
 * Public point from a private scalar. JCA has no public "d·G" API, so the scalar multiplication is
 * done by the provider's own constant-time code paths: an ECDH of the private key against the
 * generator yields X = x(d·G); Y is then one of the two square roots (public math, as above), and the
 * provider's ECDSA picks which: a signature made with the private key verifies under exactly one of
 * the two candidate points. Only the public coordinates are handled with BigInteger arithmetic.
 */

private val PARITY_PROBE_MESSAGE = "ec public key from scalar".encodeToByteArray()

internal actual suspend fun ecPublicPointFromValidScalar(
    curve: KeyAgreementCurve,
    scalar: ReadBuffer,
    factory: BufferFactory,
): ReadBuffer {
    val field = fieldBytes(curve)
    val params = ecParams(curve)
    val keys = KeyFactory.getInstance("EC")
    val scalarBytes = ByteArray(field) { scalar.get(scalar.position() + it) }
    val privateKey =
        try {
            keys.generatePrivate(ECPrivateKeySpec(BigInteger(1, scalarBytes), params))
        } finally {
            scalarBytes.fill(0)
        }
    val generator = keys.generatePublic(ECPublicKeySpec(params.generator, params))
    val x =
        KeyAgreement.getInstance("ECDH").run {
            init(privateKey)
            doPhase(generator, true)
            BigInteger(1, generateSecret())
        }
    val p = BigInteger(ecPrimeHex(curve), 16)
    val rhs =
        x
            .modPow(THREE, p)
            .subtract(THREE.multiply(x))
            .add(BigInteger(ecBHex(curve), 16))
            .mod(p)
    val root = rhs.modPow(p.add(BigInteger.ONE).shiftRight(2), p)
    check(root.multiply(root).mod(p) == rhs) { "ECDH against the generator returned an off-curve X" }
    val signature =
        Signature.getInstance(PARITY_PROBE_ALGORITHM).run {
            initSign(privateKey)
            update(PARITY_PROBE_MESSAGE)
            sign()
        }
    val y =
        listOf(root, p.subtract(root)).firstOrNull { candidate ->
            verifiesUnder(keys, params, ECPoint(x, candidate), signature)
        } ?: error("neither square root verified the private key's signature")

    val dest = factory.allocate(1 + 2 * field)
    dest.writeByte(SEC1_UNCOMPRESSED_BYTE)
    writeFixedBigEndian(dest, x, field)
    writeFixedBigEndian(dest, y, field)
    dest.resetForRead()
    return dest
}

private const val PARITY_PROBE_ALGORITHM = "SHA256withECDSA"

private fun verifiesUnder(
    keys: KeyFactory,
    params: ECParameterSpec,
    point: ECPoint,
    signature: ByteArray,
): Boolean =
    Signature.getInstance(PARITY_PROBE_ALGORITHM).run {
        initVerify(keys.generatePublic(ECPublicKeySpec(point, params)))
        update(PARITY_PROBE_MESSAGE)
        verify(signature)
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
