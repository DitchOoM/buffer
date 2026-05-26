package com.ditchoom.buffer.codec.test.protocols.batch

import com.ditchoom.buffer.codec.annotations.Endianness
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.When
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
