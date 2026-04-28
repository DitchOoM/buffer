// PHASE 9 FIXTURE — distilled from websocket/src/commonMain/kotlin/com/ditchoom/websocket/frame/MessageAssembler.kt
// CloseCode is the value class used by WsCloseBody. The MessageAssembler runtime is not copied.
// Deleted in Phase 9 Step 7 once consumer cutover is verified.
package com.ditchoom.buffer.codec.test.consumer.websocket.frame

import kotlin.jvm.JvmInline

@JvmInline
value class CloseCode(
    val code: UShort,
) {
    val isPresent: Boolean get() = code != 1005u.toUShort()

    val isValid: Boolean
        get() =
            code in 1000u..1003u ||
                code in 1007u..1011u ||
                code in 3000u..3999u ||
                code in 4000u..4999u

    val isValidForWire: Boolean
        get() =
            isValid &&
                code != 1005u.toUShort() &&
                code != 1006u.toUShort() &&
                code != 1015u.toUShort()

    companion object {
        val NORMAL = CloseCode(1000u)
        val PROTOCOL_ERROR = CloseCode(1002u)
        val NO_STATUS_RECEIVED = CloseCode(1005u)
        val INVALID_PAYLOAD = CloseCode(1007u)
    }
}
