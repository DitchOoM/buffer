package com.ditchoom.buffer.codec.test.protocols.versioning

import com.ditchoom.buffer.codec.annotations.Endianness
import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.SinceVersion
import kotlin.jvm.JvmInline

/**
 * A registration handshake that grew trailing fields across three revisions — the canonical
 * `@SinceVersion` shape, modelled on the real-world pattern that motivated the annotation: a
 * struct appends fields with Kotlin defaults on the belief that older producers still decode.
 *
 * Without `@SinceVersion`, a Kotlin default is invisible to the generated decoder (the
 * constructor call always passes every argument), so a v1 frame throws partway through decode.
 * These fixtures pin the opposite: a v1 frame decodes, and the absent fields hold their defaults.
 */
@JvmInline
@ProtocolMessage(wireOrder = Endianness.Big)
value class BuildNumber(
    val value: Int,
)

/** Runner lifecycle state — an enum trailing field, whose ordinal rides as a LEB128 varint. */
enum class RegisterMode {
    Standard,
    Restricted,
}

/**
 * v1 shape: the three original fields, all required. Kept as a separate declaration so the
 * tests can encode a genuine old-producer frame rather than simulating one by truncation
 * alone — truncation proves the decoder tolerates short input, but only a real v1 encode
 * proves the two shapes agree on the bytes they share.
 */
@ProtocolMessage(wireOrder = Endianness.Big)
data class RegisterV1(
    @LengthPrefixed val hostname: String,
    val pid: Int,
    val secure: Boolean,
)

/**
 * v3 shape: the same three required fields, plus three optional trailing ones added over two
 * later revisions. Byte-compatible with [RegisterV1] for its first three fields.
 *
 * The three optional fields deliberately cover all three supported minimum-width shapes:
 * a plain scalar, a value class over a scalar, and an enum.
 */
@ProtocolMessage(wireOrder = Endianness.Big)
data class RegisterV3(
    @LengthPrefixed val hostname: String,
    val pid: Int,
    val secure: Boolean,
    @SinceVersion(2) val retries: Int = DEFAULT_RETRIES,
    @SinceVersion(2) val build: BuildNumber = BuildNumber(0),
    @SinceVersion(3) val mode: RegisterMode = RegisterMode.Standard,
) {
    companion object {
        const val DEFAULT_RETRIES: Int = 3
    }
}

/**
 * A length-framed carrier for [RegisterV3]. `@LengthPrefixed` on a nested `@ProtocolMessage` is
 * terminal-only, so the optional-trailing message rides last — which is also the only position
 * where its extent can be bounded. This is the shape the boundedness rule permits; the rejected
 * shape (a bare nested field with siblings after it) is pinned by a processor compile test,
 * because it must fail the build rather than produce a decodable frame.
 */
@ProtocolMessage(wireOrder = Endianness.Big)
data class RegisterEnvelope(
    val sequence: Int,
    @LengthPrefixed val register: RegisterV3,
)
