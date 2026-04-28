package com.ditchoom.buffer.codec.processor

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STAR
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.TypeVariableName
import com.squareup.kotlinpoet.ksp.writeTo

class SealedDispatchGenerator(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) {
    private data class DispatchResult(
        val functions: List<FunSpec>,
        val implementsCodec: Boolean,
    )

    /**
     * Discriminator-match metadata for one sealed variant. Sealed so adding a future kind (e.g. a
     * bit-mask claim) is a compile error at every emission site. The two kinds carry exactly the
     * fields they need — no `low`/`high`/`isRange` flag to keep in sync.
     *
     * - [Point]: claims a single match value. Without `@DispatchOn` it matches the raw byte;
     *   with `@DispatchOn` it matches the `@DispatchValue` extraction.
     * - [Range]: claims a contiguous raw-byte span (`from..to`, both inclusive, `from <= to`
     *   enforced at construction). The variant must self-encode via a `DiscriminatorField`.
     */
    private sealed interface WireMatch {
        val subclass: KSClassDeclaration

        /** The smallest match value claimed — used for stable emission ordering only. */
        val low: Int

        data class Point(
            override val subclass: KSClassDeclaration,
            val wire: Int,
        ) : WireMatch {
            override val low: Int get() = wire
        }

        data class Range(
            override val subclass: KSClassDeclaration,
            val from: Int,
            val to: Int,
        ) : WireMatch {
            init {
                require(from <= to) { "Range from=$from must be <= to=$to" }
            }

            override val low: Int get() = from
        }
    }

    /**
     * Generates the encode statement for writing the discriminator byte(s).
     * Without @DispatchOn: writes a single byte.
     * With @DispatchOn: constructs the discriminator value class and encodes via its codec.
     */
    private fun addWireWrite(
        code: CodeBlock.Builder,
        wire: Int,
        dispatchOnInfo: DispatchOnInfo?,
        contextExpr: String = "context",
    ) {
        if (dispatchOnInfo != null) {
            val conversion = wireConversion(dispatchOnInfo.innerTypeName, wire)
            code.addStatement(
                "%T.encode(buffer, %T($conversion), $contextExpr)",
                ClassName(dispatchOnInfo.poetClassName.packageName, dispatchOnInfo.codecName),
                dispatchOnInfo.poetClassName,
            )
        } else {
            code.addStatement("buffer.writeByte($wire.toByte())")
        }
    }

    private fun wireConversion(
        innerTypeName: String,
        wire: Int,
    ): String =
        when (innerTypeName) {
            "UByte" -> "$wire.toUByte()"
            "Byte" -> "$wire.toByte()"
            "UShort" -> "$wire.toUShort()"
            "Short" -> "$wire.toShort()"
            "UInt" -> "$wire.toUInt()"
            "Int" -> "$wire"
            "ULong" -> "$wire.toULong()"
            "Long" -> "$wire.toLong()"
            else -> "$wire.toUByte()" // fallback
        }

    /**
     * Returns a Kotlin Int expression for the discriminator's wire byte count.
     * Without @DispatchOn: literal `1` (single discriminator byte).
     * With @DispatchOn: defers to the discriminator codec's `wireSize` so codegen
     * stays correct even if the discriminator type's wire width ever changes.
     */
    private fun discriminatorWireSizeExpr(
        wire: Int,
        dispatchOnInfo: DispatchOnInfo?,
        contextExpr: String = "context",
    ): String {
        if (dispatchOnInfo == null) return "1"
        val conversion = wireConversion(dispatchOnInfo.innerTypeName, wire)
        val codecRef = "${dispatchOnInfo.codecName}"
        val typeRef = dispatchOnInfo.poetClassName.simpleName
        return "$codecRef.wireSize($typeRef($conversion), $contextExpr)"
    }

    /**
     * Wraps a body-size expression with framing overhead, asking the framer at runtime
     * for its prefix wire size via `bodyLengthSize(n)`. Returns just [bodyExpr] when there
     * is no body framing or when the framer is peek-only (no separate length prefix on
     * the wire). Evaluates [bodyExpr] once via a `run { val _b = ...; ... }` block.
     */
    private fun wrapBodyLengthSizeExpr(
        bodyExpr: String,
        dispatchOnInfo: DispatchOnInfo?,
    ): String {
        if (dispatchOnInfo?.hasBodyLength != true) return bodyExpr
        val framing = dispatchOnInfo.framing ?: return bodyExpr
        return "run { val _b = $bodyExpr; ${framing.framerFqn}.bodyLengthSize(_b) + _b }"
    }

    /** Returns the valid range for a wire value given the discriminator's inner type, or null if any Int fits. */
    private fun wireRange(innerTypeName: String): LongRange? =
        when (innerTypeName) {
            "UByte" -> 0L..255L
            "Byte" -> -128L..127L
            "UShort" -> 0L..65535L
            "Short" -> -32768L..32767L
            "UInt" -> 0L..4294967295L
            // Int, Long, ULong — any @PacketType Int value fits
            else -> null
        }

