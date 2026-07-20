package com.ditchoom.buffer.codec.test.protocols.count

import com.ditchoom.buffer.codec.annotations.Count
import com.ditchoom.buffer.codec.annotations.Endianness
import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.PacketType
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import kotlin.jvm.JvmInline

/*
 * `@Count` fixtures whose ELEMENT wireSize is BackPatch for a reason the
 * one-level annotation scan (`detectElementBackPatch`) historically missed.
 * Each shape here used to generate a `wireSize()` that cast the element
 * codec's `WireSize` `as WireSize.Exact` and threw `ClassCastException` on
 * every `encodeToPlatformBuffer` call — even a 1-element list.
 *
 * The contract under test: a `@Count` list whose element wireSize is
 * BackPatch must collapse the containing message's wireSize to BackPatch
 * (never throw); a `@Count` list of Exact-sized elements must keep an
 * Exact wireSize (see `CountFixedList`).
 */

/** Sealed field carried by a list element — variants mix BackPatch (LP string) and Exact (data object). */
@ProtocolMessage
sealed interface CountBadge {
    @ProtocolMessage
    @PacketType(0x00)
    data class Named(
        @LengthPrefixed val name: String,
    ) : CountBadge

    @ProtocolMessage
    @PacketType(0x01)
    data object Anonymous : CountBadge
}

/**
 * Element holding a bare nested sealed `@ProtocolMessage` field. The bare
 * nested field is a `ProtocolMessageScalar`, which collapses the element
 * codec's wireSize to BackPatch — but carries no annotation for the
 * element-level scan to see.
 */
@ProtocolMessage(wireOrder = Endianness.Big)
data class CountSealedEntry(
    val id: UShort,
    val badge: CountBadge,
)

@ProtocolMessage(wireOrder = Endianness.Big)
data class CountSealedEntryList(
    @Count val entries: List<CountSealedEntry>,
)

/** Value-class-over-String id — wire-identical to a bare `@LengthPrefixed String`. */
@JvmInline
value class CountTag(
    val value: String,
)

/**
 * Element whose only field is a `@LengthPrefixed` value-class-over-String.
 * The element codec's wireSize is BackPatch (same as a bare LP String), but
 * the parameter's declared type is the value class, not `kotlin.String`.
 */
@ProtocolMessage(wireOrder = Endianness.Big)
data class CountTaggedEntry(
    @LengthPrefixed val tag: CountTag,
)

@ProtocolMessage(wireOrder = Endianness.Big)
data class CountTaggedList(
    @Count val tags: List<CountTaggedEntry>,
)

/** All-fixed nested message — 2 bytes on the wire. */
@ProtocolMessage(wireOrder = Endianness.Big)
data class CountInnerFixed(
    val a: Short,
)

/**
 * Element holding a bare nested data-class `@ProtocolMessage` field. Even
 * though the inner message is all-fixed, a bare `ProtocolMessageScalar`
 * field conservatively collapses the element's wireSize to BackPatch.
 */
@ProtocolMessage(wireOrder = Endianness.Big)
data class CountNestedEntry(
    val inner: CountInnerFixed,
)

@ProtocolMessage(wireOrder = Endianness.Big)
data class CountNestedEntryList(
    @Count val entries: List<CountNestedEntry>,
)
