package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer

/*
 * Edge transcoders between the module's canonical EC wire encodings and the other standard
 * interchange formats consumers run into (JOSE/WebAuthn P1363 signatures, OpenSSL/PEM PKCS#8 private
 * keys, X.509 SPKI public keys, SEC1 compressed points).
 *
 * These are pure, synchronous, buffer-in / buffer-out functions that sit at the BOUNDARY: the witness
 * ops always speak the canonical encoding (raw scalar, uncompressed SEC1 point, the platform's ECDSA
 * signature form), and a caller composes a transcoder on either side to interoperate with an external
 * system. Nothing here is cryptographic — it is strict ASN.1/DER plumbing — so it is deliberately
 * separate from the native-or-throw primitive surface.
 *
 * Conventions, matching the rest of the module:
 *  - Inputs are [ReadBuffer], outputs are [ReadBuffer] allocated from a caller-supplied [BufferFactory]
 *    (the work stays in the buffer world; a caller wanting a `ByteArray` passes `BufferFactory.managed()`
 *    or calls `copyToByteArray` on the result). No intermediate `ByteArray` churn.
 *  - Errors are the typed, **string-free** [EcEncodingException] carrying an exhaustive
 *    [EcEncodingError] — callers `when` over the reason; they never parse a message (same contract as
 *    the rest of [CryptoException]).
 *  - Every parser is STRICT (minimal-form lengths, no trailing bytes, range-checked); it consumes
 *    untrusted bytes.
 *
 * Point DECOMPRESSION (compressed -> uncompressed) needs a modular square root over the curve field,
 * which would be hand-rolled crypto; it is provided through the platform's vetted stack as a `suspend`
 * function elsewhere, not here.
 */

/** The exhaustive, non-secret reason an EC transcoder rejected its input. Branch on this, not a string. */
enum class EcEncodingError {
    /** A structural ASN.1/DER error: wrong tag, bad/over-long length, truncation, or trailing bytes. */
    MalformedDer,

    /** A DER INTEGER was not in minimal form (a superfluous leading `0x00`). */
    NonMinimalInteger,

    /** A DER INTEGER had its sign bit set (negative); these encodings carry only positive magnitudes. */
    NegativeInteger,

    /** An integer magnitude was wider than the curve's field. */
    IntegerTooWide,

    /** A P1363 signature was not exactly `2 × field` bytes for the scheme. */
    WrongSignatureLength,

    /** The scheme has no DER/P1363 transcoding (Ed25519 has a single canonical 64-byte encoding). */
    UnsupportedScheme,
}

/**
 * A boundary transcoder rejected structurally-invalid input. Carries a typed [error] (an exhaustive
 * [EcEncodingError]); like the rest of [CryptoMisuseException] it exposes no free-text discriminator.
 */
class EcEncodingException internal constructor(
    val error: EcEncodingError,
) : CryptoMisuseException(error.name)

private fun fail(error: EcEncodingError): Nothing = throw EcEncodingException(error)

// =============================================================================
// Strict, buffer-native DER reader (windows into the source buffer; no copies)
// =============================================================================

private const val DER_SEQUENCE = 0x30
private const val DER_INTEGER = 0x02
private const val DER_LONG_FORM = 0x80
private const val BYTE_MASK = 0xFF
private const val HIGH_BIT = 0x80
private const val BITS_PER_BYTE = 8
private const val MAX_LEN_BYTES = 4

/** An absolute `[start, start+len)` window into a source [ReadBuffer] (no bytes copied). */
private class Window(
    val start: Int,
    val len: Int,
)