    fun generate(
        sealedInterface: KSClassDeclaration,
        subclasses: List<KSClassDeclaration>,
        variantPayloadInfos: List<SealedVariantPayloadInfo> = emptyList(),
        dispatchOnInfo: DispatchOnInfo? = null,
        variantsHandlingDiscriminator: Set<String> = emptySet(),
        variantsSupportingPeek: Set<String> = emptySet(),
        direction: CodecDirection = CodecDirection.Bidirectional,
        onUnknownDiscriminator: String = "kotlin.IllegalArgumentException",
    ) {
        val interfaceName = sealedInterface.simpleName.asString()
        val packageName = sealedInterface.packageName.asString()
        val codecName = "${interfaceName}Codec"
        val interfaceTypeName = ClassName(packageName, interfaceName)

        // Collect @PacketType / @PacketTypeRange variants
        val variants = mutableListOf<WireMatch>()
        for (subclass in subclasses) {
            val packetType =
                subclass.annotations.find {
                    it.qualifiedName() == "com.ditchoom.buffer.codec.annotations.PacketType"
                }
            val packetRange =
                subclass.annotations.find {
                    it.qualifiedName() == "com.ditchoom.buffer.codec.annotations.PacketTypeRange"
                }

            if (packetType == null && packetRange == null) {
                logger.error(
                    "Sealed subclass '${subclass.simpleName.asString()}' is missing @PacketType or " +
                        "@PacketTypeRange. Each subclass of sealed @ProtocolMessage '$interfaceName' " +
                        "needs @PacketType(wire = N) for a single-byte claim or " +
                        "@PacketTypeRange(from = F, to = T) for a contiguous wire-byte span.",
                    subclass,
                )
                return
            }
            if (packetType != null && packetRange != null) {
                logger.error(
                    "Sealed subclass '${subclass.simpleName.asString()}' has both @PacketType and " +
                        "@PacketTypeRange. Use exactly one: @PacketType(wire = N) for a single-byte " +
                        "discriminator, or @PacketTypeRange(from = F, to = T) for a contiguous span.",
                    subclass,
                )
                return
            }

            val match: WireMatch =
                if (packetType != null) {
                    val wireArg = packetType.arguments.find { it.name?.asString() == "wire" }?.value as? Int
                        ?: packetType.arguments.firstOrNull()?.value as? Int
                    if (wireArg == null) {
                        logger.error(
                            "@PacketType on '${subclass.simpleName.asString()}' must specify `wire`. " +
                                "Use @PacketType(wire = N) or positional @PacketType(N).",
                            subclass,
                        )
                        return
                    }
                    val wire = wireArg
                    if (dispatchOnInfo == null) {
                        if (wire < 0 || wire > 255) {
                            logger.error(
                                "@PacketType(wire = $wire) on '${subclass.simpleName.asString()}' is out of range. " +
                                    "The type discriminator is a single byte, so valid values are 0-255. " +
                                    "Use @DispatchOn for multi-byte discriminators.",
                                subclass,
                            )
                            return
                        }
                    } else {
                        val wireRange = wireRange(dispatchOnInfo.innerTypeName)
                        if (wireRange != null && wire.toLong() !in wireRange) {
                            logger.error(
                                "@PacketType(wire = $wire) on '${subclass.simpleName.asString()}' overflows " +
                                    "the discriminator's ${dispatchOnInfo.innerTypeName} type (valid range: " +
                                    "${wireRange.first}..${wireRange.last}). The wire value is converted to " +
                                    "${dispatchOnInfo.innerTypeName} during encode, so values outside this range " +
                                    "silently wrap and will not round-trip correctly.",
                                subclass,
                            )
                            return
                        }
                    }
                    WireMatch.Point(subclass = subclass, wire = wire)
                } else {
                    // packetRange != null
                    val from = packetRange!!.arguments.find { it.name?.asString() == "from" }?.value as? Int
                        ?: (packetRange.arguments.getOrNull(0)?.value as? Int)
                    val to = packetRange.arguments.find { it.name?.asString() == "to" }?.value as? Int
                        ?: (packetRange.arguments.getOrNull(1)?.value as? Int)
                    if (from == null || to == null) {
                        logger.error(
                            "@PacketTypeRange on '${subclass.simpleName.asString()}' must specify `from` and `to`.",
                            subclass,
                        )
                        return
                    }
                    if (to < from) {
                        logger.error(
                            "@PacketTypeRange(from = $from, to = $to) on '${subclass.simpleName.asString()}' " +
                                "is invalid: `to` must be >= `from`.",
                            subclass,
                        )
                        return
                    }
                    if (dispatchOnInfo == null) {
                        if (from < 0 || from > 255 || to < 0 || to > 255) {
                            logger.error(
                                "@PacketTypeRange(from = $from, to = $to) on " +
                                    "'${subclass.simpleName.asString()}' is out of range. The type discriminator " +
                                    "is a single byte; both bounds must be 0-255.",
                                subclass,
                            )
                            return
                        }
                    } else {
                        if (!dispatchOnInfo.isValueClass ||
                            (dispatchOnInfo.innerTypeName != "UByte" && dispatchOnInfo.innerTypeName != "Byte")
                        ) {
                            logger.error(
                                "@PacketTypeRange on '${subclass.simpleName.asString()}' requires either no " +
                                    "@DispatchOn or a @DispatchOn whose discriminator is a single-byte value class " +
                                    "(UByte or Byte). The current discriminator " +
                                    "(${dispatchOnInfo.innerTypeName}, value-class=${dispatchOnInfo.isValueClass}) " +
                                    "cannot be addressed by a raw-byte range.",
                                subclass,
                            )
                            return
                        }
                        if (from < 0 || from > 255 || to < 0 || to > 255) {
                            logger.error(
                                "@PacketTypeRange(from = $from, to = $to) on " +
                                    "'${subclass.simpleName.asString()}' must lie within 0-255 (single byte).",
                                subclass,
                            )
                            return
                        }
                    }
                    val qn = subclass.qualifiedName?.asString() ?: subclass.simpleName.asString()
                    if (qn !in variantsHandlingDiscriminator) {
                        logger.error(
                            "@PacketTypeRange on '${subclass.simpleName.asString()}' requires the variant to " +
                                "carry the discriminator byte itself in a discriminator field (a constructor " +
                                "parameter typed as the @DispatchOn discriminator class, with a default value). " +
                                "Without one, the dispatcher has no way to encode the per-instance bytes that " +
                                "make this a range rather than a single value.",
                            subclass,
                        )
                        return
                    }
                    WireMatch.Range(subclass = subclass, from = from, to = to)
                }

            variants.add(match)
        }

        // Disjoint validation. Range claims must not overlap each other in raw-byte space.
        // Point claims must not collide on their match key (raw byte without @DispatchOn,
        // extraction value with @DispatchOn).
        val rangeVariants = variants.filterIsInstance<WireMatch.Range>().sortedBy { it.from }
        for (i in 1 until rangeVariants.size) {
            val prev = rangeVariants[i - 1]
            val cur = rangeVariants[i]
            if (cur.from <= prev.to) {
                logger.error(
                    "@PacketTypeRange spans overlap on sealed root '$interfaceName': " +
                        "'${prev.subclass.simpleName.asString()}' " +
                        "(0x${prev.from.toString(16)}..0x${prev.to.toString(16)}) and " +
                        "'${cur.subclass.simpleName.asString()}' " +
                        "(0x${cur.from.toString(16)}..0x${cur.to.toString(16)}) collide on " +
                        "byte 0x${cur.from.toString(16)}. Each raw byte must route to at most one variant.",
                    cur.subclass,
                )
                return
            }
        }
        val pointVariants = variants.filterIsInstance<WireMatch.Point>()
        val pointSeen = mutableMapOf<Int, KSClassDeclaration>()
        for (p in pointVariants) {
            val existing = pointSeen[p.wire]
            if (existing != null) {
                logger.error(
                    "@PacketType(wire = ${p.wire}) is used by both " +
                        "'${existing.simpleName.asString()}' and '${p.subclass.simpleName.asString()}'. " +
                        "Each subclass needs a unique discriminator so the codec can identify which type to decode.",
                    p.subclass,
                )
                return
            }
            pointSeen[p.wire] = p.subclass
        }
        if (dispatchOnInfo == null) {
            // Without @DispatchOn we dispatch on raw byte everywhere — points and ranges share the
            // same domain, so a point inside any range is also a collision.
            for (p in pointVariants) {
                val containing = rangeVariants.firstOrNull { p.wire in it.from..it.to }
                if (containing != null) {
                    logger.error(
                        "@PacketType(wire = ${p.wire}) on '${p.subclass.simpleName.asString()}' falls " +
                            "inside @PacketTypeRange on '${containing.subclass.simpleName.asString()}' " +
                            "(0x${containing.from.toString(16)}..0x${containing.to.toString(16)}). " +
                            "Raw byte 0x${p.wire.toString(16)} must route to at most one variant.",
                        p.subclass,
                    )
                    return
                }
            }
        }

        // Sealed dispatch is aggregating: it depends on the sealed interface AND all subclass files
        val sourceFiles =
            buildList {
                sealedInterface.containingFile?.let { add(it) }
                for (sub in subclasses) {
                    sub.containingFile?.let { add(it) }
                }
            }
        val dependencies = Dependencies(aggregating = true, sources = sourceFiles.toTypedArray())

        val hasAnyPayload = variantPayloadInfos.any { it.payloadFields.isNotEmpty() }
        val canDecode = direction != CodecDirection.EncodeOnly

        val result =
            if (hasAnyPayload) {
                buildPayloadDispatch(
                    packageName,
                    interfaceTypeName,
                    variants,
                    variantPayloadInfos,
                    dispatchOnInfo,
                    variantsHandlingDiscriminator,
                    direction,
                    onUnknownDiscriminator,
                )
            } else {
                buildSimpleDispatch(
                    interfaceTypeName,
                    variants,
                    dispatchOnInfo,
                    variantsHandlingDiscriminator,
                    direction,
                    onUnknownDiscriminator,
                )
            }

        val objectBuilder = TypeSpec.objectBuilder(codecName)
        if (result.implementsCodec) {
            when (direction) {
                CodecDirection.Bidirectional ->
                    objectBuilder.addSuperinterface(CODEC.parameterizedBy(interfaceTypeName))
                CodecDirection.DecodeOnly ->
                    objectBuilder.addSuperinterface(DECODER.parameterizedBy(interfaceTypeName))
                CodecDirection.EncodeOnly ->
                    objectBuilder.addSuperinterface(ENCODER.parameterizedBy(interfaceTypeName))
            }
        }

        // If @DispatchOn is used, generate a context key for the discriminator
        if (dispatchOnInfo != null) {
            objectBuilder.addType(
                TypeSpec
                    .objectBuilder("DiscriminatorKey")
                    .addModifiers(KModifier.DATA)
                    .superclass(CODEC_CONTEXT_KEY.parameterizedBy(dispatchOnInfo.poetClassName))
                    .build(),
            )
        }

        for (fn in result.functions) {
            objectBuilder.addFunction(fn)
        }

        // Generate peekFrameSize for sealed dispatch.
        // - With bodyLength framing, total frame size derives from the length prefix alone,
        //   so per-variant peek delegation is not required.
        // - Without bodyLength, ALL variants must support peek — otherwise we can't compute
        //   the full frame size without consuming bytes.
        if (canDecode) {
            val needsVariantPeek = dispatchOnInfo?.hasBodyLength != true
            val allVariantsSupportPeek =
                !needsVariantPeek ||
                    variants.all { v ->
                        val name = v.subclass.qualifiedName?.asString() ?: v.subclass.simpleName.asString()
                        name in variantsSupportingPeek
                    }
            val sealedPeek = if (allVariantsSupportPeek) buildSealedPeekFrameSize(variants, dispatchOnInfo) else null
            if (sealedPeek != null) {
                objectBuilder.addProperty(sealedPeek.minHeaderProperty)
                objectBuilder.addFunction(sealedPeek.syncFun)
                sealedPeek.suspendFun?.let { objectBuilder.addFunction(it) }
            }
        }

        val fileBuilder = fileSpecBuilder(packageName, codecName).addType(objectBuilder.build())
        // Body-length-framed dispatchers with payload variants emit a scratch-buffer fallback
        // that calls `BufferFactory.Default.allocate(...)`. `Default` is an extension property
        // on `BufferFactory.Companion`, so it must be imported by name. Peek-only framers
        // never emit the scratch fallback (no length prefix to write).
        if (dispatchOnInfo?.hasBodyLength == true && hasAnyPayload) {
            fileBuilder.addImport("com.ditchoom.buffer", "Default")
        }
        fileBuilder.build().writeTo(codeGenerator, dependencies)
    }

