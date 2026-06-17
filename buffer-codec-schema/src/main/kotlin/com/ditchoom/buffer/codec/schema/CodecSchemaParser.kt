package com.ditchoom.buffer.codec.schema

/**
 * Parser for the `codec-schema.txt` descriptor (SCHEMA_DRIFT.md) — the inverse of
 * [CodecSchemaDescriptor]'s emit. Reconstructs the [SchemaRecord] model from text so the step-2
 * Gradle plugin can diff a freshly-generated descriptor against a baselined one without re-deriving
 * analyzer IR. Emit and parse share one model, and `renderSchemaRecords(parse(text)) == text` is a
 * locked round-trip (`CodecSchemaParserTest`), so the two directions are dogfooded against each
 * other and the wire format cannot drift.
 *
 * The grammar is line-oriented and context-sensitive only on the enclosing record kind: an
 * unindented line opens a record (`enum` / `message` / `sealed`); a two-space-indented line is an
 * entry of the open record. Every structural token is space-free (see [SchemaRecord]), so headers
 * tokenize by a plain `split(' ')`; a message field's descriptor is the lone multi-token tail and
 * is captured opaquely.
 */
object CodecSchemaParser {
    /** Parse descriptor text into records, preserving file order. */
    fun parse(text: String): List<SchemaRecord> {
        val groups = mutableListOf<MutableList<String>>()
        for (line in text.lines()) {
            if (line.isEmpty()) continue
            if (line.startsWith(SCHEMA_INDENT)) {
                val group = groups.lastOrNull() ?: error("schema entry line before any record header: '$line'")
                group += line.removePrefix(SCHEMA_INDENT)
            } else {
                groups += mutableListOf(line)
            }
        }
        return groups.map { parseRecord(it.first(), it.drop(1)) }
    }

    private fun parseRecord(
        header: String,
        entries: List<String>,
    ): SchemaRecord {
        val tokens = header.split(' ')
        return when (tokens[0]) {
            "enum" ->
                SchemaRecord.EnumRecord(
                    typeName = tokens[1],
                    default = tokens.drop(2).firstNotNullOfOrNull { it.attrValue("default") },
                    entries =
                        entries.map { entry ->
                            val (ordinal, name) = entry.split(' ', limit = 2)
                            SchemaRecord.EnumRecord.Entry(ordinal.toInt(), name)
                        },
                )
            "message" ->
                SchemaRecord.MessageRecord(
                    typeName = tokens[1],
                    fields = entries.map { parseField(it) },
                )
            "sealed" -> {
                val attrs = tokens.drop(2).mapNotNull { it.splitAttr() }.toMap()
                SchemaRecord.SealedRecord(
                    typeName = tokens[1],
                    dispatch = attrs.getValue("dispatch"),
                    framedBy = attrs["framedBy"],
                    forwardCompatible = attrs["forwardCompatible"],
                    variants =
                        entries.map { entry ->
                            val (label, name) = entry.split(' ', limit = 2)
                            SchemaRecord.SealedRecord.Variant(label, name)
                        },
                )
            }
            else -> error("unknown schema record kind: '${tokens[0]}' in header '$header'")
        }
    }

    private fun parseField(entry: String): SchemaRecord.MessageRecord.Field {
        // "<position> <name>[?] <descriptor...>" — descriptor is the opaque (possibly multi-token) tail.
        val parts = entry.split(' ', limit = 3)
        val nameToken = parts[1]
        val optional = nameToken.endsWith("?")
        return SchemaRecord.MessageRecord.Field(
            position = parts[0].toInt(),
            name = if (optional) nameToken.dropLast(1) else nameToken,
            optional = optional,
            descriptor = parts[2],
        )
    }

    /** Split a `key=value` token into a pair, or null if it has no `=`. */
    private fun String.splitAttr(): Pair<String, String>? {
        val eq = indexOf('=')
        return if (eq < 0) null else substring(0, eq) to substring(eq + 1)
    }

    /** The value of this token if it is `key=value` for the given key, else null. */
    private fun String.attrValue(key: String): String? = if (startsWith("$key=")) removePrefix("$key=") else null
}
