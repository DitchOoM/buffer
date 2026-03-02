package com.ditchoom.buffer.codec.processor

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSClassDeclaration
import java.io.OutputStream

class CodecGenerator(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) {
    fun generate(
        classDeclaration: KSClassDeclaration,
        fields: List<FieldInfo>,
        batches: List<CodegenItem>,
        hasPayload: Boolean,
    ) {
        val className = classDeclaration.simpleName.asString()
        val packageName = classDeclaration.packageName.asString()
        val codecName = "${className}Codec"
        val containingFile = classDeclaration.containingFile

        val dependencies =
            if (containingFile != null) {
                Dependencies(true, containingFile)
            } else {
                Dependencies(true)
            }

        val file =
            codeGenerator.createNewFile(
                dependencies = dependencies,
                packageName = packageName,
                fileName = codecName,
            )

        if (hasPayload) {
            file.writePayloadCodec(packageName, className, codecName, fields, batches, classDeclaration)
        } else {
            file.writeCodec(packageName, className, codecName, fields, batches)
        }
        file.close()
    }

    // ──────────────────────── Standard (non-payload) codec ────────────────────────

    private fun OutputStream.writeCodec(
        packageName: String,
        className: String,
        codecName: String,
        fields: List<FieldInfo>,
        batches: List<CodegenItem>,
    ) {
        writeLine("package $packageName")
        writeLine()
        writeLine("import com.ditchoom.buffer.ReadBuffer")
        writeLine("import com.ditchoom.buffer.WriteBuffer")
        writeLine("import com.ditchoom.buffer.codec.Codec")
        writeLine("import com.ditchoom.buffer.readLengthPrefixedUtf8String")
        writeLine("import com.ditchoom.buffer.writeLengthPrefixedUtf8String")
        writeLine()
        writeLine("object $codecName : Codec<$className> {")
        writeLine("    override fun decode(buffer: ReadBuffer): $className {")

        // Generate decode body with batch optimization
        var batchIndex = 0
        for (item in batches) {
            when (item) {
                is CodegenItem.Batched -> {
                    writeBatchRead(item.group, batchIndex)
                    batchIndex++
                }
                is CodegenItem.Single -> {
                    generateFieldRead(item.field)
                }
            }
        }

        // Constructor call
        val fieldNames = fields.joinToString(", ") { it.name }
        writeLine("        return $className($fieldNames)")
        writeLine("    }")
        writeLine()

        // Generate encode
        writeLine("    override fun encode(buffer: WriteBuffer, value: $className) {")
        for (field in fields) {
            generateFieldWrite(field, "value.${field.name}")
        }
        writeLine("    }")

        // Generate sizeOf if all fields are fixed-size
        val totalFixedSize =
            fields.sumOf {
                val s = it.strategy.fixedSize
                if (s < 0 || it.condition != null) return@sumOf Int.MIN_VALUE
                s
            }
        if (totalFixedSize >= 0) {
            writeLine()
            writeLine("    override fun sizeOf(value: $className): Int? = $totalFixedSize")
        }

        writeLine("}")
    }

    // ──────────────────────── Payload codec ────────────────────────

