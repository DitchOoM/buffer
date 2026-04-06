# Proposed buffer-flow Changes for Scope-Based QUIC

## Context

The socket library is adding QUIC support with multiplexed streams. The QUIC connection
lifecycle is scope-based: `engine.connect(host, port, opts) { /* block */ }` — the block
runs with an established connection, and when it returns, the connection closes. The
`QuicScope` inside the block extends `CoroutineScope`, so child coroutines (`launch {}`)
are tied to the connection lifetime.

This eliminates TOCTOU (time-of-check-time-of-use) bugs:
- Can't use a connection that isn't established (block only runs after handshake)
- Can't use a connection after it's closed (scope cancelled → CancellationException)
- Can't leak streams (force-closed when scope ends)
- Can't have orphan coroutines (structured concurrency)

The buffer-flow interfaces (`Connection<T>`, `StreamMux<T>`) are what protocol libraries
(mqtt) code against. They need to be compatible with this scope-based design without
knowing about QUIC or sockets.

## Current Interfaces

```kotlin
interface Connection<T> : Sender<T>, Receiver<T> {
    val id: Long
    suspend fun close()
}

interface StreamMux<T> {
    suspend fun openBidirectional(): Connection<T>
    suspend fun openUnidirectional(): Sender<T>
    suspend fun acceptBidirectional(): Connection<T>
    suspend fun acceptUnidirectional(): Receiver<T>
    suspend fun close()
}
```

## Proposed Changes

### 1. Remove `close()` from `StreamMux<T>`

**Why:** StreamMux doesn't own connection lifecycle — the transport scope does. Having
`close()` on StreamMux suggests callers should manage its lifetime, but in the scope-based
model, the mux is just a view over the connection's streams. When the connection scope
ends, all streams are force-closed automatically.

If a protocol library calls `mux.close()`, it creates ambiguity: does it close the
connection? Just the mux? What about streams opened through it? With scope-based
lifetime, this question doesn't arise — the block boundary IS the lifecycle.

```kotlin
interface StreamMux<T> {
    suspend fun openBidirectional(): Connection<T>
    suspend fun openUnidirectional(): Sender<T>
    suspend fun acceptBidirectional(): Connection<T>
    suspend fun acceptUnidirectional(): Receiver<T>
    // no close() — lifecycle is external
}
```

**Impact on mqtt:** mqtt currently doesn't call `mux.close()` directly — it manages
session lifetime at a higher level. No change needed.

### 2. Keep `close()` on `Connection<T>` — it means "stream FIN", not "connection close"

**Why:** QUIC streams have explicit lifecycle. When you're done with a stream, you send
a FIN frame to tell the peer "no more data on this stream." This is `close()` on
Connection. It's NOT the same as closing the connection — other streams continue.

If a stream is not explicitly closed when the connection scope ends, the transport
force-closes it (sends FIN). But explicit close is better — it signals intent to the
peer immediately rather than waiting for scope teardown.

```kotlin
// Inside a QUIC scope:
val stream = mux.openBidirectional()
stream.send(request)
val response = stream.receive().first()
stream.close()  // sends FIN — "I'm done with this stream"
// Connection stays alive, other streams unaffected
```

**No change to Connection<T> interface.**

### 3. No changes to `Sender<T>` or `Receiver<T>`

These are pure capability interfaces — they have no lifecycle. They're valid as long as
the stream that backs them is alive. If the stream closes or the connection scope ends,
calls to `send()` or `receive()` throw `CancellationException` via structured concurrency.

**No change.**

## How It Fits Together

```
buffer-flow (pure interfaces, no transport knowledge):
    Sender<T>       — suspend fun send(T)
    Receiver<T>     — fun receive(): Flow<T>
    Connection<T>   — Sender + Receiver + id + close()  (stream FIN)
    StreamMux<T>    — open/accept streams, no close()

socket-quic (transport layer, owns lifecycle):
    QuicScope       — extends CoroutineScope, has openStream()/acceptStream()
    QuicEngine      — connect(host, port, opts) { quicScope -> ... }
    QuicStreamMux   — implements StreamMux<T>, wraps QuicScope + Codec

Usage in mqtt (depends on buffer-flow only, not socket):
    fun mqttSession(mux: StreamMux<MqttPacket>) {
        val control = mux.openBidirectional()
        control.send(ConnectPacket(...))
        control.receive().collect { handle(it) }
        // control.close() sends FIN when session ends
        // mux has no close() — transport scope handles it
    }

Usage in app (depends on socket + mqtt):
    engine.connectMux(host, port, quicOpts, mqttCodec) { mux ->
        // mux: StreamMux<MqttPacket>, this: CoroutineScope
        launch { mqttSession(mux) }    // child of connection scope
        launch { heartbeat(mux) }      // another child
    }
    // block returns → scope cancelled → all streams FIN'd → connection closed
```

## Summary of Changes

| Interface | Change | Reason |
|-----------|--------|--------|
| `StreamMux<T>` | Remove `close()` | Doesn't own lifecycle; scope does |
| `Connection<T>` | No change | `close()` = stream FIN, still needed |
| `Sender<T>` | No change | Pure capability |
| `Receiver<T>` | No change | Pure capability |
