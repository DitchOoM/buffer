# Interface Design Proposal: buffer + buffer-codec + buffer-flow

## Motivation

The current `Codec<T>` interface conflates multiple concerns (encoding, decoding, frame
detection, size estimation) into one interface with nullable defaults that allow impossible
states. Meanwhile, `MessageConnection<T>` lives in the socket library, forcing downstream
protocol libraries (mqtt) to depend on socket even though they only need a typed channel
abstraction.

This proposal splits interfaces by capability, uses sealed types instead of nullable
primitives, and places each interface in the module that matches its dependency footprint.

## Module Layout

```
buffer              ← FrameDetector, PeekResult (operates on StreamProcessor)
buffer-codec        ← Encoder, Decoder, Codec, SizeEstimate (typed encode/decode)
buffer-flow         ← Sender, Receiver, Connection, StreamMux (typed coroutine channels)
```

- `buffer-codec` depends on `buffer`
- `buffer-flow` depends on `kotlinx-coroutines` only
- `socket` depends on `buffer`, `buffer-codec`, `buffer-flow` — implements Connection via CodecConnection
- `mqtt` depends on `buffer-codec` + `buffer-flow` — zero socket dependency

---

## buffer: FrameDetector + PeekResult

**File:** `buffer/src/commonMain/kotlin/com/ditchoom/buffer/stream/FrameDetector.kt`

**Rationale:** Frame detection operates on `StreamProcessor` which lives in `buffer`.
It's a stream-level concern — "how do I find message boundaries in a byte stream?"
Separate from encoding/decoding (which work on complete frames).

```kotlin
package com.ditchoom.buffer.stream

import kotlin.jvm.JvmInline

/**
 * Result of peeking at a byte stream to determine frame boundaries.
 *
 * Value class [Size] inlines to Int at runtime — zero allocation overhead.
 * Sealed interface forces exhaustive `when` — caller must handle both cases.
 */
sealed interface PeekResult {
    /** Frame boundary found. [bytes] is the total frame size including any headers. */
    @JvmInline
    value class Size(val bytes: Int) : PeekResult

    /** Not enough data buffered to determine the frame boundary. Try again after more data arrives. */
    data object NeedsMoreData : PeekResult
}

/**
 * Determines message frame boundaries in a buffered byte stream.
 *
 * Implementations peek at the stream without consuming bytes. This is used by
 * framing layers (e.g., CodecConnection) to know when a complete message is
 * available for decoding.
 *
 * Protocol codecs implement this alongside [com.ditchoom.buffer.codec.Decoder]
 * since frame detection requires knowledge of the wire format.
 */
fun interface FrameDetector {
    /**
     * Peeks at the stream starting at [baseOffset] to determine the total frame size.
     *
     * @param stream the buffered byte stream to peek into
     * @param baseOffset byte offset from the stream's current position (usually 0)
     * @return [PeekResult.Size] with the total frame size, or [PeekResult.NeedsMoreData]
     *         if not enough bytes are buffered to determine the boundary
     */
    fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult
}
```

**Why sealed instead of Int?:**
- `Int?` where null = "not enough data" is ambiguous — is null "not supported" or "need more data"?
- `PeekResult.NeedsMoreData` is self-documenting — the name tells you exactly what to do
- Exhaustive `when` forces the caller to handle both cases — no forgotten null checks
- `@JvmInline value class` means zero runtime overhead vs returning Int

---

## buffer-codec: Encoder, Decoder, Codec, SizeEstimate

**File:** `buffer-codec/src/commonMain/kotlin/com/ditchoom/buffer/codec/Encoder.kt`

```kotlin
package com.ditchoom.buffer.codec

import com.ditchoom.buffer.WriteBuffer
import kotlin.jvm.JvmInline

/**
 * Result of estimating the encoded size of a message.
 *
 * Used for buffer pre-allocation. [Exact] enables precise allocation (no wasted bytes).
 * [UnableToPrecalculate] falls back to a default buffer size.
 */
sealed interface SizeEstimate {
    /** Exact encoded size in bytes. Enables precise buffer allocation. */
    @JvmInline
    value class Exact(val bytes: Int) : SizeEstimate

    /** Cannot determine size without encoding. Use a default or growable buffer. */
    data object UnableToPrecalculate : SizeEstimate
}

/**
 * Encodes typed messages to bytes.
 *
 * Separated from [Decoder] so that send-only streams can require only encoding capability.
 * The type system prevents using an encoder where decoding is needed, and vice versa.
 */
interface Encoder<in T> {
    /**
     * Encodes [value] to [buffer] at the current position.
     */
    fun encode(buffer: WriteBuffer, value: T)

    /**
     * Encodes [value] to [buffer] with runtime [context].
     */
    fun encode(buffer: WriteBuffer, value: T, context: EncodeContext) = encode(buffer, value)

    /**
     * Estimates the encoded size of [value] for buffer pre-allocation.
     */
    fun sizeOf(value: T): SizeEstimate = SizeEstimate.UnableToPrecalculate
}
```