    private fun OutputStream.writePayloadCodec(
        packageName: String,
        className: String,
        codecName: String,
        fields: List<FieldInfo>,
        batches: List<CodegenItem>,
        classDeclaration: KSClassDeclaration,
    ) {
        val payloadFields = fields.filter { it.strategy is FieldReadStrategy.PayloadField }
        val payloadTypeParams = payloadFields.map { (it.strategy as FieldReadStrategy.PayloadField).typeParamName }.distinct()
        val typeParamList = payloadTypeParams.joinToString(", ")
        val contextName = "${className}Context"

        writeLine("package $packageName")
        writeLine()
        writeLine("import com.ditchoom.buffer.ReadBuffer")
        writeLine("import com.ditchoom.buffer.WriteBuffer")
        writeLine("import com.ditchoom.buffer.codec.payload.PayloadReader")
        writeLine("import com.ditchoom.buffer.codec.payload.ReadBufferPayloadReader")
        writeLine("import com.ditchoom.buffer.readLengthPrefixedUtf8String")
        writeLine("import com.ditchoom.buffer.writeLengthPrefixedUtf8String")
        writeLine()
        writeLine("object $codecName {")

        // ── decode ──
        val decodeTypeParams = "<$typeParamList>"
        writeLine("    fun $decodeTypeParams decode(")
        writeLine("        buffer: ReadBuffer,")
        for (pf in payloadFields) {
            val strategy = pf.strategy as FieldReadStrategy.PayloadField
            val tpName = strategy.typeParamName
            writeLine("        decode${capitalizeFirst(pf.name)}: $contextName.(PayloadReader) -> $tpName,")
        }
        writeLine("    ): $className<$typeParamList> {")

        // Phase 1: read all wire data in order
        var batchIndex = 0
        for (item in batches) {
            when (item) {
                is CodegenItem.Batched -> {
                    writeBatchRead(item.group, batchIndex)
                    batchIndex++
                }
                is CodegenItem.Single -> {
                    val strategy = item.field.strategy
                    if (strategy is FieldReadStrategy.PayloadField) {
                        generatePayloadRawRead(item.field)
                    } else {
                        generateFieldRead(item.field)
                    }
                }
            }
        }

        // Phase 2: create context from non-payload fields
        val nonPayloadFields = fields.filter { it.strategy !is FieldReadStrategy.PayloadField }
        writeLine("        val _ctx = $contextName(${nonPayloadFields.joinToString(", ") { it.name }})")

        // Phase 3: decode payload fields using stored bytes + lambdas
        for (pf in payloadFields) {
            val rawVar = "_raw_${pf.name}"
            val condition = pf.condition
            if (condition != null || pf.isNullable) {
                writeLine("        val ${pf.name} = if ($rawVar != null) {")
                writeLine("            val _pr = ReadBufferPayloadReader($rawVar)")
                writeLine("            try { _ctx.decode${capitalizeFirst(pf.name)}(_pr) } finally { _pr.release() }")
                writeLine("        } else {")
                writeLine("            null")
                writeLine("        }")
            } else {
                writeLine("        val ${pf.name} = run {")
                writeLine("            val _pr = ReadBufferPayloadReader($rawVar)")
                writeLine("            try { _ctx.decode${capitalizeFirst(pf.name)}(_pr) } finally { _pr.release() }")
                writeLine("        }")
            }
        }

        // Phase 4: return constructor call
        val fieldNames = fields.joinToString(", ") { it.name }
        writeLine("        return $className($fieldNames)")
        writeLine("    }")
        writeLine()

        // ── encode ──
        writeLine("    fun $decodeTypeParams encode(")
        writeLine("        buffer: WriteBuffer,")
        writeLine("        value: $className<$typeParamList>,")
        for (pf in payloadFields) {
            val strategy = pf.strategy as FieldReadStrategy.PayloadField
            val tpName = strategy.typeParamName
            writeLine("        encode${capitalizeFirst(pf.name)}: (WriteBuffer, $tpName) -> Unit,")
        }
        writeLine("    ) {")
        for (field in fields) {
            val strategy = field.strategy
            if (strategy is FieldReadStrategy.PayloadField) {
                generatePayloadWrite(field)
            } else {
                generateFieldWrite(field, "value.${field.name}")
            }
        }
        writeLine("    }")

        writeLine("}")
    }

    // ──────────────────────── Payload raw read (Phase 1) ────────────────────────

    private fun OutputStream.generatePayloadRawRead(field: FieldInfo) {
        val strategy = field.strategy as FieldReadStrategy.PayloadField
        val rawVar = "_raw_${field.name}"
        val condition = field.condition

        if (condition != null) {
            val condExpr = (condition as FieldCondition.WhenTrue).expression
            writeLine("        val $rawVar: ReadBuffer? = if ($condExpr) {")
            writePayloadRawReadBody(strategy, "            ")
            writeLine("        } else {")
            writeLine("            null")
            writeLine("        }")
        } else if (field.isNullable) {
            // Nullable without condition shouldn't normally happen for payloads, but handle gracefully
            writeLine("        val $rawVar: ReadBuffer? = run {")
            writePayloadRawReadBody(strategy, "            ")
            writeLine("        }")
        } else {
            writeLine("        val $rawVar: ReadBuffer = run {")
            writePayloadRawReadBody(strategy, "            ")
            writeLine("        }")
        }
    }

