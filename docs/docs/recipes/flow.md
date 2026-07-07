---
title: Flow & Connections
---

# Flow & Connections

The `buffer-flow` module has two layers:

- **Flow extensions** — small operators for composing streaming transforms over
  Kotlin `Flow` (`lines`, `mapBuffer`, `asStringFlow`).
- **Transport abstractions** — `ByteStream`, `Connection<T>`, and `StreamMux<T>`:
  the interfaces protocol libraries code against, so they don't care whether the
  wire is TCP, WebSocket, QUIC, or in-memory.

## Installation

```kotlin
dependencies {
    implementation("com.ditchoom:buffer:<latest-version>")
    implementation("com.ditchoom:buffer-flow:<latest-version>")
}
```

All types live in `com.ditchoom.buffer.flow`.

## Flow Extensions

### `lines()` — chunked strings to complete lines

`Flow<String>.lines()` reassembles arbitrarily chunked strings into complete lines,
handling both `\n` and `\r\n`. Trailing content without a final newline is emitted
as the last element.

```kotlin
flowOf("hel", "lo\nwor", "ld\n")
    .lines()
    .collect { println(it) }   // "hello", then "world"
```

### `mapBuffer()` — transform each buffer

`Flow<ReadBuffer>.mapBuffer(transform)` applies a transform to every buffer in the flow.

```kotlin
val incoming: Flow<ReadBuffer> = /* buffers from your transport */
incoming.mapBuffer { decompress(it, CompressionAlgorithm.Gzip).getOrThrow() }
```

### `asStringFlow()` — buffers to strings

`Flow<ReadBuffer>.asStringFlow(charset)` decodes each buffer (from its position to its
limit) to a `String`. It defaults to UTF-8.

```kotlin
incoming
    .asStringFlow()
    .lines()
    .collect { line -> process(line) }
```

## Transport Abstractions

Protocol code shouldn't know what carries its bytes. `buffer-flow` provides interfaces
layered from raw bytes up to typed messages:

```
ByteSource / ByteSink / ByteStream   raw bytes, split by direction (TCP, unix socket, in-memory)
   ↓ framing
Connection<T>                        typed bidirectional messages
   ↓ multiplexing
StreamMux<T>                         many independent Connection<T> streams (QUIC, HTTP/2)
```

`ByteStream` itself is a duplex combination of two direction-scoped halves,
`ByteSource` (read) and `ByteSink` (write) — mirroring the `Sender`/`Receiver`/`Connection`
split at the typed-message layer. Beyond these three, the module also ships
`HalfCloseable`, `Resettable`, `ByteStreamMux`, and `BufferedByteSource`, covered below.

Each layer can also be adapted back down. A `Connection<WebSocketMessage>` can expose
a `ByteStream` of binary-frame payloads, which the next protocol layer frames into a
`Connection<MqttPacket>`:

```
ByteStream (TCP) → Connection<WebSocketMessage> → ByteStream (WS binary)
    → Connection<MqttPacket>
```

:::note
Concrete implementations of these interfaces (TCP, WebSocket, auto-reconnect) live in
the [socket](https://github.com/DitchOoM/socket) library. `buffer-flow` defines the
contracts; transports provide them.
:::

### `ByteStream` — raw bytes

The fundamental transport is split into a read half (`ByteSource`) and a write half
(`ByteSink`); `ByteStream` combines both for duplex transports. Each half carries its
deadline as a **policy val** rather than a defaulted timeout parameter — `readPolicy` /
`writePolicy` — so an implementation can override it (e.g. a persistent stream sets
`override val readPolicy = ReadPolicy.UntilClosed`), which a default parameter value
can never do:

```kotlin
interface ByteSource {
    val isOpen: Boolean
    val readPolicy: ReadPolicy
    suspend fun read(deadline: Duration): ReadResult
    suspend fun read(): ReadResult = read(readPolicy.toDeadline())
}

interface ByteSink {
    val isOpen: Boolean
    val writePolicy: WritePolicy
    suspend fun write(buffer: ReadBuffer, deadline: Duration): BytesWritten
    suspend fun write(buffer: ReadBuffer): BytesWritten = write(buffer, writePolicy.toDeadline())
    suspend fun writeGathered(buffers: List<ReadBuffer>, deadline: Duration): BytesWritten
    suspend fun writeGathered(buffers: List<ReadBuffer>): BytesWritten =
        writeGathered(buffers, writePolicy.toDeadline())
    suspend fun close() {}
}

interface ByteStream : ByteSource, ByteSink {
    override suspend fun close()
}
```

`ReadPolicy` and `WritePolicy` are sealed interfaces with two cases each: `Bounded(deadline)`
for request/response shapes, and `UntilClosed` (maps to `Duration.INFINITE`) for persistent
streams whose liveness is delegated to the transport's idle-timeout.

`read()` returns a `ReadResult`, which distinguishes a clean close from a peer reset:

```kotlin
when (val result = stream.read()) {
    is ReadResult.Data -> process(result.buffer)
    ReadResult.End     -> { /* peer closed gracefully */ }
    ReadResult.Reset   -> { /* peer forcibly reset the connection */ }
}
```

`write()` returns `BytesWritten` (a value class wrapping the count). `writeGathered()`
writes several buffers in one call — useful for header + payload without concatenating.

Two capabilities extend `ByteStream` for protocols that need finer-grained lifecycle
control:

- **`HalfCloseable`** — adds `shutdownSend()`, a send-side FIN that leaves the read side
  open (the half-close HTTP/3 request/response needs, RFC 9114 §4).
- **`Resettable`** — adds `reset(errorCode: Long)`, an abrupt abort with an application
  error code (QUIC RESET_STREAM/STOP_SENDING, RFC 9000 §19.4/§19.5) rather than a graceful
  close. It mixes onto whichever direction a stream actually has — `ByteSink, Resettable`
  for send-only, `ByteSource, Resettable` for receive-only.

For heterogeneous muxes where the codec isn't fixed per stream (e.g. HTTP/3's
self-describing unidirectional streams, RFC 9114 §6.2), `ByteStreamMux` returns raw
`ByteStream`/`ByteSink`/`ByteSource` from its `open*`/`accept*` methods instead of a typed
`Connection<T>`. Classifying an accepted stream before choosing a decoder — without consuming
or copying the prefix — is what `BufferedByteSource` is for: it wraps a `ByteSource` and adds
a non-consuming `peek(n)` backed by a small look-ahead queue, re-delivering the same bytes,
uncopied, to the next `read()`.

