package com.ditchoom.buffer.codec.processor

import com.ditchoom.buffer.codec.schema.CodecSchemaClassifier
import com.ditchoom.buffer.codec.schema.DriftSeverity
import com.ditchoom.buffer.codec.schema.SchemaDrift
import com.ditchoom.buffer.codec.schema.SchemaRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Classifier unit tests (SCHEMA_DRIFT.md). One case per safe/advisory/breaking row, plus the
 * rename-vs-reorder distinction: a *pure* rename is [DriftSeverity.ADVISORY] (never fails), while a
 * reorder / insert / reassignment is [DriftSeverity.BREAKING].
 */
class CodecSchemaClassifierTest {
    // ---- enums ------------------------------------------------------------

    @Test
    fun `append enum entry is safe`() {
        val drift =
            soleDrift(
                enumRec("p.E", null, 0 to "A", 1 to "B"),
                enumRec("p.E", null, 0 to "A", 1 to "B", 2 to "C"),
            )
        assertEquals(DriftSeverity.SAFE, drift.severity)
    }

    @Test
    fun `reorder enum entries is breaking`() {
        // swap ordinals 1 and 2 — both names persist but at different ordinals
        val drifts =
            CodecSchemaClassifier.classify(
                listOf(enumRec("p.E", null, 0 to "A", 1 to "B", 2 to "C")),
                listOf(enumRec("p.E", null, 0 to "A", 1 to "C", 2 to "B")),
            )
        assertTrue(drifts.any { it.severity == DriftSeverity.BREAKING && it.detail.contains("moved") })
        assertTrue(drifts.none { it.severity == DriftSeverity.ADVISORY }, "a swap must not be misread as a rename")
    }

    @Test
    fun `insert enum entry mid-list is breaking`() {
        // insert "Mid" at ordinal 1 — Medium/High shift up and are detected as moved
        val drifts =
            CodecSchemaClassifier.classify(
                listOf(enumRec("p.E", null, 0 to "Low", 1 to "Medium", 2 to "High")),
                listOf(enumRec("p.E", null, 0 to "Low", 1 to "Mid", 2 to "Medium", 3 to "High")),
            )
        assertTrue(drifts.any { it.severity == DriftSeverity.BREAKING && it.detail.contains("moved") })
    }

    @Test
    fun `pure enum rename is advisory not breaking`() {
        val drift =
            soleDrift(
                enumRec("p.E", null, 0 to "A", 1 to "Bold"),
                enumRec("p.E", null, 0 to "A", 1 to "Strong"),
            )
        assertEquals(DriftSeverity.ADVISORY, drift.severity)
        assertTrue(drift.detail.contains("renamed"))
    }

    @Test
    fun `remove enum entry is breaking`() {
        val drift =
            soleDrift(
                enumRec("p.E", null, 0 to "A", 1 to "B"),
                enumRec("p.E", null, 0 to "A"),
            )
        assertEquals(DriftSeverity.BREAKING, drift.severity)
        assertTrue(drift.detail.contains("removed"))
    }

    @Test
    fun `add EnumDefault is safe`() {
        val drift =
            soleDrift(
                enumRec("p.E", null, 0 to "A", 1 to "B"),
                enumRec("p.E", "A", 0 to "A", 1 to "B"),
            )
        assertEquals(DriftSeverity.SAFE, drift.severity)
    }

    @Test
    fun `remove EnumDefault is breaking`() {
        val drift =
            soleDrift(
                enumRec("p.E", "A", 0 to "A", 1 to "B"),
                enumRec("p.E", null, 0 to "A", 1 to "B"),
            )
        assertEquals(DriftSeverity.BREAKING, drift.severity)
        assertTrue(drift.detail.contains("@EnumDefault removed"))
    }

    // ---- message fields ---------------------------------------------------

    @Test
    fun `append message field is safe`() {
        val drift =
            soleDrift(
                msgRec("p.M", field(0, "a", "scalar:Int wire=4B order=Big")),
                msgRec("p.M", field(0, "a", "scalar:Int wire=4B order=Big"), field(1, "b", "scalar:UByte wire=1B order=Big")),
            )
        assertEquals(DriftSeverity.SAFE, drift.severity)
    }

    @Test
    fun `insert message field mid is breaking`() {
        // inserting at position 1 shifts position 1's descriptor
        val drifts =
            CodecSchemaClassifier.classify(
                listOf(msgRec("p.M", field(0, "a", "scalar:Int wire=4B order=Big"), field(1, "b", "scalar:UShort wire=2B order=Big"))),
                listOf(
                    msgRec(
                        "p.M",
                        field(0, "a", "scalar:Int wire=4B order=Big"),
                        field(1, "x", "scalar:UByte wire=1B order=Big"),
                        field(2, "b", "scalar:UShort wire=2B order=Big"),
                    ),
                ),
            )
        assertTrue(drifts.any { it.severity == DriftSeverity.BREAKING && it.detail.contains("wire shape") })
    }

