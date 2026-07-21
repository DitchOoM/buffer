package com.ditchoom.buffer.codec.processor

import com.squareup.kotlinpoet.ClassName
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Coverage + determinism tests for the schema descriptor emitter (SCHEMA_DRIFT.md, step 1).
 *
 * These exercise the **pure projection** ([CodecSchemaDescriptor]) directly off hand-built IR, so
 * they pin the format without paying a KSP compile. The KSP end-to-end (`codec-schema.txt` lands
 * in the generated dir) is covered by [CodecSchemaDescriptorCodegenTest].
 */
class CodecSchemaDescriptorTest {
    // ---- coverage ---------------------------------------------------------

    /**
     * Every concrete [FieldSpec] leaf must contribute a non-empty, single-line descriptor. The set
     * of leaves is discovered reflectively, so a newly-added field shape with no entry here fails
     * this test (mirroring the compile-time exhaustiveness of the `when` in [CodecSchemaDescriptor]
     * — together they forbid a silent gap that would greenlight a breaking change).
     */
    @Test
    fun `every FieldSpec leaf emits a non-empty single line`() {
        val byClass = sampleFields.associateBy { it::class }
        assertExhaustive(FieldSpec::class, byClass.keys)
        for (field in sampleFields) {
            val line = CodecSchemaDescriptor.describeField(field)
            assertNonEmptyLine(line, "FieldSpec ${field::class.simpleName}")
        }
    }

    @Test
    fun `every ConditionalInner leaf emits a non-empty single line`() {
        val byClass = sampleConditionalInners.associateBy { it::class }
        assertExhaustive(ConditionalInner::class, byClass.keys)
        for (inner in sampleConditionalInners) {
            assertNonEmptyLine(
                CodecSchemaDescriptor.describeConditionalInner(inner),
                "ConditionalInner ${inner::class.simpleName}",
            )
        }
    }

    @Test
    fun `every Discriminator leaf emits a non-empty single line`() {
        val byClass = sampleDiscriminators.associateBy { it::class }
        assertExhaustive(Discriminator::class, byClass.keys)
        for (d in sampleDiscriminators) {
            assertNonEmptyLine(CodecSchemaDescriptor.describeDiscriminator(d), "Discriminator ${d::class.simpleName}")
        }
    }

    @Test
    fun `every LengthSource leaf emits a non-empty token`() {
        val byClass = sampleLengthSources.associateBy { it::class }
        assertExhaustive(LengthSource::class, byClass.keys)
        for (s in sampleLengthSources) {
            assertNonEmptyLine(CodecSchemaDescriptor.describeLengthSource(s), "LengthSource ${s::class.simpleName}")
        }
    }

    @Test
    fun `every ConditionRef leaf emits a non-empty token`() {
        val byClass = sampleConditions.associateBy { it::class }
        assertExhaustive(ConditionRef::class, byClass.keys)
        for (c in sampleConditions) {
            assertNonEmptyLine(CodecSchemaDescriptor.describeCondition(c), "ConditionRef ${c::class.simpleName}")
        }
    }

    @Test
    fun `every PayloadCodecSource leaf emits a non-empty token`() {
        val byClass = samplePayloadCodecSources.associateBy { it::class }
        assertExhaustive(PayloadCodecSource::class, byClass.keys)
        for (s in samplePayloadCodecSources) {
            assertNonEmptyLine(
                CodecSchemaDescriptor.describePayloadCodecSource(s),
                "PayloadCodecSource ${s::class.simpleName}",
            )
        }
    }

    // ---- determinism ------------------------------------------------------

    @Test
    fun `records are sorted by type name regardless of input order`() {
        // mAlpha contributes both an enum (com.acme.Aaa) and a message (com.acme.MAlpha).
        val enumField =
            FieldSpec.EnumScalar("f", "Owner", ClassName("com.acme", "Aaa"), 2, "X", listOf("X", "Y"))
        val mAlpha = messageShape("com.acme.MAlpha", listOf(enumField))
        val mBeta = messageShape("com.acme.MBeta")
        val sealed = sealedShape("com.acme.ZOp")

        val forward = CodecSchemaDescriptor.render(listOf(mAlpha, mBeta), listOf(sealed))
        val reversed = CodecSchemaDescriptor.render(listOf(mBeta, mAlpha), listOf(sealed))
        assertEquals(forward, reversed, "input order must not affect output (stable sort)")

        val headers = forward.lines().filter { it.isNotEmpty() && !it.startsWith("  ") }
        assertEquals(
            listOf(
                "enum com.acme.Aaa default=X",
                "message com.acme.MAlpha",
                "message com.acme.MBeta",
                "sealed com.acme.ZOp dispatch=fixed-byte/1B",
            ),
            headers,
            "records must be sorted by fully-qualified type name",
        )
    }

