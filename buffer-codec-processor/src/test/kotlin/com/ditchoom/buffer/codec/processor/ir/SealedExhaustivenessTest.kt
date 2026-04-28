package com.ditchoom.buffer.codec.processor.ir

import com.squareup.kotlinpoet.ClassName
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Compile-stub tests pinning the variant set of every sealed family in the Plan IR.
 *
 * The `*Label` helpers below `when` over each sealed family without an `else` arm.
 * If a sealed family gains a variant that this file doesn't enumerate, kotlinc
 * fails this test source set with a non-exhaustive-when error — a much louder
 * signal than a runtime fall-through. The bodies never execute; compilation alone
 * is the assertion.
 *
 * Runtime invariants the IR claims to enforce by construction (`require(...)` in
 * `init`) are also exercised here so a regression that drops them fails the test
 * suite immediately.
 */
class SealedExhaustivenessTest {
    @Test
    fun planIrCompiles() {
        // No-op — the real assertions are the exhaustive `when`s below + invariant tests.
    }

    @Test
    fun wireMatchRangeRejectsInvertedBounds() {
        assertFailsWith<IllegalArgumentException> {
            WireMatch.Range(TypeFqn("com.example.Variant"), from = 5, to = 3)
        }
    }

    @Test
    fun framingModeBodyLengthRejectsZeroDiscriminatorBytes() {
        assertFailsWith<IllegalArgumentException> {
            FramingMode.BodyLength(
                framerFqn = ClassName("com.example", "Framer"),
                discriminatorBytes = 0,
            )
        }
    }

    @Test
    fun variantPlanWithPayloadRejectsEmptyTypeParams() {
        val decl = TypeFqn("com.example.Variant")
        assertFailsWith<IllegalArgumentException> {
            VariantPlan.WithPayload(
                decl = decl,
                codec = ClassName("com.example", "VariantCodec"),
                wire = WireMatch.Point(decl, 1),
                selfEncodes = true,
                dir = Direction.Bidirectional,
                fields = emptyList(),
                typeParams = emptyList(),
                payloadFields = emptyList(),
            )
        }
    }

    @Test
    fun typeFqnRejectsBlank() {
        assertFailsWith<IllegalArgumentException> { TypeFqn("") }
        assertFailsWith<IllegalArgumentException> { TypeFqn("   ") }
    }

    @Test
    fun providerIdRejectsBlank() {
        assertFailsWith<IllegalArgumentException> { ProviderId("") }
    }

    @Suppress("unused")
    private fun planLabel(plan: Plan): String =
        when (plan) {
            is Plan.Leaf -> "Leaf"
            is Plan.Object_ -> "Object_"
            is Plan.Sealed_ -> "Sealed_"
        }

    @Suppress("unused")
    private fun directionLabel(d: Direction): String =
        when (d) {
            Direction.Bidirectional -> "Bidirectional"
            Direction.DecodeOnly -> "DecodeOnly"
            Direction.EncodeOnly -> "EncodeOnly"
        }

    @Suppress("unused")
    private fun dispatchShapeLabel(shape: DispatchShape): String =
        when (shape) {
            DispatchShape.RawByte -> "RawByte"
            is DispatchShape.TypedDiscriminator -> "TypedDiscriminator"
        }

    @Suppress("unused")
    private fun discriminatorShapeLabel(shape: DiscriminatorShape): String =
        when (shape) {
            is DiscriminatorShape.ValueClass -> "ValueClass"
            is DiscriminatorShape.DataClass -> "DataClass"
        }

    @Suppress("unused")
    private fun framingModeLabel(mode: FramingMode): String =
        when (mode) {
            FramingMode.Unframed -> "Unframed"
            is FramingMode.PeekOnly -> "PeekOnly"
            is FramingMode.BodyLength -> "BodyLength"
        }

    @Suppress("unused")
    private fun wireMatchLabel(match: WireMatch): String =
        when (match) {
            is WireMatch.Point -> "Point"
            is WireMatch.Range -> "Range"
        }

    @Suppress("unused")
    private fun variantPlanLabel(variant: VariantPlan): String =
        when (variant) {
            is VariantPlan.NoPayload -> "NoPayload"
            is VariantPlan.WithPayload -> "WithPayload"
        }

    @Suppress("unused")
    private fun fieldStrategyLabel(strategy: FieldStrategy): String =
        when (strategy) {
            is FieldStrategy.Primitive -> "Primitive"
            is FieldStrategy.VarInt -> "VarInt"
            is FieldStrategy.StringField -> "StringField"
            is FieldStrategy.Collection_ -> "Collection_"
            is FieldStrategy.NestedMessage -> "NestedMessage"
            is FieldStrategy.External -> "External"
            is FieldStrategy.DiscriminatorOwned -> "DiscriminatorOwned"
            is FieldStrategy.PayloadSlot -> "PayloadSlot"
            is FieldStrategy.Spi -> "Spi"
            is FieldStrategy.ValueClass -> "ValueClass"
        }

    @Suppress("unused")
    private fun lengthSourceLabel(source: LengthSource): String =
        when (source) {
            is LengthSource.Inline -> "Inline"
            is LengthSource.FromField -> "FromField"
            is LengthSource.Remaining -> "Remaining"
        }

    @Suppress("unused")
    private fun conditionalityLabel(c: Conditionality): String =
        when (c) {
            Conditionality.Always -> "Always"
            is Conditionality.WhenExpr -> "WhenExpr"
        }

    @Suppress("unused")
    private fun booleanExpressionLabel(expr: BooleanExpression): String =
        when (expr) {
            is BooleanExpression.FieldRef -> "FieldRef"
            is BooleanExpression.RemainingGte -> "RemainingGte"
            is BooleanExpression.Eq -> "Eq"
            is BooleanExpression.Gt -> "Gt"
        }

    @Suppress("unused")
    private fun primitiveKindLabel(k: PrimitiveKind): String =
        when (k) {
            PrimitiveKind.Bool -> "Bool"
            PrimitiveKind.Byte -> "Byte"
            PrimitiveKind.UByte -> "UByte"
            PrimitiveKind.Short -> "Short"
            PrimitiveKind.UShort -> "UShort"
            PrimitiveKind.Int -> "Int"
            PrimitiveKind.UInt -> "UInt"
            PrimitiveKind.Long -> "Long"
            PrimitiveKind.ULong -> "ULong"
            PrimitiveKind.Float -> "Float"
            PrimitiveKind.Double -> "Double"
        }

    @Suppress("unused")
    private fun endiannessLabel(e: Endianness): String =
        when (e) {
            Endianness.Big -> "Big"
            Endianness.Little -> "Little"
        }

    @Suppress("unused")
    private fun lengthEncodingLabel(e: LengthEncoding): String =
        when (e) {
            LengthEncoding.Byte -> "Byte"
            LengthEncoding.Short -> "Short"
            LengthEncoding.Int -> "Int"
            LengthEncoding.Varint -> "Varint"
        }
}
