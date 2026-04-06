# buffer-flow: Composable Protocol Architecture

## Core Abstractions

```
ByteStream          — byte-level transport (TCP, QUIC, unix socket, in-memory)
Connection<T>       — typed bidirectional message connection
Sender<in T>        — outbound messages only
Receiver<out T>     — inbound messages only
```

Every protocol is a function: `ByteStream → Connection<T>`.
Every `Connection<T>` can be mapped to `Connection<U>`.

## Layering

Protocols compose by alternating between bytes and messages:

```
ByteStream (TCP)
    ↓ WebSocket protocol
Connection<WebSocketMessage>
    ↓ .map(encode, decode)
Connection<ChatMessage>
```

Or deeper:

```
ByteStream (TCP)
    ↓ WebSocket protocol
Connection<WebSocketMessage>
    ↓ .asBinaryByteStream()
ByteStream (WebSocket binary frames)
    ↓ MQTT codec
Connection<MqttPacket>
    ↓ .map(encode, decode)
Connection<SensorReading>
```

Each layer only knows about its immediate input/output types. TCP doesn't know
about WebSocket. WebSocket doesn't know about MQTT. MQTT doesn't know about
sensor readings.

## Transforms

### Sender.contramap — transform outbound

```kotlin
val textSender: Sender<String> = wsSender.contramap { text ->
    WebSocketMessage.Text(text)
}
textSender.send("hello") // sends WebSocketMessage.Text("hello")
```

### Receiver.map — transform inbound

```kotlin
val textReceiver: Receiver<String> = wsReceiver.map { msg ->
    (msg as WebSocketMessage.Text).value
}
textReceiver.receive().collect { text -> println(text) }
```

### Connection.map — both directions

```kotlin
val chat: Connection<ChatMessage> = wsConn.map(
    encode = { msg -> WebSocketMessage.Text(Json.encodeToString(msg)) },
    decode = { Json.decodeFromString((it as WebSocketMessage.Text).value) },
)
chat.send(ChatMessage(user = "alice", text = "hello"))
chat.receive().collect { msg -> println("${msg.user}: ${msg.text}") }
```

Transforms are `suspend` functions — async serialization (streaming protobuf,
buffer pool allocation) works naturally.

## Interceptors

Because every layer is an interface, interceptors are just wrappers:

```kotlin
// Log at byte level
class LoggingByteStream(private val delegate: ByteStream) : ByteStream by delegate {
    override suspend fun read(timeout: Duration): ReadResult {
        val result = delegate.read(timeout)
        if (result is ReadResult.Data) log("← ${result.buffer.remaining()} bytes")
        return result
    }
    override suspend fun write(buffer: ReadBuffer, timeout: Duration): BytesWritten {
        log("→ ${buffer.remaining()} bytes")
        return delegate.write(buffer, timeout)
    }
}

// Log at message level
class LoggingConnection<T>(private val delegate: Connection<T>) : Connection<T> by delegate {
    override fun receive() = delegate.receive().onEach { log("← $it") }
    override suspend fun send(message: T) {
        log("→ $message")
        delegate.send(message)
    }
}
```

Metrics, auth, rate limiting, circuit breakers — all the same pattern.
Apply at the right layer without the protocol knowing about them.

## Reconnection

`ReconnectingConnection<T>` wraps a `Connection<T>` factory. When the inner
connection fails, it creates a new one and resumes the message flow.

```kotlin
val ws = ReconnectingConnection<WebSocketMessage>(
    connect = {
        val tcp = TcpTransport().connect(host, port, tlsOptions)
        DefaultWebSocketClient(tcp, wsOptions).also { it.connect() }
    },
    classifier = DefaultReconnectionClassifier(),
)
```

Because `.map()` is applied outside reconnection, typed consumers see a
continuous stream across reconnects:

```kotlin
val chat: Connection<ChatMessage> = ws.map(encode, decode)
// reconnection is invisible — chat just sees ChatMessage flow
chat.receive().collect { msg -> handle(msg) }
```

## Variance

The type parameters follow standard variance:

| Interface | Variance | Why |
|-----------|----------|-----|
| `Sender<in T>` | contravariant | you put messages IN |
| `Receiver<out T>` | covariant | messages come OUT |
| `Connection<T>` | invariant | both in and out |

This means:
- A `Sender<WebSocketMessage>` accepts `Sender<WebSocketMessage.Text>` (wider input)
- A `Receiver<WebSocketMessage.Text>` satisfies `Receiver<WebSocketMessage>` (narrower output)
- `Connection<T>` requires exact type match — use `.map()` to change types

## End-to-End Example

```kotlin
// Transport
val tcp = TcpTransport().connect("broker.example.com", 443, tlsOptions)

// WebSocket protocol layer
val ws = DefaultWebSocketClient(tcp, wsOptions).also { it.connect() }

// MQTT protocol layer (over WebSocket binary frames)
val mqtt = CodecConnection(ws.asBinaryByteStream(), MqttCodec, pool)

// Application types
val sensors: Connection<SensorReading> = mqtt.map(
    encode = { reading -> MqttPublish(topic = "sensors/${reading.id}", payload = reading.encode()) },
    decode = { packet -> SensorReading.decode((packet as MqttPublish).payload) },
)

// Application code — no transport, protocol, or framing details
sensors.send(SensorReading(id = "temp-1", value = 22.5))
sensors.receive().collect { reading ->
    println("${reading.id}: ${reading.value}")
}
```

Five layers (TCP → TLS → WebSocket → MQTT → SensorReading), each independent,
each testable in isolation, each replaceable.