    private fun WireMatch.selfEncodesDiscriminator(variantsHandlingDiscriminator: Set<String>): Boolean =
        when (this) {
            // Range variants always delegate discriminator-encode to their @DiscriminatorField; KSP
            // already enforced this earlier.
            is WireMatch.Range -> true
            // Point variants opt in via DiscriminatorField presence (auto-detected by type).
            is WireMatch.Point -> {
                val qn = subclass.qualifiedName?.asString()
                qn != null && qn in variantsHandlingDiscriminator
            }
        }

    /** Returns the call expression for `throw <configured>("$message")`. Defaults to
     * `IllegalArgumentException` when [fqn] is empty or the placeholder `kotlin.IllegalArgumentException`. */
    private fun unknownExceptionClassName(fqn: String): ClassName {
        val resolved = fqn.ifEmpty { "kotlin.IllegalArgumentException" }
        val pkg = resolved.substringBeforeLast('.')
        val simple = resolved.substringAfterLast('.')
        return ClassName(pkg, simple)
    }

    /** Emits a single `when`-arm condition for a [WireMatch]. Range arms emit `in from..to`,
     * point arms emit the bare integer. */
    private fun whenArmKey(match: WireMatch): String =
        when (match) {
            is WireMatch.Range -> "in ${match.from}..${match.to}"
            is WireMatch.Point -> match.wire.toString()
        }

    /** Emits a `when {}` predicate that mixes raw-byte ranges with extraction-value points
     * under `@DispatchOn`. The dispatcher reads both `rawByte` and `type` so each kind compares
     * against the right domain. */
    private fun whenBlockArmKey(match: WireMatch): String =
        when (match) {
            is WireMatch.Range -> "rawByte in ${match.from}..${match.to}"
            is WireMatch.Point -> "type == ${match.wire}"
        }

    /** The wire value used by `addWireWrite` and `discriminatorWireSizeExpr` for a point claim
     * — these helpers only run for non-self-encoding point variants. Calling for a [WireMatch.Range]
     * is a programming error. */
    private fun WireMatch.Point.encodeWire(): Int = wire

    /** Returns the discriminator value class's inner-property name, or fails loudly. Range dispatch
     * needs this to read the raw byte; KSP rejects ranges on data-class discriminators earlier so
     * any null at emission time is a processor bug, not user error. */
    private fun DispatchOnInfo.requireInnerPropertyName(): String =
        innerPropertyName
            ?: error(
                "Range dispatch on '$typeName' requires its inner-property name, but the @DispatchOn " +
                    "target is not a value class. KSP should have rejected the @PacketTypeRange earlier; " +
                    "this is a processor bug.",
            )