**File:** `buffer-codec/src/commonMain/kotlin/com/ditchoom/buffer/codec/Decoder.kt`

```kotlin
package com.ditchoom.buffer.codec

import com.ditchoom.buffer.ReadBuffer

/**
 * Decodes typed messages from bytes.
 *
 * Separated from [Encoder] so that receive-only streams can require only decoding capability.
 */
fun interface Decoder<out T> {
    /**
     * Decodes a value from [buffer] at the current position.
     */
    fun decode(buffer: ReadBuffer): T
}
```

**File:** Update existing `buffer-codec/src/commonMain/kotlin/com/ditchoom/buffer/codec/Codec.kt`

```kotlin
package com.ditchoom.buffer.codec

import com.ditchoom.buffer.stream.FrameDetector

/**
 * Complete codec: encodes, decodes, and detects frame boundaries.
 *
 * This is what framing layers (e.g., CodecConnection) require. By combining all three
 * capabilities into one interface, CodecConnection can be constructed with a single
 * codec parameter — no separate frame detector needed.
 *
 * Protocol implementations (MQTT, WebSocket, etc.) implement this interface.
 * The type system guarantees that a Codec always has encoding, decoding, AND
 * frame detection — no nullable "optional" capabilities.
 *
 * Context-aware overloads are provided with defaults that ignore context,
 * so simple codecs only need to implement the context-free methods.
 */
interface Codec<T> : Encoder<T>, Decoder<T>, FrameDetector {
    /**
     * Decodes a value from [buffer] with runtime [context].
     */
    fun decode(buffer: com.ditchoom.buffer.ReadBuffer, context: DecodeContext): T = decode(buffer)
}
```

**Why split Encoder/Decoder/FrameDetector:**
- `Codec<T>` currently has `sizeOf(): Int? = null` and would have `peekFrameSize(): Int? = null`
  — nullable defaults that hide missing capabilities. A CodecConnection given a codec with no
  frame detection silently breaks at runtime.
- Split interfaces make requirements explicit in the type system:
  - Send-only stream needs `Encoder<T>` only — can't accidentally require decoding
  - Receive-only stream needs `Decoder<T>` + `FrameDetector` — compiler enforces both
  - Bidirectional framed connection needs `Codec<T>` — guaranteed all three
- `fun interface Decoder<T>` enables SAM lambda: `Decoder<Int> { buffer -> buffer.readInt() }`

**Why SizeEstimate sealed instead of Int?:**
- `Int?` where null = "can't calculate" is ambiguous — could mean "zero bytes" edge case
- `SizeEstimate.UnableToPrecalculate` is unambiguous and self-documenting
- Exhaustive `when` at the call site:
  ```kotlin
  when (val size = codec.sizeOf(message)) {
      is SizeEstimate.Exact -> pool.acquire(size.bytes)
      SizeEstimate.UnableToPrecalculate -> pool.acquire(options.defaultBufferSize)
  }
  ```

---

## buffer-flow: Sender, Receiver, Connection, StreamMux

**File:** `buffer-flow/src/commonMain/kotlin/com/ditchoom/buffer/flow/Sender.kt`

```kotlin
package com.ditchoom.buffer.flow

/**
 * Sends typed messages. Used for unidirectional outbound streams.
 *
 * `fun interface` enables SAM lambda for simple cases:
 * ```kotlin
 * val sender = Sender<String> { msg -> channel.send(msg) }
 * ```
 */
fun interface Sender<in T> {
    suspend fun send(message: T)
}
```

**File:** `buffer-flow/src/commonMain/kotlin/com/ditchoom/buffer/flow/Receiver.kt`

```kotlin
package com.ditchoom.buffer.flow

import kotlinx.coroutines.flow.Flow

/**
 * Receives typed messages as a Flow. Used for unidirectional inbound streams.
 *
 * `fun interface` enables SAM lambda for simple cases:
 * ```kotlin
 * val receiver = Receiver<String> { flowOf("hello", "world") }
 * ```
 */
fun interface Receiver<out T> {
    fun receive(): Flow<T>
}
```

