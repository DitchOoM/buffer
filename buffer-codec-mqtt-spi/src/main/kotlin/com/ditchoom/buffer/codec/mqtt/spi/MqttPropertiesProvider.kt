package com.ditchoom.buffer.codec.mqtt.spi

import com.ditchoom.buffer.codec.processor.spi.CodecFieldProvider
import com.ditchoom.buffer.codec.processor.spi.CustomFieldDescriptor
import com.ditchoom.buffer.codec.processor.spi.FieldContext
import com.ditchoom.buffer.codec.processor.spi.FunctionRef

/**
 * SPI provider for `@MqttProperties` fields (MQTT v5 property sections).
 *
 * Delegates to extension functions that must be defined in the MQTT project:
 * - `ReadBuffer.readMqttProperties(): Collection<Property>?`
 * - `WriteBuffer.writeMqttProperties(properties: Collection<Property>?)`
 * - `mqttPropertiesSize(properties: Collection<Property>?): Int`
 *
 * These handle the VBI-prefixed property length + individual property encoding
 * as defined in MQTT v5 §2.2.2.
 */
class MqttPropertiesProvider : CodecFieldProvider {
    override val annotationFqn = "com.ditchoom.buffer.codec.mqtt.annotations.MqttProperties"

    override fun describe(context: FieldContext): CustomFieldDescriptor =
        CustomFieldDescriptor(
            readFunction = FunctionRef("com.ditchoom.mqtt.controlpacket", "readMqttProperties"),
            writeFunction = FunctionRef("com.ditchoom.mqtt.controlpacket", "writeMqttProperties"),
            fixedSize = -1,
            sizeOfFunction = FunctionRef("com.ditchoom.mqtt.controlpacket", "mqttPropertiesSize"),
        )
}
