package com.ditchoom.buffer.codec.processor

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Modifier
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeSpec

/**
 * Stage A + B emitter.
 *
 * Generates a sibling `object ${MessageName}Codec : Codec<${MessageName}>`
 * for each `@ProtocolMessage`-annotated symbol whose shape fits the
 * supported surface:
 *
 *   - **Stage A — fixed-size unsigned scalar fields.** A `data class`
 *     (or `@JvmInline value class`) with one or more `UByte` /
 *     `UShort` / `UInt` / `ULong` fields, each with optional
 *     `@WireOrder` per-field overrides of the message-level wireOrder.
 *   - **Stage A — `@LengthPrefixed @ProtocolMessage`-typed body.** A
 *     trailing field of `@ProtocolMessage` data class type, length-
 *     prefixed by `LengthPrefix.{Byte|Short|Int}` in the message wire
 *     order; emitter generates `setLimit` + restore decode, prefix-
 *     peek `peekFrameSize`, and the slice-4 lock #4 `Int.MAX_VALUE`
 *     overflow guards.
 *   - **Stage B — `@WireBytes(N)` narrowing.** A scalar field whose
 *     wire width is narrower than the Kotlin type's natural size.
 *     Always uses manual byte assembly; effective byte order falls
 *     back to `Big` (network) when neither the field nor the message
 *     declares one. Encode emits an `EncodeException` runtime guard
 *     when the value exceeds the narrowed range.
 *   - **Stage B — value-class wrapper at the top level.** A
 *     `@JvmInline value class` with a single inner unsigned scalar is
 *     treated as a one-field shape. The codec wraps the read scalar
 *     into the value class on decode and unwraps it via the inner
 *     property name on encode. Bit-packed logical fields exposed as
 *     getters in user code are invisible to the emitter (they
 *     introduce no wire format).
 *
 * Anything outside this surface — `@LengthFrom`, `@WhenTrue`,
 * `@RemainingBytes`, `@UseCodec`, sealed dispatch (`@DispatchOn`,
 * `@PacketType`), signed scalars, `String` fields, `@LengthPrefixed`
 * on a non-terminal field — is silently skipped here and picked up by
 * later stages as their capability lands.
 */
