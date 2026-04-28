package com.ditchoom.buffer.codec.processor.planbuilder

import com.ditchoom.buffer.codec.processor.discovery.RawClassMetadata
import com.ditchoom.buffer.codec.processor.discovery.RawDirection
import com.ditchoom.buffer.codec.processor.discovery.RawSymbol
import com.ditchoom.buffer.codec.processor.ir.Direction
import com.ditchoom.buffer.codec.processor.ir.FieldPlan
import com.ditchoom.buffer.codec.processor.ir.FieldStrategy
import com.squareup.kotlinpoet.ClassName

/**
 * Slice 5b: derives the codec direction implied by a class's field strategies and
 * reconciles it with the user's explicit class-level direction marker.
 *
 * The new pipeline previously inferred direction only from the class-level
 * `@Decode` / `@Encode` / `@ProtocolMessage(direction = ...)` markers. With the
 * `coverable()` gate flipped, every `@ProtocolMessage` flows through here, and
 * field-level direction (the codec referenced by `@UseCodec`, the direction of
 * a nested `@ProtocolMessage` field, the element direction of a `Collection_`)
 * must propagate up to the class direction so the emitter selects the right
 * superinterface (Codec / Decoder / Encoder).
 *
 * Mirrors legacy `ProtocolMessageProcessor.inferDirection` +
 * `ProtocolMessageProcessor.validateDirection`. Diagnostics:
 *
 *  * Mixed decode-only and encode-only fields with no explicit class direction
 *    → `Cannot infer direction for '<owner>': it has both decode-only fields
 *    [...] and encode-only fields [...]`. Same wording as legacy.
 *  * Explicit `direction = Codec` (Bidirectional) with any unidirectional field
 *    → `@ProtocolMessage(direction = Codec) on '<owner>' requires all fields to
 *    be bidirectional, but these are not: ...`.
 *  * Explicit `direction = DecodeOnly` with an encode-only field, or vice versa
 *    → conflict diagnostic.
 *
 * Errors accumulate into [errors] and the returned direction falls back to the
 * pre-refinement value so downstream emission can still proceed (the caller
 * already plans to surface the errors and bail).
 */
internal object FieldDirectionInference {
    private const val CODEC_FQN = "com.ditchoom.buffer.codec.Codec"
    private const val ENCODER_FQN = "com.ditchoom.buffer.codec.Encoder"
    private const val DECODER_FQN = "com.ditchoom.buffer.codec.Decoder"

    fun refine(
        fields: List<FieldPlan>,
        /** Class-level `@Decode` / `@Encode` / `direction = ...`, or null when defaulted. */
        explicit: Direction?,
        /** Direction the resolver returned (used when no refinement is possible). */
        fallback: Direction,
        scope: Map<String, RawSymbol>,
        externalClasses: Map<String, RawClassMetadata>,
        ownerFqn: String,
        errors: MutableList<KspError>,
    ): Direction {
        val perField =
            fields.map { f -> directionOf(f, scope, externalClasses) }
        val hasDecodeOnly = perField.any { it == Direction.DecodeOnly }
        val hasEncodeOnly = perField.any { it == Direction.EncodeOnly }

        val inferred =
            when {
                hasDecodeOnly && hasEncodeOnly -> null // conflict
                hasDecodeOnly -> Direction.DecodeOnly
                hasEncodeOnly -> Direction.EncodeOnly
                else -> Direction.Bidirectional
            }

        return when (explicit) {
            null -> {
                if (inferred == null) {
                    errors +=
                        KspError(
                            message =
                                "Cannot infer direction for '$ownerFqn': it has both decode-only " +
                                    "fields and encode-only fields. These are incompatible.\n" +
                                    "To fix, either:\n" +
                                    "  • Make all codecs bidirectional\n" +
                                    "  • Split into separate decode-only and encode-only messages\n" +
                                    "  • Explicitly annotate: @ProtocolMessage(direction = DecodeOnly) or EncodeOnly",
                            sourceFqn = ownerFqn,
                        )
                    fallback
                } else {
                    inferred
                }
            }
            Direction.Bidirectional -> {
                if (hasDecodeOnly || hasEncodeOnly) {
                    val unidirectional =
                        fields
                            .zip(perField)
                            .filter { (_, dir) -> dir != Direction.Bidirectional }
                    val details =
                        unidirectional.joinToString(", ") { (f, dir) ->
                            val tag = if (dir == Direction.DecodeOnly) "decode-only" else "encode-only"
                            "'${f.name}' ($tag)"
                        }
                    errors +=
                        KspError(
                            message =
                                "@ProtocolMessage(direction = Codec) on '$ownerFqn' requires all fields to be " +
                                    "bidirectional, but these are not: $details.\n" +
                                    "To fix, either make those codecs bidirectional, or change direction to " +
                                    "DecodeOnly / EncodeOnly.",
                            sourceFqn = ownerFqn,
                        )
                    Direction.Bidirectional
                } else {
                    Direction.Bidirectional
                }
            }
            Direction.DecodeOnly -> {
                val encodeOnly =
                    fields.zip(perField).filter { (_, dir) -> dir == Direction.EncodeOnly }
                if (encodeOnly.isNotEmpty()) {
                    val details = encodeOnly.joinToString(", ") { (f, _) -> "'${f.name}' (encode-only)" }
                    errors +=
                        KspError(
                            message =
                                "@ProtocolMessage(direction = DecodeOnly) on '$ownerFqn' conflicts with " +
                                    "encode-only fields: $details.\n" +
                                    "A decode-only message cannot contain encode-only fields.",
                            sourceFqn = ownerFqn,
                        )
                }
                Direction.DecodeOnly
            }
            Direction.EncodeOnly -> {
                val decodeOnly =
                    fields.zip(perField).filter { (_, dir) -> dir == Direction.DecodeOnly }
                if (decodeOnly.isNotEmpty()) {
                    val details = decodeOnly.joinToString(", ") { (f, _) -> "'${f.name}' (decode-only)" }
                    errors +=
                        KspError(
                            message =
                                "@ProtocolMessage(direction = EncodeOnly) on '$ownerFqn' conflicts with " +
                                    "decode-only fields: $details.\n" +
                                    "An encode-only message cannot contain decode-only fields.",
                            sourceFqn = ownerFqn,
                        )
                }
                Direction.EncodeOnly
            }
        }
    }