    /** No payload variants — generates a standard Codec<T> implementation with context forwarding. */
    private fun buildSimpleDispatch(
        interfaceTypeName: ClassName,
        variants: List<WireMatch>,
        dispatchOnInfo: DispatchOnInfo? = null,
        variantsHandlingDiscriminator: Set<String> = emptySet(),
        direction: CodecDirection = CodecDirection.Bidirectional,
        onUnknownDiscriminator: String = "",
    ): DispatchResult {
        val canDecode = direction != CodecDirection.EncodeOnly
        val canEncode = direction != CodecDirection.DecodeOnly
        val functions = mutableListOf<FunSpec>()
        val unknownException = unknownExceptionClassName(onUnknownDiscriminator)
        val hasRanges = variants.any { it is WireMatch.Range }
        // Sort emission order: ranges first (in low order), then points (in low order). Within
        // each group, ordering is purely cosmetic — disjoint validation guarantees no overlap.
        val orderedVariants =
            variants.filterIsInstance<WireMatch.Range>().sortedBy { it.from } +
                variants.filterIsInstance<WireMatch.Point>().sortedBy { it.wire }

        // decode(buffer, context) — context-aware decode
        if (canDecode) {
            val decodeCtxBody = CodeBlock.builder()
            if (dispatchOnInfo != null) {
                decodeCtxBody.addStatement(
                    "val _discriminator = %T.decode(buffer, context)",
                    ClassName(dispatchOnInfo.poetClassName.packageName, dispatchOnInfo.codecName),
                )
                decodeCtxBody.addStatement(
                    "val type = _discriminator.%L",
                    dispatchOnInfo.dispatchProperty,
                )
                if (hasRanges) {
                    decodeCtxBody.addStatement(
                        "val rawByte = _discriminator.%L.toInt() and 0xFF",
                        dispatchOnInfo.requireInnerPropertyName(),
                    )
                }
                decodeCtxBody.addStatement(
                    "val _ctx = context.with(DiscriminatorKey, _discriminator)",
                )
            } else {
                decodeCtxBody.addStatement("val type = buffer.readByte().toInt() and 0xFF")
            }
            emitBodyLengthDecodePrelude(decodeCtxBody, dispatchOnInfo, "context")
            val bodyVar = bodyVarName(dispatchOnInfo)
            val ctxVar = if (dispatchOnInfo != null) "_ctx" else "context"
            val framed = dispatchOnInfo?.hasBodyLength == true
            // With @DispatchOn AND ranges, ranges match raw byte while points match extraction —
            // need a multi-condition `when {}`. Otherwise stick with `when (type)` for cleanliness.
            val useWhenBlock = dispatchOnInfo != null && hasRanges
            if (framed) {
                if (useWhenBlock) decodeCtxBody.beginControlFlow("val _result = when") else decodeCtxBody.beginControlFlow("val _result = when (type)")
            } else {
                if (useWhenBlock) decodeCtxBody.beginControlFlow("return when") else decodeCtxBody.beginControlFlow("return when (type)")
            }
            for (v in orderedVariants) {
                val arm =
                    if (useWhenBlock) {
                        whenBlockArmKey(v)
                    } else {
                        whenArmKey(v)
                    }
                decodeCtxBody.addStatement("$arm -> ${v.subclass.codecName()}.decode($bodyVar, $ctxVar)")
            }
            decodeCtxBody
                .addStatement(
                    "else -> throw %T(%P)",
                    unknownException,
                    if (useWhenBlock) "Unknown discriminator: 0x\${rawByte.toString(16)}" else "Unknown packet type: \$type",
                )
                .endControlFlow()
            if (framed) {
                emitBodyLengthOverrunCheck(decodeCtxBody, unknownException)
                decodeCtxBody.addStatement("return _result")
            }

            val decodeCtxBuilder =
                FunSpec
                    .builder("decode")
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter("buffer", READ_BUFFER)
                    .addParameter("context", DECODE_CONTEXT)
                    .returns(interfaceTypeName)
                    .addCode(decodeCtxBody.build())
            functions.add(decodeCtxBuilder.build())
        }

        // encode(buffer, value, context) — context-aware encode
        if (canEncode) {
            val encodeCtxBody = CodeBlock.builder().beginControlFlow("when (value)")
            for (v in variants) {
                encodeCtxBody.beginControlFlow("is %T ->", v.subclass.toPoetClassName())
                if (!v.selfEncodesDiscriminator(variantsHandlingDiscriminator)) {
                    addWireWrite(encodeCtxBody, v.low, dispatchOnInfo)
                }
                emitBodyLengthEncodeWrap(
                    code = encodeCtxBody,
                    info = dispatchOnInfo,
                    encodeStmt = "${v.subclass.codecName()}.encode(buffer, value, context)",
                    bodySizeExpr = "${v.subclass.codecName()}.wireSize(value, context)",
                )
                encodeCtxBody.endControlFlow()
            }
            encodeCtxBody.endControlFlow()

            val encodeCtxBuilder =
                FunSpec
                    .builder("encode")
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter("buffer", WRITE_BUFFER)
                    .addParameter("value", interfaceTypeName)
                    .addParameter("context", ENCODE_CONTEXT)
                    .addCode(encodeCtxBody.build())
            functions.add(encodeCtxBuilder.build())

            // wireSize(value, context): exact byte count, mirroring encode's discriminator + body
            // shape. Context flows into each variant's wireSize so nested payload-bearing codecs
            // can resolve SizeKey lambdas from it.
            val wireSizeBody = CodeBlock.builder().beginControlFlow("return when (value)")
            for (v in variants) {
                val subTypeName = v.subclass.toPoetClassName()
                val subCodecName = v.subclass.codecName()
                val discSize = discriminatorWireSizeExpr(v.low, dispatchOnInfo)
                val variantBody = "$subCodecName.wireSize(value, context)"
                val wrapped = wrapBodyLengthSizeExpr(variantBody, dispatchOnInfo)
                val term = if (v.selfEncodesDiscriminator(variantsHandlingDiscriminator)) wrapped else "$discSize + $wrapped"
                wireSizeBody.addStatement("is %T -> %L", subTypeName, term)
            }
            wireSizeBody.endControlFlow()
            functions.add(
                FunSpec
                    .builder("wireSize")
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter("value", interfaceTypeName)
                    .addParameter("context", ENCODE_CONTEXT)
                    .returns(INT)
                    .addCode(wireSizeBody.build())
                    .build(),
            )
        }

        return DispatchResult(functions, true)
    }

