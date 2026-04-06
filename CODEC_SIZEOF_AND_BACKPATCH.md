# Codec Processor: sizeOf Improvements + Backpatching

## Context

The codec processor generates `sizeOf()` only for types with statically-known field sizes (primitives, SPI-backed fields like `@MqttProperties`). Three categories of fields don't get `sizeOf()`:

1. `@LengthPrefixed String` — variable UTF-8 byte length
2. `@RemainingBytes List<T>` — variable-length element lists
3. `@Payload P` — generic consumer-provided type

Additionally, computing `sizeOf` for strings is as expensive as encoding them (both require UTF-8 byte counting). Backpatching eliminates the need to pre-compute size on the encode path.

## Fix 1: `sizeOf` for `@LengthPrefixed String`

**Problem:** The processor doesn't emit `sizeOf` for `@LengthPrefixed String` because it can't determine the UTF-8 byte length at generation time.

**Fix:** Emit `sizeOf` that calls `utf8Length()`:

```kotlin
// For a field: @LengthPrefixed val topicFilter: String
// Currently generated encode:
buffer.writeLengthPrefixedUtf8String(value.topicFilter)
// Add to generated sizeOf:
size += 2 + value.topicFilter.utf8Length()
```

The `utf8Length()` extension already exists in the buffer library. The prefix size (2 for `LengthPrefix.Short`, 1 for `Byte`, 4 for `Int`) is known from the annotation parameter.

**Impact:** Enables `sizeOf` for `SubscriptionWireCodec`, `TopicFilterWireCodec`, and all wire types with string fields.

## Fix 2: `sizeOf` for `@RemainingBytes List<T>`

**Problem:** The processor doesn't emit `sizeOf` for `@RemainingBytes List<T>` even when the element codec has `sizeOf`.

**Fix:** When the element type's codec has `sizeOf`, emit a summation:

```kotlin
// For a field: @RemainingBytes val subscriptions: List<SubscriptionWire>
// Currently generated encode:
value.subscriptions.forEach { SubscriptionWireCodec.encode(buffer, it, context) }
// Add to generated sizeOf:
for (item in value.subscriptions) {
    when (val est = SubscriptionWireCodec.sizeOf(item)) {
        is SizeEstimate.Exact -> size += est.bytes
        is SizeEstimate.UnableToPrecalculate -> return SizeEstimate.UnableToPrecalculate
    }
}
```

If the element `sizeOf` is constant (e.g., `SubAckReturnCodeWireCodec.sizeOf()` always returns `Exact(1)`), optimize to:
```kotlin
size += value.returnCodes.size * 1
```

**Impact:** Enables `sizeOf` for `SubscribeWireCodec`, `SubAckWireCodec`, `UnsubscribeWireCodec`, and all V5 equivalents. Depends on Fix 1 for element types with string fields.

## Fix 3: `sizeOf` for `@Payload P` with lambda

**Problem:** `@Payload P` makes the wire type generic. The generated codec can't implement `Codec<T>` (type is polymorphic), so no `sizeOf` is generated. The encode/decode already accept lambdas for the payload — `sizeOf` should follow the same pattern.

**Fix:** Generate a `sizeOf` method that accepts a payload-size lambda:

```kotlin
// Already generated:
fun <P> encode(buffer: WriteBuffer, value: Wire<P>, encodePayload: (WriteBuffer, P) -> Unit)

// Add:
fun <P> sizeOf(value: Wire<P>, payloadSizeOf: (P) -> Int): SizeEstimate {
    var size = 0
    // ... fixed fields (same logic as non-payload sizeOf) ...
    
    // For @RemainingBytes @Payload P:
    size += payloadSizeOf(value.payload)
    
    // For @LengthPrefixed @Payload P:
    size += 2 + payloadSizeOf(value.willPayload!!)
    
    return SizeEstimate.Exact(size)
}
```

Also generate context-based variant matching the existing `encodeFromContext`/`decodeFromContext` pattern:

```kotlin
data object PayloadSizeKey : CodecContext.Key<(Any?) -> Int>()

fun sizeOfFromContext(value: Wire<*>, context: CodecContext): SizeEstimate {
    val payloadSizeOf = context[PayloadSizeKey]
        ?: return SizeEstimate.UnableToPrecalculate
    return sizeOf(value, payloadSizeOf)
}
```

