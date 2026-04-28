// PHASE 9 FIXTURE — copied from websocket/src/commonMain/kotlin/com/ditchoom/websocket/frame/TestPayload.kt
// Deleted in Phase 9 Step 7 once consumer cutover is verified.
package com.ditchoom.buffer.codec.test.consumer.websocket.frame

import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.RemainingBytes

@ProtocolMessage
data class TestPayload(
    @RemainingBytes val text: String,
)
