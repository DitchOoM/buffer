package com.ditchoom.buffer.codec.test.protocols.count

import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.codec.encodeToPlatformBuffer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression coverage for `@Count` lists whose element wireSize is BackPatch
 * for a reason the element-level annotation scan used to miss. Every shape
 * here previously generated a `wireSize()` that cast the element codec's
 * result `as WireSize.Exact` and threw `ClassCastException` on every
 * `encodeToPlatformBuffer` call.
 *
 * Contract: BackPatch-element lists report `WireSize.BackPatch` (never
 * throw) and round-trip for empty / one / many elements. Exact-element
 * lists must NOT regress to BackPatch — `CountPrefixedListCodecTest.
 * wireSizeFixedListIsExact` pins that side.
 */
class CountBackPatchGapsCodecTest {
    // ---- element with a bare nested sealed field --------------------------

    @Test
    fun sealedEntryListWireSizeIsBackPatchNotThrow() {
        val msg =
            CountSealedEntryList(
                entries =
                    listOf(
                        CountSealedEntry(1u, CountBadge.Named("a")),
                        CountSealedEntry(2u, CountBadge.Anonymous),
                    ),
            )
        assertEquals(WireSize.BackPatch, CountSealedEntryListCodec.wireSize(msg, EncodeContext.Empty))
    }

    @Test
    fun sealedEntryListRoundTripsEmptyOneMany() {
        for (
        entries in
        listOf(
            emptyList(),
            listOf(CountSealedEntry(7u, CountBadge.Named("solo"))),
            listOf(
                CountSealedEntry(1u, CountBadge.Named("a")),
                CountSealedEntry(2u, CountBadge.Anonymous),
                CountSealedEntry(3u, CountBadge.Named("a longer badge name")),
            ),
        )
        ) {
            assertRoundTrips(CountSealedEntryList(entries))
        }
    }

    // ---- element with a @LengthPrefixed value-class-over-String -----------

    @Test
    fun taggedListWireSizeIsBackPatchNotThrow() {
        val msg = CountTaggedList(tags = listOf(CountTaggedEntry(CountTag("x"))))
        assertEquals(WireSize.BackPatch, CountTaggedListCodec.wireSize(msg, EncodeContext.Empty))
    }

    @Test
    fun taggedListRoundTripsEmptyOneMany() {
        for (
        tags in
        listOf(
            emptyList(),
            listOf(CountTaggedEntry(CountTag("a"))),
            listOf(
                CountTaggedEntry(CountTag("")),
                CountTaggedEntry(CountTag("bb")),
                CountTaggedEntry(CountTag("ccc")),
            ),
        )
        ) {
            assertRoundTrips(CountTaggedList(tags))
        }
    }

    // ---- element with a bare nested (all-fixed) data-class message --------

    @Test
    fun nestedEntryListWireSizeDoesNotThrow() {
        // A bare ProtocolMessageScalar field conservatively collapses the
        // element to BackPatch even though CountInnerFixed is all-fixed; the
        // list must inherit BackPatch instead of CCEing on the Exact cast.
        val msg = CountNestedEntryList(entries = listOf(CountNestedEntry(CountInnerFixed(5))))
        assertEquals(WireSize.BackPatch, CountNestedEntryListCodec.wireSize(msg, EncodeContext.Empty))
    }

    @Test
    fun nestedEntryListRoundTripsEmptyOneMany() {
        for (
        entries in
        listOf(
            emptyList(),
            listOf(CountNestedEntry(CountInnerFixed(1))),
            List(5) { CountNestedEntry(CountInnerFixed(it.toShort())) },
        )
        ) {
            assertRoundTrips(CountNestedEntryList(entries))
        }
    }

    // ---- helpers ----------------------------------------------------------

    private fun assertRoundTrips(msg: CountSealedEntryList) {
        val buf = CountSealedEntryListCodec.encodeToPlatformBuffer(msg)
        assertEquals(msg, CountSealedEntryListCodec.decode(buf, DecodeContext.Empty))
    }

    private fun assertRoundTrips(msg: CountTaggedList) {
        val buf = CountTaggedListCodec.encodeToPlatformBuffer(msg)
        assertEquals(msg, CountTaggedListCodec.decode(buf, DecodeContext.Empty))
    }

    private fun assertRoundTrips(msg: CountNestedEntryList) {
        val buf = CountNestedEntryListCodec.encodeToPlatformBuffer(msg)
        assertEquals(msg, CountNestedEntryListCodec.decode(buf, DecodeContext.Empty))
    }
}
