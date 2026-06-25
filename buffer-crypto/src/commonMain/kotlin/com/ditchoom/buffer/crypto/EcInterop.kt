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

    /** The curve has no encoding for this operation (e.g. X25519 has no SEC1 point form / SPKI point). */
    UnsupportedCurve,

    /** A raw scalar or SEC1 point was not the curve's expected width, or a point had a wrong prefix byte. */
    WrongKeyLength,

    /**
     * A wrapped encoding named a different curve/algorithm than requested: the embedded `namedCurve`
     * OID, or the `id-ecPublicKey` / `id-X25519` / `id-Ed25519` algorithm OID, did not match the
     * algorithm passed in.
     */
    CurveMismatch,

    /**
     * A compressed point's `x` has no square root on the curve — `x³ + a·x + b` is not a quadratic
     * residue mod `p` — so it is not a valid point. (A genuine on-curve check, not a parse error.)
     */
    PointNotOnCurve,
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
private const val DER_BIT_STRING = 0x03
private const val DER_OCTET_STRING = 0x04
private const val DER_OID = 0x06
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
        for (i in 0 until numBytes) {
            val b = u8()
            if (i == 0 && numBytes > 1 && b == 0) fail(EcEncodingError.MalformedDer) // leading-zero length octet
            len = (len shl BITS_PER_BYTE) or b
        }
        if (len < DER_LONG_FORM || len < 0) fail(EcEncodingError.MalformedDer) // long form for < 128 / overflow
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

    /** Reads an INTEGER element and asserts it is the single-byte [expected] value (DER versions). */
    fun expectIntegerValue(expected: Int) {
        val w = element(DER_INTEGER)
        if (w.len != 1 || (buf.get(w.start).toInt() and BYTE_MASK) != expected) fail(EcEncodingError.MalformedDer)
    }

    /** True if the next element is an OID whose value bytes equal [oidValue]; consumes it either way it matches. */
    fun matchOid(oidValue: ByteArray): Boolean {
        val w = element(DER_OID)
        if (w.len != oidValue.size) return false
        for (i in oidValue.indices) if (buf.get(w.start + i) != oidValue[i]) return false
        return true
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

// =============================================================================
// EC key transcoders: curve metadata + generic DER writer helpers
// =============================================================================

// OID *value* bytes (the content of an OID TLV, without the 0x06 tag / length). Fixed non-secret
// templates used both to emit and to match — never as a data structure.
private val OID_ID_EC_PUBLIC_KEY = byteArrayOf(0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x02, 0x01)
private val OID_X25519 = byteArrayOf(0x2b, 0x65, 0x6e) // id-X25519 1.3.101.110
private val OID_ED25519 = byteArrayOf(0x2b, 0x65, 0x70) // id-Ed25519 1.3.101.112

/** Raw key width (bytes) for the RFC 8410 algorithms (Ed25519 seed / public, X25519 scalar / public). */
private const val RFC8410_KEY_BYTES = 32
private val OID_P256 = byteArrayOf(0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x03, 0x01, 0x07)
private val OID_P384 = byteArrayOf(0x2b, 0x81.toByte(), 0x04, 0x00, 0x22)
private val OID_P521 = byteArrayOf(0x2b, 0x81.toByte(), 0x04, 0x00, 0x23)

private const val SEC1_UNCOMPRESSED = 0x04
private const val SEC1_COMPRESSED_EVEN = 0x02
private const val SEC1_COMPRESSED_ODD = 0x03

/** DER INTEGER version element size (`02 01 vv`), used for the fixed `0`/`1` versions in PKCS#8. */
private const val INT_VERSION_TLV = 3

/** Rejects [KeyAgreementCurve.X25519], which has no SEC1 point / NIST-named-curve encoding. */
internal fun requireEcCurve(curve: KeyAgreementCurve) {
    if (curve == KeyAgreementCurve.X25519) fail(EcEncodingError.UnsupportedCurve)
}

/** Coordinate / scalar width in bytes (P-256 32, P-384 48, P-521 66, X25519 32). */
internal fun fieldBytes(curve: KeyAgreementCurve): Int = curve.privateKeyBytes

/** The named-curve OID value bytes for a NIST prime curve. */
private fun namedCurveOid(curve: KeyAgreementCurve): ByteArray =
    when (curve) {
        KeyAgreementCurve.P256 -> OID_P256
        KeyAgreementCurve.P384 -> OID_P384
        KeyAgreementCurve.P521 -> OID_P521
        KeyAgreementCurve.X25519 -> fail(EcEncodingError.UnsupportedCurve)
    }

/** Total bytes a TLV with [contentLen]-byte content occupies (tag + DER length + content). */
private fun tlvSize(contentLen: Int): Int = 1 + derLengthSize(contentLen) + contentLen

private fun writeTlvHeader(
    dest: WriteBuffer,
    tag: Int,
    contentLen: Int,
) {
    dest.writeByte(tag.toByte())
    writeDerLength(dest, contentLen)
}

private fun writeOidElement(
    dest: WriteBuffer,
    oidValue: ByteArray,
) {
    writeTlvHeader(dest, DER_OID, oidValue.size)
    for (b in oidValue) dest.writeByte(b)
}

private fun writeIntVersion(
    dest: WriteBuffer,
    version: Int,
) {
    dest.writeByte(DER_INTEGER.toByte())
    dest.writeByte(1)
    dest.writeByte(version.toByte())
}

// =============================================================================
// EC private key: raw big-endian scalar <-> PKCS#8 (RFC 5208 / RFC 5915 / RFC 8410)
// =============================================================================

/**
 * Wraps a raw big-endian private [rawScalar] (the module's canonical EC private encoding) in a PKCS#8
 * `PrivateKeyInfo` — RFC 5208 around an RFC 5915 `ECPrivateKey` for the NIST curves, or the RFC 8410
 * form for X25519. The byte layout is identical to what each platform's native stack accepts, so the
 * result round-trips with WebCrypto `importKey('pkcs8')` / JCA `PKCS8EncodedKeySpec`. The scalar must
 * be exactly the curve's width ([WrongKeyLength] otherwise). Allocated from [factory], read-ready.
 */
fun ecPrivateKeyToPkcs8(
    curve: KeyAgreementCurve,
    rawScalar: ReadBuffer,
    factory: BufferFactory = BufferFactory.Default,
): ReadBuffer {
    val field = fieldBytes(curve)
    if (rawScalar.remaining() != field) fail(EcEncodingError.WrongKeyLength)
    val scalarStart = rawScalar.position()

    // X25519 is the RFC 8410 raw-key form (id-X25519 + an OCTET STRING-wrapped scalar), shared with Ed25519.
    if (curve == KeyAgreementCurve.X25519) return rawKeyToPkcs8(OID_X25519, rawScalar, factory)

    val named = namedCurveOid(curve)
    val ecPrivContent = INT_VERSION_TLV + tlvSize(field) // version(1) + privateKey OCTET STRING(scalar)
    val ecPriv = tlvSize(ecPrivContent)
    val algIdContent = tlvSize(OID_ID_EC_PUBLIC_KEY.size) + tlvSize(named.size)
    val pkInfoContent = INT_VERSION_TLV + tlvSize(algIdContent) + tlvSize(ecPriv)
    val dest = factory.allocate(tlvSize(pkInfoContent))
    writeTlvHeader(dest, DER_SEQUENCE, pkInfoContent)
    writeIntVersion(dest, 0)
    writeTlvHeader(dest, DER_SEQUENCE, algIdContent)
    writeOidElement(dest, OID_ID_EC_PUBLIC_KEY)
    writeOidElement(dest, named)
    writeTlvHeader(dest, DER_OCTET_STRING, ecPriv) // privateKey OCTET STRING wraps ECPrivateKey
    writeTlvHeader(dest, DER_SEQUENCE, ecPrivContent)
    writeIntVersion(dest, 1)
    writeTlvHeader(dest, DER_OCTET_STRING, field)
    copyWindow(rawScalar, scalarStart, field, dest)
    dest.resetForRead()
    return dest
}

/**
 * Extracts the raw big-endian private scalar from a PKCS#8 `PrivateKeyInfo` for [curve] (the inverse of
 * [ecPrivateKeyToPkcs8]). Parsed strictly: the embedded algorithm and `namedCurve` OIDs must match
 * [curve] ([CurveMismatch] otherwise); optional RFC 5915 `[0]`/`[1]` fields and PKCS#8 attributes are
 * tolerated and ignored. The scalar is left-padded to the curve width. Allocated from [factory].
 */
fun pkcs8ToEcPrivateKey(
    curve: KeyAgreementCurve,
    pkcs8: ReadBuffer,
    factory: BufferFactory = BufferFactory.Default,
): ReadBuffer {
    // X25519 is the RFC 8410 raw-key form (id-X25519, OCTET STRING-wrapped scalar), shared with Ed25519.
    if (curve == KeyAgreementCurve.X25519) return pkcs8ToRawKey(OID_X25519, pkcs8, factory)

    val field = fieldBytes(curve)
    val outer = DerCursor(pkcs8, pkcs8.position(), pkcs8.position() + pkcs8.remaining())
    val info = outer.scoped(outer.element(DER_SEQUENCE))
    outer.ensureFullyConsumed()
    info.expectIntegerValue(0) // PrivateKeyInfo version 0
    val algId = info.scoped(info.element(DER_SEQUENCE))
    if (!algId.matchOid(OID_ID_EC_PUBLIC_KEY)) fail(EcEncodingError.CurveMismatch)
    if (!algId.matchOid(namedCurveOid(curve))) fail(EcEncodingError.CurveMismatch)
    algId.ensureFullyConsumed() // no trailing bytes after id-ecPublicKey + namedCurve
    val ecPriv = info.scoped(info.element(DER_OCTET_STRING)) // privateKey OCTET STRING -> ECPrivateKey
    val ec = ecPriv.scoped(ecPriv.element(DER_SEQUENCE))
    ec.expectIntegerValue(1) // ECPrivateKey version 1
    val scalar = ec.element(DER_OCTET_STRING)
    if (scalar.len > field) fail(EcEncodingError.WrongKeyLength)
    val dest = factory.allocate(field)
    repeat(field - scalar.len) { dest.writeByte(0) } // left-pad a short (non-canonical) scalar
    copyWindow(pkcs8, scalar.start, scalar.len, dest)
    dest.resetForRead()
    return dest
}

// =============================================================================
// EC public key: uncompressed SEC1 point <-> X.509 SubjectPublicKeyInfo (SPKI)
// =============================================================================

/**
 * Wraps an uncompressed SEC1 point (`0x04 ‖ X ‖ Y`) in an X.509 `SubjectPublicKeyInfo`
 * (`id-ecPublicKey` + the [curve] `namedCurve` OID + a BIT STRING of the point) — the form X.509
 * certificates, JWK-to-DER, and `openssl` consume. NIST prime curves only ([UnsupportedCurve] for
 * X25519, whose SPKI carries a raw key, not a point). The point must be the curve's uncompressed
 * width and start with `0x04` ([WrongKeyLength] otherwise). Allocated from [factory], read-ready.
 */
fun ecPublicKeyToSpki(
    curve: KeyAgreementCurve,
    uncompressedPoint: ReadBuffer,
    factory: BufferFactory = BufferFactory.Default,
): ReadBuffer {
    requireEcCurve(curve)
    val field = fieldBytes(curve)
    val pointLen = 1 + 2 * field
    val base = uncompressedPoint.position()
    if (uncompressedPoint.remaining() != pointLen) fail(EcEncodingError.WrongKeyLength)
    if ((uncompressedPoint.get(base).toInt() and BYTE_MASK) != SEC1_UNCOMPRESSED) fail(EcEncodingError.WrongKeyLength)

    val named = namedCurveOid(curve)
    val algIdContent = tlvSize(OID_ID_EC_PUBLIC_KEY.size) + tlvSize(named.size)
    val bitStrContent = 1 + pointLen // leading "unused bits" byte (0x00) + the point
    val spkiContent = tlvSize(algIdContent) + tlvSize(bitStrContent)
    val dest = factory.allocate(tlvSize(spkiContent))
    writeTlvHeader(dest, DER_SEQUENCE, spkiContent)
    writeTlvHeader(dest, DER_SEQUENCE, algIdContent)
    writeOidElement(dest, OID_ID_EC_PUBLIC_KEY)
    writeOidElement(dest, named)
    writeTlvHeader(dest, DER_BIT_STRING, bitStrContent)
    dest.writeByte(0) // 0 unused bits
    copyWindow(uncompressedPoint, base, pointLen, dest)
    dest.resetForRead()
    return dest
}

/**
 * Extracts the uncompressed SEC1 point (`0x04 ‖ X ‖ Y`) from an X.509 `SubjectPublicKeyInfo` for
 * [curve] (the inverse of [ecPublicKeyToSpki]). Strict: the algorithm and `namedCurve` OIDs must match
 * [curve] ([CurveMismatch]); the BIT STRING must have 0 unused bits and carry a full-width uncompressed
 * point ([WrongKeyLength] / [MalformedDer] otherwise). A compressed point inside the SPKI is rejected
 * (decompress it first). Allocated from [factory], read-ready.
 */
fun spkiToEcPublicKey(
    curve: KeyAgreementCurve,
    spki: ReadBuffer,
    factory: BufferFactory = BufferFactory.Default,
): ReadBuffer {
    requireEcCurve(curve)
    val field = fieldBytes(curve)
    val pointLen = 1 + 2 * field
    val outer = DerCursor(spki, spki.position(), spki.position() + spki.remaining())
    val info = outer.scoped(outer.element(DER_SEQUENCE))
    outer.ensureFullyConsumed()
    val algId = info.scoped(info.element(DER_SEQUENCE))
    if (!algId.matchOid(OID_ID_EC_PUBLIC_KEY)) fail(EcEncodingError.CurveMismatch)
    if (!algId.matchOid(namedCurveOid(curve))) fail(EcEncodingError.CurveMismatch)
    algId.ensureFullyConsumed() // no trailing bytes after id-ecPublicKey + namedCurve
    val bits = info.element(DER_BIT_STRING)
    info.ensureFullyConsumed()
    if (bits.len != 1 + pointLen) fail(EcEncodingError.WrongKeyLength)
    if ((spki.get(bits.start).toInt() and BYTE_MASK) != 0) fail(EcEncodingError.MalformedDer) // unused bits
    if ((spki.get(bits.start + 1).toInt() and BYTE_MASK) != SEC1_UNCOMPRESSED) fail(EcEncodingError.WrongKeyLength)
    val dest = factory.allocate(pointLen)
    copyWindow(spki, bits.start + 1, pointLen, dest)
    dest.resetForRead()
    return dest
}

// =============================================================================
// RFC 8410 raw-key keys: Ed25519 / X25519 <-> PKCS#8 (private) and SPKI (public)
// =============================================================================
//
// Ed25519 (id-Ed25519) and X25519 (id-X25519) wrap a single canonical 32-byte raw key — no field
// math, no SEC1 point, one encoding everywhere — so these are pure commonMain (no per-platform paths).
// The private (PKCS#8) and public (SPKI) shapes are identical for both algorithms apart from the OID;
// the only difference from the NIST-curve forms is that there is no `namedCurve` parameter and the
// public key is the raw key (not an `0x04 ‖ X ‖ Y` point) inside the BIT STRING.

/** RFC 8410 PKCS#8: `SEQ { INTEGER 0, SEQ { oid }, OCTET STRING ( OCTET STRING(rawKey) ) }`. */
private fun rawKeyToPkcs8(
    oid: ByteArray,
    rawKey: ReadBuffer,
    factory: BufferFactory,
): ReadBuffer {
    if (rawKey.remaining() != RFC8410_KEY_BYTES) fail(EcEncodingError.WrongKeyLength)
    val start = rawKey.position()
    val innerOctet = tlvSize(RFC8410_KEY_BYTES) // CurvePrivateKey ::= OCTET STRING (rawKey)
    val pkOctet = tlvSize(innerOctet) // privateKey OCTET STRING wraps the CurvePrivateKey
    val algIdContent = tlvSize(oid.size)
    val pkInfoContent = INT_VERSION_TLV + tlvSize(algIdContent) + pkOctet
    val dest = factory.allocate(tlvSize(pkInfoContent))
    writeTlvHeader(dest, DER_SEQUENCE, pkInfoContent)
    writeIntVersion(dest, 0)
    writeTlvHeader(dest, DER_SEQUENCE, algIdContent)
    writeOidElement(dest, oid)
    writeTlvHeader(dest, DER_OCTET_STRING, innerOctet)
    writeTlvHeader(dest, DER_OCTET_STRING, RFC8410_KEY_BYTES)
    copyWindow(rawKey, start, RFC8410_KEY_BYTES, dest)
    dest.resetForRead()
    return dest
}

/** Strict inverse of [rawKeyToPkcs8]: version 0, [oid] match (no params), 32-byte inner OCTET STRING. */
private fun pkcs8ToRawKey(
    oid: ByteArray,
    pkcs8: ReadBuffer,
    factory: BufferFactory,
): ReadBuffer {
    val outer = DerCursor(pkcs8, pkcs8.position(), pkcs8.position() + pkcs8.remaining())
    val info = outer.scoped(outer.element(DER_SEQUENCE))
    outer.ensureFullyConsumed()
    info.expectIntegerValue(0) // PrivateKeyInfo version 0
    val algId = info.scoped(info.element(DER_SEQUENCE))
    if (!algId.matchOid(oid)) fail(EcEncodingError.CurveMismatch)
    algId.ensureFullyConsumed() // RFC 8410 AlgorithmIdentifier has no parameters
    val pkOctet = info.scoped(info.element(DER_OCTET_STRING))
    val key = pkOctet.element(DER_OCTET_STRING) // CurvePrivateKey OCTET STRING(rawKey)
    if (key.len != RFC8410_KEY_BYTES) fail(EcEncodingError.WrongKeyLength)
    val dest = factory.allocate(RFC8410_KEY_BYTES)
    copyWindow(pkcs8, key.start, RFC8410_KEY_BYTES, dest)
    dest.resetForRead()
    return dest
}

/** RFC 8410 SPKI: `SEQ { SEQ { oid }, BIT STRING ( 0x00 ‖ rawKey ) }` (raw key, no point prefix). */
private fun rawKeyToSpki(
    oid: ByteArray,
    rawKey: ReadBuffer,
    factory: BufferFactory,
): ReadBuffer {
    if (rawKey.remaining() != RFC8410_KEY_BYTES) fail(EcEncodingError.WrongKeyLength)
    val start = rawKey.position()
    val algIdContent = tlvSize(oid.size)
    val bitStrContent = 1 + RFC8410_KEY_BYTES // leading "unused bits" byte (0x00) + the raw key
    val spkiContent = tlvSize(algIdContent) + tlvSize(bitStrContent)
    val dest = factory.allocate(tlvSize(spkiContent))
    writeTlvHeader(dest, DER_SEQUENCE, spkiContent)
    writeTlvHeader(dest, DER_SEQUENCE, algIdContent)
    writeOidElement(dest, oid)
    writeTlvHeader(dest, DER_BIT_STRING, bitStrContent)
    dest.writeByte(0) // 0 unused bits
    copyWindow(rawKey, start, RFC8410_KEY_BYTES, dest)
    dest.resetForRead()
    return dest
}

/** Strict inverse of [rawKeyToSpki]: [oid] match (no params), BIT STRING with 0 unused bits + 32 bytes. */
private fun spkiToRawKey(
    oid: ByteArray,
    spki: ReadBuffer,
    factory: BufferFactory,
): ReadBuffer {
    val outer = DerCursor(spki, spki.position(), spki.position() + spki.remaining())
    val info = outer.scoped(outer.element(DER_SEQUENCE))
    outer.ensureFullyConsumed()
    val algId = info.scoped(info.element(DER_SEQUENCE))
    if (!algId.matchOid(oid)) fail(EcEncodingError.CurveMismatch)
    algId.ensureFullyConsumed()
    val bits = info.element(DER_BIT_STRING)
    info.ensureFullyConsumed()
    if (bits.len != 1 + RFC8410_KEY_BYTES) fail(EcEncodingError.WrongKeyLength)
    if ((spki.get(bits.start).toInt() and BYTE_MASK) != 0) fail(EcEncodingError.MalformedDer) // unused bits
    val dest = factory.allocate(RFC8410_KEY_BYTES)
    copyWindow(spki, bits.start + 1, RFC8410_KEY_BYTES, dest)
    dest.resetForRead()
    return dest
}

/**
 * Wraps a raw 32-byte Ed25519 private seed in an RFC 8410 PKCS#8 `OneAsymmetricKey` (the form
 * OpenSSL / PEM / WebCrypto consume). The seed must be 32 bytes ([WrongKeyLength] otherwise).
 * Allocated from [factory], read-ready.
 */
fun ed25519PrivateKeyToPkcs8(
    rawSeed: ReadBuffer,
    factory: BufferFactory = BufferFactory.Default,
): ReadBuffer = rawKeyToPkcs8(OID_ED25519, rawSeed, factory)

/** Extracts the raw 32-byte Ed25519 seed from an RFC 8410 PKCS#8 (the inverse of [ed25519PrivateKeyToPkcs8]). */
fun pkcs8ToEd25519PrivateKey(
    pkcs8: ReadBuffer,
    factory: BufferFactory = BufferFactory.Default,
): ReadBuffer = pkcs8ToRawKey(OID_ED25519, pkcs8, factory)

/** Wraps a raw 32-byte Ed25519 public key in an RFC 8410 X.509 `SubjectPublicKeyInfo`. */
fun ed25519PublicKeyToSpki(
    rawPublicKey: ReadBuffer,
    factory: BufferFactory = BufferFactory.Default,
): ReadBuffer = rawKeyToSpki(OID_ED25519, rawPublicKey, factory)

/** Extracts the raw 32-byte Ed25519 public key from an RFC 8410 SPKI (the inverse of [ed25519PublicKeyToSpki]). */
fun spkiToEd25519PublicKey(
    spki: ReadBuffer,
    factory: BufferFactory = BufferFactory.Default,
): ReadBuffer = spkiToRawKey(OID_ED25519, spki, factory)

/**
 * Wraps a raw 32-byte X25519 public key in an RFC 8410 X.509 `SubjectPublicKeyInfo`. (The X25519
 * *private* key is handled by [ecPrivateKeyToPkcs8] / [pkcs8ToEcPrivateKey], which take its
 * [KeyAgreementCurve].)
 */
fun x25519PublicKeyToSpki(
    rawPublicKey: ReadBuffer,
    factory: BufferFactory = BufferFactory.Default,
): ReadBuffer = rawKeyToSpki(OID_X25519, rawPublicKey, factory)

/** Extracts the raw 32-byte X25519 public key from an RFC 8410 SPKI (the inverse of [x25519PublicKeyToSpki]). */
fun spkiToX25519PublicKey(
    spki: ReadBuffer,
    factory: BufferFactory = BufferFactory.Default,
): ReadBuffer = spkiToRawKey(OID_X25519, spki, factory)

// =============================================================================
// EC public key: point compression (pure) and decompression (per-platform bignum / native stack)
// =============================================================================

/**
 * Compresses an uncompressed SEC1 point (`0x04 ‖ X ‖ Y`) to the compressed form (`0x02/0x03 ‖ X`),
 * where the prefix encodes the parity of Y (`0x02` even, `0x03` odd). Pure — drops Y, no field math.
 * NIST prime curves only ([UnsupportedCurve] for X25519). The point must be the curve's uncompressed
 * width and start with `0x04` ([WrongKeyLength] otherwise). Allocated from [factory], read-ready.
 */
fun ecPublicKeyCompress(
    curve: KeyAgreementCurve,
    uncompressedPoint: ReadBuffer,
    factory: BufferFactory = BufferFactory.Default,
): ReadBuffer {
    requireEcCurve(curve)
    val field = fieldBytes(curve)
    val pointLen = 1 + 2 * field
    val base = uncompressedPoint.position()
    if (uncompressedPoint.remaining() != pointLen) fail(EcEncodingError.WrongKeyLength)
    if ((uncompressedPoint.get(base).toInt() and BYTE_MASK) != SEC1_UNCOMPRESSED) fail(EcEncodingError.WrongKeyLength)
    val yOdd = (uncompressedPoint.get(base + pointLen - 1).toInt() and 1) == 1
    val dest = factory.allocate(1 + field)
    dest.writeByte((if (yOdd) SEC1_COMPRESSED_ODD else SEC1_COMPRESSED_EVEN).toByte())
    copyWindow(uncompressedPoint, base + 1, field, dest) // X
    dest.resetForRead()
    return dest
}

/** A validated compressed SEC1 point: its X coordinate window and the required parity of Y. */
internal class CompressedPoint(
    val field: Int,
    /** Absolute index of the X coordinate's first byte within the source buffer. */
    val xStart: Int,
    /** Whether the recovered Y must be odd (prefix `0x03`) rather than even (`0x02`). */
    val wantOdd: Boolean,
)

/** Validates a compressed SEC1 point for [curve] (prefix `0x02/0x03`, exact `1 + field` length). */
internal fun requireCompressedPoint(
    curve: KeyAgreementCurve,
    compressedPoint: ReadBuffer,
): CompressedPoint {
    requireEcCurve(curve)
    val field = fieldBytes(curve)
    val base = compressedPoint.position()
    if (compressedPoint.remaining() != 1 + field) fail(EcEncodingError.WrongKeyLength)
    val wantOdd =
        when (compressedPoint.get(base).toInt() and BYTE_MASK) {
            SEC1_COMPRESSED_EVEN -> false
            SEC1_COMPRESSED_ODD -> true
            else -> fail(EcEncodingError.WrongKeyLength)
        }
    return CompressedPoint(field, base + 1, wantOdd)
}

/** Throws the typed "point has no square root on the curve" error from a platform decompressor. */
internal fun failPointNotOnCurve(): Nothing = fail(EcEncodingError.PointNotOnCurve)

// Domain parameters for the bignum decompressors (JVM/Android via BigInteger, JS/WASM via BigInt).
// The NIST prime curves all have a = p - 3; the recovered Y solves y^2 = x^3 - 3x + b (mod p). All
// three primes are p ≡ 3 (mod 4), so sqrt(v) = v^((p+1)/4) mod p — a single modular exponentiation.
// Big-endian hex, no 0x prefix. Apple/Linux do not use these (they delegate to the native stack).

/** Big-endian hex of the field prime `p` for a NIST prime [curve]. */
internal fun ecPrimeHex(curve: KeyAgreementCurve): String =
    when (curve) {
        KeyAgreementCurve.P256 ->
            "ffffffff00000001000000000000000000000000ffffffffffffffffffffffff"
        KeyAgreementCurve.P384 ->
            "fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffe" +
                "ffffffff0000000000000000ffffffff"
        KeyAgreementCurve.P521 ->
            "01" + "ff".repeat(65)
        KeyAgreementCurve.X25519 -> fail(EcEncodingError.UnsupportedCurve)
    }

/** Big-endian hex of the curve constant `b` for a NIST prime [curve]. */
internal fun ecBHex(curve: KeyAgreementCurve): String =
    when (curve) {
        KeyAgreementCurve.P256 ->
            "5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b"
        KeyAgreementCurve.P384 ->
            "b3312fa7e23ee7e4988e056be3f82d19181d9c6efe8141120314088f5013875a" +
                "c656398d8a2ed19d2a85c8edd3ec2aef"
        KeyAgreementCurve.P521 ->
            "0051953eb9618e1c9a1f929a21a0b68540eea2da725b99b315f3b8b489918ef1" +
                "09e156193951ec7e937b1652c0bd3bb1bf073573df883d2c34f1ef451fd46b503f00"
        KeyAgreementCurve.X25519 -> fail(EcEncodingError.UnsupportedCurve)
    }

/**
 * Recovers the uncompressed SEC1 point (`0x04 ‖ X ‖ Y`) from a compressed point (`0x02/0x03 ‖ X`) for a
 * NIST prime [curve] — the inverse of [ecPublicKeyCompress]. Decompression solves `y² = x³ - 3x + b`
 * over the curve field and selects Y by the prefix parity; an X with no square root is rejected with
 * [PointNotOnCurve], so the result is always a genuine on-curve point.
 *
 * Uniformly available on every platform (no capability gap): JVM/Android compute it with
 * `java.math.BigInteger`, JS/WASM with the host `BigInt`, Apple/Linux through the native CryptoKit /
 * BoringSSL stack. The math runs only on the *public* X coordinate (no secret), so a variable-time
 * implementation leaks nothing. X25519 has no compressed form ([UnsupportedCurve]); a wrong length or
 * prefix is [WrongKeyLength]. Allocated from [factory], read-ready.
 */
expect fun ecPublicKeyDecompress(
    curve: KeyAgreementCurve,
    compressedPoint: ReadBuffer,
    factory: BufferFactory = BufferFactory.Default,
): ReadBuffer
