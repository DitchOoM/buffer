// PHASE 9 STUB — distilled from websocket/src/commonMain/kotlin/com/ditchoom/websocket/frame/WsFraming.kt
// The runtime peek logic depends on `WsFrameHeaderCodec.peekFrameSize` (a generated codec
// reference); for KSP discovery we only need a `DispatchFraming<WsFrameHeader>` symbol that
// the @DispatchOn annotation can resolve. The body returns NeedsMoreData unconditionally.
// Deleted in Phase 9 Step 7 once consumer cutover is verified.
package com.ditchoom.buffer.codec.test.consumer.websocket.frame

import com.ditchoom.buffer.codec.DispatchFraming
import com.ditchoom.buffer.stream.PeekResult
import com.ditchoom.buffer.stream.StreamProcessor

object WsFraming : DispatchFraming<WsFrameHeader> {
    override fun peekFrameSize(
        stream: StreamProcessor,
        baseOffset: Int,
    ): PeekResult = PeekResult.NeedsMoreData
}
