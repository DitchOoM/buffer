package com.ditchoom.buffer.codec.test.spi

import com.ditchoom.buffer.codec.processor.spi.CodecFieldProvider
import com.ditchoom.buffer.codec.processor.spi.CustomFieldDescriptor
import com.ditchoom.buffer.codec.processor.spi.FieldContext
import com.ditchoom.buffer.codec.processor.spi.FunctionRef

class VariableByteIntegerProvider : CodecFieldProvider {
    override val annotationFqn = "com.ditchoom.buffer.codec.test.annotations.VariableByteInteger"

    override fun describe(context: FieldContext): CustomFieldDescriptor {
        require(context.typeName == "kotlin.Int") {
            "@VariableByteInteger can only be applied to Int fields, found: ${context.typeName}"
        }
        return CustomFieldDescriptor(
            readFunction = FunctionRef("com.ditchoom.buffer", "readVariableByteInteger"),
            writeFunction = FunctionRef("com.ditchoom.buffer", "writeVariableByteInteger"),
            fixedSize = -1,
            sizeOfFunction = FunctionRef("com.ditchoom.buffer", "variableByteSizeInt"),
        )
    }
}

class PropertyBagProvider : CodecFieldProvider {
    override val annotationFqn = "com.ditchoom.buffer.codec.test.annotations.PropertyBag"

    override fun describe(context: FieldContext): CustomFieldDescriptor =
        CustomFieldDescriptor(
            readFunction = FunctionRef("com.ditchoom.buffer.codec.test.functions", "readPropertyBag"),
            writeFunction = FunctionRef("com.ditchoom.buffer.codec.test.functions", "writePropertyBag"),
            fixedSize = -1,
            sizeOfFunction = null,
        )
}
