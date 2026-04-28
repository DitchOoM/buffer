// PHASE 9 STUB — copied from mqtt/models-base/src/commonMain/kotlin/com/ditchoom/mqtt/Exception.kt
// and MqttWarning.kt. Trimmed to remove dependency on ReasonCode in the constructor.
// Deleted in Phase 9 Step 7 once consumer cutover is verified.
package com.ditchoom.buffer.codec.test.consumer.mqtt

open class MqttException(
    msg: String,
    val reasonCode: UByte,
) : Exception(msg)

open class MalformedPacketException(
    msg: String,
) : MqttException(msg, 0x81.toUByte())

open class ProtocolError(
    msg: String,
) : MqttException(msg, 0x82.toUByte())

class MalformedInvalidVariableByteInteger(
    value: Int,
) : MqttException(
        "Malformed Variable Byte Integer: $value",
        0x81.toUByte(),
    )

open class MqttWarning(
    mandatoryNormativeStatement: String,
    message: String,
) : Exception("$mandatoryNormativeStatement $message")