    /**
     * Some variants have @Payload — generates a dispatch with type params and lambda forwarding.
     * Cannot implement Codec<T> because decode/encode need extra lambda parameters.
     */
    private fun buildPayloadDispatch(
        packageName: String,
        interfaceTypeName: ClassName,
        variants: List<WireMatch>,
        variantPayloadInfos: List<SealedVariantPayloadInfo>,
        dispatchOnInfo: DispatchOnInfo? = null,
        variantsHandlingDiscriminator: Set<String> = emptySet(),
        direction: CodecDirection = CodecDirection.Bidirectional,
        onUnknownDiscriminator: String = "",
    ): DispatchResult {
        val unknownException = unknownExceptionClassName(onUnknownDiscriminator)
        val hasRanges = variants.any { it is WireMatch.Range }
        val orderedVariants =
            variants.filterIsInstance<WireMatch.Range>().sortedBy { it.from } +
                variants.filterIsInstance<WireMatch.Point>().sortedBy { it.wire }
        // Collect all distinct type params from all payload variants
        val allTypeParams =
            variantPayloadInfos
                .flatMap { it.payloadFields }
                .map { it.typeParamName }
                .distinct()
        val typeVariables = allTypeParams.map { TypeVariableName(it) }

        // Build payload info lookup
        val payloadBySubclass =
            variantPayloadInfos.associateBy { it.subclass.qualifiedName?.asString() }

        // ── Decode ──
        val decodeBuilder =
            FunSpec
                .builder("decode")
                .addParameter("buffer", READ_BUFFER)
                .returns(interfaceTypeName)

        for (tv in typeVariables) {
            decodeBuilder.addTypeVariable(tv)
        }

        // Add lambda params for each payload field in each payload variant
        for (info in variantPayloadInfos) {
            for (pf in info.payloadFields) {
                val variantName = info.subclass.simpleName.asString()
                val paramName = "decode${variantName}${capitalizeFirst(pf.fieldName)}"
                val tpName = TypeVariableName(pf.typeParamName)
                val contextType = ClassName(packageName, pf.contextClassName)
                val lambdaType =
                    LambdaTypeName.get(
                        receiver = contextType,
                        parameters = listOf(ParameterSpec.unnamed(READ_BUFFER)),
                        returnType = tpName,
                    )
                decodeBuilder.addParameter(paramName, lambdaType)
            }
        }

        val decodeBody = CodeBlock.builder()
        if (dispatchOnInfo != null) {
            decodeBody.addStatement(
                "val _discriminator = %T.decode(buffer, %T.Empty)",
                ClassName(dispatchOnInfo.poetClassName.packageName, dispatchOnInfo.codecName),
                DECODE_CONTEXT,
            )
            decodeBody.addStatement("val type = _discriminator.%L", dispatchOnInfo.dispatchProperty)
            if (hasRanges) {
                decodeBody.addStatement(
                    "val rawByte = _discriminator.%L.toInt() and 0xFF",
                    dispatchOnInfo.requireInnerPropertyName(),
                )
            }
            decodeBody.addStatement("val _ctx = %T.Empty.with(DiscriminatorKey, _discriminator)", DECODE_CONTEXT)
        } else {
            decodeBody.addStatement("val type = buffer.readByte().toInt() and 0xFF")
        }
        // Non-context decode entrypoint: delegate the body-length read to the framer when
        // the framer extends `BodyLengthFraming<D>`. Peek-only `DispatchFraming<D>` framers
        // (WebSocket) carry the payload length inside the discriminator/header bytes, so
        // there is no separate length prefix to consume — the variant codec reads its
        // payload via `@RemainingBytes`.
        if (dispatchOnInfo?.hasBodyLength == true) {
            val framing = dispatchOnInfo.framing!!
            decodeBody.addStatement("val _bodyLen = %L.readBodyLength(buffer)", framing.framerFqn)
            decodeBody.addStatement("val _bodySlice = buffer.readBytes(_bodyLen)")
        }
        val bodyVar = bodyVarName(dispatchOnInfo)
        val conv1CtxArg = if (dispatchOnInfo != null) ", _ctx" else ", ${DECODE_CONTEXT.canonicalName}.Empty"
        val framed = dispatchOnInfo?.hasBodyLength == true
        val useWhenBlock = dispatchOnInfo != null && hasRanges
        if (framed) {
            if (useWhenBlock) decodeBody.beginControlFlow("val _result = when") else decodeBody.beginControlFlow("val _result = when (type)")
        } else {
            if (useWhenBlock) decodeBody.beginControlFlow("return when") else decodeBody.beginControlFlow("return when (type)")
        }

        for (v in orderedVariants) {
            val info = payloadBySubclass[v.subclass.qualifiedName?.asString()]
            val subCodecName = v.subclass.codecName()
            val arm =
                if (useWhenBlock) {
                    whenBlockArmKey(v)
                } else {
                    whenArmKey(v)
                }
            if (info != null && info.payloadFields.isNotEmpty()) {
                val lambdaArgs =
                    info.payloadFields.joinToString(", ") { pf ->
                        "decode${v.subclass.simpleName.asString()}${capitalizeFirst(pf.fieldName)}"
                    }
                // Variant typed-lambda decode now always accepts `context` positionally
                // (default `DecodeContext.Empty`); when this dispatcher built a `_ctx`
                // carrying DiscriminatorKey, thread it so DiscriminatorField reads resolve.
                val payloadCtxArg =
                    if (dispatchOnInfo != null) "_ctx" else "${DECODE_CONTEXT.canonicalName}.Empty"
                decodeBody.addStatement("$arm -> $subCodecName.decode($bodyVar, $payloadCtxArg, $lambdaArgs)")
            } else {
                decodeBody.addStatement("$arm -> $subCodecName.decode($bodyVar$conv1CtxArg)")
            }
        }
        decodeBody
            .addStatement(
                "else -> throw %T(%P)",
                unknownException,
                if (useWhenBlock) "Unknown discriminator: 0x\${rawByte.toString(16)}" else "Unknown packet type: \$type",
            )
            .endControlFlow()
        if (framed) {
            emitBodyLengthOverrunCheck(decodeBody, unknownException)
            decodeBody.addStatement("return _result")
        }

        decodeBuilder.addCode(decodeBody.build())

        // ── Encode ──
        val encodeBuilder =
            FunSpec
                .builder("encode")
                .addParameter("buffer", WRITE_BUFFER)
                .addParameter("value", interfaceTypeName)

        for (tv in typeVariables) {
            encodeBuilder.addTypeVariable(tv)
        }

        // Add encode lambda params
        for (info in variantPayloadInfos) {
            for (pf in info.payloadFields) {
                val variantName = info.subclass.simpleName.asString()
                val paramName = "encode${variantName}${capitalizeFirst(pf.fieldName)}"
                val tpName = TypeVariableName(pf.typeParamName)
                val encodeLambdaType =
                    LambdaTypeName.get(
                        parameters =
                            listOf(
                                ParameterSpec.unnamed(WRITE_BUFFER),
                                ParameterSpec.unnamed(tpName),
                            ),
                        returnType = UNIT,
                    )
                encodeBuilder.addParameter(paramName, encodeLambdaType)
            }
        }

        val encodeBody = CodeBlock.builder().beginControlFlow("when (value)")
        for (v in variants) {
            val info = payloadBySubclass[v.subclass.qualifiedName?.asString()]
            val subTypeName = v.subclass.toPoetClassName()
            val subCodecName = v.subclass.codecName()

            val skipForVariant = v.selfEncodesDiscriminator(variantsHandlingDiscriminator)
            val emptyEncodeCtxExpr = "${ENCODE_CONTEXT.canonicalName}.Empty"
            if (info != null && info.payloadFields.isNotEmpty()) {
                // Star-projected match for generic variant
                val starType = subTypeName.parameterizedBy(info.payloadFields.map { STAR })
                encodeBody.beginControlFlow("is %T ->", starType)
                if (!skipForVariant) {
                    addWireWrite(encodeBody, v.low, dispatchOnInfo, contextExpr = emptyEncodeCtxExpr)
                }
                // Unchecked cast to typed variant
                val castTypeParams = info.payloadFields.map { TypeVariableName(it.typeParamName) }
                val castType = subTypeName.parameterizedBy(castTypeParams)
                val lambdaArgs =
                    info.payloadFields.joinToString(", ") { pf ->
                        "encode${v.subclass.simpleName.asString()}${capitalizeFirst(pf.fieldName)}"
                    }
                // Variant typed-lambda encode now always accepts `context` positionally
                // (default `EncodeContext.Empty`); the typed-lambda dispatcher entrypoint
                // doesn't have a caller context, so pass Empty.
                val emptyEncodeCtx = "${ENCODE_CONTEXT.canonicalName}.Empty"
                val payloadFraming = dispatchOnInfo?.framing.takeIf { dispatchOnInfo?.hasBodyLength == true }
                if (payloadFraming != null) {
                    // Scratch-buffer fallback: encode into a scratch, then prefix via the framer.
                    encodeBody.addStatement(
                        "val _scratch = %T.Default.allocate(buffer.remaining().coerceAtLeast(16), buffer.byteOrder)",
                        ClassName("com.ditchoom.buffer", "BufferFactory"),
                    )
                    encodeBody.addStatement(
                        "@Suppress(\"UNCHECKED_CAST\") $subCodecName.encode(_scratch, value as %T, $emptyEncodeCtx, $lambdaArgs)",
                        castType,
                    )
                    encodeBody.addStatement("_scratch.resetForRead()")
                    encodeBody.addStatement(
                        "%L.writeBodyLength(buffer, _scratch.remaining())",
                        payloadFraming.framerFqn,
                    )
                    encodeBody.addStatement("buffer.write(_scratch)")
                } else {
                    encodeBody.addStatement(
                        "@Suppress(\"UNCHECKED_CAST\") $subCodecName.encode(buffer, value as %T, $emptyEncodeCtx, $lambdaArgs)",
                        castType,
                    )
                }
                encodeBody.endControlFlow()
            } else {
                // Non-payload variant: simple dispatch
                encodeBody.beginControlFlow("is %T ->", subTypeName)
                if (!skipForVariant) {
                    addWireWrite(encodeBody, v.low, dispatchOnInfo, contextExpr = emptyEncodeCtxExpr)
                }
                val variantEncodeStmt = "$subCodecName.encode(buffer, value, $emptyEncodeCtxExpr)"
                emitBodyLengthEncodeWrap(
                    code = encodeBody,
                    info = dispatchOnInfo,
                    encodeStmt = variantEncodeStmt,
                    bodySizeExpr = "$subCodecName.wireSize(value, $emptyEncodeCtxExpr)",
                )
                encodeBody.endControlFlow()
            }
        }
        encodeBody.endControlFlow()

        encodeBuilder.addCode(encodeBody.build())

        // ── Context-based overloads (Convention 2: enables nesting) ──
        val canDecode = direction != CodecDirection.EncodeOnly
        val canEncode = direction != CodecDirection.DecodeOnly
        val contextFunctions = mutableListOf<FunSpec>()

        // decode(buffer, context) reads lambdas from context for payload variants
        if (canDecode) {
            val decodeCtxBody = CodeBlock.builder()
            if (dispatchOnInfo != null) {
                decodeCtxBody.addStatement(
                    "val _discriminator = %T.decode(buffer, context)",
                    ClassName(dispatchOnInfo.poetClassName.packageName, dispatchOnInfo.codecName),
                )
                decodeCtxBody.addStatement("val type = _discriminator.%L", dispatchOnInfo.dispatchProperty)
                if (hasRanges) {
                    decodeCtxBody.addStatement(
                        "val rawByte = _discriminator.%L.toInt() and 0xFF",
                        dispatchOnInfo.requireInnerPropertyName(),
                    )
                }
                decodeCtxBody.addStatement("val _ctx = context.with(DiscriminatorKey, _discriminator)")
            } else {
                decodeCtxBody.addStatement("val type = buffer.readByte().toInt() and 0xFF")
            }
            emitBodyLengthDecodePrelude(decodeCtxBody, dispatchOnInfo, "context")
            val bodyVar = bodyVarName(dispatchOnInfo)
            val payloadCtxVar = if (dispatchOnInfo != null) "_ctx" else "context"
            val framed = dispatchOnInfo?.hasBodyLength == true
            val useWhenBlock = dispatchOnInfo != null && hasRanges
            if (framed) {
                if (useWhenBlock) decodeCtxBody.beginControlFlow("val _result = when") else decodeCtxBody.beginControlFlow("val _result = when (type)")
            } else {
                if (useWhenBlock) decodeCtxBody.beginControlFlow("return when") else decodeCtxBody.beginControlFlow("return when (type)")
            }

            for (v in orderedVariants) {
                val info = payloadBySubclass[v.subclass.qualifiedName?.asString()]
                val subCodecName = v.subclass.codecName()
                val arm =
                    if (useWhenBlock) {
                        whenBlockArmKey(v)
                    } else {
                        whenArmKey(v)
                    }
                if (info != null && info.payloadFields.isNotEmpty()) {
                    decodeCtxBody.addStatement("$arm -> $subCodecName.decodeFromContext($bodyVar, $payloadCtxVar)")
                } else {
                    decodeCtxBody.addStatement("$arm -> $subCodecName.decode($bodyVar, $payloadCtxVar)")
                }
            }
            decodeCtxBody
                .addStatement(
                    "else -> throw %T(%P)",
                    unknownException,
                    if (useWhenBlock) "Unknown discriminator: 0x\${rawByte.toString(16)}" else "Unknown packet type: \$type",
                )
                .endControlFlow()
            if (framed) {
                emitBodyLengthOverrunCheck(decodeCtxBody, unknownException)
                decodeCtxBody.addStatement("return _result")
            }

            val decodeCtxBuilder =
                FunSpec
                    .builder("decode")
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter("buffer", READ_BUFFER)
                    .addParameter("context", DECODE_CONTEXT)
                    .returns(interfaceTypeName)
                    .addCode(decodeCtxBody.build())
            contextFunctions.add(decodeCtxBuilder.build())
        }

        // encode(buffer, value, context) reads lambdas from context for payload variants
        if (canEncode) {
            val encodeCtxBody = CodeBlock.builder().beginControlFlow("when (value)")
            for (v in variants) {
                val info = payloadBySubclass[v.subclass.qualifiedName?.asString()]
                val subTypeName = v.subclass.toPoetClassName()
                val subCodecName = v.subclass.codecName()

                val skipForVariant = v.selfEncodesDiscriminator(variantsHandlingDiscriminator)
                if (info != null && info.payloadFields.isNotEmpty()) {
                    val starType = subTypeName.parameterizedBy(info.payloadFields.map { STAR })
                    encodeCtxBody.beginControlFlow("is %T ->", starType)
                    if (!skipForVariant) addWireWrite(encodeCtxBody, v.low, dispatchOnInfo)
                    emitBodyLengthEncodeWrap(
                        code = encodeCtxBody,
                        info = dispatchOnInfo,
                        encodeStmt = "$subCodecName.encodeFromContext(buffer, value, context)",
                        bodySizeExpr = "$subCodecName.wireSizeFromContext(value, context)",
                    )
                    encodeCtxBody.endControlFlow()
                } else {
                    encodeCtxBody.beginControlFlow("is %T ->", subTypeName)
                    if (!skipForVariant) addWireWrite(encodeCtxBody, v.low, dispatchOnInfo)
                    emitBodyLengthEncodeWrap(
                        code = encodeCtxBody,
                        info = dispatchOnInfo,
                        encodeStmt = "$subCodecName.encode(buffer, value, context)",
                        bodySizeExpr = "$subCodecName.wireSize(value, context)",
                    )
                    encodeCtxBody.endControlFlow()
                }
            }
            encodeCtxBody.endControlFlow()

            val encodeCtxBuilder =
                FunSpec
                    .builder("encode")
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter("buffer", WRITE_BUFFER)
                    .addParameter("value", interfaceTypeName)
                    .addParameter("context", ENCODE_CONTEXT)
                    .addCode(encodeCtxBody.build())
            contextFunctions.add(encodeCtxBuilder.build())

            // wireSize(value, context): exact byte count. Non-payload variants delegate to
            // their codec's wireSize(value, context). Payload variants delegate to
            // wireSizeFromContext, which reads each payload field's SizeKey lambda from the
            // provided context — making dispatcher-level size computation work for variants
            // like CorrelationData<D> without the caller hand-rolling the variant size.
            val wireSizeBody = CodeBlock.builder().beginControlFlow("return when (value)")
            for (v in variants) {
                val info = payloadBySubclass[v.subclass.qualifiedName?.asString()]
                val subTypeName = v.subclass.toPoetClassName()
                val subCodecName = v.subclass.codecName()
                val discSize = discriminatorWireSizeExpr(v.low, dispatchOnInfo)
                val skipDisc = v.selfEncodesDiscriminator(variantsHandlingDiscriminator)
                if (info != null && info.payloadFields.isNotEmpty()) {
                    val starType = subTypeName.parameterizedBy(info.payloadFields.map { STAR })
                    val variantBody = "$subCodecName.wireSizeFromContext(value, context)"
                    val wrapped = wrapBodyLengthSizeExpr(variantBody, dispatchOnInfo)
                    val term = if (skipDisc) wrapped else "$discSize + $wrapped"
                    wireSizeBody.addStatement("is %T -> %L", starType, term)
                } else {
                    val variantBody = "$subCodecName.wireSize(value, context)"
                    val wrapped = wrapBodyLengthSizeExpr(variantBody, dispatchOnInfo)
                    val term = if (skipDisc) wrapped else "$discSize + $wrapped"
                    wireSizeBody.addStatement("is %T -> %L", subTypeName, term)
                }
            }
            wireSizeBody.endControlFlow()
            contextFunctions.add(
                FunSpec
                    .builder("wireSize")
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter("value", interfaceTypeName)
                    .addParameter("context", ENCODE_CONTEXT)
                    .returns(INT)
                    .addCode(wireSizeBody.build())
                    .build(),
            )
        }

        val allFunctions =
            buildList {
                if (canDecode) add(decodeBuilder.build())
                if (canEncode) add(encodeBuilder.build())
                addAll(contextFunctions)
            }

        return DispatchResult(allFunctions, true)
    }