    @Test
    fun `render is byte-identical across runs and ends with a trailing newline`() {
        val shapes = sampleFields.mapIndexed { i, f -> messageShape("com.acme.M$i", listOf(f)) }
        val once = CodecSchemaDescriptor.render(shapes, emptyList())
        val twice = CodecSchemaDescriptor.render(shapes, emptyList())
        assertEquals(once, twice, "same IR must produce byte-identical output")
        assertTrue(once.endsWith("\n"), "descriptor must end with a trailing newline")
    }

    @Test
    fun `an enum referenced by multiple fields is de-duplicated into one record`() {
        val enumField =
            FieldSpec.EnumScalar(
                name = "intensity",
                ownerSimpleName = "M",
                enumType = ClassName("com.acme", "Intensity"),
                entryCount = 2,
                defaultEntryName = "Normal",
                entryNames = listOf("Normal", "Bold"),
            )
        val rendered =
            CodecSchemaDescriptor.render(
                listOf(messageShape("com.acme.A", listOf(enumField)), messageShape("com.acme.B", listOf(enumField))),
                emptyList(),
            )
        val enumHeaders = rendered.lines().count { it.startsWith("enum ") }
        assertEquals(1, enumHeaders, "the same enum referenced twice must yield exactly one enum record")
    }

    @Test
    fun `enum entries are keyed by ordinal in declaration order`() {
        val shape = enumShape("com.acme.Intensity", listOf("Normal", "Bold", "Faint"), default = "Normal")
        val rendered = CodecSchemaDescriptor.render(listOf(shape), emptyList())
        assertTrue(
            rendered.contains("enum com.acme.Intensity default=Normal\n  0 Normal\n  1 Bold\n  2 Faint"),
            "ordinal→name table must be emitted in declaration order:\n$rendered",
        )
    }

    @Test
    fun `enum without a default omits the default marker`() {
        val shape = enumShape("com.acme.Plain", listOf("A", "B"), default = null)
        val rendered = CodecSchemaDescriptor.render(listOf(shape), emptyList())
        assertTrue(rendered.startsWith("enum com.acme.Plain\n"), "no default → no default= token:\n$rendered")
    }

    // ---- value-class-over-String is wire-insignificant --------------------

    /**
     * A `@JvmInline value class` over `String` is byte-for-byte identical on
     * the wire to the bare `String` it wraps, so the descriptor — which tracks
     * only wire-significant attributes — must render the two the same. This is
     * what makes a plain `@LengthPrefixed String` field and a `@LengthPrefixed
     * UserId` field interchangeable without registering as schema drift.
     */
    @Test
    fun `value-class-over-String descriptor is identical to plain String under every framing`() {
        val wrapper = ValueClassStringWrapper(ClassName("com.acme", "UserId"), "value")

        assertEquals(
            CodecSchemaDescriptor.describeField(
                FieldSpec.LengthPrefixedString("id", "Owner", 2, Endianness.Big),
            ),
            CodecSchemaDescriptor.describeField(
                FieldSpec.LengthPrefixedString("id", "Owner", 2, Endianness.Big, valueClass = wrapper),
            ),
            "@LengthPrefixed value-class-over-String must share the plain-String schema line",
        )

        assertEquals(
            CodecSchemaDescriptor.describeField(
                FieldSpec.LengthFromString("id", "Owner", LengthSource.Sibling("len", ScalarKind.UShort)),
            ),
            CodecSchemaDescriptor.describeField(
                FieldSpec.LengthFromString(
                    "id",
                    "Owner",
                    LengthSource.Sibling("len", ScalarKind.UShort),
                    valueClass = wrapper,
                ),
            ),
            "@LengthFrom value-class-over-String must share the plain-String schema line",
        )

        assertEquals(
            CodecSchemaDescriptor.describeField(
                FieldSpec.RemainingBytesString("id", "Owner", reservedTrailingBytes = 0),
            ),
            CodecSchemaDescriptor.describeField(
                FieldSpec.RemainingBytesString("id", "Owner", reservedTrailingBytes = 0, valueClass = wrapper),
            ),
            "@RemainingBytes value-class-over-String must share the plain-String schema line",
        )

        assertEquals(
            CodecSchemaDescriptor.describeConditionalInner(
                ConditionalInner.LengthPrefixedString(2, Endianness.Big),
            ),
            CodecSchemaDescriptor.describeConditionalInner(
                ConditionalInner.LengthPrefixedString(2, Endianness.Big, valueClass = wrapper),
            ),
            "@When @LengthPrefixed value-class-over-String must share the plain-String schema line",
        )
    }