    private fun OutputStream.writePayloadRawReadBody(
        strategy: FieldReadStrategy.PayloadField,
        indent: String,
    ) {
        when (val lk = strategy.lengthKind) {
            is LengthKind.Prefixed -> {
                val lenExpr = prefixConfig(lk.prefix).readExpr
                writeLine("${indent}val _len = $lenExpr")
                writeLine("${indent}buffer.readBytes(_len)")
            }
            is LengthKind.Remaining -> {
                writeLine("${indent}buffer.readBytes(buffer.remaining())")
            }
            is LengthKind.FromField -> {
                writeLine("${indent}buffer.readBytes(${lk.field}.toInt())")
            }
        }
    }

    // ──────────────────────── Payload write (encode) ────────────────────────

    private fun OutputStream.generatePayloadWrite(field: FieldInfo) {
        val strategy = field.strategy as FieldReadStrategy.PayloadField
        val condition = field.condition

        if (condition != null) {
            val condExpr = (condition as FieldCondition.WhenTrue).expression.replace(Regex("^([^.]+)"), "value.$1")
            writeLine("        if ($condExpr) {")
            writePayloadEncodeBody(strategy, field, "            ")
            writeLine("        }")
        } else {
            writePayloadEncodeBody(strategy, field, "        ")
        }
    }

    private fun OutputStream.writePayloadEncodeBody(
        strategy: FieldReadStrategy.PayloadField,
        field: FieldInfo,
        indent: String,
    ) {
        val valueExpr = if (field.isNullable && field.condition != null) "value.${field.name}!!" else "value.${field.name}"
        val encodeFn = "encode${capitalizeFirst(field.name)}"
        val suffix = "_${field.name}"

        when (val lk = strategy.lengthKind) {
            is LengthKind.Prefixed -> {
                val cfg = prefixConfig(lk.prefix)
                writeLine("${indent}val _pos$suffix = buffer.position()")
                writeLine("${indent}${cfg.writePlaceholder}")
                writeLine("${indent}$encodeFn(buffer, $valueExpr)")
                writeLine("${indent}val _end$suffix = buffer.position()")
                writeLine("${indent}val _len$suffix = _end$suffix - _pos$suffix - ${cfg.byteCount}")
                writeLine("${indent}buffer.position(_pos$suffix)")
                writeLine("${indent}${cfg.writeExpr("_len$suffix")}")
                writeLine("${indent}buffer.position(_end$suffix)")
            }
            is LengthKind.Remaining -> {
                writeLine("${indent}$encodeFn(buffer, $valueExpr)")
            }
            is LengthKind.FromField -> {
                // Length field already written
                writeLine("${indent}$encodeFn(buffer, $valueExpr)")
            }
        }
    }

    // ──────────────────────── Prefix helpers keyed by LengthPrefix enum name ────────────────────────

    private data class PrefixConfig(
        val byteCount: Int,
        val readExpr: String,
        val writePlaceholder: String,
        val writeExpr: (String) -> String,
    )

    private val prefixConfigs =
        mapOf(
            "Byte" to
                PrefixConfig(
                    1,
                    "buffer.readByte().toInt() and 0xFF",
                    "buffer.writeByte(0.toByte())",
                ) { "buffer.writeByte($it.toByte())" },
            "Short" to
                PrefixConfig(
                    2,
                    "buffer.readUnsignedShort().toInt()",
                    "buffer.writeShort(0.toShort())",
                ) { "buffer.writeShort($it.toShort())" },
            "Int" to
                PrefixConfig(
                    4,
                    "buffer.readInt()",
                    "buffer.writeInt(0)",
                ) { "buffer.writeInt($it)" },
        )

    private fun prefixConfig(prefix: String): PrefixConfig = prefixConfigs[prefix] ?: error("Unknown LengthPrefix: $prefix")

    // ──────────────────────── Batch read ────────────────────────