    // ──────────────────────── peekFrameSize for sealed dispatch ────────────────────────

    private data class SealedPeekResult(
        val minHeaderProperty: PropertySpec,
        val syncFun: FunSpec,
        /** `null` when the framing path is sync-only (DispatchFraming has no suspend variant). */
        val suspendFun: FunSpec?,
    )

    /**
     * Generates peekFrameSize for a sealed dispatch codec.
     * Peeks the discriminator, branches per variant, delegates to each variant's peekFrameSize.
     *
     * When `dispatchOnInfo.hasBodyLength` is true, the framing collapses to
     * `[discriminator][lengthPrefix(bodyLength)][body]` — total frame size is determined
     * by the length prefix alone and we do not need per-variant peek delegation.
     */
    private fun buildSealedPeekFrameSize(
        variants: List<WireMatch>,
        dispatchOnInfo: DispatchOnInfo?,
    ): SealedPeekResult? {
        val discriminatorSize =
            if (dispatchOnInfo != null) {
                if (dispatchOnInfo.constructorParams.isEmpty()) return null
                dispatchOnInfo.totalWireBytes
            } else {
                1 // default: single byte
            }

        // For body-framed dispatch the minimum header is discriminator + 1. The framer's
        // `peekFrameSize` consults the actual prefix bytes to decide NeedsMoreData; we
        // only need a conservative floor here to gate "is it worth peeking yet?".
        val minHeaderBytes =
            if (dispatchOnInfo?.framing != null) discriminatorSize + 1 else discriminatorSize

        val minHeaderProp =
            PropertySpec
                .builder("MIN_HEADER_BYTES", INT)
                .addModifiers(KModifier.CONST)
                .initializer("%L", minHeaderBytes)
                .build()

        return SealedPeekResult(
            minHeaderProperty = minHeaderProp,
            syncFun = buildSealedPeekFun(variants, dispatchOnInfo, discriminatorSize, suspending = false),
            // Framer interface is sync-only; skip the suspending overload entirely when framed.
            suspendFun =
                if (dispatchOnInfo?.framing != null) {
                    null
                } else {
                    buildSealedPeekFun(variants, dispatchOnInfo, discriminatorSize, suspending = true)
                },
        )
    }

