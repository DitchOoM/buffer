Problem: There's no way to go from a typed Connection<T> back down to a ByteStream.
This blocks protocol stacking — e.g., MQTT over WebSocket requires MQTT to see a
ByteStream, not a Connection<WebSocketMessage>.

Solution: A generic extension function:
fun <T> Connection<T>.asByteStream(
    scope: CoroutineScope,
    extract: (T) -> ReadBuffer?,
    wrap: (ReadBuffer) -> T,
): ByteStream

A CoroutineScope parameter is required for structured concurrency — the adapter
launches a collector coroutine that bridges the push-based receive() flow into a
pull-based Channel.

Key design points:
- Generic — works for any Connection<T>, not just WebSocket
- Zero-copy — extract/wrap just unwrap/wrap references
- Skips non-data messages (e.g., ping/pong) via null returns from extract
- read(): receives from the internal channel, returns ReadResult.Data or ReadResult.End
- write(): calls send(wrap(buffer)), returns BytesWritten(buffer.remaining())
- close(): cancels the collector coroutine and delegates to connection.close()
- isOpen: returns collector.isActive

Location:
buffer-flow/src/commonMain/kotlin/com/ditchoom/buffer/flow/ConnectionByteStream.kt
