package com.ditchoom.buffer.codec.processor

import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.BYTE
import com.squareup.kotlinpoet.DOUBLE
import com.squareup.kotlinpoet.FLOAT
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.LONG
import com.squareup.kotlinpoet.SHORT
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.U_BYTE
import com.squareup.kotlinpoet.U_INT
import com.squareup.kotlinpoet.U_LONG
import com.squareup.kotlinpoet.U_SHORT

/*
 * Pure `ScalarKind` / `WireWidth` utilities for [CodecEmitter] — kind→type / kind→
 * read-expr / kind→range mappings, the `@DispatchValue` Int-coercion, and the
 * wire-width arithmetic. Extracted verbatim from `CodecEmitter` as the second step
 * of the incremental split; all are pure functions of their arguments (no emitter
 * state), so the unqualified / extension call sites resolve unchanged and codegen
 * is byte-identical (verified by the snapshot suite).
 */

internal fun scalarTypeName(kind: ScalarKind): TypeName =
    when (kind) {
        ScalarKind.Boolean -> BOOLEAN
        ScalarKind.UByte -> U_BYTE
        ScalarKind.UShort -> U_SHORT
        ScalarKind.UInt -> U_INT
        ScalarKind.ULong -> U_LONG
        ScalarKind.Byte -> BYTE
        ScalarKind.Short -> SHORT
        ScalarKind.Int -> INT
        ScalarKind.Long -> LONG
        ScalarKind.Float -> FLOAT
        ScalarKind.Double -> DOUBLE
    }

/**
 * Read expression for a natural-width scalar. Used by
 * the conditional emit path (which needs an expression, not a statement)
 * and by the existing non-conditional decode (refactored to share).
 */
internal fun naturalScalarReadExpr(kind: ScalarKind): String =
    when (kind) {
        ScalarKind.Boolean -> "buffer.readByte() != 0.toByte()"
        ScalarKind.UByte -> "buffer.readUByte()"
        ScalarKind.UShort -> "buffer.readUShort()"
        ScalarKind.UInt -> "buffer.readUInt()"
        ScalarKind.ULong -> "buffer.readULong()"
        ScalarKind.Byte -> "buffer.readByte()"
        ScalarKind.Short -> "buffer.readShort()"
        ScalarKind.Int -> "buffer.readInt()"
        ScalarKind.Long -> "buffer.readLong()"
        ScalarKind.Float -> "buffer.readFloat()"
        ScalarKind.Double -> "buffer.readDouble()"
    }

internal fun dispatchValuePacketTypeRange(kind: ScalarKind): IntRange =
    when (kind) {
        ScalarKind.Boolean -> 0..1
        ScalarKind.Byte -> Byte.MIN_VALUE.toInt()..Byte.MAX_VALUE.toInt()
        ScalarKind.UByte -> 0..0xFF
        ScalarKind.Short -> Short.MIN_VALUE.toInt()..Short.MAX_VALUE.toInt()
        ScalarKind.UShort -> 0..0xFFFF
        ScalarKind.Int -> Int.MIN_VALUE..Int.MAX_VALUE
        ScalarKind.UInt -> 0..Int.MAX_VALUE
        ScalarKind.Long, ScalarKind.ULong, ScalarKind.Float, ScalarKind.Double ->
            error("Long / ULong / Float / Double are not in DISPATCH_VALUE_RETURN_KINDS — analyze should have rejected this kind")
    }

/**
 * Slice — Int-coercion for an `@DispatchValue`
 * property's runtime value, lifting it into the `Int` domain that
 * the dispatcher's `when (__dispatchValue)` branches use. Int
 * returns flow through unchanged, Boolean lifts to a 0/1 ternary,
 * the other primitive numeric kinds use `.toInt()` (sign-extending
 * for Byte / Short, zero-extending for UByte / UShort / UInt).
 * Long / ULong are unreachable — `DISPATCH_VALUE_RETURN_KINDS`
 * filters them out at analyze time.
 */
internal fun dispatchValueIntCoercion(
    kind: ScalarKind,
    propertyAccess: String,
): String =
    when (kind) {
        ScalarKind.Int -> propertyAccess
        ScalarKind.Boolean -> "if ($propertyAccess) 1 else 0"
        ScalarKind.Byte, ScalarKind.UByte,
        ScalarKind.Short, ScalarKind.UShort,
        ScalarKind.UInt,
        -> "$propertyAccess.toInt()"
        ScalarKind.Long, ScalarKind.ULong, ScalarKind.Float, ScalarKind.Double ->
            error("Long / ULong / Float / Double are not in DISPATCH_VALUE_RETURN_KINDS — analyze should have rejected this kind")
    }

/**
 * Additive fold over WireWidth. Two Fixed values add numerically
 * (the exact `a + b` the old `Int` arithmetic produced); any Variable
 * operand makes the whole sum Variable. Used by `sumOfFixedWireBytes`
 * and the framed-header `n + 1` arithmetic so the Fixed path stays
 * byte-identical and the Variable path propagates instead of throwing
 * prematurely.
 */
internal operator fun WireWidth.plus(other: WireWidth): WireWidth =
    when {
        this is WireWidth.Fixed && other is WireWidth.Fixed -> WireWidth.Fixed(this.bytes + other.bytes)
        else -> WireWidth.Variable
    }

/**
 * Unwrap a WireWidth that the call site requires to be Fixed, with a
 * symbol-named error for the stubbed Variable arm. This is THE single
 * Phase-1 stub helper: every consumer that needs a literal byte count
 * calls `width.requireFixed("siteName")` and gets `n` for Fixed,
 * error() for Variable. Centralizing it means the Variable behavior is
 * a one-liner to find and (in Phase 2) replace per site.
 */
internal fun WireWidth.requireFixed(site: String): Int =
    when (this) {
        is WireWidth.Fixed -> bytes
        WireWidth.Variable -> error("$site requires a Fixed wire width; Variable not yet supported (Phase 1 stub)")
    }

internal fun List<FieldSpec>.sumOfFixedWireBytes(): WireWidth =
    filterIsInstance<FieldSpec.FixedSize>()
        .map { it.wireWidth }
        .fold(WireWidth.Zero as WireWidth) { a, b -> a + b }

/**
 * Write statement for a natural-width scalar given an
 * accessor expression. Boolean encodes as `0x00` / `0x01`.
 */
internal fun naturalScalarWriteStatement(
    kind: ScalarKind,
    accessor: String,
): String =
    when (kind) {
        ScalarKind.Boolean -> "buffer.writeByte(if ($accessor) 1.toByte() else 0.toByte())"
        ScalarKind.UByte -> "buffer.writeUByte($accessor)"
        ScalarKind.UShort -> "buffer.writeUShort($accessor)"
        ScalarKind.UInt -> "buffer.writeUInt($accessor)"
        ScalarKind.ULong -> "buffer.writeULong($accessor)"
        ScalarKind.Byte -> "buffer.writeByte($accessor)"
        ScalarKind.Short -> "buffer.writeShort($accessor)"
        ScalarKind.Int -> "buffer.writeInt($accessor)"
        ScalarKind.Long -> "buffer.writeLong($accessor)"
        ScalarKind.Float -> "buffer.writeFloat($accessor)"
        ScalarKind.Double -> "buffer.writeDouble($accessor)"
    }
