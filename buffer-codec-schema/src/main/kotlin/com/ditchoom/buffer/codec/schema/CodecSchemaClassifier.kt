package com.ditchoom.buffer.codec.schema

/**
 * Severity of a single schema delta (SCHEMA_DRIFT.md drift classification).
 *
 * The three tiers exist because a name change is *not* uniformly wire-significant:
 * - [SAFE] — additive, never affects bytes already on the wire (silent, always passes).
 * - [ADVISORY] — wire-neutral *if* it is what it looks like (a pure rename), but the differ wants a
 *   human to confirm it isn't a disguised semantic change. Always warns; **never fails**, even under
 *   `failOnBreaking`. Accept an intentional rename with `updateCodecSchema`.
 * - [BREAKING] — changes the meaning of bytes for a peer that lacks the new code. Warns by default;
 *   fails the build under `failOnBreaking`.
 */
enum class DriftSeverity { SAFE, ADVISORY, BREAKING }

/** One classified difference between a baselined and a freshly-generated descriptor. */
data class SchemaDrift(
    val severity: DriftSeverity,
    val typeName: String,
    val detail: String,
)

/**
 * Classifies the difference between a baselined `codec-schema.txt` and a freshly-generated one
 * (SCHEMA_DRIFT.md). Pure: operates on the parsed [SchemaRecord] model, so it is exhaustively
 * unit-testable without Gradle. The (step-2) plugin parses both files, calls [classify], then warns
 * on [DriftSeverity.ADVISORY]/[DriftSeverity.BREAKING] and fails on [DriftSeverity.BREAKING] when
 * `failOnBreaking` is set.
 *
 * ## Why names are classified by record kind
 *
 * A field/entry/variant name never rides the wire — yet a name change is not uniformly safe:
 * - **Enum entries / sealed variants:** the wire identity is the ordinal / dispatch value, and the
 *   name is the only thing distinguishing "renamed in place" (safe) from "reordered / inserted /
 *   reassigned" (breaking). The latter is detected structurally: an old name that reappears at a
 *   *different* key has moved, which means the bytes at that key changed meaning → [BREAKING]. A
 *   name that simply vanishes and is replaced by a brand-new one at the same key is an ambiguous
 *   rename → [ADVISORY].
 * - **Message fields:** the per-position `descriptor` independently captures the full wire shape, so
 *   an inserted/reordered field surfaces as descriptor changes at the shifted positions (caught
 *   directly). A field rename with an unchanged descriptor is therefore provably wire-neutral →
 *   [ADVISORY], and the name is never needed to catch a real break.
 */
object CodecSchemaClassifier {
    fun classify(
        baseline: List<SchemaRecord>,
        current: List<SchemaRecord>,
    ): List<SchemaDrift> {
        val base = baseline.associateBy { it.typeName }
        val curr = current.associateBy { it.typeName }
        val drifts = mutableListOf<SchemaDrift>()

        // Stable, type-sorted iteration so the diff output is deterministic.
        for (name in (base.keys + curr.keys).toSortedSet()) {
            val b = base[name]
            val c = curr[name]
            when {
                b == null && c != null -> drifts += SchemaDrift(DriftSeverity.SAFE, name, "new type added")
                b != null && c == null ->
                    drifts +=
                        SchemaDrift(
                            DriftSeverity.BREAKING,
                            name,
                            "type removed — peers can no longer decode it; if it was renamed, the " +
                                "wire may be unaffected (run updateCodecSchema to accept)",
                        )
                b != null && c != null -> drifts += diffRecord(b, c)
            }
        }
        return drifts
    }

    private fun diffRecord(
        b: SchemaRecord,
        c: SchemaRecord,
    ): List<SchemaDrift> =
        when {
            b is SchemaRecord.EnumRecord && c is SchemaRecord.EnumRecord -> diffEnum(b, c)
            b is SchemaRecord.MessageRecord && c is SchemaRecord.MessageRecord -> diffMessage(b, c)
            b is SchemaRecord.SealedRecord && c is SchemaRecord.SealedRecord -> diffSealed(b, c)
            else ->
                listOf(
                    SchemaDrift(
                        DriftSeverity.BREAKING,
                        b.typeName,
                        "record kind changed (${kindOf(b)} → ${kindOf(c)}) — the wire shape is incompatible",
                    ),
                )
        }