/** A cursor over an absolute byte window of [buf], with strict fail-closed DER reads. */
private class DerCursor(
    private val buf: ReadBuffer,
    private var pos: Int,
    private val end: Int,
) {
    val remaining: Int get() = end - pos

    private fun u8(): Int {
        if (pos >= end) fail(EcEncodingError.MalformedDer)
        return buf.get(pos++).toInt() and BYTE_MASK
    }

    private fun readLength(): Int {
        val first = u8()
        if (first < DER_LONG_FORM) return first
        val numBytes = first and HIGH_BIT.inv()
        if (numBytes !in 1..MAX_LEN_BYTES) fail(EcEncodingError.MalformedDer)
        var len = 0
        repeat(numBytes) { len = (len shl BITS_PER_BYTE) or u8() }
        if (len < DER_LONG_FORM || len < 0) fail(EcEncodingError.MalformedDer) // non-minimal / overflow
        return len
    }

    /** Reads a [tag]'d TLV and returns the absolute window of its value, advancing past it. */
    fun element(tag: Int): Window {
        if (u8() != tag) fail(EcEncodingError.MalformedDer)
        val len = readLength()
        if (len > end - pos) fail(EcEncodingError.MalformedDer)
        val w = Window(pos, len)
        pos += len
        return w
    }

    /** A cursor scoped to a child window (e.g. a SEQUENCE's contents). */
    fun scoped(w: Window): DerCursor = DerCursor(buf, w.start, w.start + w.len)

    fun ensureFullyConsumed() {
        if (remaining != 0) fail(EcEncodingError.MalformedDer)
    }
}

/** Strips the single sign byte from a DER INTEGER value [w], returning the minimal positive magnitude window. */
private fun positiveMagnitude(
    buf: ReadBuffer,
    w: Window,
): Window {
    if (w.len == 0) fail(EcEncodingError.MalformedDer)
    val first = buf.get(w.start).toInt() and BYTE_MASK
    if (first and HIGH_BIT != 0) fail(EcEncodingError.NegativeInteger)
    if (w.len > 1 && first == 0) {
        val second = buf.get(w.start + 1).toInt() and BYTE_MASK
        if (second and HIGH_BIT == 0) fail(EcEncodingError.NonMinimalInteger) // superfluous leading zero
        return Window(w.start + 1, w.len - 1)
    }
    return w
}

// =============================================================================
// Buffer-native DER length writing
// =============================================================================

private const val ONE_BYTE_LEN = 0x100

private fun derLengthSize(len: Int): Int =
    when {
        len < DER_LONG_FORM -> 1
        len < ONE_BYTE_LEN -> 2
        else -> 3
    }

private fun writeDerLength(
    dest: WriteBuffer,
    len: Int,
) {
    when {
        len < DER_LONG_FORM -> dest.writeByte(len.toByte())
        len < ONE_BYTE_LEN -> {
            dest.writeByte((DER_LONG_FORM or 1).toByte())
            dest.writeByte(len.toByte())
        }
        else -> {
            dest.writeByte((DER_LONG_FORM or 2).toByte())
            dest.writeByte((len ushr BITS_PER_BYTE).toByte())
            dest.writeByte(len.toByte())
        }
    }
}

/** Copies [len] bytes of [src] starting at absolute [start] into [dest] (small, buffer-to-buffer). */
private fun copyWindow(
    src: ReadBuffer,
    start: Int,
    len: Int,
    dest: WriteBuffer,
) {
    for (i in 0 until len) dest.writeByte(src.get(start + i))
}

// =============================================================================
// ECDSA signature transcoding: DER <-> P1363 (raw r||s)
// =============================================================================

/** The curve field width (bytes) an ECDSA [scheme] signs over; the P1363 half-width. */
private fun ecdsaFieldBytes(scheme: SignatureScheme): Int =
    when (scheme) {
        SignatureScheme.EcdsaP256 -> 32
        SignatureScheme.EcdsaP384 -> 48
        SignatureScheme.EcdsaP521 -> 66
        SignatureScheme.Ed25519 -> fail(EcEncodingError.UnsupportedScheme)
    }

/**
 * Converts a DER ECDSA signature (`SEQUENCE { INTEGER r, INTEGER s }`) to fixed-width P1363
 * (`r ‖ s`, each left-padded to the [scheme] curve's field width) — the form JOSE/JWT/WebAuthn use.
 * Parsed strictly (canonical DER only); the result is allocated from [factory] and read-ready.
 */
