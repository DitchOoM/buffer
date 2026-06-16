package com.ditchoom.buffer.codec.schema

/** Two-space indent for entry lines (enum entries, message fields, sealed variants). */
internal const val SCHEMA_INDENT = "  "

/**
 * Structured model of one `codec-schema.txt` record (SCHEMA_DRIFT.md). This is the **single format
 * authority**: both directions go through it — [CodecSchemaDescriptor] projects analyzer IR into
 * these records and calls [render]; [CodecSchemaParser] reconstructs them from text. The
 * `render → parse → render` round-trip is identity (locked by `CodecSchemaParserTest`), so the
 * emitter and the (step-2) plugin's differ can never drift apart on the wire format.
 *
 * ## Tokenization contract
 *
 * Every **structural** token is space-free: the record kind, the fully-qualified type name, each
 * header attribute *value* (`default=`, `dispatch=`, `framedBy=`, `forwardCompatible=`), the field
 * position, the field name, the enum ordinal, and the sealed variant label. A header therefore
 * tokenizes by a plain `split(' ')`. The **only** place spaces are allowed is a message field's
 * descriptor ([MessageRecord.Field.descriptor]) — it is an opaque comparison unit the differ checks
 * whole, so it carries multiple `key=value` tokens. The dispatch discriminator is collapsed to a
 * single comma-joined token by [CodecSchemaDescriptor.describeDiscriminator] precisely to keep the
 * sealed header tokenizable.
 */
sealed interface SchemaRecord {
    /** Fully-qualified type name — the stable sort + diff key for the whole record. */
    val typeName: String

    /** Render to the canonical line list: a header line followed by indented entry lines. */
    fun render(): List<String>

    data class EnumRecord(
        override val typeName: String,
        val default: String?,
        val entries: List<Entry>,
    ) : SchemaRecord {
        /** One enum entry, keyed by [ordinal] (its wire identity). */
        data class Entry(
            val ordinal: Int,
            val name: String,
        )

        override fun render(): List<String> {
            val header =
                buildString {
                    append("enum ").append(typeName)
                    if (default != null) append(" default=").append(default)
                }
            return listOf(header) + entries.map { "$SCHEMA_INDENT${it.ordinal} ${it.name}" }
        }
    }

    data class MessageRecord(
        override val typeName: String,
        val fields: List<Field>,
    ) : SchemaRecord {
        /**
         * One message field, keyed by [position]. [name] is advisory (a rename is wire-safe);
         * [descriptor] is the opaque wire-significant unit the differ compares whole. [optional]
         * is the `@When` `?` marker.
         */
        data class Field(
            val position: Int,
            val name: String,
            val optional: Boolean,
            val descriptor: String,
        )

        override fun render(): List<String> =
            listOf("message $typeName") +
                fields.map { "$SCHEMA_INDENT${it.position} ${it.name}${if (it.optional) "?" else ""} ${it.descriptor}" }
    }

    data class SealedRecord(
        override val typeName: String,
        val dispatch: String,
        val framedBy: String?,
        val forwardCompatible: String?,
        val variants: List<Variant>,
    ) : SchemaRecord {
        /** One sealed variant, keyed by [label] (the `@PacketType` / `@DispatchValue`, formatted). */
        data class Variant(
            val label: String,
            val name: String,
        )

        override fun render(): List<String> {
            val header =
                buildString {
                    append("sealed ").append(typeName)
                    append(" dispatch=").append(dispatch)
                    if (framedBy != null) append(" framedBy=").append(framedBy)
                    if (forwardCompatible != null) append(" forwardCompatible=").append(forwardCompatible)
                }
            return listOf(header) + variants.map { "$SCHEMA_INDENT${it.label} ${it.name}" }
        }
    }
}

/** Render a list of records to descriptor text: records joined by newline, with a trailing newline. */
fun renderSchemaRecords(records: List<SchemaRecord>): String =
    if (records.isEmpty()) {
        ""
    } else {
        records.joinToString(separator = "\n", postfix = "\n") { it.render().joinToString("\n") }
    }