    // ---- enum -------------------------------------------------------------

    private fun diffEnum(
        b: SchemaRecord.EnumRecord,
        c: SchemaRecord.EnumRecord,
    ): List<SchemaDrift> {
        val drifts = mutableListOf<SchemaDrift>()
        val bByOrd = b.entries.associate { it.ordinal to it.name }
        val cByOrd = c.entries.associate { it.ordinal to it.name }
        val cOrdOfName = c.entries.associate { it.name to it.ordinal }
        val bOrdOfName = b.entries.associate { it.name to it.ordinal }

        for (ord in bByOrd.keys.sorted()) {
            val was = bByOrd.getValue(ord)
            val now = cByOrd[ord]
            when {
                now == null ->
                    drifts +=
                        breaking(
                            b.typeName,
                            "enum ordinal $ord ('$was') removed — a peer may still send it; append " +
                                "new entries at the end instead of removing",
                        )
                now != was -> {
                    val movedTo = cOrdOfName[was]
                    val newNameWasAt = bOrdOfName[now]
                    when {
                        movedTo != null ->
                            drifts +=
                                breaking(
                                    b.typeName,
                                    "enum entry '$was' moved from ordinal $ord to $movedTo — reordering/" +
                                        "inserting changes the meaning of bytes already on the wire",
                                )
                        newNameWasAt != null ->
                            drifts +=
                                breaking(
                                    b.typeName,
                                    "enum ordinal $ord is now '$now', which previously rode at ordinal " +
                                        "$newNameWasAt — reordering changes wire meaning",
                                )
                        else ->
                            drifts +=
                                advisory(
                                    b.typeName,
                                    "enum ordinal $ord renamed '$was' → '$now' — wire-safe if a pure " +
                                        "rename; confirm it is not a semantic change, then run updateCodecSchema",
                                )
                    }
                }
            }
        }
        for (ord in cByOrd.keys.sorted()) {
            if (ord !in bByOrd) {
                drifts += safe(b.typeName, "enum ordinal $ord ('${cByOrd.getValue(ord)}') added")
            }
        }
        drifts += diffEnumDefault(b, c)
        return drifts
    }

    private fun diffEnumDefault(
        b: SchemaRecord.EnumRecord,
        c: SchemaRecord.EnumRecord,
    ): List<SchemaDrift> =
        when {
            b.default == null && c.default != null ->
                listOf(safe(b.typeName, "@EnumDefault added ('${c.default}') — widens forward-compat"))
            b.default != null && c.default == null ->
                listOf(
                    breaking(
                        b.typeName,
                        "@EnumDefault removed ('${b.default}') — unknown ordinals now throw instead of " +
                            "resolving to a sink",
                    ),
                )
            b.default != null && c.default != null && b.default != c.default ->
                listOf(advisory(b.typeName, "@EnumDefault changed '${b.default}' → '${c.default}'"))
            else -> emptyList()
        }

    // ---- message ----------------------------------------------------------

    private fun diffMessage(
        b: SchemaRecord.MessageRecord,
        c: SchemaRecord.MessageRecord,
    ): List<SchemaDrift> {
        val drifts = mutableListOf<SchemaDrift>()
        val bByPos = b.fields.associateBy { it.position }
        val cByPos = c.fields.associateBy { it.position }

        for (pos in bByPos.keys.sorted()) {
            val bf = bByPos.getValue(pos)
            val cf = cByPos[pos]
            when {
                cf == null ->
                    drifts +=
                        breaking(
                            b.typeName,
                            "field position $pos ('${bf.name}') removed — later fields shift, so peers misframe",
                        )
                bf.descriptor != cf.descriptor ->
                    drifts +=
                        breaking(
                            b.typeName,
                            "field position $pos changed wire shape: '${bf.descriptor}' → '${cf.descriptor}'",
                        )
                bf.name != cf.name ->
                    // Descriptor identical → the wire is unchanged; the name is cosmetic.
                    drifts +=
                        advisory(
                            b.typeName,
                            "field position $pos renamed '${bf.name}' → '${cf.name}' — wire-neutral " +
                                "(the descriptor carries the wire shape)",
                        )
            }
        }
        for (pos in cByPos.keys.sorted()) {
            if (pos !in bByPos) {
                drifts += safe(b.typeName, "field position $pos ('${cByPos.getValue(pos).name}') appended")
            }
        }
        return drifts
    }

