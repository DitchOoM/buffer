package com.ditchoom.buffer.codec.processor

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.Modifier

/**
 * Detect a `@JvmInline value class` declaration, including when it is loaded from another
 * module's KMP commonMain `.knm` metadata jar.
 *
 * KSP populates `Modifier.VALUE` reliably on declarations parsed from source in the current
 * compilation, but not on declarations resolved from a binary `.knm` metadata jar in a
 * dependency module. The `@JvmInline` annotation is a real compile-time annotation —
 * required by the Kotlin compiler on every `value class` targeting JVM bytecode — and it
 * is preserved in the metadata. We treat its presence as an equivalent signal so cross-
 * module discriminators (`@DispatchOn`), framing-header siblings (`@FramedBy`), and dotted
 * predicate sources (`@When("foo.bar")`, `@LengthFrom("foo")`) can resolve to value classes
 * declared in another module.
 */
internal fun KSClassDeclaration.isValueClassDecl(): Boolean {
    // Exclude kotlin-stdlib unsigned types: they are `@JvmInline value class` in source
    // form but every emit path treats them as primitive scalars (NATURAL_WIDTHS), not as
    // user-declared wrappers. Their primary constructors are `internal` so generated code
    // cannot call them — surfacing them through this helper would break consumers that
    // declare a plain `UByte` / `UShort` / `UInt` / `ULong` field.
    if (qualifiedName?.asString() in STDLIB_UNSIGNED_QNAMES) return false
    // KSP surfaces `Modifier.VALUE` for declarations parsed from source in the current
    // compilation, but `Modifier.INLINE` for declarations resolved from another module's
    // KMP commonMain `.knm` metadata jar (legacy `inline class` modifier name). Accept both.
    if (Modifier.VALUE in modifiers) return true
    if (Modifier.INLINE in modifiers) return true
    // Final fallback for environments where neither modifier is surfaced: detect the
    // `@JvmInline` annotation directly (required by the Kotlin compiler on every value
    // class targeting JVM bytecode).
    return annotations.any { ann ->
        ann.shortName.asString() == "JvmInline" &&
            ann.annotationType.resolve().declaration.qualifiedName?.asString() == "kotlin.jvm.JvmInline"
    }
}

private val STDLIB_UNSIGNED_QNAMES =
    setOf(
        "kotlin.UByte",
        "kotlin.UShort",
        "kotlin.UInt",
        "kotlin.ULong",
    )
