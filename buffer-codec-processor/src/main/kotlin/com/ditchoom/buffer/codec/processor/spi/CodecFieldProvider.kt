package com.ditchoom.buffer.codec.processor.spi

/**
 * SPI for extending the codec processor with custom field strategies.
 * Implementations are discovered via ServiceLoader at KSP build time.
 */
interface CodecFieldProvider {
    /** Fully-qualified annotation name this provider handles */
    val annotationFqn: String

    /** Return a descriptor for how this field should be read/written/sized */
    fun describe(context: FieldContext): CustomFieldDescriptor
}

data class FieldContext(
    val fieldName: String,
    val typeName: String,
    val annotationArguments: Map<String, Any?>,
)

/**
 * Describes a custom field strategy via function references.
 *
 * Read function: ReadBuffer extension, takes optional context args, returns field type.
 *   Generated: `buffer.readFoo(contextArg1, contextArg2)`
 *
 * Write function: WriteBuffer extension, takes field value + optional context args.
 *   Generated: `buffer.writeFoo(value.fieldName, value.contextArg1)`
 */
data class CustomFieldDescriptor(
    /** ReadBuffer extension function to call for decoding */
    val readFunction: FunctionRef,
    /** WriteBuffer extension function to call for encoding */
    val writeFunction: FunctionRef,
    /** Fixed byte size if known at compile time, or -1 for variable-length */
    val fixedSize: Int = -1,
    /** Previously decoded field names to pass as extra arguments to read/write functions */
    val contextFields: List<String> = emptyList(),
    /**
     * Top-level function to call for `wireSize(value)`. Receives the field value as the
     * first argument followed by [contextFields]. When null, the generator uses
     * [fixedSize] as a literal size (and errors at codegen time if [fixedSize] is -1).
     */
    val wireSizeFunction: FunctionRef? = null,
)

data class FunctionRef(
    val packageName: String,
    val functionName: String,
)
