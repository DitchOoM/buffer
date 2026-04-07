package com.ditchoom.buffer.codec.processor.spi

class VariableByteIntegerProvider : CodecFieldProvider {
    override val annotationFqn = "com.ditchoom.buffer.codec.annotations.test.VariableByteInteger"

    override fun describe(context: FieldContext): CustomFieldDescriptor {
        require(context.typeName == "kotlin.Int") {
            "@VariableByteInteger can only be applied to Int fields, found: ${context.typeName}"
        }
        return CustomFieldDescriptor(
            readFunction = FunctionRef("com.ditchoom.buffer", "readVariableByteInteger"),
            writeFunction = FunctionRef("com.ditchoom.buffer", "writeVariableByteInteger"),
            fixedSize = -1,
        )
    }
}

class RepeatedProvider : CodecFieldProvider {
    override val annotationFqn = "com.ditchoom.buffer.codec.annotations.test.Repeated"

    override fun describe(context: FieldContext): CustomFieldDescriptor {
        val countField =
            context.annotationArguments["countField"] as? String
                ?: error("@Repeated requires countField argument")
        return CustomFieldDescriptor(
            readFunction = FunctionRef("com.ditchoom.buffer.codec.test", "readRepeatedShorts"),
            writeFunction = FunctionRef("com.ditchoom.buffer.codec.test", "writeRepeatedShorts"),
            fixedSize = -1,
            contextFields = listOf(countField),
        )
    }
}

class PropertyBagProvider : CodecFieldProvider {
    override val annotationFqn = "com.ditchoom.buffer.codec.annotations.test.PropertyBag"

    override fun describe(context: FieldContext): CustomFieldDescriptor =
        CustomFieldDescriptor(
            readFunction = FunctionRef("com.ditchoom.buffer.codec.test", "readPropertyBag"),
            writeFunction = FunctionRef("com.ditchoom.buffer.codec.test", "writePropertyBag"),
            fixedSize = -1,
        )
}

/** Test provider that returns a fixed size */
class FixedCustomProvider : CodecFieldProvider {
    override val annotationFqn = "com.ditchoom.buffer.codec.annotations.test.CustomFixed"

    override fun describe(context: FieldContext): CustomFieldDescriptor =
        CustomFieldDescriptor(
            readFunction = FunctionRef("com.ditchoom.buffer.codec.test", "readFixedInt"),
            writeFunction = FunctionRef("com.ditchoom.buffer.codec.test", "writeFixedInt"),
            fixedSize = 4,
        )
}