    private fun buildSealedPeekFun(
        variants: List<WireMatch>,
        dispatchOnInfo: DispatchOnInfo?,
        discriminatorSize: Int,
        suspending: Boolean,
    ): FunSpec {
        val streamType = if (suspending) SUSPENDING_STREAM_PROCESSOR else STREAM_PROCESSOR
        val builder =
            FunSpec
                .builder("peekFrameSize")
                .addParameter("stream", streamType)

        if (suspending) {
            builder.addParameter(
                com.squareup.kotlinpoet.ParameterSpec
                    .builder("baseOffset", INT)
                    .defaultValue("0")
                    .build(),
            )
            builder.addModifiers(KModifier.SUSPEND)
        } else {
            builder.addParameter("baseOffset", INT)
            builder.addModifiers(KModifier.OVERRIDE)
        }
        builder.returns(PEEK_RESULT)

        val code = CodeBlock.builder()
        code.addStatement("if (stream.available() < baseOffset + %L) return %T.NeedsMoreData", discriminatorSize, PEEK_RESULT)

        val framing = dispatchOnInfo?.framing
        if (framing != null) {
            // Delegate the entire frame-size computation to the user-supplied framer.
            // The framer's peekFrameSize must include the discriminator bytes in its result.
            code.addStatement("return %L.peekFrameSize(stream, baseOffset)", framing.framerFqn)
            builder.addCode(code.build())
            return builder.build()
        }

        // Peek and extract the dispatch value
        val hasRanges = variants.any { it is WireMatch.Range }
        if (dispatchOnInfo != null) {
            if (dispatchOnInfo.isValueClass) {
                // Value class: peek inner type, wrap in constructor
                val peekExpr = discriminatorPeekExpr("stream", "baseOffset", dispatchOnInfo.innerTypeName)
                code.addStatement("val _raw = %L", peekExpr)
                code.addStatement(
                    "val type = %T(_raw).%L",
                    dispatchOnInfo.poetClassName,
                    dispatchOnInfo.dispatchProperty,
                )
                if (hasRanges) {
                    code.addStatement("val rawByte = _raw.toInt() and 0xFF")
                }
            } else {
                // Data class: peek each constructor parameter, build the object
                var paramOffset = 0
                val paramExprs = mutableListOf<String>()
                for (param in dispatchOnInfo.constructorParams) {
                    val peekExpr = discriminatorPeekExpr("stream", "baseOffset + $paramOffset", paramTypeName(param.typeName))
                    paramExprs.add(peekExpr)
                    paramOffset += param.wireBytes
                }
                code.addStatement(
                    "val type = %T(%L).%L",
                    dispatchOnInfo.poetClassName,
                    paramExprs.joinToString(", "),
                    dispatchOnInfo.dispatchProperty,
                )
            }
        } else {
            code.addStatement("val type = stream.peekByte(baseOffset).toInt() and 0xFF")
        }

        // Branch per variant, delegate to variant's peekFrameSize
        val orderedVariants =
            variants.filterIsInstance<WireMatch.Range>().sortedBy { it.from } +
                variants.filterIsInstance<WireMatch.Point>().sortedBy { it.wire }
        val useWhenBlock = dispatchOnInfo != null && hasRanges
        if (useWhenBlock) code.beginControlFlow("return when") else code.beginControlFlow("return when (type)")
        for (v in orderedVariants) {
            val variantCodecName = v.subclass.codecName()
            val arm =
                if (useWhenBlock) {
                    whenBlockArmKey(v)
                } else {
                    whenArmKey(v)
                }
            code.addStatement(
                "%L -> when (val r = %L.peekFrameSize(stream, baseOffset + %L)) { is %T.Size -> %T.Size(r.bytes + %L); else -> r }",
                arm,
                variantCodecName,
                discriminatorSize,
                PEEK_RESULT,
                PEEK_RESULT,
                discriminatorSize,
            )
        }
        code.addStatement("else -> %T.NeedsMoreData", PEEK_RESULT)
        code.endControlFlow()

        builder.addCode(code.build())
        return builder.build()
    }