    private fun OutputStream.writeBatchRead(
        item: BatchGroup,
        batchIndex: Int,
    ) {
        val batchVar = "_batch$batchIndex"
        val readType =
            when (item.readMethod) {
                "readLong" -> "Long"
                "readInt" -> "Int"
                "readShort" -> "Short"
                else -> "Byte"
            }
        writeLine("        val $batchVar = buffer.${item.readMethod}()")
        if (readType == "Short" || readType == "Byte") {
            writeLine(
                "        val ${batchVar}Bits = $batchVar.toInt() and ${if (readType == "Short") "0xFFFF" else "0xFF"}",
            )
        }

        var bitOffset = item.totalBytes * 8
        for (field in item.fields) {
            val fieldSize = field.strategy.fixedSize
            val fieldBits = fieldSize * 8
            bitOffset -= fieldBits
            val extractExpr =
                generateExtractExpression(
                    batchVar = if (readType == "Short" || readType == "Byte") "${batchVar}Bits" else batchVar,
                    bitOffset = bitOffset,
                    fieldBits = fieldBits,
                    readType = readType,
                    strategy = field.strategy,
                )
            writeLine("        val ${field.name} = $extractExpr")
        }
    }

    private fun generateExtractExpression(
        batchVar: String,
        bitOffset: Int,
        fieldBits: Int,
        readType: String,
        strategy: FieldReadStrategy,
    ): String {
        val batchBits =
            when (readType) {
                "Long" -> 64
                "Int" -> 32
                "Short" -> 16
                else -> 8
            }
        val mask = if (fieldBits >= batchBits) "" else hexMask(fieldBits)

        val shift = if (bitOffset > 0) " ushr $bitOffset" else ""
        val maskApply = if (mask.isNotEmpty()) " and $mask" else ""

        val rawExpr = "($batchVar$shift$maskApply)"

        return when (strategy) {
            is FieldReadStrategy.PrimitiveField -> {
                if (strategy.wireBytes == strategy.primitive.defaultWireBytes) {
                    standardBatchCast(strategy.primitive, rawExpr, readType)
                } else {
                    customWidthBatchCast(strategy, rawExpr, readType)
                }
            }
            is FieldReadStrategy.ValueClassField -> {
                val innerExpr = generateExtractExpression(batchVar, bitOffset, fieldBits, readType, strategy.innerStrategy)
                "${strategy.wrapperType}($innerExpr)"
            }
            else -> rawExpr
        }
    }

    private fun hexMask(bits: Int): String {
        val hex = "FF".repeat(bits / 8)
        return if (bits >= 32) "0x${hex}L" else "0x$hex"
    }

    private fun standardBatchCast(
        primitive: Primitive,
        rawExpr: String,
        readType: String,
    ): String =
        when (primitive) {
            Primitive.BYTE -> "$rawExpr.toByte()"
            Primitive.UBYTE -> "$rawExpr.toUByte()"
            Primitive.SHORT -> "$rawExpr.toShort()"
            Primitive.USHORT -> "$rawExpr.toUShort()"
            Primitive.INT -> if (readType == "Long") "$rawExpr.toInt()" else rawExpr
            Primitive.UINT -> "$rawExpr.toUInt()"
            Primitive.LONG -> rawExpr
            Primitive.ULONG -> "$rawExpr.toULong()"
            Primitive.FLOAT -> "Float.fromBits($rawExpr.toInt())"
            Primitive.DOUBLE -> "Double.fromBits($rawExpr)"
            Primitive.BOOLEAN -> "$rawExpr != 0"
        }

    private fun customWidthBatchCast(
        strategy: FieldReadStrategy.PrimitiveField,
        rawExpr: String,
        readType: String,
    ): String {
        val p = strategy.primitive
        val wireBits = strategy.wireBytes * 8
        val holdingType = if (p.defaultWireBytes <= 4) "Int" else "Long"

        // Convert from batch read type to target's holding type
        val converted =
            when {
                readType == "Long" && holdingType == "Int" -> "$rawExpr.toInt()"
                readType != "Long" && holdingType == "Long" -> "$rawExpr.toLong()"
                else -> rawExpr
            }

        return if (p.signed) {
            val totalBits = if (holdingType == "Long") 64 else 32
            val shiftAmount = totalBits - wireBits
            val extended = "($converted shl $shiftAmount) shr $shiftAmount"
            typeCast(p, extended, holdingType)
        } else {
            typeCast(p, converted, holdingType)
        }
    }