### `Connection<T>` — typed messages

Once a `ByteStream` has framing, it becomes a `Connection<T>` of typed messages.
`Connection<T>` combines `Sender<T>` and `Receiver<T>` with a lifecycle and a stable `id`:

```kotlin
interface Connection<T> : Sender<T>, Receiver<T> {
    val id: Long
    suspend fun close()
}
```

- `Sender<T>` — `suspend fun send(message: T)`
- `Receiver<T>` — `fun receive(): Flow<T>`

Both are `fun interface`s, so simple cases are one-liners:

```kotlin
val sender = Sender<String> { msg -> channel.send(msg) }
val receiver = Receiver<String> { flowOf("hello", "world") }
```

The `id` is the connection's identity within its `StreamMux` (or `0` for single-stream
transports like TCP). It lets the transport layer and the protocol layer correlate
logs by the same id without coupling.

:::warning Thread safety
`Connection` implementations are *not* assumed to be thread-safe. Confine `send` to one
coroutine and collect `receive()` from one coroutine, or guard with a `Mutex`.
:::

### Splitting direction with `Sender` / `Receiver`

Depending on just `Sender<T>` or just `Receiver<T>` makes a one-directional dependency
explicit. A function that only publishes takes a `Sender<T>`; a function that only
consumes takes a `Receiver<T>` — it then cannot accidentally do the opposite.

### `StreamMux<T>` — multiplexed streams

Transports with native multiplexing (QUIC, HTTP/2) implement `StreamMux<T>`. Each
method returns the tightest type for the stream's direction, so impossible states are
compile errors:

```kotlin
interface StreamMux<T> {
    suspend fun openBidirectional(): Connection<T>   // can send + receive
    suspend fun openUnidirectional(): Sender<T>      // send only
    suspend fun acceptBidirectional(): Connection<T> // peer-initiated, send + receive
    suspend fun acceptUnidirectional(): Receiver<T>  // peer-initiated, receive only
}
```

Calling `receive()` on a stream opened with `openUnidirectional()` is a compile error,
not a runtime failure. Transports *without* multiplexing (TCP, WebSocket) simply don't
implement `StreamMux` — they hand you a single `Connection` directly, with no fake
multiplexing capability.

`StreamMux` does not own stream lifecycles — the surrounding coroutine scope does. When
that scope ends or is cancelled, all open streams are force-closed via structured
concurrency.

### Protocol layering with `mapNotNull`

`Connection<A>.mapNotNull(encode, decode)` adapts a connection of one message type to
another. Inbound messages where `decode` returns `null` are dropped — the idiomatic way
to handle a connection that carries more message types than the consumer cares about:

```kotlin
fun Connection<WebSocketMessage>.asChatConnection(): Connection<ChatMessage> =
    mapNotNull(
        encode = { chat -> WebSocketMessage.Text(Json.encodeToString(chat)) },
        decode = { msg ->
            when (msg) {
                is WebSocketMessage.Text -> Json.decodeFromString<ChatMessage>(msg.value)
                else -> null   // skip ping/pong/close frames — no crash, no impossible state
            }
        },
    )
```

The result is a `Connection<ChatMessage>` that delegates `id` and `close()` to the
source connection. This is how a stack like `TCP → WebSocket → your protocol` is
assembled: each layer is a `mapNotNull` (or richer framing) over the layer below.

## When to Use What

| You have… | Use… |
|-----------|------|
| A `Flow` of chunked strings/buffers to transform | Flow extensions (`lines`, `mapBuffer`, `asStringFlow`) |
| Raw duplex bytes from a socket | `ByteStream` |
| A one-directional byte dependency | `ByteSource` or `ByteSink` |
| A stream that needs to FIN one direction independently | `HalfCloseable` |
| A stream that can be abruptly aborted with an error code | `Resettable` |
| Typed messages over one stream | `Connection<T>` |
| A one-directional message dependency | `Sender<T>` or `Receiver<T>` |
| A transport with native multiplexing, one codec for all streams | `StreamMux<T>` |
| A transport with native multiplexing, self-describing/per-stream codec | `ByteStreamMux` |
| Classifying a stream before consuming its payload | `BufferedByteSource.peek` |
| To adapt a connection to a narrower message type | `Connection.mapNotNull` |