**File:** `buffer-flow/src/commonMain/kotlin/com/ditchoom/buffer/flow/Connection.kt`

```kotlin
package com.ditchoom.buffer.flow

/**
 * A typed, bidirectional message connection with a stable identity.
 *
 * Combines [Sender] and [Receiver] with lifecycle management. This is the primary
 * interface that protocol libraries code against — they don't need to know whether
 * the underlying transport is TCP, WebSocket, QUIC, or in-memory.
 *
 * The [id] uniquely identifies this connection within its parent [StreamMux] (or is 0
 * for single-stream transports like TCP). It enables cross-layer log correlation:
 * the transport layer logs stream lifecycle by [id], the protocol layer logs decoded
 * messages by [id] — both sides correlate without coupling.
 *
 * For incoming (peer-initiated) streams, the protocol identity (topic, QoS, packet ID)
 * is discovered on first read. The [id] provides correlation even before the first
 * message is decoded.
 *
 * Implementations:
 * - `CodecConnection` (socket library): framing via Codec + ByteStream
 * - `ReconnectingConnection` (socket library): auto-reconnection wrapper
 * - `MemoryConnection` (test utilities): in-memory pair for testing
 */
interface Connection<T> : Sender<T>, Receiver<T> {
    /**
     * Opaque identifier for this connection/stream.
     *
     * For multiplexed transports (QUIC): the transport-assigned stream ID.
     * For single-stream transports (TCP): 0.
     * For in-memory test pairs: sequential counter.
     *
     * Used for log correlation across layers — transport logs lifecycle events,
     * protocol logs decoded messages, both keyed by [id].
     */
    val id: Long

    suspend fun close()
}
```

**File:** `buffer-flow/src/commonMain/kotlin/com/ditchoom/buffer/flow/StreamMux.kt`

```kotlin
package com.ditchoom.buffer.flow

/**
 * A multiplexed connection that can open multiple independent typed streams.
 *
 * Models transports with native multiplexing (QUIC, HTTP/2). Each method returns
 * the tightest type for its stream direction, preventing impossible states at
 * compile time:
 *
 * - [openBidirectional]: returns [Connection] — can send and receive
 * - [openUnidirectional]: returns [Sender] — can only send (compile error to receive)
 * - [acceptBidirectional]: returns [Connection] — peer-initiated bidirectional
 * - [acceptUnidirectional]: returns [Receiver] — peer-initiated, can only receive
 *
 * Transports without multiplexing (TCP, WebSocket) don't implement this —
 * they provide a single [Connection] directly. No fake capabilities.
 *
 * ## Stream identity and logging
 *
 * Each [Connection] returned by the mux carries an [Connection.id] assigned by the
 * transport. For client-initiated streams, the protocol layer knows the purpose at
 * open time (e.g., "QoS 2 publish for packet 42"). For peer-initiated streams
 * ([acceptBidirectional], [acceptUnidirectional]), the purpose is discovered on
 * first read — the [Connection.id] provides log correlation even before decoding.
 *
 * Transport-layer loggers decorate the mux and log stream lifecycle:
 * ```
 * stream[7]: opened bidirectional
 * stream[7]: 3 messages exchanged
 * stream[7]: closed
 * ```
 *
 * Protocol-layer loggers add semantic context after decoding:
 * ```
 * stream[7]: PUBLISH sensor/temp qos=AT_LEAST_ONCE
 * stream[7]: PUBACK
 * ```
 *
 * Both layers correlate by [Connection.id] without coupling to each other.
 *
 * ## Example (MQTT over QUIC)
 *
 * ```kotlin
 * val mux: StreamMux<ControlPacket> = quicTransport.connectMux(host, port, codec)
 *
 * // Control channel — bidirectional, long-lived
 * val control = mux.openBidirectional()
 * control.send(connectPacket)
 *
 * // QoS 0 publish — fire and forget, shared unidirectional stream
 * val qos0 = mux.openUnidirectional()
 * qos0.send(publishPacket)
 * // qos0.receive() ← compile error, Sender has no receive()
 *
 * // QoS 1 publish — per-message bidirectional stream
 * val qos1 = mux.openBidirectional()
 * qos1.send(publishPacket)
 * val ack = qos1.receive().first()
 *
 * // Incoming publish from broker — identity discovered on first read
 * val incoming = mux.acceptBidirectional()          // stream[7]
 * incoming.receive().collect { pub ->               // NOW we know it's sensor/temp
 *     log.debug("stream[${incoming.id}]: ${pub}")
 *     incoming.send(ackFor(pub))
 * }
 * ```
 */
interface StreamMux<T> {
    /** Opens a client-initiated bidirectional stream. */
    suspend fun openBidirectional(): Connection<T>

    /** Opens a client-initiated unidirectional (send-only) stream. */
    suspend fun openUnidirectional(): Sender<T>

    /** Accepts a peer-initiated bidirectional stream. Suspends until one is opened by the peer. */
    suspend fun acceptBidirectional(): Connection<T>

    /** Accepts a peer-initiated unidirectional (receive-only) stream. Suspends until one is opened by the peer. */
    suspend fun acceptUnidirectional(): Receiver<T>

    suspend fun close()
}
```