    /**
     * Direction implied by a single field's strategy.
     *
     *  - `External` (`@UseCodec`): looks up the codec's directly-declared supertypes
     *    in [externalClasses]. If it declares `Codec<T>` it's bidirectional; otherwise
     *    Decoder = decode-only, Encoder = encode-only. Both Decoder and Encoder
     *    (without Codec) is treated as bidirectional (legacy behaviour).
     *  - `NestedMessage`: looks up the nested `@ProtocolMessage` symbol in [scope]
     *    and reads its `RawDirection`.
     *  - `Collection_` with an external element codec: same External lookup applied
     *    to the element codec's FQN.
     *  - Everything else (Primitive, VarInt, StringField, DiscriminatorOwned,
     *    PayloadSlot, Spi, NestedMessage on an unresolved symbol): bidirectional.
     */
    private fun directionOf(
        field: FieldPlan,
        scope: Map<String, RawSymbol>,
        externalClasses: Map<String, RawClassMetadata>,
    ): Direction =
        when (val s = field.strategy) {
            is FieldStrategy.External -> directionFromCodecClass(s.codec.toFqn(), externalClasses)
            is FieldStrategy.NestedMessage -> directionFromNestedSymbol(s.codec.toFqn(), scope, externalClasses)
            is FieldStrategy.Collection_ -> {
                // ElementCodecRef carries a single `codec` ClassName. We don't know
                // upfront whether it's an external (`@UseCodec`) class or a generated
                // nested-message codec. Try the nested-symbol lookup first (strip
                // `Codec` suffix); if no symbol matches, treat it as external.
                directionFromNestedSymbol(s.elementCodec.codec.toFqn(), scope, externalClasses)
            }
            else -> Direction.Bidirectional
        }

    private fun directionFromCodecClass(
        codecFqn: String,
        externalClasses: Map<String, RawClassMetadata>,
    ): Direction {
        val metadata = externalClasses[codecFqn] ?: return Direction.Bidirectional
        val supertypeFqns = metadata.directlyDeclaredSupertypes.map { it.fqn }.toSet()
        val implementsCodec = CODEC_FQN in supertypeFqns
        val implementsDecoder = DECODER_FQN in supertypeFqns
        val implementsEncoder = ENCODER_FQN in supertypeFqns
        return when {
            implementsCodec -> Direction.Bidirectional
            implementsDecoder && implementsEncoder -> Direction.Bidirectional
            implementsDecoder -> Direction.DecodeOnly
            implementsEncoder -> Direction.EncodeOnly
            else -> Direction.Bidirectional
        }
    }

    /**
     * Resolves the direction of a nested `@ProtocolMessage` codec.
     *
     * Generated codecs are named `<Class>Codec`. The nested codec's user-facing
     * direction lives on the original `@ProtocolMessage` class — strip the `Codec`
     * suffix and look up the symbol in [scope]. If not found there (e.g. cross-module
     * reference), fall back to the codec's external metadata (rarely populated for
     * generated codecs, so usually returns Bidirectional and we let downstream
     * checks catch any mismatch).
     */
    private fun directionFromNestedSymbol(
        codecFqn: String,
        scope: Map<String, RawSymbol>,
        externalClasses: Map<String, RawClassMetadata>,
    ): Direction {
        val sourceFqn = codecFqn.removeSuffix("Codec")
        val symbol = scope[sourceFqn]
        if (symbol != null) {
            return when (symbol.direction) {
                RawDirection.Default, RawDirection.Codec -> Direction.Bidirectional
                RawDirection.DecodeOnly -> Direction.DecodeOnly
                RawDirection.EncodeOnly -> Direction.EncodeOnly
                RawDirection.Conflict -> Direction.Bidirectional
            }
        }
        return directionFromCodecClass(codecFqn, externalClasses)
    }

    private fun ClassName.toFqn(): String =
        if (packageName.isEmpty()) simpleNames.joinToString(".") else "$packageName.${simpleNames.joinToString(".")}"
}