    // ---- helpers ----------------------------------------------------------

    private fun assertNonEmptyLine(
        line: String,
        what: String,
    ) {
        assertTrue(line.isNotBlank(), "$what produced a blank descriptor line")
        assertTrue(!line.contains("\n"), "$what descriptor must be a single line, was:\n$line")
    }

    private fun assertExhaustive(
        sealedRoot: KClass<*>,
        covered: Set<KClass<*>>,
    ) {
        val missing = leaves(sealedRoot).filter { it !in covered }
        assertTrue(
            missing.isEmpty(),
            "${sealedRoot.simpleName} leaves with no descriptor sample: ${missing.map { it.simpleName }}",
        )
    }

    /** Concrete (non-sealed) leaf classes reachable through a sealed hierarchy. */
    private fun leaves(k: KClass<*>): List<KClass<*>> {
        if (!k.isSealed) return listOf(k)
        return k.sealedSubclasses.flatMap { leaves(it) }
    }

    // ---- IR samples -------------------------------------------------------

    private val cn = ClassName("com.acme", "T")
    private val codec = ClassName("com.acme", "TCodec")

    private fun enumShape(
        fqcn: String,
        entries: List<String>,
        default: String?,
    ): CodecShape {
        val pkg = fqcn.substringBeforeLast('.')
        val simple = fqcn.substringAfterLast('.')
        val enumField =
            FieldSpec.EnumScalar(
                name = "f",
                ownerSimpleName = "Owner",
                enumType = ClassName(pkg, simple),
                entryCount = entries.size,
                defaultEntryName = default,
                entryNames = entries,
            )
        // The enum projects from the field, but the carrier message would also appear; give it a
        // type name that sorts after the enum so tests can assert on the enum header in isolation.
        return messageShape("$pkg.${simple}Carrier", listOf(enumField))
    }

    private fun messageShape(
        fqcn: String,
        fields: List<FieldSpec> = emptyList(),
    ): CodecShape {
        val pkg = fqcn.substringBeforeLast('.')
        val simple = fqcn.substringAfterLast('.')
        return CodecShape(
            packageName = pkg,
            messageClassName = ClassName(pkg, simple),
            ownerSimpleName = simple,
            codecSimpleName = "${simple}Codec",
            fields = fields,
        )
    }

    private fun sealedShape(fqcn: String): DispatchShape {
        val pkg = fqcn.substringBeforeLast('.')
        val simple = fqcn.substringAfterLast('.')
        return DispatchShape(
            packageName = pkg,
            parentClassName = ClassName(pkg, simple),
            parentSimpleName = simple,
            codecSimpleName = "${simple}Codec",
            discriminator = Discriminator.FixedByte,
            variants =
                listOf(
                    DispatchVariant(
                        simpleName = "Two",
                        className = ClassName(pkg, "Two"),
                        codecClassName = ClassName(pkg, "TwoCodec"),
                        dispatchValue = 0x13,
                        codecRef = VariantCodecRef.StaticObject,
                        wireSize = VariantWireSize.LiteralExact(2),
                    ),
                    DispatchVariant(
                        simpleName = "One",
                        className = ClassName(pkg, "One"),
                        codecClassName = ClassName(pkg, "OneCodec"),
                        dispatchValue = 0x12,
                        codecRef = VariantCodecRef.StaticObject,
                        wireSize = VariantWireSize.LiteralExact(2),
                    ),
                ),
            genericity = Genericity.Monomorphic,
            framing = Framing.Unframed,
            forwardCompat = ForwardCompat.Disabled,
            visibility = CodecVisibility.Public,
        )
    }

    private val listSpec =
        LengthPrefixedListSpec(
            codecType = ClassName("com.acme", "LenCodec"),
            elementClassName = ClassName("com.acme", "Elem"),
            elementCodecClassName = ClassName("com.acme", "ElemCodec"),
            elementIsBackPatch = false,
        )

