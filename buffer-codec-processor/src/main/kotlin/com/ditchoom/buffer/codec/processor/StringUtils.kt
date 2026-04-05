package com.ditchoom.buffer.codec.processor

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec

internal fun capitalizeFirst(s: String): String = s.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

/** Creates a [FileSpec.Builder] with `@file:Suppress("ktlint")` and 4-space indentation. */
internal fun fileSpecBuilder(
    packageName: String,
    fileName: String,
): FileSpec.Builder =
    FileSpec
        .builder(packageName, fileName)
        .addAnnotation(
            AnnotationSpec
                .builder(Suppress::class)
                .useSiteTarget(AnnotationSpec.UseSiteTarget.FILE)
                .addMember("%S", "ktlint")
                .build(),
        ).indent("    ")

/**
 * Prepends "value." to the first path segment of a condition expression,
 * so that generated code references the constructor parameter.
 * E.g. "flags.willFlag" → "value.flags.willFlag", "enabled" → "value.enabled"
 */
internal fun conditionToValueExpr(expression: String): String = expression.replace(Regex("^([^.]+)"), "value.$1")

/**
 * Returns the codec object name for this class, handling nested classes.
 *
 * Top-level: `MqttPacketConnect` → `MqttPacketConnectCodec`
 * Nested:    `AnimChunk.Header`  → `AnimChunkHeaderCodec`
 */
internal fun KSClassDeclaration.codecName(): String = enclosingSimpleNames().joinToString("") + "Codec"

/**
 * Returns the simple name chain from the outermost enclosing class to this class.
 *
 * Top-level: `MqttPacketConnect` → `["MqttPacketConnect"]`
 * Nested:    `AnimChunk.Header`  → `["AnimChunk", "Header"]`
 */
internal fun KSClassDeclaration.enclosingSimpleNames(): List<String> {
    val names = mutableListOf<String>()
    var current: KSClassDeclaration? = this
    while (current != null) {
        names.add(0, current.simpleName.asString())
        current = current.parentDeclaration as? KSClassDeclaration
    }
    return names
}

/**
 * Returns a KotlinPoet [ClassName] that correctly handles nested classes.
 *
 * Top-level: `ClassName("com.example", "MqttPacketConnect")`
 * Nested:    `ClassName("com.example", "AnimChunk", "Header")`
 */
internal fun KSClassDeclaration.toPoetClassName(): ClassName {
    val names = enclosingSimpleNames()
    return ClassName(packageName.asString(), *names.toTypedArray())
}

/** Payload metadata for a sealed interface variant. */
data class SealedVariantPayloadInfo(
    val subclass: KSClassDeclaration,
    val payloadFields: List<PayloadFieldInfo>,
)

/** A single @Payload field within a sealed variant. */
data class PayloadFieldInfo(
    val fieldName: String,
    val typeParamName: String,
    val contextClassName: String,
)

/**
 * Constructor parameter metadata for discriminator types.
 * Used for peeking data class discriminators byte-by-byte.
 */
data class DiscriminatorParam(
    val name: String,
    val typeName: String,
    val wireBytes: Int,
)

data class DispatchOnInfo(
    val typeName: String,
    val codecName: String,
    val dispatchProperty: String,
    val poetClassName: ClassName,
    val innerTypeName: String,
    /** True if the discriminator is a value class (single constructor parameter). */
    val isValueClass: Boolean = true,
    /** All constructor parameters with their wire sizes (for data class discriminators). */
    val constructorParams: List<DiscriminatorParam> = emptyList(),
    /** Simple name of the sealed interface dispatch codec (e.g., "PngChunkCodec"). */
    val sealedCodecSimpleName: String = "",
    /** Package of the sealed interface. */
    val sealedPackage: String = "",
) {
    /** Total wire bytes for the discriminator type. */
    val totalWireBytes: Int get() = constructorParams.sumOf { it.wireBytes }
}