    /** Returns the wire byte count for a discriminator's inner type name. */
    private fun innerTypeWireBytes(innerTypeName: String): Int? =
        when (innerTypeName) {
            "UByte", "Byte" -> 1
            "UShort", "Short" -> 2
            "UInt", "Int" -> 4
            "ULong", "Long" -> 8
            else -> null
        }

    /** Extracts the simple type name from a qualified primitive type (e.g., "kotlin.UInt" -> "UInt"). */
    private fun paramTypeName(qualifiedName: String): String = qualifiedName.substringAfterLast('.')

    /** Generates a peek expression for the discriminator raw value, casting to the inner type. */
    private fun discriminatorPeekExpr(
        stream: String,
        offset: String,
        innerTypeName: String,
    ): String =
        when (innerTypeName) {
            "UByte" -> "$stream.peekByte($offset).toUByte()"
            "Byte" -> "$stream.peekByte($offset)"
            "UShort" -> "$stream.peekShort($offset).toUShort()"
            "Short" -> "$stream.peekShort($offset)"
            "UInt" -> "$stream.peekInt($offset).toUInt()"
            "Int" -> "$stream.peekInt($offset)"
            "ULong" -> "$stream.peekLong($offset).toULong()"
            "Long" -> "$stream.peekLong($offset)"
            else -> "$stream.peekByte($offset).toUByte()" // fallback
        }

    /** Variable name used by variant-decode call sites: `"_bodySlice"` when bodyLength
     * framing is in effect, `"buffer"` otherwise. */
    internal fun bodyVarName(info: DispatchOnInfo?): String = if (info?.hasBodyLength == true) "_bodySlice" else "buffer"

    /**
     * Emits a post-dispatch assertion that the body slice was fully consumed by the
     * variant decoder. Catches malformed wires where the VBI claims more bytes than
     * the variant actually reads — without this, those trailing bytes would silently
     * leak into the next frame.
     */
    internal fun emitBodyLengthOverrunCheck(
        code: CodeBlock.Builder,
        unknownException: ClassName,
    ) {
        code.beginControlFlow("if (_bodySlice.remaining() != 0)")
        code.addStatement(
            "throw %T(%P)",
            unknownException,
            "Variant decoder consumed \${_bodyLen - _bodySlice.remaining()} of \$_bodyLen body bytes; " +
                "\${_bodySlice.remaining()} unread. Wire is malformed or variant codec is buggy.",
        )
        code.endControlFlow()
    }

    /**
     * Emits the decode-side prelude for body-length framing: delegates to the framer's
     * `readBodyLength(buffer)` and slices the body. No-op when [info] is null, has no
     * framer, or carries a peek-only framer — peek-only framers don't have a separate
     * length prefix on the wire (WebSocket carries the payload length inside the header).
     */
    internal fun emitBodyLengthDecodePrelude(
        code: CodeBlock.Builder,
        info: DispatchOnInfo?,
        @Suppress("UNUSED_PARAMETER") contextVarName: String,
    ) {
        if (info?.hasBodyLength != true) return
        val framing = info.framing ?: return
        code.addStatement("val _bodyLen = %L.readBodyLength(buffer)", framing.framerFqn)
        code.addStatement("val _bodySlice = buffer.readBytes(_bodyLen)")
    }

    /**
     * Wraps an encode statement in the body-length framing helper when [info] requests it.
     * For non-framed dispatch (or when [info] is null), emits [encodeStmt] verbatim.
     *
     * When [bodySizeExpr] is provided, emits the inline-prefix path: compute body size,
     * call `framer.writeBodyLength(buffer, size)`, then run the encode statement.
     *
     * When null (e.g. typed-lambda payload variants where size lambdas aren't available
     * because each `<@Payload P>` lambda's wire size depends on a runtime callback), uses
     * a scratch buffer to encode the body, then writes the framer's prefix sized to that
     * scratch's resulting bytes.
     */
    internal fun emitBodyLengthEncodeWrap(
        code: CodeBlock.Builder,
        info: DispatchOnInfo?,
        encodeStmt: String,
        bodySizeExpr: String? = null,
    ) {
        // Peek-only framing carries the payload length inside the discriminator/header
        // bytes themselves (WebSocket), so there is no separate length prefix to write —
        // emit the body verbatim and skip the framer's writeBodyLength call (which doesn't
        // exist on `DispatchFraming`, only on `BodyLengthFraming`).
        if (info?.hasBodyLength != true) {
            code.addStatement(encodeStmt)
            return
        }
        val framing = info.framing ?: run {
            code.addStatement(encodeStmt)
            return
        }
        if (bodySizeExpr != null) {
            code.addStatement("val _len_body = %L", bodySizeExpr)
            code.addStatement("%L.writeBodyLength(buffer, _len_body)", framing.framerFqn)
            code.addStatement(encodeStmt)
        } else {
            // Scratch-buffer fallback for typed-lambda payload variants where the body
            // size isn't computable up-front. Encode into the scratch, then frame the
            // result. The scratch is allocated through BufferFactory.Default so the
            // generated code stays platform-agnostic.
            code.addStatement(
                "val _scratch = %T.Default.allocate(buffer.remaining().coerceAtLeast(16), buffer.byteOrder)",
                ClassName("com.ditchoom.buffer", "BufferFactory"),
            )
            // Substitute the user-passed `buffer` identifier inside encodeStmt with `_scratch`.
            // The substring patterns we know the dispatcher emits: "buffer," / "buffer)" /
            // "buffer ".
            val scratchEncodeStmt =
                encodeStmt
                    .replace("buffer,", "_scratch,")
                    .replace("buffer)", "_scratch)")
                    .replace("buffer ", "_scratch ")
            code.addStatement(scratchEncodeStmt)
            code.addStatement("_scratch.resetForRead()")
            code.addStatement("%L.writeBodyLength(buffer, _scratch.remaining())", framing.framerFqn)
            code.addStatement("buffer.write(_scratch)")
        }
    }
}
