package com.ditchoom.buffer.codec.test.protocols.valueclassstring

import com.ditchoom.buffer.codec.annotations.LengthFrom
import com.ditchoom.buffer.codec.annotations.LengthPrefix
import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.RemainingBytes
import com.ditchoom.buffer.codec.annotations.When
import kotlin.jvm.JvmInline

/**
 * Vectors for `@JvmInline value class`-over-`String` fields under each
 * of the three String framings (`@LengthPrefixed`, `@LengthFrom`,
 * `@RemainingBytes`).
 *
 * These id wrappers are the common shape for consumers that give their
 * string identifiers a distinct type (`UserId`, `SessionId`, …) instead
 * of passing bare `String`s around. Each is wire-**identical** to the
 * same field typed as a plain `String` under the same framing — the
 * value class only changes the generated Kotlin (wrap on decode via the
 * primary constructor, unwrap on encode via `.value`), never the bytes.
 * The `*PlainString` twins below exist to prove that byte-identity in a
 * golden test.
 *
 * None of these wrappers is `@ProtocolMessage`: an id wrapper has no
 * codec of its own, so the field-consumption path must accept the plain
 * shape directly.
 */
@JvmInline
value class UserId(
    val value: String,
)

@JvmInline
value class SessionId(
    val value: String,
)

/**
 * A value-class inner property named something other than `value`, to
 * prove the unwrap uses the actual constructor-parameter name rather
 * than a hard-coded `.value`.
 */
@JvmInline
value class TraceId(
    val hex: String,
)

// ---- @LengthPrefixed (default 2-byte big-endian prefix) -----------------

@ProtocolMessage
data class LpValueClassId(
    @LengthPrefixed val id: UserId,
)

/** Byte-identity twin of [LpValueClassId]. */
@ProtocolMessage
data class LpPlainStringId(
    @LengthPrefixed val id: String,
)

// ---- @LengthPrefixed, explicit prefix widths ----------------------------

@ProtocolMessage
data class LpByteValueClassId(
    @LengthPrefixed(LengthPrefix.Byte) val id: UserId,
)

@ProtocolMessage
data class LpIntValueClassId(
    @LengthPrefixed(LengthPrefix.Int) val id: TraceId,
)

// ---- @When (nullable) + @LengthPrefixed value class ---------------------

@ProtocolMessage
data class OptionalValueClassId(
    val hasId: Boolean,
    @When("hasId") @LengthPrefixed val id: UserId? = null,
)

// ---- @LengthFrom value class over String --------------------------------

/**
 * `payload` is sized by the non-adjacent `len` carrier (`@LengthFrom`
 * is reserved for remote-prefix uses, so a `flags` field sits between
 * the carrier and the bound field). The author is responsible for
 * keeping `len` equal to the UTF-8 byte length of `payload.value` (the
 * same contract a plain `@LengthFrom String` field carries).
 */
@ProtocolMessage
data class LengthFromValueClassId(
    val len: UByte,
    val flags: Byte,
    @LengthFrom("len") val payload: UserId,
)

// ---- @RemainingBytes value class over String (terminal) -----------------

@ProtocolMessage
data class RemainingValueClassId(
    val kind: Byte,
    @RemainingBytes val id: SessionId,
)