**Impact:** Enables `sizeOf` for `ConnectWireCodec`, `PublishWithIdWireCodec`, `PublishNoIdWireCodec`, and V5 equivalents. The consumer provides `payloadSizeOf` at the MqttClient level — it's threaded down per-publish/per-subscribe call.

## Fix 4: Backpatching encode — eliminate sizeOf on hot path

**Problem:** Computing `sizeOf` for variable-length fields (strings, properties, payloads) is as expensive as encoding them. For strings, both `utf8Length()` and `writeString()` must iterate the characters. This means pre-computing size effectively doubles the work on the encode path.

**Fix:** Add a backpatching encode mode to the codec or provide a utility in buffer-flow. The pattern:

```kotlin
// In buffer-flow or buffer-codec:
fun <T> WriteBuffer.encodeWithBackpatch(
    codec: Codec<T>,
    value: T,
    headerWriter: (WriteBuffer, bodySize: Int) -> Unit,
): ReadBuffer {
    val reserveStart = position()
    position(reserveStart + maxHeaderSize)   // reserve space for header
    codec.encode(this, value, context)       // write body directly
    val bodySize = position() - reserveStart - maxHeaderSize
    // backpatch header
    val headerSize = headerWriter(bodySize)  // writes length prefix / VBI / etc.
    // slice valid region
    return slice(reserveStart + maxHeaderSize - headerSize, bodySize + headerSize)
}
```

For MQTT, this is the `serializeToSlice` pattern:
1. Reserve 5 bytes (max MQTT fixed header: 1 byte type + 4 bytes VBI)
2. Encode body (wire codec writes all fields including payload via consumer lambda)
3. Body size = bytes written
4. Backpatch byte1 + VBI at the front
5. Slice — zero-copy result

**Why this matters:** With backpatching, `sizeOf` becomes optional. The hot path (publish messages) can skip size pre-computation entirely. `sizeOf` is still useful for:
- Max packet size validation before encoding
- Buffer pool allocation hints
- Flow control decisions

But it's no longer required for correctness.

**Proposed API in buffer-flow:**

```kotlin
// Generic backpatching encoder
interface BackpatchEncoder<T> {
    /** Max bytes the header can occupy. */
    val maxHeaderSize: Int
    
    /** Write the header given the known body size. Returns actual header size. */
    fun writeHeader(buffer: WriteBuffer, bodySize: Int): Int
    
    /** Encode the body. */
    fun encodeBody(buffer: WriteBuffer, value: T, context: EncodeContext)
}
```

This pairs naturally with `ByteStream.write()` — encode into a pooled buffer via backpatching, then write the slice to the stream.

## ByteStream in buffer-flow

Moving `ByteStream` from socket into buffer-flow creates a clean layering:

```
buffer-flow:  ByteStream (raw bytes) → Connection<T> (typed messages)
socket:       TcpByteStream, QuicByteStream (platform impls of ByteStream)
mqtt:         depends on buffer-flow for Connection<T>, socket only for transport
```

This helps the MQTT v2 design because:

1. **Testability** — MQTT core logic tests use in-memory `ByteStream`/`Connection<T>` from buffer-flow. No socket dependency needed for testing codec integration, QoS state machines, or persistence.

2. **Transport agnostic** — MQTT client takes `Connection<ControlPacket>`, doesn't care if it's TCP, WebSocket, QUIC, or in-memory. Transport selection is a one-line factory swap.

3. **Codec ↔ ByteStream bridge** — `CodecConnection<T>` (in socket, or movable to buffer-flow) bridges `ByteStream` → `Connection<T>` using a `Codec<T>`. With backpatching, this bridge encodes directly into the ByteStream's write buffer — no intermediate allocation.

4. **Multiplexing ready** — `StreamMux` in buffer-flow supports QUIC-style multiplexed connections. Each stream is a `Connection<T>`. MQTT v5 could map topic subscriptions to separate streams over QUIC.

## Implementation Order

1. **Fix 1** (`@LengthPrefixed String` sizeOf) — smallest change, unblocks Fix 2
2. **Fix 2** (`@RemainingBytes List<T>` sizeOf) — depends on Fix 1 for string elements
3. **Fix 4** (backpatching utility in buffer-flow) — independent, high value
4. **Fix 3** (`@Payload P` sizeOf lambda) — lower priority since backpatching covers the hot path

Fixes 1+2 are processor changes. Fix 4 is a buffer-flow addition. Fix 3 is processor + buffer-codec API.