    private val sampleConditions: List<ConditionRef> =
        listOf(
            ConditionRef.Sibling("hasTtl"),
            ConditionRef.ValueClassProperty("flags", "willPresent"),
            ConditionRef.RemainingCmp(RemainingComparisonOp.GreaterOrEqual, 2),
        )

    private val sampleLengthSources: List<LengthSource> =
        listOf(
            LengthSource.Sibling("len", ScalarKind.UShort),
            LengthSource.ValueClassProperty("hdr", "length", ScalarKind.UInt),
        )

    private val samplePayloadCodecSources: List<PayloadCodecSource> =
        listOf(
            PayloadCodecSource.UserCodecObject(codec),
            PayloadCodecSource.ConstructorInjected("payloadCodec"),
        )

    private val sampleConditionalInners: List<ConditionalInner> =
        listOf(
            ConditionalInner.Scalar(ScalarKind.UInt, Endianness.Big),
            ConditionalInner.LengthPrefixedString(2, Endianness.Big),
            ConditionalInner.ValueClassScalar(cn, ScalarKind.UShort, "raw", Endianness.Big),
            ConditionalInner.LengthPrefixedUseCodecList(listSpec),
            ConditionalInner.LengthPrefixedUseCodecPayload(cn, codec, 2, Endianness.Big),
            ConditionalInner.UseCodecScalar(cn, codec),
            ConditionalInner.ProtocolMessageScalar(cn, codec),
        )

    private val sampleDiscriminators: List<Discriminator> =
        listOf(
            Discriminator.FixedByte,
            Discriminator.ValueClass(
                className = cn,
                codecClassName = codec,
                innerKind = ScalarKind.UByte,
                innerWireOrder = Endianness.Big,
                dispatchValueProperty = "packetType",
                dispatchValueKind = ScalarKind.Int,
            ),
            Discriminator.Varint(
                className = cn,
                codecClassName = codec,
                dispatchValueProperty = "type",
                dispatchValueKind = ScalarKind.Int,
                innerPropertyName = "raw",
                innerKind = ScalarKind.Long,
            ),
        )

    private val sampleFields: List<FieldSpec> =
        listOf(
            FieldSpec.Scalar("id", ScalarKind.Int, Endianness.Big, 4),
            FieldSpec.EnumScalar("intensity", "Owner", cn, 2, "Normal", listOf("Normal", "Bold")),
            FieldSpec.LengthPrefixedMessage("body", "Owner", cn, codec, 2, Endianness.Big),
            FieldSpec.LengthFromMessage("body", "Owner", LengthSource.Sibling("len", ScalarKind.UShort), cn, codec),
            FieldSpec.LengthPrefixedString("name", "Owner", 2, Endianness.Big),
            FieldSpec.ProtocolMessageScalar("nested", "Owner", cn, codec),
            FieldSpec.UseCodecScalar("v", "Owner", cn, codec, isBounding = false, isVariableLength = true),
            FieldSpec.RemainingBytesString("rest", "Owner", reservedTrailingBytes = 0),
            FieldSpec.DeferredPayload("payload", "Owner", cn, PayloadCodecSource.UserCodecObject(codec), 0),
            FieldSpec.LengthPrefixedUseCodecList("props", "Owner", listSpec),
            FieldSpec.LengthPrefixedUseCodecPayload("blob", "Owner", cn, codec, 2, Endianness.Big),
            FieldSpec.RemainingBytesProtocolMessageList("topics", "Owner", cn, codec, elementIsBackPatch = false),
            FieldSpec.CountPrefixedProtocolMessageList("points", "Owner", cn, codec, elementIsBackPatch = false),
            FieldSpec.LengthFromList("items", "Owner", LengthSource.Sibling("count", ScalarKind.UByte), cn, codec),
            FieldSpec.LengthFromString("text", "Owner", LengthSource.Sibling("len", ScalarKind.UShort)),
            FieldSpec.ValueClassScalar("packetId", "Owner", cn, ScalarKind.UShort, "raw", 2, Endianness.Big),
            FieldSpec.Conditional(
                "ttl",
                "Owner",
                ConditionRef.Sibling("hasTtl"),
                cn,
                ConditionalInner.Scalar(ScalarKind.UInt, Endianness.Big),
            ),
        )
}