    @Test
    fun `widen WireBytes is breaking`() {
        val drift =
            soleDrift(
                msgRec("p.M", field(0, "len", "scalar:UInt wire=3B order=Big")),
                msgRec("p.M", field(0, "len", "scalar:UInt wire=4B order=Big")),
            )
        assertEquals(DriftSeverity.BREAKING, drift.severity)
        assertTrue(drift.detail.contains("wire shape"))
    }

    @Test
    fun `flip WireOrder is breaking`() {
        val drift =
            soleDrift(
                msgRec("p.M", field(0, "v", "scalar:UShort wire=2B order=Big")),
                msgRec("p.M", field(0, "v", "scalar:UShort wire=2B order=Little")),
            )
        assertEquals(DriftSeverity.BREAKING, drift.severity)
    }

    @Test
    fun `rename message field with identical descriptor is advisory`() {
        val drift =
            soleDrift(
                msgRec("p.M", field(0, "ttl", "scalar:UInt wire=4B order=Big")),
                msgRec("p.M", field(0, "timeout", "scalar:UInt wire=4B order=Big")),
            )
        assertEquals(DriftSeverity.ADVISORY, drift.severity)
        assertTrue(drift.detail.contains("wire-neutral"))
    }

    @Test
    fun `remove message field is breaking`() {
        val drift =
            soleDrift(
                msgRec("p.M", field(0, "a", "scalar:Int wire=4B order=Big"), field(1, "b", "scalar:UByte wire=1B order=Big")),
                msgRec("p.M", field(0, "a", "scalar:Int wire=4B order=Big")),
            )
        assertEquals(DriftSeverity.BREAKING, drift.severity)
        assertTrue(drift.detail.contains("removed"))
    }

    // ---- sealed -----------------------------------------------------------

    @Test
    fun `add variant with unused dispatch value is safe`() {
        val drift =
            soleDrift(
                sealedRec("p.S", "fixed-byte/1B", variants = arrayOf("0x01" to "A")),
                sealedRec("p.S", "fixed-byte/1B", variants = arrayOf("0x01" to "A", "0x02" to "B")),
            )
        assertEquals(DriftSeverity.SAFE, drift.severity)
    }

    @Test
    fun `reassign PacketType is breaking`() {
        // 'A' moves from 0x01 to 0x02 — a reassignment, not a rename
        val drifts =
            CodecSchemaClassifier.classify(
                listOf(sealedRec("p.S", "fixed-byte/1B", variants = arrayOf("0x01" to "A", "0x02" to "B"))),
                listOf(sealedRec("p.S", "fixed-byte/1B", variants = arrayOf("0x01" to "B", "0x02" to "A"))),
            )
        assertTrue(drifts.any { it.severity == DriftSeverity.BREAKING && it.detail.contains("moved") })
        assertTrue(drifts.none { it.severity == DriftSeverity.ADVISORY })
    }

    @Test
    fun `pure variant rename is advisory`() {
        val drift =
            soleDrift(
                sealedRec("p.S", "fixed-byte/1B", variants = arrayOf("0x01" to "Scroll")),
                sealedRec("p.S", "fixed-byte/1B", variants = arrayOf("0x01" to "Pan")),
            )
        assertEquals(DriftSeverity.ADVISORY, drift.severity)
    }

    @Test
    fun `remove variant is breaking`() {
        val drift =
            soleDrift(
                sealedRec("p.S", "fixed-byte/1B", variants = arrayOf("0x01" to "A", "0x02" to "B")),
                sealedRec("p.S", "fixed-byte/1B", variants = arrayOf("0x01" to "A")),
            )
        assertEquals(DriftSeverity.BREAKING, drift.severity)
        assertTrue(drift.detail.contains("removed"))
    }

    @Test
    fun `change discriminator is breaking`() {
        val drift =
            soleDrift(
                sealedRec("p.S", "fixed-byte/1B", variants = arrayOf("0x01" to "A")),
                sealedRec("p.S", "valueclass:p.Tag/2B,inner=UShort/Big,dispatchValue=type:Int", variants = arrayOf("0x01" to "A")),
            )
        assertEquals(DriftSeverity.BREAKING, drift.severity)
        assertTrue(drift.detail.contains("discriminator"))
    }