    // ──────────────────────── Individual field read/write ────────────────────────

    private fun OutputStream.generateFieldRead(field: FieldInfo) {
        val condition = field.condition
        if (condition != null) {
            val condExpr = (condition as FieldCondition.WhenTrue).expression
            writeLine("        val ${field.name} = if ($condExpr) {")
            writeLine("            ${readExpression(field.strategy)}")
            writeLine("        } else {")
            writeLine("            null")
            writeLine("        }")
        } else {
            writeLine("        val ${field.name} = ${readExpression(field.strategy)}")
        }
    }

    private fun readExpression(strategy: FieldReadStrategy): String =
        when (strategy) {
            is FieldReadStrategy.PrimitiveField -> {
                if (strategy.wireBytes == strategy.primitive.defaultWireBytes) {
                    strategy.primitive.readExpr
                } else {
                    customWidthReadExpr(strategy)
                }
            }
            is FieldReadStrategy.LengthPrefixedStringField -> {
                if (strategy.prefix == "Short") {
                    "buffer.readLengthPrefixedUtf8String().second"
                } else {
                    val lenExpr = prefixConfig(strategy.prefix).readExpr
                    "run { val _len = $lenExpr; buffer.readString(_len) }"
                }
            }
            is FieldReadStrategy.RemainingBytesStringField -> "buffer.readString(buffer.remaining())"
            is FieldReadStrategy.LengthFromStringField -> "buffer.readString(${strategy.field}.toInt())"
            is FieldReadStrategy.ValueClassField -> {
                val inner = readExpression(strategy.innerStrategy)
                "${strategy.wrapperType}($inner)"
            }
            is FieldReadStrategy.NestedMessageField -> "${strategy.codecName}.decode(buffer)"
            is FieldReadStrategy.PayloadField -> error("PayloadField uses writePayloadCodec path")
        }

    private fun OutputStream.generateFieldWrite(
        field: FieldInfo,
        valueExpr: String,
    ) {
        val condition = field.condition
        if (condition != null) {
            val condExpr = (condition as FieldCondition.WhenTrue).expression.replace(Regex("^([^.]+)"), "value.$1")
            writeLine("        if ($condExpr) {")
            writeLine("            ${writeExpression(field.strategy, "$valueExpr!!")}")
            writeLine("        }")
        } else {
            writeLine("        ${writeExpression(field.strategy, valueExpr)}")
        }
    }

    private fun writeExpression(
        strategy: FieldReadStrategy,
        valueExpr: String,
    ): String =
        when (strategy) {
            is FieldReadStrategy.PrimitiveField -> {
                if (strategy.wireBytes == strategy.primitive.defaultWireBytes) {
                    strategy.primitive.writeExpr(valueExpr)
                } else {
                    customWidthWriteExpr(strategy, valueExpr)
                }
            }
            is FieldReadStrategy.LengthPrefixedStringField -> {
                if (strategy.prefix == "Short") {
                    "buffer.writeLengthPrefixedUtf8String($valueExpr)"
                } else {
                    val cfg = prefixConfig(strategy.prefix)
                    val writeLenExpr = cfg.writeExpr("_len")
                    "run { val _pos = buffer.position(); ${cfg.writePlaceholder}; buffer.writeString($valueExpr); " +
                        "val _end = buffer.position(); val _len = _end - _pos - ${cfg.byteCount}; " +
                        "buffer.position(_pos); $writeLenExpr; buffer.position(_end) }"
                }
            }
            is FieldReadStrategy.RemainingBytesStringField -> "buffer.writeString($valueExpr)"
            is FieldReadStrategy.LengthFromStringField -> "buffer.writeString($valueExpr)"
            is FieldReadStrategy.ValueClassField -> {
                val inner = "$valueExpr.${strategy.innerPropertyName}"
                writeExpression(strategy.innerStrategy, inner)
            }
            is FieldReadStrategy.NestedMessageField -> "${strategy.codecName}.encode(buffer, $valueExpr)"
            is FieldReadStrategy.PayloadField -> error("PayloadField uses writePayloadCodec path")
        }