internal class CodecEmitter(
    private val codeGenerator: CodeGenerator,
    @Suppress("unused") private val logger: KSPLogger,
) {
    fun tryEmit(symbol: KSClassDeclaration) {
        val shape = analyze(symbol) ?: return
        val sourceFile = symbol.containingFile ?: return
        val file = buildFileSpec(shape)
        codeGenerator
            .createNewFile(
                Dependencies(aggregating = false, sourceFile),
                shape.packageName,
                shape.codecSimpleName,
            ).bufferedWriter()
            .use { writer ->
                file.writeTo(writer)
            }
    }

    private fun analyze(symbol: KSClassDeclaration): CodecShape? {
        if (symbol.classKind != ClassKind.CLASS) return null
        val isData = Modifier.DATA in symbol.modifiers
        val isValue = Modifier.VALUE in symbol.modifiers
        if (!isData && !isValue) return null
        if (Modifier.SEALED in symbol.modifiers) return null
        if (symbol.annotations.any { it.shortName.asString() == "DispatchOn" }) return null
        if (symbol.annotations.any { it.shortName.asString() == "PacketType" }) return null
        val ctor = symbol.primaryConstructor ?: return null
        if (ctor.parameters.isEmpty()) return null
        // Value class must have exactly one primary constructor parameter (Kotlin
        // already enforces this, but we add a defensive guard rather than relying on it).
        if (isValue && ctor.parameters.size != 1) return null

        val ownerSimpleName = symbol.simpleName.asString()
        val messageWireOrder = readMessageWireOrder(symbol)

        val fields = mutableListOf<FieldSpec>()
        val params = ctor.parameters
        for ((index, param) in params.withIndex()) {
            val isTerminal = index == params.lastIndex
            val field = analyzeField(param, messageWireOrder, ownerSimpleName, isTerminal) ?: return null
            fields += field
        }

        val pkg = symbol.packageName.asString()
        return CodecShape(
            packageName = pkg,
            messageClassName = ClassName(pkg, ownerSimpleName),
            ownerSimpleName = ownerSimpleName,
            codecSimpleName = "${ownerSimpleName}Codec",
            fields = fields,
        )
    }

    private fun analyzeField(
        param: KSValueParameter,
        messageWireOrder: Endianness,
        ownerSimpleName: String,
        isTerminal: Boolean,
    ): FieldSpec? {
        var lengthPrefixed: KSAnnotation? = null
        var wireBytesAnn: KSAnnotation? = null
        for (ann in param.annotations) {
            when (ann.shortName.asString()) {
                "WireOrder" -> { /* allowed on scalars */ }
                "LengthPrefixed" -> lengthPrefixed = ann
                "WireBytes" -> wireBytesAnn = ann
                else -> return null
            }
        }
        val name = param.name?.asString() ?: return null
        val type = param.type.resolve()
        if (type.isError) return null
        if (type.isMarkedNullable) return null

        if (lengthPrefixed != null) {
            // `@LengthPrefixed` and `@WireBytes` together is meaningless and
            // out of scope for this emitter; bail rather than try to interpret.
            if (wireBytesAnn != null) return null
            if (!isTerminal) return null
            val decl = type.declaration as? KSClassDeclaration ?: return null
            val isProtocolMessage =
                decl.annotations.any { ann ->
                    ann.shortName.asString() == "ProtocolMessage" &&
                        ann.annotationType
                            .resolve()
                            .declaration.qualifiedName
                            ?.asString() == PROTOCOL_MESSAGE_QNAME
                }
            if (!isProtocolMessage) return null
            if (Modifier.DATA !in decl.modifiers) return null
            val prefixWidth = readLengthPrefix(lengthPrefixed)
            return FieldSpec.LengthPrefixedMessage(
                name = name,
                ownerSimpleName = ownerSimpleName,
                messageType = ClassName(decl.packageName.asString(), decl.simpleName.asString()),
                codecType = ClassName(decl.packageName.asString(), "${decl.simpleName.asString()}Codec"),
                prefixWidth = prefixWidth,
                prefixWireOrder = messageWireOrder,
            )
        }

        val qualified = type.declaration.qualifiedName?.asString() ?: return null
        val kind = SUPPORTED_SCALARS[qualified] ?: return null
        val resolved = readFieldWireOrder(param) ?: messageWireOrder
        val wireBytes = wireBytesAnn?.let { readWireBytes(it) } ?: kind.width
        // R4 narrows what the emitter accepts; the validator emits the actual
        // diagnostic. Skip emission for unsupported widths so generated code
        // never references an out-of-bounds bit shift.
        if (wireBytes < 1 || wireBytes > 8) return null
        if (wireBytes > kind.width) return null
        return FieldSpec.Scalar(
            name = name,
            kind = kind,
            resolvedWireOrder = resolved,
            wireBytes = wireBytes,
        )
    }

    private fun readMessageWireOrder(symbol: KSClassDeclaration): Endianness {
        val ann =
            symbol.annotations.firstOrNull { a ->
                a.shortName.asString() == "ProtocolMessage" &&
                    a.annotationType
                        .resolve()
                        .declaration.qualifiedName
                        ?.asString() == PROTOCOL_MESSAGE_QNAME
            } ?: return Endianness.Default
        val arg = ann.arguments.firstOrNull { it.name?.asString() == "wireOrder" }?.value
        return parseEndianness(arg) ?: Endianness.Default
    }

    private fun readFieldWireOrder(param: KSValueParameter): Endianness? {
        val ann = param.annotations.firstOrNull { it.shortName.asString() == "WireOrder" } ?: return null
        val arg = ann.arguments.firstOrNull { it.name?.asString() == "order" }?.value
        return parseEndianness(arg)
    }

    private fun parseEndianness(arg: Any?): Endianness? {
        val name =
            when (arg) {
                is KSType -> arg.declaration.simpleName.asString()
                is KSClassDeclaration -> arg.simpleName.asString()
                else -> arg?.toString()?.substringAfterLast('.')
            } ?: return null
        return when (name) {
            "Default" -> Endianness.Default
            "Big" -> Endianness.Big
            "Little" -> Endianness.Little
            else -> null
        }
    }

    private fun readLengthPrefix(ann: KSAnnotation): Int {
        val arg = ann.arguments.firstOrNull { it.name?.asString() == "prefix" }?.value
        val name =
            when (arg) {
                is KSType -> arg.declaration.simpleName.asString()
                is KSClassDeclaration -> arg.simpleName.asString()
                else -> arg?.toString()?.substringAfterLast('.')
            }
        return when (name) {
            "Byte" -> 1
            "Short" -> 2
            "Int" -> 4
            // Default per Annotations.kt: LengthPrefix.Short.
            else -> 2
        }
    }

    private fun readWireBytes(ann: KSAnnotation): Int {
        val arg =
            ann.arguments.firstOrNull { it.name?.asString() == "value" }?.value
                ?: ann.arguments.firstOrNull()?.value
        return (arg as? Int) ?: -1
    }

    private fun buildFileSpec(shape: CodecShape): FileSpec {
        val codecType =
            TypeSpec
                .objectBuilder(shape.codecSimpleName)
                .addSuperinterface(CODEC_CN.parameterizedBy(shape.messageClassName))
                .addFunction(buildDecodeFun(shape))
                .addFunction(buildEncodeFun(shape))
                .addFunction(buildWireSizeFun(shape))
                .addFunction(buildPeekFrameFun(shape))
                .build()
        return FileSpec
            .builder(shape.packageName, shape.codecSimpleName)
            .addType(codecType)
            .build()
    }

    private fun buildDecodeFun(shape: CodecShape): FunSpec {
        val body = CodeBlock.builder()
        for (field in shape.fields) {
            when (field) {
                is FieldSpec.Scalar -> appendDecodeScalar(body, field)
                is FieldSpec.LengthPrefixedMessage -> appendDecodeLengthPrefixed(body, field)
            }
        }
        val ctorArgs = shape.fields.joinToString(", ") { "${it.name} = ${it.name}" }
        body.addStatement("return %T(%L)", shape.messageClassName, ctorArgs)
        return FunSpec
            .builder("decode")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("buffer", READ_BUFFER_CN)
            .addParameter("context", DECODE_CONTEXT_CN)
            .returns(shape.messageClassName)
            .addCode(body.build())
            .build()
    }

    private fun buildEncodeFun(shape: CodecShape): FunSpec {
        val body = CodeBlock.builder()
        for (field in shape.fields) {
            when (field) {
                is FieldSpec.Scalar -> appendEncodeScalar(body, field, shape.ownerSimpleName)
                is FieldSpec.LengthPrefixedMessage -> appendEncodeLengthPrefixed(body, field)
            }
        }
        return FunSpec
            .builder("encode")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("buffer", WRITE_BUFFER_CN)
            .addParameter("value", shape.messageClassName)
            .addParameter("context", ENCODE_CONTEXT_CN)
            .addCode(body.build())
            .build()
    }

    private fun buildWireSizeFun(shape: CodecShape): FunSpec {
        val builder =
            FunSpec
                .builder("wireSize")
                .addModifiers(KModifier.OVERRIDE)
                .addParameter("value", shape.messageClassName)
                .addParameter("context", ENCODE_CONTEXT_CN)
                .returns(WIRE_SIZE_CN)
        val terminal = shape.fields.lastOrNull() as? FieldSpec.LengthPrefixedMessage
        if (terminal == null) {
            val total = shape.fields.sumOf { (it as FieldSpec.Scalar).wireBytes }
            builder.addStatement("return %T.Exact(%L)", WIRE_SIZE_CN, total)
        } else {
            val headerBytes = scalarHeaderBytes(shape) + terminal.prefixWidth
            builder.addStatement(
                "val %L = (%T.wireSize(value.%L, context) as %T.Exact).bytes",
                "${terminal.name}Size",
                terminal.codecType,
                terminal.name,
                WIRE_SIZE_CN,
            )
            builder.addStatement(
                "return %T.Exact(%L + %L)",
                WIRE_SIZE_CN,
                headerBytes,
                "${terminal.name}Size",
            )
        }
        return builder.build()
    }

    private fun buildPeekFrameFun(shape: CodecShape): FunSpec {
        val builder =
            FunSpec
                .builder("peekFrameSize")
                .addModifiers(KModifier.OVERRIDE)
                .addParameter("stream", STREAM_PROCESSOR_CN)
                .addParameter("baseOffset", INT)
                .returns(PEEK_RESULT_CN)
        val terminal = shape.fields.lastOrNull() as? FieldSpec.LengthPrefixedMessage
        if (terminal == null) {
            val total = shape.fields.sumOf { (it as FieldSpec.Scalar).wireBytes }
            builder.addStatement(
                "return if (stream.available() - baseOffset >= %L) %T.Complete(%L) else %T.NeedsMoreData",
                total,
                PEEK_RESULT_CN,
                total,
                PEEK_RESULT_CN,
            )
            return builder.build()
        }
        val prefixOffset = scalarHeaderBytes(shape)
        val headerBytes = prefixOffset + terminal.prefixWidth
        val body = CodeBlock.builder()
        body.addStatement(
            "if (stream.available() - baseOffset < %L) return %T.NeedsMoreData",
            headerBytes,
            PEEK_RESULT_CN,
        )
        appendPeekPrefixAssembly(body, terminal, prefixOffset)
        body.beginControlFlow(
            "if (%L > (Int.MAX_VALUE - %L).toUInt())",
            "${terminal.name}Prefix",
            headerBytes,
        )
        body.addStatement(
            "throw %T(fieldPath = %S, bufferPosition = baseOffset + %L, expected = %S, actual = %P)",
            DECODE_EXCEPTION_CN,
            "${terminal.ownerSimpleName}.${terminal.name}",
            prefixOffset,
            "$headerBytes + length prefix <= \${Int.MAX_VALUE}",
            "$headerBytes + \${${terminal.name}Prefix}",
        )
        body.endControlFlow()
        body.addStatement("val total = %L + %L.toInt()", headerBytes, "${terminal.name}Prefix")
        body.addStatement(
            "return if (stream.available() - baseOffset >= total) %T.Complete(total) else %T.NeedsMoreData",
            PEEK_RESULT_CN,
            PEEK_RESULT_CN,
        )
        builder.addCode(body.build())
        return builder.build()
    }

    private fun scalarHeaderBytes(shape: CodecShape): Int =
        shape.fields
            .filterIsInstance<FieldSpec.Scalar>()
            .sumOf { it.wireBytes }

    private fun appendDecodeScalar(
        body: CodeBlock.Builder,
        field: FieldSpec.Scalar,
    ) {
        val needsManual =
            field.wireBytes != field.kind.width || field.resolvedWireOrder != Endianness.Default
        if (!needsManual) {
            val read =
                when (field.kind) {
                    ScalarKind.UByte -> "readUByte"
                    ScalarKind.UShort -> "readUShort"
                    ScalarKind.UInt -> "readUInt"
                    ScalarKind.ULong -> "readULong"
                }
            body.addStatement("val %L = buffer.%L()", field.name, read)
            return
        }
        val bigEndian =
            when (field.resolvedWireOrder) {
                Endianness.Little -> false
                Endianness.Big, Endianness.Default -> true
            }
        appendManualScalarDecode(body, field, bigEndian)
    }

    private fun appendManualScalarDecode(
        body: CodeBlock.Builder,
        field: FieldSpec.Scalar,
        bigEndian: Boolean,
    ) {
        val width = field.wireBytes
        if (field.kind == ScalarKind.UByte && width == 1) {
            body.addStatement("val %L = buffer.readUByte()", field.name)
            return
        }
        val accumulator = if (width >= 5) "toULong" else "toUInt"
        for (i in 0 until width) {
            body.addStatement(
                "val %L = buffer.readUByte().%L()",
                "${field.name}B$i",
                accumulator,
            )
        }
        val parts =
            (0 until width).map { i ->
                val byteName = "${field.name}B$i"
                val shiftBits = if (bigEndian) (width - 1 - i) * 8 else i * 8
                if (shiftBits == 0) byteName else "($byteName shl $shiftBits)"
            }
        val combined = if (parts.size == 1) parts[0] else "(${parts.joinToString(" or ")})"
        val narrow =
            when (field.kind) {
                ScalarKind.UByte -> ".toUByte()"
                ScalarKind.UShort -> ".toUShort()"
                ScalarKind.UInt, ScalarKind.ULong -> ""
            }
        body.addStatement("val %L = %L%L", field.name, combined, narrow)
    }

    private fun appendEncodeScalar(
        body: CodeBlock.Builder,
        field: FieldSpec.Scalar,
        ownerSimpleName: String,
    ) {
        appendEncodeGuard(body, field, ownerSimpleName)
        val accessor = "value.${field.name}"
        val needsManual =
            field.wireBytes != field.kind.width || field.resolvedWireOrder != Endianness.Default
        if (!needsManual) {
            val write =
                when (field.kind) {
                    ScalarKind.UByte -> "writeUByte"
                    ScalarKind.UShort -> "writeUShort"
                    ScalarKind.UInt -> "writeUInt"
                    ScalarKind.ULong -> "writeULong"
                }
            body.addStatement("buffer.%L(%L)", write, accessor)
            return
        }
        val bigEndian =
            when (field.resolvedWireOrder) {
                Endianness.Little -> false
                Endianness.Big, Endianness.Default -> true
            }
        appendManualScalarEncode(body, field, accessor, bigEndian)
    }

    private fun appendEncodeGuard(
        body: CodeBlock.Builder,
        field: FieldSpec.Scalar,
        ownerSimpleName: String,
    ) {
        if (field.wireBytes >= field.kind.width) return
        val accessor = "value.${field.name}"
        val (lhs, maxLit) =
            when (field.kind) {
                ScalarKind.ULong -> accessor to "((1uL shl ${8 * field.wireBytes}) - 1uL)"
                ScalarKind.UInt -> accessor to "((1u shl ${8 * field.wireBytes}) - 1u)"
                ScalarKind.UShort -> "$accessor.toUInt()" to "((1u shl ${8 * field.wireBytes}) - 1u)"
                ScalarKind.UByte -> return // wireBytes < 1 is rejected by analyzeField
            }
        val maxValue = (1L shl (8 * field.wireBytes)) - 1
        body.beginControlFlow("if (%L > %L)", lhs, maxLit)
        body.addStatement(
            "throw %T(fieldPath = %S, reason = %S)",
            ENCODE_EXCEPTION_CN,
            "$ownerSimpleName.${field.name}",
            "value exceeds @WireBytes(${field.wireBytes}) range (max $maxValue)",
        )
        body.endControlFlow()
    }

    private fun appendManualScalarEncode(
        body: CodeBlock.Builder,
        field: FieldSpec.Scalar,
        accessor: String,
        bigEndian: Boolean,
    ) {
        val width = field.wireBytes
        if (field.kind == ScalarKind.UByte && width == 1) {
            body.addStatement("buffer.writeUByte(%L)", accessor)
            return
        }
        val widePromote =
            when (field.kind) {
                ScalarKind.UByte, ScalarKind.UShort -> ".toUInt()"
                ScalarKind.UInt, ScalarKind.ULong -> ""
            }
        val wide = "$accessor$widePromote"
        val maskLit = if (width >= 5) "0xFFuL" else "0xFFu"
        for (i in 0 until width) {
            val shiftBits = if (bigEndian) (width - 1 - i) * 8 else i * 8
            val expr =
                if (shiftBits == 0) {
                    "$wide and $maskLit"
                } else {
                    "($wide shr $shiftBits) and $maskLit"
                }
            body.addStatement("buffer.writeUByte((%L).toUByte())", expr)
        }
    }

    private fun appendDecodeLengthPrefixed(
        body: CodeBlock.Builder,
        field: FieldSpec.LengthPrefixedMessage,
    ) {
        val prefixVar = "${field.name}Prefix"
        appendBufferPrefixDecode(body, prefixVar, field.prefixWidth, field.prefixWireOrder)
        body.beginControlFlow("if (%L > Int.MAX_VALUE.toUInt())", prefixVar)
        body.addStatement(
            "throw %T(fieldPath = %S, bufferPosition = -1, expected = %S, actual = %L.toString())",
            DECODE_EXCEPTION_CN,
            "${field.ownerSimpleName}.${field.name}",
            "length prefix <= \${Int.MAX_VALUE}",
            prefixVar,
        )
        body.endControlFlow()
        val resolvedVar = "${field.name}Length"
        body.addStatement("val %L = %L.toInt()", resolvedVar, prefixVar)
        val outerVar = "${field.name}OuterLimit"
        body.addStatement("val %L = buffer.limit()", outerVar)
        body.addStatement("buffer.setLimit(buffer.position() + %L)", resolvedVar)
        body.beginControlFlow("val %L = try", field.name)
        body.addStatement("%T.decode(buffer, context)", field.codecType)
        body.nextControlFlow("finally")
        body.addStatement("buffer.setLimit(%L)", outerVar)
        body.endControlFlow()
    }

    private fun appendBufferPrefixDecode(
        body: CodeBlock.Builder,
        targetVar: String,
        prefixWidth: Int,
        wireOrder: Endianness,
    ) {
        if (prefixWidth == 1) {
            body.addStatement("val %L = buffer.readUByte().toUInt()", targetVar)
            return
        }
        val bigEndian =
            when (wireOrder) {
                Endianness.Big -> true
                Endianness.Little -> false
                Endianness.Default -> true
            }
        for (i in 0 until prefixWidth) {
            body.addStatement(
                "val %L = buffer.readUByte().toUInt()",
                "${targetVar}B$i",
            )
        }
        val parts =
            (0 until prefixWidth).map { i ->
                val byteName = "${targetVar}B$i"
                val shiftBits = if (bigEndian) (prefixWidth - 1 - i) * 8 else i * 8
                if (shiftBits == 0) byteName else "($byteName shl $shiftBits)"
            }
        body.addStatement("val %L = (%L)", targetVar, parts.joinToString(" or "))
    }

    private fun appendEncodeLengthPrefixed(
        body: CodeBlock.Builder,
        field: FieldSpec.LengthPrefixedMessage,
    ) {
        val prefixVar = "${field.name}Prefix"
        body.addStatement(
            "val %L = (%T.wireSize(value.%L, context) as %T.Exact).bytes.toUInt()",
            prefixVar,
            field.codecType,
            field.name,
            WIRE_SIZE_CN,
        )
        appendBufferPrefixEncode(body, prefixVar, field.prefixWidth, field.prefixWireOrder)
        body.addStatement("%T.encode(buffer, value.%L, context)", field.codecType, field.name)
    }

    private fun appendBufferPrefixEncode(
        body: CodeBlock.Builder,
        prefixVar: String,
        prefixWidth: Int,
        wireOrder: Endianness,
    ) {
        if (prefixWidth == 1) {
            body.addStatement("buffer.writeUByte((%L and 0xFFu).toUByte())", prefixVar)
            return
        }
        val bigEndian =
            when (wireOrder) {
                Endianness.Big -> true
                Endianness.Little -> false
                Endianness.Default -> true
            }
        for (i in 0 until prefixWidth) {
            val shiftBits = if (bigEndian) (prefixWidth - 1 - i) * 8 else i * 8
            val expr =
                if (shiftBits == 0) {
                    "$prefixVar and 0xFFu"
                } else {
                    "($prefixVar shr $shiftBits) and 0xFFu"
                }
            body.addStatement("buffer.writeUByte((%L).toUByte())", expr)
        }
    }

    private fun appendPeekPrefixAssembly(
        body: CodeBlock.Builder,
        field: FieldSpec.LengthPrefixedMessage,
        prefixOffset: Int,
    ) {
        val prefixVar = "${field.name}Prefix"
        val width = field.prefixWidth
        if (width == 1) {
            body.addStatement(
                "val %L = (stream.peekByte(baseOffset + %L).toInt() and 0xFF).toUInt()",
                prefixVar,
                prefixOffset,
            )
            return
        }
        val bigEndian =
            when (field.prefixWireOrder) {
                Endianness.Big -> true
                Endianness.Little -> false
                Endianness.Default -> true
            }
        for (i in 0 until width) {
            body.addStatement(
                "val %L = stream.peekByte(baseOffset + %L).toInt() and 0xFF",
                "${prefixVar}B$i",
                prefixOffset + i,
            )
        }
        val parts =
            (0 until width).map { i ->
                val byteName = "${prefixVar}B$i"
                val shiftBits = if (bigEndian) (width - 1 - i) * 8 else i * 8
                if (shiftBits == 0) byteName else "($byteName shl $shiftBits)"
            }
        body.addStatement("val %L = (%L).toUInt()", prefixVar, parts.joinToString(" or "))
    }

    private data class CodecShape(
        val packageName: String,
        val messageClassName: ClassName,
        val ownerSimpleName: String,
        val codecSimpleName: String,
        val fields: List<FieldSpec>,
    )

    private sealed interface FieldSpec {
        val name: String

        data class Scalar(
            override val name: String,
            val kind: ScalarKind,
            val resolvedWireOrder: Endianness,
            val wireBytes: Int,
        ) : FieldSpec

        data class LengthPrefixedMessage(
            override val name: String,
            val ownerSimpleName: String,
            val messageType: ClassName,
            val codecType: ClassName,
            val prefixWidth: Int,
            val prefixWireOrder: Endianness,
        ) : FieldSpec
    }

    private enum class ScalarKind(
        val width: Int,
    ) {
        UByte(1),
        UShort(2),
        UInt(4),
        ULong(8),
    }

    private enum class Endianness {
        Default,
        Big,
        Little,
    }

    private companion object {
        private const val PROTOCOL_MESSAGE_QNAME = "com.ditchoom.buffer.codec.annotations.ProtocolMessage"

        private val SUPPORTED_SCALARS =
            mapOf(
                "kotlin.UByte" to ScalarKind.UByte,
                "kotlin.UShort" to ScalarKind.UShort,
                "kotlin.UInt" to ScalarKind.UInt,
                "kotlin.ULong" to ScalarKind.ULong,
            )

        private val READ_BUFFER_CN = ClassName("com.ditchoom.buffer", "ReadBuffer")
        private val WRITE_BUFFER_CN = ClassName("com.ditchoom.buffer", "WriteBuffer")
        private val CODEC_CN = ClassName("com.ditchoom.buffer.codec", "Codec")
        private val DECODE_CONTEXT_CN = ClassName("com.ditchoom.buffer.codec", "DecodeContext")
        private val ENCODE_CONTEXT_CN = ClassName("com.ditchoom.buffer.codec", "EncodeContext")
        private val WIRE_SIZE_CN = ClassName("com.ditchoom.buffer.codec", "WireSize")
        private val PEEK_RESULT_CN = ClassName("com.ditchoom.buffer.codec", "PeekResult")
        private val STREAM_PROCESSOR_CN = ClassName("com.ditchoom.buffer.stream", "StreamProcessor")
        private val DECODE_EXCEPTION_CN = ClassName("com.ditchoom.buffer.codec", "DecodeException")
        private val ENCODE_EXCEPTION_CN = ClassName("com.ditchoom.buffer.codec", "EncodeException")
    }
}