    @Test
    fun `change FramedBy is breaking`() {
        val drift =
            soleDrift(
                sealedRec("p.S", "fixed-byte/1B", framedBy = "p.LenA", variants = arrayOf("0x01" to "A")),
                sealedRec("p.S", "fixed-byte/1B", framedBy = "p.LenB", variants = arrayOf("0x01" to "A")),
            )
        assertEquals(DriftSeverity.BREAKING, drift.severity)
        assertTrue(drift.detail.contains("@FramedBy"))
    }

    @Test
    fun `add ForwardCompatible is safe and remove is breaking`() {
        val without = sealedRec("p.S", "fixed-byte/1B", variants = arrayOf("0x01" to "A"))
        val with = sealedRec("p.S", "fixed-byte/1B", forwardCompatible = "p.S.Unknown", variants = arrayOf("0x01" to "A"))
        assertEquals(DriftSeverity.SAFE, soleDrift(without, with).severity)
        val removed = soleDrift(with, without)
        assertEquals(DriftSeverity.BREAKING, removed.severity)
        assertTrue(removed.detail.contains("@ForwardCompatible removed"))
    }

    // ---- whole-record + identity -----------------------------------------

    @Test
    fun `identical schema yields no drift`() {
        val rec = msgRec("p.M", field(0, "a", "scalar:Int wire=4B order=Big"))
        assertTrue(CodecSchemaClassifier.classify(listOf(rec), listOf(rec)).isEmpty())
    }

    @Test
    fun `new type is safe and removed type is breaking`() {
        val a = msgRec("p.A", field(0, "x", "scalar:Int wire=4B order=Big"))
        val b = msgRec("p.B", field(0, "y", "scalar:UByte wire=1B order=Big"))
        assertEquals(DriftSeverity.SAFE, soleDrift(listOf(a), listOf(a, b)) { it.typeName == "p.B" }.severity)
        assertEquals(DriftSeverity.BREAKING, soleDrift(listOf(a, b), listOf(a)) { it.typeName == "p.B" }.severity)
    }

    @Test
    fun `output is deterministic and type-sorted`() {
        val base =
            listOf(
                msgRec("p.Zed", field(0, "a", "scalar:Int wire=4B order=Big")),
                msgRec("p.Abc", field(0, "a", "scalar:Int wire=4B order=Big")),
            )
        val curr =
            listOf(
                msgRec("p.Zed", field(0, "a", "scalar:Int wire=4B order=Little")),
                msgRec("p.Abc", field(0, "a", "scalar:Int wire=4B order=Little")),
            )
        val once = CodecSchemaClassifier.classify(base, curr)
        assertEquals(once, CodecSchemaClassifier.classify(base, curr))
        assertEquals(listOf("p.Abc", "p.Zed"), once.map { it.typeName })
    }

    // ---- helpers ----------------------------------------------------------

    private fun soleDrift(
        baseline: SchemaRecord,
        current: SchemaRecord,
    ): SchemaDrift {
        val drifts = CodecSchemaClassifier.classify(listOf(baseline), listOf(current))
        assertEquals(1, drifts.size, "expected exactly one drift, got: $drifts")
        return drifts.single()
    }

    private fun soleDrift(
        baseline: List<SchemaRecord>,
        current: List<SchemaRecord>,
        predicate: (SchemaDrift) -> Boolean,
    ): SchemaDrift = CodecSchemaClassifier.classify(baseline, current).single(predicate)

    private fun enumRec(
        typeName: String,
        default: String?,
        vararg entries: Pair<Int, String>,
    ) = SchemaRecord.EnumRecord(
        typeName = typeName,
        default = default,
        entries = entries.map { SchemaRecord.EnumRecord.Entry(it.first, it.second) },
    )

    private fun field(
        position: Int,
        name: String,
        descriptor: String,
        optional: Boolean = false,
    ) = SchemaRecord.MessageRecord.Field(position, name, optional, descriptor)

    private fun msgRec(
        typeName: String,
        vararg fields: SchemaRecord.MessageRecord.Field,
    ) = SchemaRecord.MessageRecord(typeName, fields.toList())

    private fun sealedRec(
        typeName: String,
        dispatch: String,
        framedBy: String? = null,
        forwardCompatible: String? = null,
        variants: Array<Pair<String, String>>,
    ) = SchemaRecord.SealedRecord(
        typeName = typeName,
        dispatch = dispatch,
        framedBy = framedBy,
        forwardCompatible = forwardCompatible,
        variants = variants.map { SchemaRecord.SealedRecord.Variant(it.first, it.second) },
    )
}