fun ecdsaSignatureToP1363(
    scheme: SignatureScheme,
    derSignature: ReadBuffer,
    factory: BufferFactory = BufferFactory.Default,
): ReadBuffer {
    val field = ecdsaFieldBytes(scheme)
    val outer = DerCursor(derSignature, derSignature.position(), derSignature.position() + derSignature.remaining())
    val seq = outer.scoped(outer.element(DER_SEQUENCE))
    outer.ensureFullyConsumed() // no trailing bytes after the SEQUENCE
    val r = positiveMagnitude(derSignature, seq.element(DER_INTEGER))
    val s = positiveMagnitude(derSignature, seq.element(DER_INTEGER))
    seq.ensureFullyConsumed()
    if (r.len > field || s.len > field) fail(EcEncodingError.IntegerTooWide)

    val dest = factory.allocate(field * 2)
    repeat(field - r.len) { dest.writeByte(0) }
    copyWindow(derSignature, r.start, r.len, dest)
    repeat(field - s.len) { dest.writeByte(0) }
    copyWindow(derSignature, s.start, s.len, dest)
    dest.resetForRead()
    return dest
}

/** Scans a fixed-width field of [src] at [fieldStart] for its minimal magnitude window (≥1 byte). */
private fun trimLeadingZeros(
    src: ReadBuffer,
    fieldStart: Int,
    field: Int,
): Window {
    var s = fieldStart
    val lastByte = fieldStart + field - 1
    while (s < lastByte && (src.get(s).toInt() and BYTE_MASK) == 0) s++
    return Window(s, fieldStart + field - s)
}

/** Bytes a minimal positive INTEGER element occupies for a [magnitude] (tag + length + optional pad + value). */
private fun intElementSize(
    src: ReadBuffer,
    magnitude: Window,
): Int {
    val needsPad = src.get(magnitude.start).toInt() and HIGH_BIT != 0
    val contentLen = magnitude.len + if (needsPad) 1 else 0
    return 1 + derLengthSize(contentLen) + contentLen
}

private fun writeIntElement(
    src: ReadBuffer,
    magnitude: Window,
    dest: WriteBuffer,
) {
    val needsPad = src.get(magnitude.start).toInt() and HIGH_BIT != 0
    val contentLen = magnitude.len + if (needsPad) 1 else 0
    dest.writeByte(DER_INTEGER.toByte())
    writeDerLength(dest, contentLen)
    if (needsPad) dest.writeByte(0)
    copyWindow(src, magnitude.start, magnitude.len, dest)
}

/**
 * Converts a fixed-width P1363 ECDSA signature (`r ‖ s`, `2 × field` bytes) to canonical DER
 * (`SEQUENCE { INTEGER r, INTEGER s }`) — the form X.509 / TLS / `openssl` and the native verify
 * paths use. The result is allocated from [factory] and read-ready.
 */
fun ecdsaSignatureToDer(
    scheme: SignatureScheme,
    p1363Signature: ReadBuffer,
    factory: BufferFactory = BufferFactory.Default,
): ReadBuffer {
    val field = ecdsaFieldBytes(scheme)
    if (p1363Signature.remaining() != field * 2) fail(EcEncodingError.WrongSignatureLength)
    val base = p1363Signature.position()
    val r = trimLeadingZeros(p1363Signature, base, field)
    val s = trimLeadingZeros(p1363Signature, base + field, field)

    val body = intElementSize(p1363Signature, r) + intElementSize(p1363Signature, s)
    val dest = factory.allocate(1 + derLengthSize(body) + body)
    dest.writeByte(DER_SEQUENCE.toByte())
    writeDerLength(dest, body)
    writeIntElement(p1363Signature, r, dest)
    writeIntElement(p1363Signature, s, dest)
    dest.resetForRead()
    return dest
}