---

## Design Rationale Summary

### Why sealed types instead of nullable primitives
- `PeekResult.NeedsMoreData` vs `null` — self-documenting, can't confuse with "not supported"
- `SizeEstimate.UnableToPrecalculate` vs `null` — can't confuse with "zero bytes"
- Exhaustive `when` forces handling every case — no forgotten null checks
- `@JvmInline value class` — zero runtime overhead, inlines to Int on JVM

### Why split Encoder/Decoder/FrameDetector
- No nullable "optional" capabilities — type system enforces what's required
- Send-only streams need `Encoder<T>` only — can't accidentally require decoding
- Receive-only streams need `Decoder<T>` + `FrameDetector` — compiler enforces both
- `Codec<T>` = all three — guaranteed complete for bidirectional framed connections

### Why Sender/Receiver/Connection split
- Can't receive on a send-only stream — `Sender<T>` has no `receive()` method
- Can't send on a receive-only stream — `Receiver<T>` has no `send()` method
- Maps to real transport capabilities (QUIC uni/bidi streams)
- Decorators (logging, metrics) compose naturally via interface wrapping

### Why Connection has an id
- Enables cross-layer log correlation without coupling transport and protocol layers
- Transport logger: "stream[7] opened/closed" (knows lifecycle, not semantics)
- Protocol logger: "stream[7] PUBLISH sensor/temp" (knows semantics, not lifecycle)
- For peer-initiated streams, identity is discovered on first read — id provides
  correlation even before decoding
- Cheap: QUIC already assigns stream IDs, TCP uses 0, tests use a counter

### Why StreamMux returns the tightest type per method
- `openUnidirectional()` → `Sender<T>` (not `Connection<T>`)
- `acceptUnidirectional()` → `Receiver<T>` (not `Connection<T>`)
- Compiler catches misuse: calling `receive()` on a `Sender` is a compile error
- Maps exactly to QUIC's 4 stream types

### Why StreamMux is in buffer-flow (not socket)
- Depends only on `kotlinx.coroutines.flow.Flow` — no transport types
- Protocol libraries (mqtt) use it without knowing about TCP/QUIC/WebSocket
- socket implements it (`QuicStreamMux : StreamMux`)
- Clean dependency graph: mqtt → buffer-flow ← socket (no mqtt → socket dependency)

---

## Migration Path

### socket (CodecConnection)
```kotlin
// Before
class CodecConnection<T>(
    val codec: Codec<T>,
    val peekFrameSize: PeekFrameSize,  // separate lambda
    ...
) : MessageConnection<T>

// After
class CodecConnection<T>(
    val codec: Codec<T>,  // codec includes FrameDetector — no separate param
    ...
) : Connection<T>  // from buffer-flow, not socket's MessageConnection
```

### socket (ReconnectingConnection)
```kotlin
// Before
class ReconnectingConnection<T>(
    connect: suspend () -> MessageConnection<T>,
    ...
) : MessageConnection<T>

// After
class ReconnectingConnection<T>(
    connect: suspend () -> Connection<T>,
    ...
) : Connection<T>  // from buffer-flow
```

### mqtt
```kotlin
// Before — depends on socket
import com.ditchoom.socket.transport.MessageConnection
import com.ditchoom.socket.transport.ReconnectingConnection
import com.ditchoom.socket.ReconnectionClassifier
import com.ditchoom.socket.ReconnectDecision

// After — depends on buffer-flow + buffer-codec only, zero socket dependency
import com.ditchoom.buffer.flow.Connection
// ReconnectingConnection still from socket (it's an implementation, not an interface)
// But mqtt's public API exposes only Connection<ControlPacket>
```

### Consumer wiring (unchanged)
```kotlin
val client = LocalMqttClient.stayConnected(broker, persistence) {
    CodecConnection.connect(host, port, MqttCodec(factory))
    // No separate peekFrameSize — codec knows its framing
}
```
