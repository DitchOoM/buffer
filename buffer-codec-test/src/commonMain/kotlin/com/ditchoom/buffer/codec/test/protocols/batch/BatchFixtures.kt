package com.ditchoom.buffer.codec.test.protocols.batch

import com.ditchoom.buffer.codec.annotations.Endianness
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.When
import com.ditchoom.buffer.codec.annotations.WireOrder
import kotlin.jvm.JvmInline

@ProtocolMessage
data class FourUBytes(
    val a: UByte,
    val b: UByte,
    val c: UByte,
    val d: UByte,
)

@ProtocolMessage
data class EightUBytes(
    val a: UByte,
    val b: UByte,
    val c: UByte,
    val d: UByte,
    val e: UByte,
    val f: UByte,
    val g: UByte,
    val h: UByte,
)

@ProtocolMessage
data class MixedNaturalScalars(
    val flags: UByte,
    val tag: UByte,
    val length: UShort,
    val checksum: UInt,
)

@JvmInline
@ProtocolMessage
value class HeaderByte(
    val raw: UByte,
)

@ProtocolMessage
data class ValueClassBatch(
    val header: HeaderByte,
    val tail: HeaderByte,
)

@ProtocolMessage
data class ConditionalBreaksBatch(
    val header: UByte,
    val hasExtra: Boolean,
    @When("hasExtra") val extra: UByte? = null,
    val trailer: UByte,
)

@ProtocolMessage
data class SignedAndUnsignedMix(
    val signed: Byte,
    val unsigned: UByte,
    val signedShort: Short,
)

// ─────────────────────────────────────────────────────────────────────
// Case-2 (same-shared-order) coverage. Without these fixtures the
// batching gate's behaviour on `@ProtocolMessage(wireOrder = Big|Little)`
// is uncovered — the v5 worktree port silently skipped batching on
// every real TCP/IP/TLS-style protocol because they all set an explicit
// wire order. These exercise both the explicit-Big and explicit-Little
// paths under both BIG_ENDIAN and LITTLE_ENDIAN buffer orders.

@ProtocolMessage(wireOrder = Endianness.Big)
data class BigHeader(
    val type: UByte,
    val version: UByte,
    val flags: UShort,
    val length: UInt,
)

@ProtocolMessage(wireOrder = Endianness.Little)
data class LittleHeader(
    val type: UByte,
    val version: UByte,
    val flags: UShort,
    val length: UInt,
)

// ─────────────────────────────────────────────────────────────────────
// Mixed-wire-order gate coverage. The coalescer must flush when adjacent
// batchable fields resolve to different wire orders. Two cases:
//
//   1. Plain Scalar with @WireOrder override inside an opposite-order
//      parent (MixedOrderFlush).
//   2. ValueClassScalar whose own @ProtocolMessage(wireOrder) differs
//      from the parent's order (MixedOrderValueClass).
//
// Both fixtures place batchable fields adjacent to each other such that
// a wrong gate (e.g. comparing on the parent's order only, ignoring
// per-field/per-value-class overrides) would emit one bulk read at the
// wrong order — wire-byte assertions would then fail because the middle
// field's bytes come out byte-swapped relative to the spec layout.

@JvmInline
@ProtocolMessage(wireOrder = Endianness.Little)
value class LittleTag(
    val raw: UShort,
)

/**
 * Parent declares Big; middle field overrides to Little via field-level
 * `@WireOrder`. Expected coalescer behaviour: three separate emits, one
 * per field. A bug that batched all three at the parent's order would
 * encode `middle` as big-endian, failing the wire-byte assertion.
 */
@ProtocolMessage(wireOrder = Endianness.Big)
data class MixedOrderFlush(
    val leadingBig: UShort,
    @WireOrder(Endianness.Little) val middleLittle: UShort,
    val trailingBig: UInt,
)

/**
 * Parent declares Big; the value-class field's own @ProtocolMessage
 * declares Little. The coalescer reads each part's `wireOrder` via
 * `field.valueClassWireOrder` (for ValueClassScalar) versus
 * `field.resolvedWireOrder` (for plain Scalar). If those mismatch
 * across adjacent fields, the gate must flush — this fixture pins
 * that behaviour.
 */
@ProtocolMessage(wireOrder = Endianness.Big)
data class MixedOrderValueClass(
    val leadingBig: UShort,
    val littleTag: LittleTag,
    val trailingBig: UInt,
)

/**
 * Partial-batching pinning: parent Big with two Big fields adjacent,
 * followed by a Little-override field. The coalescer must batch the
 * first two (same Big order, 4 bytes total → readInt) and emit the
 * third individually. Wire-byte assertion catches both directions: a
 * gate that flushes too aggressively (no batch) would change the
 * snapshot but pass round-trip; a gate that batches across the order
 * boundary would fail byte-level checks.
 */
@ProtocolMessage(wireOrder = Endianness.Big)
data class MixedOrderPartialBatch(
    val bigA: UShort,
    val bigB: UShort,
    @WireOrder(Endianness.Little) val trailingLittle: UInt,
)
