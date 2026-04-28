// PHASE 9 FIXTURE — copied from mqtt/models-base/src/commonMain/kotlin/com/ditchoom/mqtt/controlpacket/format/fixed/DirectionOfFlow.kt
// Deleted in Phase 9 Step 7 once consumer cutover is verified.
package com.ditchoom.buffer.codec.test.consumer.mqtt.controlpacket.format.fixed

enum class DirectionOfFlow {
    FORBIDDEN,
    CLIENT_TO_SERVER,
    SERVER_TO_CLIENT,
    BIDIRECTIONAL,
}
