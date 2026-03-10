package com.ditchoom.buffer.codec.mqtt.spi

import com.ditchoom.buffer.codec.processor.spi.CodecFieldProvider
import com.ditchoom.buffer.codec.processor.spi.CustomFieldDescriptor
import com.ditchoom.buffer.codec.processor.spi.FieldContext
import com.ditchoom.buffer.codec.processor.spi.FunctionRef

/**
 * SPI provider for `@MqttVariableByteInteger` fields.
 *
 * Delegates to the buffer library's `readVariableByteInteger()` / `writeVariableByteInteger()`
 * extension functions. Valid on `Int` fields only (MQTT VBI range: 0..268,435,455).
 */
class MqttVariableByteIntegerProvider : CodecFieldProvider {
    override val annotationFqn = "com.ditchoom.buffer.codec.mqtt.annotations.MqttVariableByteInteger"

    override fun describe(context: FieldContext): CustomFieldDescriptor {
        require(context.typeName == "kotlin.Int") {
            "@MqttVariableByteInteger can only be applied to Int fields, found: ${context.typeName}"
        }
        return CustomFieldDescriptor(
            readFunction = FunctionRef("com.ditchoom.buffer", "readVariableByteInteger"),
            writeFunction = FunctionRef("com.ditchoom.buffer", "writeVariableByteInteger"),
            fixedSize = -1,
            sizeOfFunction = FunctionRef("com.ditchoom.buffer", "variableByteSizeInt"),
        )
    }
}