    // ──────────────────────── Custom-width read/write codegen ────────────────────────

    private fun customWidthReadExpr(strategy: FieldReadStrategy.PrimitiveField): String {
        val p = strategy.primitive
        val wireBytes = strategy.wireBytes
        val chunks = decomposeWireBytes(wireBytes)
        val h = if (p.defaultWireBytes <= 4) "Int" else "Long"

        val stmts = mutableListOf<String>()
        var remainingBytes = wireBytes
        val chunkVars = mutableListOf<Pair<String, Int>>()
        var varIndex = 0

        for (cs in chunks) {
            remainingBytes -= cs
            val varName = "_c${varIndex++}"
            val read =
                when (cs) {
                    4 -> "buffer.readInt()" + if (h == "Long") ".toLong() and 0xFFFFFFFFL" else ""
                    2 -> "buffer.readShort().to$h() and ${if (h == "Long") "0xFFFFL" else "0xFFFF"}"
                    else -> "buffer.readByte().to$h() and ${if (h == "Long") "0xFFL" else "0xFF"}"
                }
            stmts.add("val $varName = $read")
            chunkVars.add(varName to remainingBytes * 8)
        }

        val rawExpr = chunkVars.joinToString(" or ") { (name, shift) -> if (shift > 0) "($name shl $shift)" else name }

        if (p.signed && wireBytes < p.defaultWireBytes) {
            val shiftAmount = (if (h == "Long") 64 else 32) - wireBytes * 8
            stmts.add("val _raw = $rawExpr")
            stmts.add(typeCast(p, "(_raw shl $shiftAmount) shr $shiftAmount", h))
        } else {
            stmts.add(typeCast(p, rawExpr, h))
        }

        return "run { ${stmts.joinToString("; ")} }"
    }

    private fun customWidthWriteExpr(
        strategy: FieldReadStrategy.PrimitiveField,
        valueExpr: String,
    ): String {
        val p = strategy.primitive
        val wireBytes = strategy.wireBytes
        val chunks = decomposeWireBytes(wireBytes)
        val h = if (p.defaultWireBytes <= 4) "Int" else "Long"

        val stmts = mutableListOf<String>()
        val vExpr = if (p == Primitive.INT || p == Primitive.LONG) valueExpr else "$valueExpr.to$h()"
        stmts.add("val _v = $vExpr")

        var remainingBytes = wireBytes
        for (cs in chunks) {
            remainingBytes -= cs
            val shift = remainingBytes * 8
            val shifted = if (shift > 0) "(_v ushr $shift)" else "_v"
            stmts.add(
                when (cs) {
                    4 -> "buffer.writeInt($shifted${if (h == "Long") ".toInt()" else ""})"
                    2 -> "buffer.writeShort($shifted.toShort())"
                    else -> "buffer.writeByte($shifted.toByte())"
                },
            )
        }

        return "run { ${stmts.joinToString("; ")} }"
    }

    // ──────────────────────── Custom-width helpers ────────────────────────

    private fun decomposeWireBytes(wireBytes: Int): List<Int> =
        buildList {
            var r = wireBytes
            for (s in intArrayOf(4, 2, 1)) {
                if (r >= s) {
                    add(s)
                    r -= s
                }
            }
        }

    private fun typeCast(
        primitive: Primitive,
        expr: String,
        holdingType: String,
    ): String =
        when (primitive) {
            Primitive.BYTE -> "($expr).toByte()"
            Primitive.UBYTE -> "($expr).toUByte()"
            Primitive.SHORT -> "($expr).toShort()"
            Primitive.USHORT -> "($expr).toUShort()"
            Primitive.INT -> expr
            Primitive.UINT -> "($expr).toUInt()"
            Primitive.LONG -> if (holdingType == "Int") "($expr).toLong()" else expr
            Primitive.ULONG -> "($expr).toULong()"
            else -> expr
        }

    // ──────────────────────── Utility ────────────────────────

    private fun capitalizeFirst(s: String): String = s.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

    private fun OutputStream.writeLine(line: String = "") {
        write("$line\n".toByteArray())
    }
}