    // ---- sealed -----------------------------------------------------------

    private fun diffSealed(
        b: SchemaRecord.SealedRecord,
        c: SchemaRecord.SealedRecord,
    ): List<SchemaDrift> {
        val drifts = mutableListOf<SchemaDrift>()
        if (b.dispatch != c.dispatch) {
            drifts += breaking(b.typeName, "discriminator changed: '${b.dispatch}' → '${c.dispatch}'")
        }
        if (b.framedBy != c.framedBy) {
            drifts +=
                breaking(
                    b.typeName,
                    "@FramedBy changed: '${b.framedBy ?: "(none)"}' → '${c.framedBy ?: "(none)"}'",
                )
        }
        drifts += diffForwardCompat(b, c)

        val bByLabel = b.variants.associate { it.label to it.name }
        val cByLabel = c.variants.associate { it.label to it.name }
        val cLabelOfName = c.variants.associate { it.name to it.label }
        for (label in bByLabel.keys.sortedBy { sortableLabel(it) }) {
            val was = bByLabel.getValue(label)
            val now = cByLabel[label]
            when {
                now == null ->
                    drifts +=
                        breaking(
                            b.typeName,
                            "dispatch value $label ('$was') removed — its @PacketType/@DispatchValue was " +
                                "deleted or reassigned",
                        )
                now != was -> {
                    val movedTo = cLabelOfName[was]
                    if (movedTo != null) {
                        drifts +=
                            breaking(
                                b.typeName,
                                "variant '$was' moved from dispatch value $label to $movedTo — reassigning " +
                                    "a discriminator breaks variant dispatch",
                            )
                    } else {
                        drifts +=
                            advisory(
                                b.typeName,
                                "dispatch value $label renamed '$was' → '$now' — wire-safe if a pure " +
                                    "rename; confirm, then run updateCodecSchema",
                            )
                    }
                }
            }
        }
        for (label in cByLabel.keys.sortedBy { sortableLabel(it) }) {
            if (label !in bByLabel) {
                drifts += safe(b.typeName, "dispatch value $label ('${cByLabel.getValue(label)}') added")
            }
        }
        return drifts
    }

    private fun diffForwardCompat(
        b: SchemaRecord.SealedRecord,
        c: SchemaRecord.SealedRecord,
    ): List<SchemaDrift> =
        when {
            b.forwardCompatible == null && c.forwardCompatible != null ->
                listOf(safe(b.typeName, "@ForwardCompatible added ('${c.forwardCompatible}') — widens forward-compat"))
            b.forwardCompatible != null && c.forwardCompatible == null ->
                listOf(
                    breaking(
                        b.typeName,
                        "@ForwardCompatible removed ('${b.forwardCompatible}') — unknown variants now throw",
                    ),
                )
            b.forwardCompatible != null &&
                c.forwardCompatible != null &&
                b.forwardCompatible != c.forwardCompatible ->
                listOf(
                    breaking(
                        b.typeName,
                        "@ForwardCompatible sink changed '${b.forwardCompatible}' → '${c.forwardCompatible}'",
                    ),
                )
            else -> emptyList()
        }

    // ---- helpers ----------------------------------------------------------

    private fun kindOf(record: SchemaRecord): String =
        when (record) {
            is SchemaRecord.EnumRecord -> "enum"
            is SchemaRecord.MessageRecord -> "message"
            is SchemaRecord.SealedRecord -> "sealed"
        }

    /** Numeric sort for dispatch labels (hex `0x..`, decimal, and negative decimals all order naturally). */
    private fun sortableLabel(label: String): Long =
        if (label.startsWith("0x") || label.startsWith("0X")) {
            label.substring(2).toLong(16)
        } else {
            label.toLongOrNull() ?: 0L
        }

    private fun safe(
        typeName: String,
        detail: String,
    ) = SchemaDrift(DriftSeverity.SAFE, typeName, detail)

    private fun advisory(
        typeName: String,
        detail: String,
    ) = SchemaDrift(DriftSeverity.ADVISORY, typeName, detail)

    private fun breaking(
        typeName: String,
        detail: String,
    ) = SchemaDrift(DriftSeverity.BREAKING, typeName, detail)
}
