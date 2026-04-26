package com.ditchoom.buffer.codec.processor.builtin

import com.ditchoom.buffer.codec.processor.spi.CodecFieldProvider
import com.ditchoom.buffer.codec.processor.spi.CustomFieldDescriptor
import com.ditchoom.buffer.codec.processor.spi.FieldContext
import com.ditchoom.buffer.codec.processor.spi.FunctionRef

/**
 * Built-in provider for `@VariableByteInteger` Int fields. Wired in
 * [com.ditchoom.buffer.codec.processor.ProtocolMessageProcessorProvider]; SPI providers
 * cannot override the built-in annotation namespace.
 */
internal class VariableByteIntegerProvider : CodecFieldProvider {
    override val annotationFqn = "com.ditchoom.buffer.codec.annotations.VariableByteInteger"

    override fun describe(context: FieldContext): CustomFieldDescriptor {
        require(context.typeName == "kotlin.Int") {
            "@VariableByteInteger can only be applied to Int fields, found: ${context.typeName}"
        }
        return CustomFieldDescriptor(
            readFunction = FunctionRef("com.ditchoom.buffer", "readVariableByteInteger"),
            writeFunction = FunctionRef("com.ditchoom.buffer", "writeVariableByteInteger"),
            fixedSize = -1,
            wireSizeFunction = FunctionRef("com.ditchoom.buffer", "variableByteSizeInt"),
        )
    }
}
