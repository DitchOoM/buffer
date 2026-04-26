package com.ditchoom.buffer.codec.test.spi

import com.ditchoom.buffer.codec.processor.spi.CodecFieldProvider
import com.ditchoom.buffer.codec.processor.spi.CustomFieldDescriptor
import com.ditchoom.buffer.codec.processor.spi.FieldContext
import com.ditchoom.buffer.codec.processor.spi.FunctionRef

class PropertyBagProvider : CodecFieldProvider {
    override val annotationFqn = "com.ditchoom.buffer.codec.test.annotations.PropertyBag"

    override fun describe(context: FieldContext): CustomFieldDescriptor =
        CustomFieldDescriptor(
            readFunction = FunctionRef("com.ditchoom.buffer.codec.test.functions", "readPropertyBag"),
            writeFunction = FunctionRef("com.ditchoom.buffer.codec.test.functions", "writePropertyBag"),
            fixedSize = -1,
            wireSizeFunction = FunctionRef("com.ditchoom.buffer.codec.test.functions", "propertyBagSize"),
        )
}
