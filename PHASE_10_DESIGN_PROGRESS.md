# Phase 10 design progress (2026-04-29)

In-progress design notes for the codec rewrite that PHASE_9_RESET.md
called for. Captures locked decisions and remaining open questions.

The `@Payload` shape — previously unresolved — was settled this session.
See locked decision 8.

Resume here. Next session can continue from a fresh context with this
file as the briefing. The cherry-pick replay plan from PHASE_9_RESET's
"Next steps" is superseded by this design effort — do not attempt to
replay 8.1/8.2/8.3/8.7/8.8 directly; the contract inversion will land
naturally inside the new processor's first commit.

## Current branch state

- HEAD: `d61b206 buffer-flow: restore Connection/StreamMux/ByteStream + adapters`
  (one commit past `4fbe05a codec: Stage 0 — strip processor to scaffolding`).
- Revert commit: `c03dac7` (reset all Phase 9 changes back to merge-base
  shape from `676d53f`).
- Stage 0 (`4fbe05a`) reduced `:buffer-codec-processor` to KSP scaffolding
  — runtime types + annotations remain, no emitter source. `:buffer-codec-test:jvmTest`
  reports `NO-SOURCE` until Stage A puts a fixture back.
- `d61b206` restored the eight buffer-flow source files + three test files
  that the `c03dac7` revert had collateral-deleted. These are the transport
  abstractions (`Connection`, `Sender`, `Receiver`, `StreamMux`, `ByteStream`,
  `ReadResult`, `BytesWritten`, `ConnectionByteStream` adapter, `Map` adapters)
  that the upcoming `Codec<T>.asConnection` bridge plugs into. `:buffer-flow:check`
  is green.
- mavenLocal stale jar still blocks downstream mqtt repo — republish is
  post-rewrite, not now.

**No emitter code, no Stage A, until decisions 9 + 10 are locked, all ten
annotation slices below pass hand-walked validation, and the Stage-A-gate
integration test (final section) is sketched and approved.**

## Locked decisions

These are settled. Treat as constraints during the rewrite.

### 1. Codec interface — split with union

```kotlin
interface Encoder<in T> {
    fun encode(buffer: WriteBuffer, value: T, context: EncodeContext)
    fun wireSize(value: T, context: EncodeContext): WireSize = WireSize.BackPatch
}

interface Decoder<out T> {
    fun decode(buffer: ReadBuffer, context: DecodeContext): T
}

interface SuspendingDecoder<out T> {
    suspend fun decode(buffer: ReadBuffer, context: DecodeContext): T
}

interface FrameDetector {
    fun peekFrameSize(stream: StreamProcessor, baseOffset: Int = 0): PeekResult
        = PeekResult.NoFraming
}

interface Codec<T> : Encoder<T>, Decoder<T>, FrameDetector
```

Rationale: send-only and receive-only consumers can implement just one
side. `@UseCodec(MyImageDecoder::class)` on a decode-only field requires
only `Decoder<T>`. Processor validates per-field path-reachability at
compile time.

### 2. `WireSize` sealed type

```kotlin
sealed interface WireSize {
    /** Codec knows the exact byte count up front; framework pre-allocates. */
    @JvmInline value class Exact(val bytes: Int) : WireSize

    /** Framework uses GrowableWriteBuffer + back-patch on length-prefixed framing. */
    data object BackPatch : WireSize
}
```

Default = `BackPatch` so codecs only opt into the fast path when sizing
is cheap. Variable-length codecs (UTF-8 strings, sealed dispatch with
variable variants, MQTT v5 properties, generic payloads) leave default
and the framework pool-backs.

### 3. `PeekResult` sealed three-variant

```kotlin
sealed interface PeekResult {
    @JvmInline value class Complete(val bytes: Int) : PeekResult
    data object NeedsMoreData : PeekResult
    data object NoFraming : PeekResult
}
```

Default = `NoFraming` so misconfigured codecs at the framing boundary
throw at startup instead of hanging the streaming loop.

### 4. Exception model — open base classes, consumers subclass

```kotlin
open class DecodeException(
    val fieldPath: String,
    val bufferPosition: Int,
    val expected: String,
    val actual: String,
    cause: Throwable? = null,
) : IllegalStateException(
    "Decode failed at $fieldPath (offset=$bufferPosition): expected $expected, got $actual",
    cause,
)

open class EncodeException(
    val fieldPath: String,
    val reason: String,
    cause: Throwable? = null,
) : IllegalStateException("Encode failed at $fieldPath: $reason", cause)
```

MQTT/WebSocket/etc. layer subclasses, attaching protocol-specific
fields like `MqttReasonCode`. Drop the `Codec` prefix; package already
provides "codec" context.

### 5. Async decode + buffer bounding

- Sync framing (`Decoder<T>`) uses `buffer.setLimit()` — zero-alloc, codec
  reads to `remaining() == 0`.
- Async framing (`SuspendingDecoder<T>`) uses `parent.slice(N).use { ... }`
  where slice is `ClosableReadBufferSlice` (implements both `ReadBuffer`
  and `Closeable`). Read-after-close throws `IllegalStateException` with
  a clear message naming the codec contract: codecs must not retain
  buffer/slice references past decode return.
- Pool/scope release across suspend points handled by lexical
  `withBuffer { }` / `use { }` semantics — fires on normal exit, exception,
  or cancellation.
- Encode stays sync. `SuspendingEncoder<T>` is a future addition only if
  a real use case demands it.

Buffer-bounding strategy is chosen at processor emission time per field,
based on the codec interface type. No runtime mode switch.

### 6. CodecContext — keep base shape, add three things

Existing `CodecContext.kt` shape stays:
- Immutable map-backed `DecodeContext` and `EncodeContext`.
- Identity-keyed lookups via `with` / `get`.
- `Empty` companion singletons.

Additions:
- `getOrDefault(key, default)` extension function.
- Field-path tracking via context (mechanism TBD — likely a `PathContext`
  facet that generated decoders push/pop through nested calls; reads only
  on exception, so happy-path overhead is one push/pop per nested codec
  level).
- Direction-specific keys via interfaces (next item).

### 7. Direction-specific keys via interfaces, KSP-enforced object-only

```kotlin
interface DecodeKey<T : Any>
interface EncodeKey<T : Any>
interface CodecKey<T : Any> : DecodeKey<T>, EncodeKey<T>

interface DecodeContext : CodecContext {
    operator fun <T : Any> get(key: DecodeKey<T>): T?
    fun <T : Any> with(key: DecodeKey<T>, value: T): DecodeContext
}
interface EncodeContext : CodecContext {
    operator fun <T : Any> get(key: EncodeKey<T>): T?
    fun <T : Any> with(key: EncodeKey<T>, value: T): EncodeContext
}
```

Type system enforces direction. `CodecKey` extends both for legitimate
bidirectional keys. KSP processor adds a validation pass that fails
compilation if any `DecodeKey` / `EncodeKey` / `CodecKey` implementation
isn't a Kotlin `object` (or anonymous object expression). Identity
equality is preserved by construction without needing `final override`
on the interface.

### 8. Payload shape — slot generics + empty marker + SAM-with-receiver

Resolved 2026-04-29 after a Q&A walk. Replaces the "Unresolved — @Payload
shape" section that previously lived here.

The constraints (carryover):
1. Sealed dispatch on packet type, exhaustively type-checked.
2. Per-instance payload type — different `Publish` on the same connection
   carry different payload types.
3. Type safety with no manual casts at every use site.
4. Zero-copy on decode AND encode.
5. Async payload decode possible (browser bitmap, hardware decoder).
6. Send-only / receive-only consumers must work without defining the
   unused direction.
7. No accidental `ReadBuffer` / `ByteArray` in payload subtypes —
   copies must be explicit.

Locked shape:

#### 8.1 Empty `Payload` marker interface

```kotlin
interface Payload   // empty — discipline marker, no methods
```

Purely an upper-bound tag. `ByteArray`, `ReadBuffer`, `String`, `Int`,
etc. don't implement it, so they can't be used as a payload type
parameter. `Nothing` satisfies it (subtype of every type) so payload-free
variants compose via covariance.

If a consumer genuinely needs to hold raw bytes, they wrap and copy
explicitly inside their decoder lambda:
```kotlin
data class OpaqueBlob(val bytes: ByteArray) : MyPub      // wrapper marked Payload
"forward/+" -> OpaqueBlob(slice.readByteArray())         // explicit memcpy at boundary
```
The `slice.readByteArray()` call is the visible copy point. The framework
doesn't sneak it in.

#### 8.2 Sealed parent parameterized per slot family

Each distinct payload-slot family gets its own type parameter on the
sealed parent. Variants bind only the params they use; unused slots are
`Nothing`.

```kotlin
sealed interface MqttControlPacket<out Will : Payload, out Auth : Payload, out Pub : Payload>

@ProtocolMessage
data class Connect<out Will : Payload, out Auth : Payload>(
    val fixedHeader: MqttFixedHeader,
    ...,
    @LengthPrefixed val willPayload: Will?,
    val username: String?,
    @LengthPrefixed val password: Auth?,
) : MqttControlPacket<Will, Auth, Nothing>

@ProtocolMessage
data class Publish<out Pub : Payload>(
    val fixedHeader: MqttFixedHeader,
    val topic: String,
    val packetId: PacketId?,
    val properties: List<Property>,
    val payload: Pub,
) : MqttControlPacket<Nothing, Nothing, Pub>

@ProtocolMessage data class Subscribe(...)   : MqttControlPacket<Nothing, Nothing, Nothing>
@ProtocolMessage data class PingReq()        : MqttControlPacket<Nothing, Nothing, Nothing>
@ProtocolMessage data class Disconnect(...)  : MqttControlPacket<Nothing, Nothing, Nothing>
```

`out` variance is sound because every payload field is `val` (out
position only). Smart-cast preserves the generic through `when`, so
`is Publish ->` narrows to `Publish<MyPub>` — exhaustive at both levels
without `<*>` or unchecked casts.

The MQTT module designer chooses how many type params and whether two
slots share one (Connect.password and Auth.authData *might* share an
`Auth` param if they're the same conceptual cargo, or be split into
`Password` and `AuthChallenge` if independent). The framework enforces
nothing about sharing — it just emits codecs that match what's declared.

#### 8.3 No `@Body` / `@Payload` annotation needed

The marker type is the signal. A field whose declared type is `: Payload`
triggers the SAM generation. Sizing rule (same as any variable-length
field):
- **Last field in the message** → consumes remaining bytes from outer
  framing.
- **Not last** → must have a length source (`@LengthPrefixed` self-prefix
  or `@LengthFrom("siblingField")`).

Processor errors at compile time if a Payload-typed field is non-terminal
and lacks a length source.

#### 8.4 Per-slot SAMs with `Partial` receiver (decode) / `value` access (encode)

For each Payload-typed field, the processor emits a `Partial` data class
holding everything decoded before that field, plus a SAM whose
`decode` is an extension on `Partial`:

```kotlin
object PublishCodec {
    data class Partial(
        val fixedHeader: MqttFixedHeader,
        val topic: String,
        val packetId: PacketId?,
        val properties: List<Property>,
    )

    fun interface PayloadDecoder<out P : Payload> {
        suspend fun Partial.decode(slice: ReadBuffer, ctx: DecodeContext): P
    }

    fun interface PayloadEncoder<in P : Payload> {
        fun encode(value: Publish<P>, buffer: WriteBuffer, ctx: EncodeContext)
        fun wireSize(value: Publish<P>, ctx: EncodeContext): WireSize = WireSize.BackPatch
    }

    suspend fun <P : Payload> decode(
        buffer: ReadBuffer,
        ctx: DecodeContext,
        payloadDecoder: PayloadDecoder<P>,
    ): Publish<P>

    fun <P : Payload> encode(
        buffer: WriteBuffer,
        value: Publish<P>,
        ctx: EncodeContext,
        payloadEncoder: PayloadEncoder<P>,
    )

    fun <P : Payload> wireSize(
        value: Publish<P>,
        ctx: EncodeContext,
        payloadEncoder: PayloadEncoder<P>,
    ): WireSize
}
```

Decoder lambda gets bare-name access to siblings via the receiver:
```kotlin
PublishCodec.decode(buffer, ctx) { slice, ctx ->
    when (topic) {                                       // bare via Partial receiver
        "images/cam1" -> JpegFrame(JpegImageCodec.decode(slice, ctx))
        else          -> error("unsubscribed topic: $topic")
    }
}
```

Encoder reads siblings via `value.field` (encode time has the full value).

Variance: `PayloadDecoder<out P>` covariant, `PayloadEncoder<in P>`
contravariant. Throwing-default decoders are typed `<Nothing>` and slot
into any decoder position; throwing-default encoders are typed `<Any>`
and slot into any encoder position.

#### 8.5 Sealed parent codec aggregates SAMs across all variants

```kotlin
suspend fun <Will : Payload, Auth : Payload, Pub : Payload> MqttControlPacketCodec.decode(
    buffer: ReadBuffer,
    ctx: DecodeContext,
    connectWillPayloadDecoder: ConnectCodec.WillPayloadDecoder<Will>     = throwingDefault("CONNECT will not expected"),
    connectPasswordDecoder:    ConnectCodec.PasswordDecoder<Auth>        = throwingDefault("CONNECT password not expected"),
    publishPayloadDecoder:     PublishCodec.PayloadDecoder<Pub>          = throwingDefault("PUBLISH not expected"),
    authAuthDataDecoder:       AuthCodec.AuthDataDecoder<Auth>           = throwingDefault("AUTH not expected"),
): MqttControlPacket<Will, Auth, Pub>

fun <Will : Payload, Auth : Payload, Pub : Payload> MqttControlPacketCodec.encode(
    buffer: WriteBuffer,
    value: MqttControlPacket<Will, Auth, Pub>,
    ctx: EncodeContext,
    connectWillPayloadEncoder: ConnectCodec.WillPayloadEncoder<Will>     = throwingDefault(...),
    connectPasswordEncoder:    ConnectCodec.PasswordEncoder<Auth>        = throwingDefault(...),
    publishPayloadEncoder:     PublishCodec.PayloadEncoder<Pub>          = throwingDefault(...),
    authAuthDataEncoder:       AuthCodec.AuthDataEncoder<Auth>           = throwingDefault(...),
)
```

Defaults throw `DecodeException`/`EncodeException` with field path. A
consumer that doesn't expect a particular packet type leaves its
decoder/encoder as the default; if it ever arrives, the exception names
exactly which slot was missing.

#### 8.6 Send-only / receive-only consumers

Locked decision #1 (split codec interface) carries through naturally:
`decode` and `encode` are separate entry points. A receive-only consumer
defines decoders and never calls `encode`. A send-only consumer defines
encoders and never calls `decode`. Per-field path-reachability (locked
#1) means the platform actual codecs only need to implement the
direction the consumer's code path actually exercises — link-time
enforcement, not runtime.

#### 8.7 Platform-actual codecs target typed shapes

Payload subtypes hold the fully-decoded shape (`ImageBitmap`,
`AudioFrame`, parsed structs). Bytes↔value translation lives in
`@UseCodec`-referenced platform codecs:

```kotlin
// commonMain
expect class ImageBitmap
expect object JpegImageCodec : Codec<ImageBitmap>

// jvmMain
actual typealias ImageBitmap = java.awt.image.BufferedImage
actual object JpegImageCodec : Codec<ImageBitmap> { ... ImageIO ... }

// jsMain
actual external class ImageBitmap
actual object JpegImageCodec : SuspendingDecoder<ImageBitmap>, Encoder<ImageBitmap> {
    actual override suspend fun decode(...) = createImageBitmap(blob).await()
}
```

Async-on-some-platforms is invisible to common code — the interface is
`SuspendingDecoder` if any platform needs it; sync platforms cost
nothing. Per locked decision #5, async decode uses `slice().use { }`
buffer-bounding; codecs must not retain the slice past return.

`@UseCodec(JpegImageCodec::class)` on a field emits direct calls to the
`expect` codec object; the linker resolves to the platform actual at
compile time. KSP doesn't inspect the actual.

#### 8.8 Worked consumer example

```kotlin
sealed interface MyWill : Payload
data class HeartbeatWill(val deviceId: String, val timestamp: Long) : MyWill

sealed interface MyAuth : Payload
data class OAuthToken(val token: String) : MyAuth

sealed interface MyPub : Payload
data class JpegFrame(val cameraId: String, val image: ImageBitmap) : MyPub
data class TempReading(val sensorId: String, val celsius: Double) : MyPub
data class RestartCommand(val delaySeconds: Int) : MyPub
data class HeartbeatStatus(val deviceId: String, val uptime: Long) : MyPub

typealias MyMqttPacket = MqttControlPacket<MyWill, MyAuth, MyPub>

val packet: MyMqttPacket = MqttCodec.decode(buffer, ctx, ...)

when (packet) {
    is Connect -> {                                    // Connect<MyWill, MyAuth>
        when (packet.willPayload) { is HeartbeatWill -> ...; null -> ... }
        when (packet.password)    { is OAuthToken -> ...; null -> ... }
    }
    is Publish -> {                                    // Publish<MyPub>
        when (val p = packet.payload) {
            is JpegFrame       -> render(p.image)
            is TempReading     -> log(p.celsius)
            is RestartCommand  -> schedule(p.delaySeconds)
            is HeartbeatStatus -> Unit
        }
    }
    is Auth, is Subscribe, is PingReq, is Disconnect, /* ... */ -> ...
}
```

No `<*>`, no casts, fully exhaustive at every level.

#### 8.9 Edge cases

1. **No preceding fields.** Skip the `Partial` type and the receiver;
   emit `fun interface ...Decoder<out P : Payload> { suspend fun decode(slice, ctx): P }`.
2. **Multiple payload-typed fields per message.** Supported. One SAM,
   one `Partial`, one type parameter binding per field.
3. **Variants without payloads.** Bind every parent type param to
   `Nothing`. Covariance makes them assignable to any concrete
   instantiation of the parent.
4. **Conditional payload fields** (`willPayload: Will?` with
   `@WhenTrue("flags.willFlag")`). SAM invoked only when gate is true;
   field is null otherwise.
5. **Nested `@ProtocolMessage` fields.** Appear as their full decoded
   type in `Partial`s. If a nested message has a `Payload` field, its
   SAM propagates to the outer codec's signature with names disambiguated
   by enclosing field path.
6. **Reserved field names.** Processor errors on fields named `slice`,
   `ctx`, `value`, or `buffer` in any Payload-bearing message (would
   shadow lambda parameters).
7. **`ReadBuffer` / `ByteArray` field check.** Processor warns (not
   errors) if a Payload-implementing class has a `ReadBuffer` field
   directly — because of slice-lifetime risk. `ByteArray` allowed
   silently (it's already a heap copy by the time it enters the field).
8. **Cross-slot type-param sharing.** MQTT module author's call. Share
   when slots are conceptually one cargo type; split when independent.
   Framework reflects whatever's declared on the sealed parent.
9. **`MqttCodec` is convenience alias.** `MqttCodec.decode` =
   `MqttControlPacketCodec.decode` extension. Naming nicety, not a
   separate type.
10. **Value classes compose seamlessly.** Value classes can implement
    `Payload` (empty marker, no method conflict), be members of a sealed
    payload hierarchy, appear as preceding fields in a `Partial`, serve
    as `@DispatchOn` discriminators, and carry computed properties
    referenced by `@WhenTrue("flags.x")` siblings. Kotlin's standard
    boxing rules apply (interface upcast, generic erasure, nullable
    field) — the framework neither prevents nor papers over this. Single
    -backing-property value classes without `@UseCodec` get a transparent
    codec that reads/writes the backing property type directly.
11. **Multiple Payload fields → generic `Partial`s.** When a message has
    two or more Payload-typed fields, the later field's `Partial` carries
    the earlier Payload-typed values typed by the parent's type
    parameters. The Partial itself becomes generic. Example: Connect's
    `password` decoder receives `PasswordPartial<Will>` so the decoder
    can read `willPayload: Will?` as a typed value. SAM signature
    therefore carries both type params:
    `fun interface PasswordDecoder<out Will : Payload, out Auth : Payload>`.
    Tractable; processor must emit generic Partial declarations.
12. **Slice is an async-safe bounded view, not a materialized buffer.**
    The `slice: ReadBuffer` handed to the decoder lambda is bounded by
    the framing region. Codecs inside the lambda may suspend during
    decode (e.g., browser ImageBitmap creation) and read incrementally
    — the slice contract (locked #5) keeps the underlying buffer alive
    until `slice().use { }` exits. No materialization cost for large
    payloads; the framework does not pre-load all bytes before invoking
    the lambda.
13. **`@ProtocolMessage` on payload subtypes composes naturally.** A
    payload subtype annotated `@ProtocolMessage` gets its own generated
    codec; the consumer's payload-decoder lambda calls
    `JpegFrameCodec.decode(slice, ctx)` directly. Same pattern as
    platform-actual codecs via `@UseCodec`.
14. **Decoding into bare `Payload` is a valid escape hatch.**
    `PublishCodec.decode<Payload>(...)` works for proxies, sniffers, and
    forwarders that don't need to dispatch on payload subtype. Loses
    sealed exhaustiveness on the payload (Payload itself is not sealed),
    which is the right tradeoff for that use case.

### 9. `asConnection` streaming bridge — codec to `Connection<T>`

Locked 2026-04-30. Bridges a generated `Codec<T>` to the buffer-flow
`Connection<T>` interface so protocol layers stop hand-rolling
`while (stream.available() >= …) { peek; read; decode; emit }` loops.

#### 9.1 Surface

```kotlin
// buffer-codec → depends on :buffer-flow (api), :buffer (StreamProcessor, BufferPool)

/**
 * Wraps a [ByteStream] transport in a typed [Connection] driven by this codec.
 *
 * `send` encodes via [Encoder.encode] / [Encoder.wireSize] into a pool-backed
 * buffer and writes the bytes to [transport]. `receive` reads bytes from
 * [transport] into a private [StreamProcessor], gates on
 * [FrameDetector.peekFrameSize], and emits decoded values.
 *
 * Single subscriber semantics: one collector at a time, no internal
 * channel, natural backpressure. Concurrency contract matches [Connection]
 * (not thread-safe; external sync required for parallel send + collect).
 *
 * Lifecycle: [Connection.close] closes [transport] and releases any
 * retained pool buffers. Cancelling the collecting coroutine cancels the
 * in-flight `transport.read()` and releases the [StreamProcessor].
 */
fun <T> Codec<T>.asConnection(
    transport: ByteStream,
    pool: BufferPool,
    decodeContext: DecodeContext = DecodeContext.Empty,
    encodeContext: EncodeContext = EncodeContext.Empty,
    id: Long = 0L,
    streamByteOrder: ByteOrder = ByteOrder.BIG_ENDIAN,
    readTimeout: Duration = 15.seconds,
    writeTimeout: Duration = 15.seconds,
): Connection<T>

/** Send-only specialization for unidirectional outbound streams. */
fun <T> Encoder<T>.asSender(
    transport: ByteStream,
    pool: BufferPool,
    encodeContext: EncodeContext = EncodeContext.Empty,
    writeTimeout: Duration = 15.seconds,
): Sender<T>

/**
 * Receive-only specialization. Requires both decode and frame detection;
 * a `Codec<T>` satisfies both via inheritance, but a custom decoder paired
 * with a separately-held framer also fits.
 */
fun <T, FD> FD.asReceiver(
    transport: ByteStream,
    pool: BufferPool,
    decodeContext: DecodeContext = DecodeContext.Empty,
    streamByteOrder: ByteOrder = ByteOrder.BIG_ENDIAN,
    readTimeout: Duration = 15.seconds,
): Receiver<T> where FD : Decoder<T>, FD : FrameDetector
```

#### 9.2 Send semantics

1. Compute `wireSize(value, encodeContext)`.
2. **`Exact(n)` path:** `pool.withBuffer(minSize = n) { buf -> encode(buf, value, encodeContext); buf.flip(); transport.write(buf, writeTimeout) }`. One buffer, one write, zero copies past the encoded bytes; pool reclaim is automatic via `withBuffer`.
3. **`BackPatch` path:** acquire a pool buffer; wrap in a `GrowableWriteBuffer` that requests further buffers from `pool` on grow events (each grown chunk is tracked for `writeGathered`); after `encode` returns, gather-write the chunk list via `transport.writeGathered(chunks, writeTimeout)`; release every chunk in `finally`.
4. `EncodeException` propagates to the `send` caller. Transport stays open so the caller can retry or close at their discretion.
5. `send` is suspending; serialization across concurrent invocations is the caller's responsibility per the `Connection<T>` thread-safety contract.

#### 9.3 Receive semantics

`receive()` returns a **cold** `Flow<T>` built with `flow { … }`. On each collector the bridge:

1. Lazily creates a private `StreamProcessor` configured with `pool` and `streamByteOrder`. The processor lives only for the duration of the collection (released in `finally`).
2. Loops:
   - Call `peekFrameSize(stream, baseOffset = 0)`.
     - `Complete(n)` → `stream.readBufferScoped(n) { decode(this, decodeContext) }` → `emit(value)`.
     - `NeedsMoreData` → `transport.read(readTimeout)`:
       - `ReadResult.Data(chunk)` → `stream.append(chunk)` → loop.
       - `ReadResult.End` → break loop and complete the flow normally.
       - `ReadResult.Reset` → throw `TransportResetException` (defined in `:buffer-flow`; subclass of `IllegalStateException`); flow terminates as failed.
     - `NoFraming` → throw `IllegalStateException("Codec ${this::class.simpleName} returned NoFraming at the framing boundary; override peekFrameSize on bridged codecs.")`. Surfaces misconfiguration loudly at first emit instead of hanging the loop.
3. Backpressure is natural: the next `transport.read()` doesn't fire until the previous `emit` has been consumed. No internal channel.
4. `DecodeException` propagates as a terminal flow failure. The stream state after a decode failure is treated as corrupt; callers should `close()` the connection rather than re-collect.
5. Single-subscriber: the cold flow does not multiplex. Two parallel collectors race the transport and produce undefined behaviour — this matches the `Connection<T>` thread-safety contract.

#### 9.4 Lifecycle

- `close()` closes `transport`, releases any retained pool buffers held by the send-side, and is idempotent.
- Cancelling the collecting coroutine cancels the in-flight `transport.read()` (suspending — propagates `CancellationException`), releases the `StreamProcessor` in `finally`, and leaves `transport` open for reuse via `send()` or a fresh collect.
- `Connection.id` is supplied by the caller (defaults to `0L` for single-stream transports), passed through unchanged. For `StreamMux`-backed bridges the `StreamMux` implementation owns id assignment and threads the value down.

#### 9.5 No internal coroutine scope

The bridge does not take a `CoroutineScope` parameter. The receive flow runs entirely inside the collector's coroutine; the send path is synchronous-suspend inside `send()`. There is nothing for the bridge to launch and therefore no scope leak risk. The `ConnectionByteStream` adapter (which *does* take a scope because it has to bridge push→pull semantics across the `ByteStream` boundary) is a separate concern in the protocol-layering direction.

#### 9.6 Pool ownership

The bridge **borrows** `pool` — never closes it, never clears it. Callers own pool lifecycle via `withPool { … }` or equivalent. Acquired buffers are always released in a matching `finally`. If a `BackPatch` encode throws mid-stream, every chunk acquired up to that point is released before the exception propagates.

#### 9.7 Where it lives

Implementation lands in `buffer-codec/src/commonMain/kotlin/com/ditchoom/buffer/codec/AsConnection.kt`. `:buffer-codec` adds an `api` dependency on `:buffer-flow` (it already depends on `:buffer`). No platform-specific code.

### 10. Documentation generation contract — KDoc + ASCII wire diagrams

Locked 2026-04-30. Each generated codec object carries enough KDoc that a
reader skimming generated sources can match what they see against the
source spec without paging back to the model.

#### 10.1 Per-codec KDoc shape

Every generated `object FooCodec : Codec<Foo>` (and every per-payload
`object FooCodec.PayloadDecoder<…>` / `Partial`) gets exactly this KDoc
block, in this order:

1. **One-line summary, mechanical:** `Generated codec for [Foo] — wire-format details below.` Always this template; never paraphrased from the message's KDoc. Paraphrasing decays as the spec evolves; mechanical text doesn't.
2. **Type-link references:** `@see Foo` plus one `@see` per nested codec invoked by `decode` (`@see HeaderCodec`, `@see [PayloadDecoder]`, etc.). Cross-module references use FQN; same-module use simple name.
3. **ASCII wire diagram** (see 10.2; emitted only when 10.3's trigger fires).
4. **Source documentation block:** if the message class itself carries KDoc, it is reproduced *verbatim* under the heading `Source documentation:`. Never re-summarized, never elided, never reflowed. If the message has no KDoc, this block is omitted.

Per-method KDoc (encode / decode / wireSize / peekFrameSize) is one line per method describing the role plus an `@throws DecodeException` / `@throws EncodeException` block where applicable. No prose elaboration; the method bodies are short enough that names + the wire diagram carry the rest.

#### 10.2 Diagram rendering rules

Diagrams use the RFC-793 grid style — a top scale of bit positions with a vertical bar at each byte boundary, then one row per field (or one row per word for multi-byte fields).

| Field shape | Rendering |
|---|---|
| Fixed-width scalar within a byte (≤ 8 bits) | bit-level cells in the byte's row, labeled with the field name |
| Fixed-width multi-byte scalar | full-width row(s) labeled `name (Nb LE/BE)` where `Nb` is byte count |
| `@WireBytes(N)` custom-width scalar | full-width row labeled with the explicit byte count and endianness |
| Variable-length field (`@LengthPrefixed`, `@LengthFrom`, `@RemainingBytes`, terminal slice) | single full-width row labeled `<len> bytes — name` with the length-source hint in parens (e.g., `(prefix=Short BE)`, `(remaining)`, `(from chunkSize)`) |
| Conditional field (`@WhenTrue`) | row prefixed with `[when expr]` so the gate is inline; absent fields not drawn |
| Sealed dispatch (`@PacketType`) | the discriminator byte/word as one row with all known wire values listed in a sub-table; per-variant diagram lives on each variant's codec, not the dispatcher's |
| Bit-packed value class (`@DispatchOn`) | the discriminator byte rendered at the bit level with sub-property labels (e.g., `packetType (4)` `flags (4)`) |

Diagrams are emitted as fenced KDoc blocks marked with the language tag `text` (so Dokka renders them as preformatted code, not Markdown).

Anchor example (RIFF chunk header — slice 1 below uses this):

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+---------------+---------------+---------------+---------------+
|     'R'       |     'I'       |     'F'       |     'F'       |  fourCC (4b ASCII)
+---------------+---------------+---------------+---------------+
|                          chunkSize (4b LE)                    |
+---------------------------------------------------------------+
| <chunkSize> bytes — body (from chunkSize)                     |
+---------------------------------------------------------------+
```

#### 10.3 When a diagram is emitted

The processor emits a diagram on a generated codec **only if both**:

1. The annotated message has **more than 2 fields**, AND
2. **At least one field is non-trivial** — meaning any of:
   - variable-length (`@LengthPrefixed`, `@LengthFrom`, `@RemainingBytes`, terminal Payload),
   - conditional (`@WhenTrue`),
   - dispatched (`@PacketType` discriminator on a sealed parent),
   - bit-packed (value class with `@DispatchValue` extracting bits),
   - custom-width (`@WireBytes`),
   - mixed-endian (`@WireOrder` overriding the message default on at least one field).

Single-scalar messages (Heartbeat) and trivial fixed-layout messages
(two `Long`s, three `UByte`s) do not get a diagram — the field list is
already self-documenting and a diagram would be visual noise. Sealed
parents always get a diagram of the discriminator alone, even if they
themselves have ≤ 2 fields, because the dispatch is the reviewability
lever.

#### 10.4 No paraphrased KDoc

The processor never invents prose summarizing message intent. The only
generated prose is:

- the mechanical one-liner from 10.1.1,
- mechanical method-role lines,
- the verbatim source-doc block from 10.1.4.

Anything richer (rationale, history, references to spec sections) is
the model author's responsibility and is propagated unchanged.

#### 10.5 No synthetic value-class wrappers around primitives

The processor never generates a wrapper value class around a primitive
field type to "hold" wire metadata. If a message field is
`val packetId: UShort`, the generated codec reads `buffer.readUShort()`
and writes it directly; no `PacketIdCodec` indirection, no `PacketId`
inline class. Wire metadata (endianness, custom width) lives on
annotations, not on synthetic types.

The only types the processor introduces are:

- the codec `object` itself (`FooCodec`),
- payload `Partial` data classes (Section 8),
- payload decoder/encoder SAM `fun interface`s (Section 8).

User-declared value classes (e.g., `MqttFixedHeader`) are read and
written through their `@JvmInline` backing property — no wrapping or
re-boxing.

## Annotation validation slices

Each subsection below exercises one annotation against a real wire-format
field, sketches the generated `encode` / `decode` / `wireSize` /
`peekFrameSize` output by hand (with the wire diagram per Section 10),
audits zero-copy / batching, and records reviewability + streaming
findings. Order is gating: a slice is locked only after the prior one
has been signed off in this file.

> **Slices 2–10 + integration test are scheduled** but unfilled at this
> commit. They will be added in subsequent commits, one slice per
> sitting, in the order listed under "Validation slice schedule" below.

### Slice 1 — `@ProtocolMessage` on the RIFF chunk header

**Source vector.** RIFF (Resource Interchange File Format), used by WAV,
AVI, WebP, and many others. A RIFF chunk is the primitive structural
unit: a 4-byte ASCII FourCC tag, a 4-byte little-endian unsigned size,
then `size` bytes of body, then optional 1-byte pad to keep the next
chunk 2-byte aligned. For Slice 1 we exercise only the *header* — the
body is the subject of slices 4 (`@LengthFrom`) and 5 (`@RemainingBytes`).

```kotlin
/**
 * RIFF chunk header — 4-byte ASCII tag plus 4-byte little-endian size.
 * The body of [chunkSize] bytes follows on the wire; see [RiffChunk] for
 * the framed view.
 */
@ProtocolMessage(wireOrder = Endianness.Little)
data class RiffChunkHeader(
    val fourCC: UInt,    // 4 bytes; ASCII packed BE in the spec, but
                         // we expose it as a numeric tag for matching
    val chunkSize: UInt, // 4 bytes LE — payload byte count, excluding pad
)
```

Header-only is exactly 8 bytes, fixed layout, two fields → falls *below*
the Section 10.3 diagram trigger (≤ 2 fields, no non-trivial). No
diagram emitted. The codec object KDoc carries only the mechanical
one-liner + `@see RiffChunkHeader` + the verbatim KDoc block.

**Sketched generated output.**

```kotlin
/**
 * Generated codec for [RiffChunkHeader] — wire-format details below.
 *
 * @see RiffChunkHeader
 *
 * Source documentation:
 *   RIFF chunk header — 4-byte ASCII tag plus 4-byte little-endian size.
 *   The body of [chunkSize] bytes follows on the wire; see [RiffChunk] for
 *   the framed view.
 */
object RiffChunkHeaderCodec : Codec<RiffChunkHeader> {

    /** Reads 8 bytes — fourCC then little-endian chunkSize. */
    override fun decode(buffer: ReadBuffer, context: DecodeContext): RiffChunkHeader {
        val fourCC    = buffer.readUInt()                   // big-endian tag bytes
        val chunkSize = java.lang.Integer.reverseBytes(buffer.readInt()).toUInt()
        return RiffChunkHeader(fourCC, chunkSize)
    }

    /** Writes 8 bytes — fourCC then little-endian chunkSize. */
    override fun encode(buffer: WriteBuffer, value: RiffChunkHeader, context: EncodeContext) {
        buffer.writeUInt(value.fourCC)
        buffer.writeInt(java.lang.Integer.reverseBytes(value.chunkSize.toInt()))
    }

    /** Always 8 bytes. */
    override fun wireSize(value: RiffChunkHeader, context: EncodeContext): WireSize =
        WireSize.Exact(8)

    /** Always 8 bytes — peek succeeds the moment 8 bytes are buffered. */
    override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult =
        if (stream.available() - baseOffset >= 8) PeekResult.Complete(8)
        else PeekResult.NeedsMoreData
}
```

**Audit — zero-copy.** Decode pulls two scalars from the buffer; nothing
is allocated except the `RiffChunkHeader` instance itself. Encode writes
two scalars; no temporary buffer. `wireSize` returns a value-class
`WireSize.Exact` — no allocation on the JVM after escape analysis.
`peekFrameSize` is a single comparison — no allocation. Zero `ByteArray`,
zero pool acquire on the codec hot path. ✓

**Audit — batching.** No bulk operation applicable for a 4+4 split with
mixed effective endianness. The two scalar writes hit the underlying
buffer's primitive write path; on a DirectJvmBuffer that's two `putInt`
calls with no per-call object allocation. The little-endian swap on
`chunkSize` is `Integer.reverseBytes` (one CPU instruction on x86 via
`bswap`). No batching opportunity ruled out: tried "encode as one Long"
— that would force a manual 4-byte BE / 4-byte LE assembly that's both
less readable and not measurably faster on either DirectJvmBuffer or
ByteArrayBuffer. **Less-clever alternative ruled out on audit grounds.** ✓

**Audit — reviewability.**

- Self-documenting names map directly to spec terminology: `fourCC`, `chunkSize` are the literal RIFF spec terms (RIFF 1.0, "Chunk Header" section).
- The mechanical 1-liner and `@see` link tell the reader where to look. The verbatim KDoc block carries the model author's reference to `RiffChunk`.
- No `// Wire:` comment is needed — `readUInt()` + `reverseBytes(readInt()).toUInt()` is unambiguously "BE u32 then LE u32"; adding a comment would just restate the call.
- No `// Batched:` comment is needed — there is no batched optimization here.
- A reader holding the RIFF spec next to this generated file can validate the wire layout in one pass without scrolling. ✓

**Audit — streaming.**

- `peekFrameSize` returns `Complete(8)` exactly when 8 bytes are buffered past the base offset. With 7 bytes buffered it returns `NeedsMoreData`. Verified mentally against the bridge loop in 9.3: `Complete(8)` → `readBufferScoped(8) { decode(this, ctx) }` → emit. ✓
- Encode under `WireSize.Exact(8)`: bridge acquires a ≥ 8-byte pool buffer, encode writes to it, transport.write fires. No `ByteArray` materialization. Verified against 9.2 step 2. ✓
- Encode under `WireSize.BackPatch` is unreachable here (codec returns `Exact`). Confirmed the `Exact` path is exercised; the BackPatch path is exercised in slice 3.
- Terminal-field slice-bounding is not applicable to this slice (no terminal variable field). Will be exercised in slices 4 and 5.

**Findings.** `@ProtocolMessage` on a fixed-layout, ≤ 2-field, mixed-endian
header generates clean code that is byte-correct, zero-allocating, fully
streaming-compatible, and reviewable against the RIFF spec without
augmentation. The diagram-trigger threshold (Section 10.3) correctly
omits a diagram here — the field list is shorter than any diagram would
be. The little-endian override per field (via `wireOrder = Little` on
the message and BE-natural `fourCC` read as a tag) is handled by
endianness mechanics, not by the annotation under test; that interaction
is the explicit subject of slice 2.

**Slice status:** validated. Move on to slice 2.

### Slice 2 — `@WireOrder` + `@WireBytes` on a mixed-endian header

**Source vector.** Two real fields fused into one synthetic message so
the slice exercises both annotations interacting:

- **DNS header (RFC 1035 §4.1.1).** Six 16-bit big-endian fields:
  `id`, `flags`, `qdCount`, `anCount`, `nsCount`, `arCount`. 12 bytes
  fixed; everything is BE because DNS is network-order.
- **BLE ATT Read Blob Response, length triplet (Bluetooth Core 4.0+ Vol
  3 Part F §3.4.4.6).** A 3-byte little-endian unsigned offset packed
  alongside an opcode in some ATT PDU shapes — concretely: an `opcode`
  byte plus a 24-bit LE `attHandleOffset`.

We mash them into one message that is BE by default (DNS-style) but has
one explicit LE 3-byte field bolted on (ATT-style). This is the exact
shape `@WireOrder` + `@WireBytes` are meant to handle: the message
default is one endianness, a single field overrides it, and that field
also has a non-natural byte width.

```kotlin
/**
 * Mixed-endian header — DNS-style six 16-bit BE fields followed by an
 * opcode byte and a 24-bit little-endian offset. Synthetic: collapses
 * RFC 1035 §4.1.1 with a BLE ATT length-triplet shape so a single
 * vector exercises @WireOrder per-field override and @WireBytes
 * custom width.
 */
@ProtocolMessage(wireOrder = Endianness.Big)
data class MixedHeader(
    val id: UShort,
    val flags: UShort,
    val qdCount: UShort,
    val anCount: UShort,
    val nsCount: UShort,
    val arCount: UShort,
    val opcode: UByte,                                    // 1 byte; endianness moot
    @WireOrder(Endianness.Little)
    @WireBytes(3)
    val attHandleOffset: Int,                             // 3 bytes LE; sign-extended into Int
)
```

Eight fields, one is non-trivial (mixed-endian + custom width) — the
Section 10.3 trigger fires, so a diagram **is** emitted. Total wire
size: 6×2 + 1 + 3 = **16 bytes**, fully fixed.

**Sketched generated output.**

```kotlin
/**
 * Generated codec for [MixedHeader] — wire-format details below.
 *
 * @see MixedHeader
 *
 * ```text
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-------------------------------+-------------------------------+
 * |             id (2b BE)        |          flags (2b BE)        |
 * +-------------------------------+-------------------------------+
 * |          qdCount (2b BE)      |        anCount (2b BE)        |
 * +-------------------------------+-------------------------------+
 * |          nsCount (2b BE)      |        arCount (2b BE)        |
 * +-------------------------------+-------------------------------+
 * |    opcode     |       attHandleOffset (3b LE)                 |
 * +---------------+-----------------------------------------------+
 * ```
 *
 * Source documentation:
 *   Mixed-endian header — DNS-style six 16-bit BE fields followed by an
 *   opcode byte and a 24-bit little-endian offset. Synthetic: collapses
 *   RFC 1035 §4.1.1 with a BLE ATT length-triplet shape so a single
 *   vector exercises @WireOrder per-field override and @WireBytes
 *   custom width.
 */
object MixedHeaderCodec : Codec<MixedHeader> {

    /** Reads 16 bytes — six BE u16s, one u8, one 3-byte LE offset. */
    override fun decode(buffer: ReadBuffer, context: DecodeContext): MixedHeader {
        // Wire: message default is BE; readUShort hits the buffer's BE path
        val id      = buffer.readUShort()
        val flags   = buffer.readUShort()
        val qdCount = buffer.readUShort()
        val anCount = buffer.readUShort()
        val nsCount = buffer.readUShort()
        val arCount = buffer.readUShort()
        val opcode  = buffer.readUByte()
        // Wire: 3 LE bytes assembled little-end-first into an Int (top byte = 0)
        val b0 = buffer.readUByte().toInt()
        val b1 = buffer.readUByte().toInt()
        val b2 = buffer.readUByte().toInt()
        val attHandleOffset = b0 or (b1 shl 8) or (b2 shl 16)
        return MixedHeader(id, flags, qdCount, anCount, nsCount, arCount, opcode, attHandleOffset)
    }

    /** Writes 16 bytes — six BE u16s, one u8, one 3-byte LE offset. */
    override fun encode(buffer: WriteBuffer, value: MixedHeader, context: EncodeContext) {
        buffer.writeUShort(value.id)
        buffer.writeUShort(value.flags)
        buffer.writeUShort(value.qdCount)
        buffer.writeUShort(value.anCount)
        buffer.writeUShort(value.nsCount)
        buffer.writeUShort(value.arCount)
        buffer.writeUByte(value.opcode)
        // Wire: 3 LE bytes — emit low byte first, drop top byte
        val v = value.attHandleOffset
        buffer.writeUByte((v and 0xFF).toUByte())
        buffer.writeUByte(((v ushr 8) and 0xFF).toUByte())
        buffer.writeUByte(((v ushr 16) and 0xFF).toUByte())
    }

    /** Always 16 bytes. */
    override fun wireSize(value: MixedHeader, context: EncodeContext): WireSize =
        WireSize.Exact(16)

    /** Always 16 bytes — peek succeeds the moment 16 bytes are buffered. */
    override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult =
        if (stream.available() - baseOffset >= 16) PeekResult.Complete(16)
        else PeekResult.NeedsMoreData
}
```

**Audit — zero-copy.** Decode is eight scalar reads plus three byte
reads composed via shifts; the only allocation is the `MixedHeader`
itself. Encode is eight scalar writes plus three byte writes; no
intermediate buffer. `wireSize` is `WireSize.Exact(16)`. `peekFrameSize`
is one comparison. No `ByteArray`, no pool acquire on the codec hot
path. ✓

**Audit — batching.** I considered three tighter shapes and ruled each
out on audit grounds:

1. *"Read all six BE u16s as one giant `readByteArray(12)` then
   re-parse."* Forces a `ByteArray` allocation. **Ruled out — violates
   PHASE_9_RESET item 3.**
2. *"Read three LE bytes as `readMedium()` if the buffer ever grows
   such an API."* Today there's no `readMedium()` / `read24LE` on
   `ReadBuffer`; adding one is pure scope creep for one slice. The
   shift-and-combine is one CPU instruction per byte plus two ORs —
   already optimal on every target. **Ruled out — speculative API for
   no measurable win.**
3. *"Encode the 16 bytes by allocating a 16-byte `ByteArray`,
   filling it with `Bits.put`, and bulk-writing."* Same `ByteArray`
   ban; also a memory copy. **Ruled out.**

The straight-line scalar-write code is both the most readable and the
fastest path on every backend. **Less-clever alternative ruled out on
audit grounds.** ✓

**Audit — reviewability.**

- Field names are the literal RFC 1035 names (`qdCount`, `anCount`, etc.) and the literal BLE ATT term (`attHandleOffset`). A reader holding either spec next to this generated code can verify it in one pass.
- Two `// Wire:` comments are present, only where the optimization is non-obvious:
  - one on the LE 3-byte assembly (the shift pattern is the optimization vs. allocating an intermediate buffer or a stdlib `Int.fromLittleEndianBytes` that doesn't exist),
  - one on the symmetric LE 3-byte emit.
- The BE u16 reads / writes are *not* commented — the call name (`readUShort`) on a BE-default message is unambiguous.
- The wire diagram (Section 10) makes the mixed-endian byte 13–15 region visually obvious — opcode and the LE triplet are in the same row, which mirrors the wire layout exactly.
- No `// Batched:` comments are needed because there is no batched optimization (see audit-batching above).
- `attHandleOffset` is declared `Int`, not `UInt` — the underlying 24-bit value fits in either, but `Int` matches `@WireBytes(N)`'s natural Kotlin-side type for "decoded into Long/Int." Decision noted for slice 4 cross-reference. ✓

**Audit — streaming.**

- `peekFrameSize` returns `Complete(16)` precisely when 16 bytes are buffered past the base offset; with 15 bytes it returns `NeedsMoreData`. ✓
- Encode under `Exact(16)`: bridge acquires a ≥ 16-byte pool buffer (16 byte ask resolves to the pool's `defaultBufferSize` minimum, typically 8 KB), encode writes 16 bytes, transport.write fires. No `ByteArray` materialization. ✓
- BackPatch path unreachable — codec returns `Exact`. ✓
- Terminal-field slice-bounding not applicable (no terminal variable field).

**Findings.**

1. `@WireOrder(Endianness.Little)` + `@WireBytes(3)` compose cleanly: the
   per-field annotations override the message default *only* for the
   annotated field, generated code stays fixed-layout, `wireSize` stays
   `Exact`. The two annotations are independent — the processor honors
   `@WireOrder` by emitting either the BE-natural call (default), the
   LE-natural call (when the buffer's natural order is LE), or the
   manual byte assembly (when the wire width doesn't match the Kotlin
   type's natural width, as here). No coupling between the two
   annotations beyond the fact that custom-width usually goes hand in
   hand with explicit endianness.
2. **Defers PHASE_9_RESET decision "@WireOrder + @WireBytes
   consolidation".** Keeping them separate (per CLAUDE.md §8.4
   originally and confirmed here) is the right call: the annotations
   *do* different things — one selects byte order, the other selects
   wire width. Consolidating into one `@Wire(order=…, bytes=…)`
   would force the user to spell out both even when only one is
   non-default; staying separate keeps the call sites minimal. Lock
   that decision now: **separate annotations, no consolidation.**
3. **Defers PHASE_9_RESET decision "`LengthPrefix` enum shape".** Not
   yet driven — slice 3 is the first concrete vector that exercises
   it. Defer to slice 3.
4. The `// Wire:` comment policy from Section 10.4-equivalent (rephrased
   here for emitter rules): emit a `// Wire:` comment when the byte
   pattern being assembled is non-obvious from the call sequence
   (multi-byte assembly via shifts, bit-packing, custom width). Do
   *not* emit one for natural-width scalar reads in the message default
   endianness. **Lock the policy: comment only the non-obvious.**

**Slice status:** validated. Two locks recorded (separate
`@WireOrder`/`@WireBytes`; `// Wire:` comment policy). Move on to
slice 3.

### Slice 3 — `@LengthPrefixed` on MQTT v5 UTF-8 string fields

**Source vector.** MQTT v5 uses a single uniform shape for every UTF-8
string on the wire, defined in §1.5.4 of the spec ("UTF-8 Encoded
String"): a 2-byte big-endian unsigned length prefix followed by the
UTF-8 bytes (≤ 65 535 bytes). The PUBLISH packet's *topic name* is the
canonical field exercising this shape — every PUBLISH carries one,
exactly once, between the variable-header byte counts and the optional
packet identifier.

For this slice we model a stripped-down "PUBLISH variable header head"
that has just the topic name and a non-string trailing scalar so the
prefix's *not-terminal* role is also exercised (terminal would let
`@RemainingBytes` apply, which is slice 5).

```kotlin
/**
 * Stripped-down MQTT v5 PUBLISH variable header: a UTF-8 length-prefixed
 * topic name followed by a packet identifier. Mirrors §3.3.2.1
 * (Topic Name) + §3.3.2.2 (Packet Identifier) without the property
 * length / properties / payload.
 */
@ProtocolMessage(wireOrder = Endianness.Big)
data class PublishHead(
    @LengthPrefixed                              // default = LengthPrefix.Short (2 bytes BE)
    val topic: String,
    val packetId: UShort,
)
```

Two fields → falls *below* the Section 10.3 diagram trigger by field
count. But: at least one field is non-trivial (`@LengthPrefixed`), and
the rule reads "more than 2 fields **AND** at least one non-trivial."
Both conjuncts must be true; here field-count fails. **No diagram
emitted.** This is a deliberate test of the trigger boundary — the
single-prefix-plus-trailing-scalar shape is common enough (HTTP/2
header pair, DNS QNAME-len + Type, ATT prepare-write request) that we
do *not* want to clutter every two-field codec with a one-line diagram.

**Sketched generated output.**

```kotlin
/**
 * Generated codec for [PublishHead] — wire-format details below.
 *
 * @see PublishHead
 *
 * Source documentation:
 *   Stripped-down MQTT v5 PUBLISH variable header: a UTF-8 length-prefixed
 *   topic name followed by a packet identifier. Mirrors §3.3.2.1
 *   (Topic Name) + §3.3.2.2 (Packet Identifier) without the property
 *   length / properties / payload.
 */
object PublishHeadCodec : Codec<PublishHead> {

    /**
     * Reads a 2-byte BE length prefix, then `len` UTF-8 bytes for [PublishHead.topic],
     * then 2 BE bytes for [PublishHead.packetId].
     *
     * @throws DecodeException if the buffer is shorter than `2 + len + 2`.
     */
    override fun decode(buffer: ReadBuffer, context: DecodeContext): PublishHead {
        val topicLen = buffer.readUShort().toInt()
        val topic = buffer.readString(topicLen, Charset.UTF8).toString()
        val packetId = buffer.readUShort()
        return PublishHead(topic, packetId)
    }

    /**
     * Writes a 2-byte BE length prefix, the UTF-8 bytes, then 2 BE bytes for packetId.
     * Uses back-patch on the prefix: writes a placeholder, calls `writeString`, then
     * patches the prefix at the recorded absolute position.
     */
    override fun encode(buffer: WriteBuffer, value: PublishHead, context: EncodeContext) {
        // Wire: back-patched 2-byte BE length prefix
        val lenPos = buffer.position()
        buffer.writeUShort(0u)                              // placeholder
        val before = buffer.position()
        buffer.writeString(value.topic, Charset.UTF8)
        val written = buffer.position() - before
        require(written <= UShort.MAX_VALUE.toInt()) {
            "topic exceeds 65535 UTF-8 bytes (got $written)"
        }
        // Wire: patch the prefix at lenPos with the actual byte count
        buffer[lenPos]     = ((written ushr 8) and 0xFF).toByte()
        buffer[lenPos + 1] = (written and 0xFF).toByte()
        buffer.writeUShort(value.packetId)
    }

    /**
     * `BackPatch` because the topic's UTF-8 byte count isn't known without
     * encoding (or pre-measuring, which is itself a UTF-8 walk we'd just
     * re-do at write time). The bridge handles `BackPatch` by routing
     * encode through a growable pool-backed write buffer.
     */
    override fun wireSize(value: PublishHead, context: EncodeContext): WireSize =
        WireSize.BackPatch

    /**
     * Frame size = 2 + topicLen + 2. Peeks the prefix; if available,
     * returns Complete with the total byte count; otherwise NeedsMoreData.
     */
    override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
        if (stream.available() - baseOffset < 2) return PeekResult.NeedsMoreData
        val topicLen = stream.peekShort(baseOffset).toUShort().toInt()  // BE peek
        val total = 2 + topicLen + 2
        return if (stream.available() - baseOffset >= total) PeekResult.Complete(total)
        else PeekResult.NeedsMoreData
    }
}
```

**Audit — zero-copy.** Decode allocates one `String` for the topic
(unavoidable — `String` is the declared field type and Kotlin Strings
are heap objects on every backend). The `readString(n, Charset.UTF8)`
call hits the buffer's natively-optimized UTF-8 path — on Apple it's
NSString init from a sliced NSData, on JVM it's a `String(bytes, off,
len, UTF_8)` direct constructor over the underlying `byte[]`, no
intermediate `ByteArray` alloc on either backend. Encode goes through
`writeString` which delegates to the buffer's native UTF-8 encoder
(JVM: `String.getBytes(UTF_8)` once into the destination region; Apple:
`NSString.dataUsingEncoding`). Back-patch absolute writes (`buffer[i] =
…`) are zero-allocation index-set operations. `wireSize` returns the
shared `WireSize.BackPatch` data object — zero allocation. ✓

**Audit — batching.** Encode could in principle pre-compute the UTF-8
byte length to avoid back-patching, then size the buffer exactly. Two
options considered:

1. *Pre-measure with `text.utf8ByteCount()` (or equivalent), then
   `WireSize.Exact(2 + n + 2)`.* Requires a full UTF-8 codepoint walk at
   sizing time and a second walk at encode time — pure waste. Some
   buffer backends *could* expose a fused "encode into buffer, return
   bytes written" call that lets encode return without a back-patch,
   but the bridge already owns the growable buffer in `BackPatch` mode,
   so the cost is one extra acquired chunk plus two `set` calls. **Ruled
   out — measurably slower for the common short-topic case.**
2. *Use a varint length prefix when we know strings are short.* Out of
   scope: the spec is fixed at 2-byte BE for MQTT v5 strings; varints
   appear only in MQTT's "Variable Byte Integer" type, which is a
   different annotation surface (`@VariableByteInteger` or similar,
   not yet introduced).

Back-patch is the right shape here, and the bridge's `BackPatch` →
gather-write path handles it without `ByteArray`. **Less-clever
alternative ruled out on audit grounds.** ✓

**Audit — reviewability.**

- `topicLen`, `topic`, `packetId` map directly to MQTT v5 §3.3.2.1 / §3.3.2.2 vocabulary. A reader holding the spec validates this in one pass.
- Two `// Wire:` comments — both flagging non-obvious operations: the back-patch placeholder (one-line setup), and the prefix patch (the absolute-set sequence is opaque without naming what byte pattern it produces).
- `peekFrameSize`'s `peekShort(baseOffset).toUShort().toInt()` could carry a `// Wire:` comment, but the body is short and the call name is self-explanatory: peek a 2-byte BE length prefix, treat as unsigned, expand to Int for size arithmetic. Decision: **no comment** — the call sequence reads as English.
- `require(written <= UShort.MAX_VALUE.toInt())` is the only `EncodeException`-precondition; phrased as `require` so it surfaces the actual byte count in the message for debugging. Not promoted to a custom `EncodeException` here because `wireSize` couldn't have lied — the violation is a model-side bug (oversized topic), not a wire-protocol bug. ✓
- Diagram absent per Section 10.3 trigger — the field list is shorter than any diagram would be, and the back-patch mechanic is captured by the encode method's KDoc.

**Audit — streaming.**

- `peekFrameSize` advances through `NeedsMoreData → Complete`:
  - 0 or 1 bytes buffered → `NeedsMoreData` (prefix unavailable).
  - 2 bytes buffered, prefix says `len = N`, only some of `N` body bytes present → `NeedsMoreData` (body incomplete).
  - 2 + N + 2 bytes buffered → `Complete(2 + N + 2)`.
  - Verified mentally: `stream.peekShort(baseOffset)` reads the prefix without consuming bytes; the `total` arithmetic is independent of `baseOffset`. ✓
- Encode under `BackPatch`: bridge acquires a pool buffer, writes placeholder + topic + suffix into a growable wrapper; on grow events the wrapper requests another pool chunk and chains them; `transport.writeGathered(chunks)` flushes. Patch sites land in chunk[0] (the prefix is always the first 2 bytes of the first chunk because no encode action precedes the placeholder). **Confirms that the back-patch position is always within the same chunk as the placeholder write — no cross-chunk back-patch.** ✓
- Terminal-field slice-bounding not applicable.
- The `peekShort(baseOffset)` call assumes the `StreamProcessor` is BIG_ENDIAN — which is the bridge default per Section 9.1 (`streamByteOrder: ByteOrder = ByteOrder.BIG_ENDIAN`). For LE-default streams (e.g., RIFF) the codec must compose a manual 2-byte assembly via `peekByte(off) | peekByte(off+1) shl 8`. **Lock that policy: peek calls in generated `peekFrameSize` use the byte order of the field's `@WireOrder` (or message default), and emit manual byte assembly when that disagrees with the stream's natural order.** This is the same logic as the encode/decode path — symmetric, no special case.

**Findings.**

1. **Defers PHASE_9_RESET decision "`LengthPrefix` enum shape".**
   Resolved here: keep the **enum shape** (`Byte` / `Short` / `Int`)
   already present in `Annotations.kt`. Reasoning:
   - Three discrete sizes cover MQTT v3/v4/v5, WebSocket extended-length,
     RIFF, PNG IHDR, every TLV protocol surveyed.
   - `Varint` was on the leaning list but is a different *type*: it doesn't
     describe a fixed prefix size, it describes a self-delimited number.
     Force varint into a separate annotation (e.g., `@VariableByteInteger`)
     when it's needed; keep `LengthPrefix` enum shape clean.
   - `Fixed(Int)` was the alternative; rejected because (a) `Int` would
     allow nonsense values like `Fixed(7)`, (b) the three concrete sizes
     are the only ones any extant protocol uses, (c) `Fixed(Int)` blocks
     the compiler from validating the prefix range against the field's
     UTF-8 byte count (e.g., `Fixed(1)` + a String > 255 bytes is a
     compile error today; `Fixed(Int)` makes that arithmetic deferred to
     runtime). **Lock: `LengthPrefix` stays the existing enum, no
     `Fixed(Int)` overload.**
2. **Lock the back-patch placement rule.** Generated encode for a
   `@LengthPrefixed` field always writes (a) a placeholder of the
   prefix's exact byte width at the field's start, (b) the body, (c) the
   true count back to the placeholder via absolute `set`. Within a
   single growable buffer (the bridge's BackPatch path) the placeholder
   and the body's first byte land in the same chunk because the
   placeholder write is the first action of the field. Cross-chunk
   back-patch is impossible by construction — proven above.
3. **Lock the peek byte-order rule.** Generated `peekFrameSize` peeks
   length prefixes using the prefix's wire byte order, regardless of the
   stream's configured order. Emit manual byte assembly if they disagree.
   Symmetric with encode/decode.
4. **Defers the "`String` vs `CharSequence` field type" question.** Not
   raised before but worth a one-liner: the model field is declared
   `String`, the `readString` API returns `CharSequence`, generated
   decode does `.toString()`. Could relax to `CharSequence` in the
   model field type to skip that conversion, but it costs the consumer
   more — every comparison and `equals` on a `CharSequence` field is
   non-canonical. **Lock: model fields are `String`, decode does the
   `.toString()` — readability and consumer ergonomics over a single
   non-allocating call.**

**Slice status:** validated. Three locks recorded (LengthPrefix enum
stays; back-patch placement rule; peek byte-order rule; plus a
String-vs-CharSequence policy note). Move on to slice 4.

### Slice 4 — `@LengthFrom` on RIFF chunk body

**Source vector.** Continuing slice 1's RIFF chunk header. The body
size is *not* prefixed at the body's own start — it lives in the
preceding `chunkSize` field of the header. This is the textbook
`@LengthFrom("siblingField")` case: a non-string variable-length
field whose byte count is decided by an earlier sibling.

```kotlin
/**
 * One full RIFF chunk: 4-byte FourCC tag, 4-byte LE size, body of
 * exactly [chunkSize] bytes, then optionally one pad byte to keep the
 * next chunk 2-byte aligned (the pad is framed by the *enclosing*
 * RIFF list, not by the chunk itself, so it is not modeled here).
 */
@ProtocolMessage(wireOrder = Endianness.Little)
data class RiffChunk(
    val fourCC: UInt,
    val chunkSize: UInt,
    @LengthFrom("chunkSize")
    @UseCodec(RawChunkBodyCodec::class)
    val body: ChunkBody,
)

/**
 * Body of a RIFF chunk — opaque bytes for slice 4. Real codecs
 * dispatch on `fourCC` to a specific body type via @UseCodec; here
 * the body is an opaque holder so the slice tests @LengthFrom
 * routing in isolation.
 */
@JvmInline value class ChunkBody(val raw: ReadBuffer)

/**
 * Hand-written codec referenced by @UseCodec on the body field.
 * Reads `remaining()` bytes (the slice has been bounded by the
 * processor before this codec is called) and returns a buffer slice.
 */
object RawChunkBodyCodec : Codec<ChunkBody> {
    override fun decode(buffer: ReadBuffer, context: DecodeContext): ChunkBody =
        ChunkBody(buffer.readBytes(buffer.remaining()))    // zero-copy slice
    override fun encode(buffer: WriteBuffer, value: ChunkBody, context: EncodeContext) {
        buffer.write(value.raw)
    }
    override fun wireSize(value: ChunkBody, context: EncodeContext): WireSize =
        WireSize.Exact(value.raw.remaining())
    // peekFrameSize stays NoFraming — this codec is only ever invoked
    // by the parent's slice-bound decode, never as a stream root.
}
```

Three fields, two non-trivial (`@LengthFrom`, `@UseCodec`) → diagram
**is** emitted.

**Sketched generated output (RiffChunkCodec).**

```kotlin
/**
 * Generated codec for [RiffChunk] — wire-format details below.
 *
 * @see RiffChunk
 * @see RiffChunkHeaderCodec
 * @see RawChunkBodyCodec
 *
 * ```text
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +---------------+---------------+---------------+---------------+
 * |     'R'       |     'I'       |     'F'       |     'F'       |  fourCC (4b ASCII)
 * +---------------+---------------+---------------+---------------+
 * |                          chunkSize (4b LE)                    |
 * +---------------------------------------------------------------+
 * | <chunkSize> bytes — body (from chunkSize)                     |
 * +---------------------------------------------------------------+
 * ```
 *
 * Source documentation:
 *   One full RIFF chunk: 4-byte FourCC tag, 4-byte LE size, body of
 *   exactly [chunkSize] bytes, then optionally one pad byte to keep the
 *   next chunk 2-byte aligned (the pad is framed by the *enclosing*
 *   RIFF list, not by the chunk itself, so it is not modeled here).
 */
object RiffChunkCodec : Codec<RiffChunk> {

    /**
     * Reads 8 header bytes, then exactly [RiffChunk.chunkSize] body bytes
     * via [RawChunkBodyCodec] over a setLimit-bounded slice.
     */
    override fun decode(buffer: ReadBuffer, context: DecodeContext): RiffChunk {
        val fourCC    = buffer.readUInt()
        val chunkSize = java.lang.Integer.reverseBytes(buffer.readInt()).toUInt()
        // Wire: bound the buffer to chunkSize bytes so RawChunkBodyCodec sees
        // remaining()==chunkSize, then restore the outer limit.
        val outerLimit = buffer.limit()
        buffer.setLimit(buffer.position() + chunkSize.toInt())
        val body = RawChunkBodyCodec.decode(buffer, context)
        buffer.setLimit(outerLimit)
        return RiffChunk(fourCC, chunkSize, body)
    }

    /** Writes 8 header bytes, then the body bytes — wire size = 8 + body.raw.remaining(). */
    override fun encode(buffer: WriteBuffer, value: RiffChunk, context: EncodeContext) {
        buffer.writeUInt(value.fourCC)
        buffer.writeInt(java.lang.Integer.reverseBytes(value.chunkSize.toInt()))
        RawChunkBodyCodec.encode(buffer, value.body, context)
    }

    /**
     * `Exact(8 + body.raw.remaining())` — body codec reports `Exact`,
     * fields above it are fixed, so the sum is exact.
     */
    override fun wireSize(value: RiffChunk, context: EncodeContext): WireSize {
        val bodySize = (RawChunkBodyCodec.wireSize(value.body, context) as WireSize.Exact).bytes
        return WireSize.Exact(8 + bodySize)
    }

    /**
     * Frame size = 8 + chunkSize. Peek the LE chunkSize at offset 4 and add 8.
     */
    override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
        if (stream.available() - baseOffset < 8) return PeekResult.NeedsMoreData
        // Wire: 4 LE bytes at offset+4 — manual assembly because stream is BE
        val b0 = stream.peekByte(baseOffset + 4).toInt() and 0xFF
        val b1 = stream.peekByte(baseOffset + 5).toInt() and 0xFF
        val b2 = stream.peekByte(baseOffset + 6).toInt() and 0xFF
        val b3 = stream.peekByte(baseOffset + 7).toInt() and 0xFF
        val chunkSize = b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
        require(chunkSize >= 0) { "RIFF chunkSize overflow: $chunkSize" }
        val total = 8 + chunkSize
        return if (stream.available() - baseOffset >= total) PeekResult.Complete(total)
        else PeekResult.NeedsMoreData
    }
}
```

**Audit — zero-copy.** Decode reads two scalars; the body slice is
`buffer.readBytes(remaining)` which on every backend returns a
*sliced view* of the underlying bytes (zero copy — `JvmBuffer` returns
a duplicated `ByteBuffer` over the same memory; `ByteArrayBuffer`
returns a slice over the same `ByteArray`; native buffers return a
pointer offset). The `setLimit` / restore dance is two integer field
writes. `RawChunkBodyCodec.decode` allocates only the inline value
class wrapper around the slice. Encode writes two scalars then bulk-
copies the body via `buffer.write(value.raw)`, which on aligned-
backend pairs (Direct→Direct) hits the platform's `put(ByteBuffer)`
fast path. `wireSize` returns a value-class `Exact`. `peekFrameSize`
allocates nothing — four `peekByte` calls + arithmetic. ✓

**Audit — batching.** I considered three alternatives and ruled each out:

1. *Inline `body = ChunkBody(buffer.readBytes(chunkSize.toInt()))` and
   skip the `setLimit` dance.* Would work for *this* codec because
   `RawChunkBodyCodec` happens to use `remaining()`, but it bakes the
   length-source resolution into the parent rather than isolating it
   in the body codec. The general `@LengthFrom` contract is "the body
   codec sees a buffer bounded such that `remaining()` == the resolved
   length" — that contract must be uniform across all `@UseCodec`
   choices, including ones that read internal sub-structure where
   `setLimit` matters. **Ruled out — breaks the general contract.**
2. *Use `slice(N).use { }` instead of `setLimit`.* That is the *async*
   bounding strategy from locked decision #5. Sync decode uses
   `setLimit` because it's zero-allocation; `slice().use { }` allocates
   the slice wrapper. Symmetry with locked decision #5 is preserved
   here — sync = `setLimit`, async = `slice`. ✓
3. *Skip the `outerLimit` restore.* Forces the parent codec to know
   nothing came after the body in this enclosing scope, which is
   *true for a standalone RIFF chunk* but *false for a RIFF chunk
   embedded inside a RIFF LIST*. The restore costs one int write and
   makes the codec composable. **Ruled out — restore is mandatory for
   composability.**

**Less-clever alternative ruled out on audit grounds.** ✓

**Audit — reviewability.**

- `chunkSize` and `body` are the literal RIFF spec terms. The `@LengthFrom("chunkSize")` annotation reads as English: "body length is taken from the chunkSize field." A reader holding the spec validates this in one pass.
- Two `// Wire:` comments — both flagging non-obvious operations: the `setLimit` dance (the bound + restore pattern is the optimization vs. allocating a slice), and the manual 4-byte LE assembly in `peekFrameSize` (the shift pattern is the optimization vs. requiring a `peekIntLittleEndian` API that doesn't exist).
- `wireSize`'s `as WireSize.Exact` cast looks scary — it would throw `ClassCastException` if `RawChunkBodyCodec` ever returned `BackPatch`. But: this exact codec is `@UseCodec`-bound at compile time, and the processor *should* validate at compile time that any `@UseCodec`-referenced codec for a `@LengthFrom`-bounded field reports a determinable size. **Lock that as a compile-time check.**
- The cross-reference `@see RawChunkBodyCodec` is essential — without it the reader can't tell what `body` decoding does without grepping. ✓

**Audit — streaming.**

- `peekFrameSize` walks `NeedsMoreData → Complete`:
  - 0–7 bytes → NeedsMoreData.
  - 8 bytes, header readable, `chunkSize = N`, body partially present → NeedsMoreData.
  - 8 + N bytes → `Complete(8 + N)`.
  - Verified mentally; baseOffset propagates correctly. ✓
- The `require(chunkSize >= 0)` guards against a 32-bit chunk size with the high bit set being interpreted as a negative `Int`. RIFF allows `UInt` chunk sizes (up to 4 GiB), but the bridge's `Complete(bytes: Int)` is `Int`-typed, and a > 2 GiB chunk is unframable through this peek path anyway. **Lock the policy: when `@LengthFrom` resolves to a value > `Int.MAX_VALUE`, throw a typed `DecodeException` from peek**, not a silent overflow. The hand-sketched code uses `require` for clarity — the emitter will wrap as `throw DecodeException(...)`. Good catch.
- Encode under `Exact` (because the body codec reports `Exact` and the parent sums). Bridge acquires a single pool buffer of `≥ 8 + body bytes`, encode runs straight through, transport.write fires. No `BackPatch`, no `writeGathered`. ✓
- Terminal-field slice-bounding: body is the terminal field, but the bound came from a sibling field, not from "remaining outer bytes." That's `@LengthFrom`, not `@RemainingBytes` — distinct cases, slice 5 covers the latter. The `setLimit + restore` pattern is identical though, so slice 5 will reuse this proof.

**Findings.**

1. **Lock the `@LengthFrom("…")` resolution form.** `String` literal
   referencing a sibling property name. Compile-time validated:
   referenced field must exist, must precede this field, must be a
   numeric type. This matches the existing annotation
   (`Annotations.kt:141`). Ruled out alternatives:
   - *Property reference* (`@LengthFrom(RiffChunk::chunkSize)`) — needs
     `KProperty` reflection at compile time, processor would have to
     resolve the reference through KSP's symbol resolution; same end
     state as a string + name lookup but with extra ceremony at the
     call site.
   - *Compile-time-resolved name* (some wrapper around the string) —
     same as the string but with ceremony.
   String wins on minimal call-site noise, KSP validation is no harder
   than for a property reference, and string literals show up in
   error-message field-paths naturally.
2. **Lock the `setLimit + restore` bounding contract.** Sync decode of
   any `@LengthFrom`-bounded field saves the outer limit, sets the
   limit to `position + len`, calls the body codec, restores the outer
   limit. Body codec sees `remaining() == len`. This is the uniform
   contract for *every* `@UseCodec`-referenced sync codec inside a
   length-bounded field — they all read by `remaining()` or call their
   own decode logic that respects the buffer limit.
3. **Lock the "wireSize must be `Exact` if length-source resolves to
   `Exact`" compile-time check.** A `@UseCodec`-referenced codec
   inside a `@LengthFrom`-bounded field must report `WireSize.Exact`
   so the parent's `wireSize` sum is also `Exact` and the bridge takes
   the fast `pool.withBuffer` path. Processor errors at compile time
   if the referenced codec is known to return `BackPatch`. This is a
   conservative check — the processor only knows the return type
   declared on the codec object, not its runtime behavior, so the
   check is "does the codec override `wireSize` to return `Exact`
   unconditionally" — fail if not. (Generated codecs declare this
   explicitly so the check is tractable.)
4. **Lock the peek overflow rule.** When a `@LengthFrom` reference
   resolves to a value that would make the total frame size exceed
   `Int.MAX_VALUE`, the generated `peekFrameSize` throws
   `DecodeException` (not `IllegalArgumentException`, not silent
   wrap-around). Surfaces malformed inputs at frame detection rather
   than at decode time, which is friendlier to the streaming loop.
5. **Defers nothing further.** All `@LengthFrom`-relevant pending
   decisions resolved.

**Slice status:** validated. Four locks recorded. Move on to slice 5.

### Slice 5 — `@RemainingBytes` on a WebSocket close-frame body

**Source vector.** WebSocket close-frame body, RFC 6455 §5.5.1: a
2-byte big-endian status code followed by an *optional* UTF-8 reason
string that occupies whatever remains of the frame (the WebSocket
frame header already pre-bounds the buffer). The reason has no length
prefix because the outer frame's payload-length field provides it; the
reason is whatever's left after the 2-byte status code is consumed.

This is the textbook `@RemainingBytes` case: a terminal field that
consumes "everything else" relative to a pre-bounded buffer.

```kotlin
/**
 * WebSocket close-frame body — RFC 6455 §5.5.1. The 2-byte BE status
 * code is followed by an optional UTF-8 reason string that occupies
 * the remainder of the frame's payload region. The frame's payload
 * length is supplied by the WebSocket frame header (handled by the
 * outer codec), so the reason field needs no prefix of its own.
 */
@ProtocolMessage(wireOrder = Endianness.Big)
data class CloseFrameBody(
    val statusCode: UShort,
    @RemainingBytes
    val reason: String,
)
```

Two fields, one non-trivial (`@RemainingBytes`). Diagram trigger fails
on field count (≤ 2). **No diagram emitted** — the method-level KDoc
on `decode` carries the wire shape adequately.

**Sketched generated output.**

```kotlin
/**
 * Generated codec for [CloseFrameBody] — wire-format details below.
 *
 * @see CloseFrameBody
 *
 * Source documentation:
 *   WebSocket close-frame body — RFC 6455 §5.5.1. The 2-byte BE status
 *   code is followed by an optional UTF-8 reason string that occupies
 *   the remainder of the frame's payload region. The frame's payload
 *   length is supplied by the WebSocket frame header (handled by the
 *   outer codec), so the reason field needs no prefix of its own.
 */
object CloseFrameBodyCodec : Codec<CloseFrameBody> {

    /**
     * Reads 2 BE bytes for [CloseFrameBody.statusCode], then everything
     * else in the buffer as UTF-8 for [CloseFrameBody.reason].
     *
     * @throws DecodeException if the buffer is shorter than 2 bytes.
     */
    override fun decode(buffer: ReadBuffer, context: DecodeContext): CloseFrameBody {
        val statusCode = buffer.readUShort()
        val reasonBytes = buffer.remaining()
        // Wire: terminal field — consume whatever is left in the bounded buffer
        val reason = if (reasonBytes == 0) "" else buffer.readString(reasonBytes, Charset.UTF8).toString()
        return CloseFrameBody(statusCode, reason)
    }

    /** Writes 2 BE bytes for statusCode, then UTF-8 bytes for reason. */
    override fun encode(buffer: WriteBuffer, value: CloseFrameBody, context: EncodeContext) {
        buffer.writeUShort(value.statusCode)
        if (value.reason.isNotEmpty()) {
            buffer.writeString(value.reason, Charset.UTF8)
        }
    }

    /**
     * `BackPatch` because the reason's UTF-8 byte count isn't known
     * without encoding (or pre-measuring, which is itself a UTF-8
     * walk we'd just re-do at write time).
     */
    override fun wireSize(value: CloseFrameBody, context: EncodeContext): WireSize =
        WireSize.BackPatch

    /**
     * Frame size is determined by the *outer* framer (WebSocket frame
     * header). This codec is invoked over a pre-bounded buffer, never
     * as a stream root, so framing is not its concern.
     */
    override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult =
        PeekResult.NoFraming
}
```

**Audit — zero-copy.** Decode pulls one scalar; the reason string is
read via `buffer.readString(remaining, UTF_8)` which on every backend
hits the native UTF-8-decode path with no intermediate `ByteArray`
(same path validated in slice 3). The empty-reason branch avoids a
zero-length `String` allocation by using the constant `""`. Encode
writes one scalar then the UTF-8 bytes; the `isNotEmpty` check skips
the encoder call entirely for empty reasons (encoders for some
backends still allocate a 0-byte intermediate when called on an empty
string — the guard avoids that). `wireSize` returns the shared
`WireSize.BackPatch` data object. `peekFrameSize` is a constant return.
✓

**Audit — batching.** Two alternatives considered and ruled out:

1. *Fold the empty-reason guard into `writeString` by trusting it to
   noop on `""`.* `writeString` semantics are charset-implementation-
   defined; on Apple the call goes through `NSString.dataUsingEncoding`
   which allocates an empty `NSData`. The guard is one comparison and
   it removes a per-platform allocation question. **Keep the guard.**
2. *Use `buffer.write(reasonAsBuffer)` if the reason is already a
   `ByteBuffer`.* It isn't — the model field is `String`. Forcing
   pre-encoded reasons would push the encoding decision out to every
   call site, violating the model-field-types-are-Kotlin-natural
   policy. **Ruled out.**

**Less-clever alternative ruled out on audit grounds.** ✓

**Audit — reviewability.**

- `statusCode` and `reason` are the literal RFC 6455 §5.5.1 terms.
- One `// Wire:` comment, on the `remaining()` read — the reader needs to know that the bounded-buffer contract is what makes this terminal-consume-rest legal. Without the comment, a reader unfamiliar with the framework might assume `remaining()` reflects the underlying transport, not the codec's pre-bounded slice.
- The `if (reasonBytes == 0) ""` path is short enough that a comment would be noise — the call sequence is self-documenting.
- The `peekFrameSize` returning `NoFraming` is intentional: this codec is *never* the framing root for a stream. A reviewer skimming for framing bugs sees `NoFraming` and immediately knows "this codec is composed inside another framer." The KDoc on `peekFrameSize` says exactly that. ✓
- Bridge usage check: locked decision #9 says `NoFraming` returned by a bridged codec at the framing boundary throws at startup. So a consumer that *accidentally* tried `CloseFrameBodyCodec.asConnection(transport, …)` would crash immediately with the message from 9.3 — exactly the desired behavior. ✓

**Audit — streaming.**

- `peekFrameSize` returns `NoFraming`; the bridge promotes that to a startup throw. Verified against locked decision #9.3. ✓
- Encode under `BackPatch`: bridge uses growable + `writeGathered`. Two writes (scalar + string), no back-patch *position* needed (the size is *determined by* the encoded bytes, but no preceding length-prefix slot exists to patch). The bridge's `BackPatch` path is exercised purely for the growable buffer's chunk linking, not for any in-place patch. **Confirms `BackPatch` ≠ "always involves a patch site"; sometimes it just means "size unknown up front."** Lock that semantic clarification.
- Terminal-field slice-bounding **is** the central concern of this slice. Decode relies on `buffer.remaining()` returning exactly the body's byte count. That holds when the codec is invoked through:
  - the bridge's `readBufferScoped(n) { decode(this, ctx) }` path (the slice is `n` bytes, no more, no less),
  - a parent codec's `setLimit + restore` dance (e.g., `RiffChunkCodec` from slice 4 — substituted there with a hypothetical `CloseFrameBody`-typed body, the same pattern would bound the buffer for this codec),
  - an async slice via `parent.slice(N).use { … }` (locked decision #5 — same `remaining()` contract).
  All three bounding paths preserve the contract. ✓
- The `setLimit + restore` interaction with `@RemainingBytes`: when a `@RemainingBytes` field is *not* terminal in some weird future model, the processor must reject at compile time. **Lock the compile-time check: `@RemainingBytes` is only valid on the terminal non-conditional field.** Already partially documented in `Annotations.kt:120` ("Must be the last non-conditional field in the constructor") — promoting it from doc-comment to enforced KSP check.

**Findings.**

1. **Lock the `BackPatch` semantic clarification.**
   `WireSize.BackPatch` means "exact byte count not known up front,
   so the bridge uses a growable buffer." It does **not** imply "an
   in-place patch happens at encode time." Some codecs (this one,
   `LogEntry` with a single `@RemainingBytes` field, etc.) report
   `BackPatch` purely because their size is data-dependent; they have
   no preceding length slot to patch back to. Document this in the
   `WireSize.BackPatch` KDoc when the runtime types get next touched.
2. **Lock the `@RemainingBytes` placement compile-time check.**
   Processor enforces that `@RemainingBytes` annotates exactly one
   field per message, that field is the last non-conditional field in
   the primary constructor's parameter list, and (if any conditional
   `@WhenTrue` fields follow it) the conditional fields' presence is
   gated such that "absent" maintains the terminal-consume-rest
   semantics. The simplest enforcement: trailing-conditional fields
   are forbidden after a `@RemainingBytes` field. Conditional fields
   come *first* in the variable-length tail, terminal `@RemainingBytes`
   comes last. (Re-examine if a real protocol violates this — none
   surveyed so far does.)
3. **Lock the empty-string write guard.** Generated encode for any
   `String`-typed field issues `if (value.field.isNotEmpty())
   buffer.writeString(value.field, …)`. Skips a per-platform
   zero-length-allocation question. Cost: one comparison per emit.
   Worth it.
4. **Lock the empty-string decode shortcut.** Generated decode for
   any `String`-typed field that *might* be zero-length (i.e., any
   `@RemainingBytes` field) uses `if (n == 0) ""` to short-circuit
   the `readString` call. `""` is a JVM-interned constant on all
   Kotlin backends; no allocation.
5. **Lock the `peekFrameSize = NoFraming` policy for "embedded only"
   codecs.** Codecs that are only ever invoked over a pre-bounded
   buffer (e.g., body codecs referenced by `@UseCodec` in a
   `@LengthFrom` / `@RemainingBytes` field, terminal sub-message
   codecs) explicitly return `NoFraming`. The bridge's startup throw
   from 9.3 protects against accidental misuse as a stream root. This
   policy applies uniformly; the processor emits `NoFraming` whenever
   the codec lacks a length-discoverable structure.

**Slice status:** validated. Five locks recorded. Move on to slice 6.

### Slice 6 — `@WhenTrue` on MQTT v3.1.1 CONNECT optional fields

**Source vector.** MQTT v3.1.1 §3.1 CONNECT is the canonical
conditional-fields protocol: a single `connectFlags` byte in the
variable header gates the presence of will-topic, will-message,
username, and password fields in the payload section. The flag bits:

```
Bit:      7         6        5        4-3        2          1            0
Field:  user     password   will     willQoS   will      cleanStart   reserved
        Name      flag      retain              flag       flag         (0)
        flag                                                          
```

The dotted-path `@WhenTrue("flags.willFlag")` form from
`Annotations.kt:215` is exactly built for this — `connectFlags` is a
value class (`@JvmInline`) carrying a `UByte` whose bit-extracted
properties drive the gates. We use a stripped-down CONNECT (no
`protocolName` / `protocolLevel` / `keepAlive` / `clientId` — those are
covered by combinations of slices 1, 2, 3) so the slice exercises
`@WhenTrue` in isolation.

```kotlin
/**
 * MQTT v3.1.1 §3.1.2.3 Connect Flags. Bit-packed UByte; computed
 * properties extract individual flag bits for use in @WhenTrue gates.
 */
@JvmInline
@ProtocolMessage
value class ConnectFlags(val raw: UByte) {
    val cleanStart:   Boolean get() = (raw.toInt() and 0b0000_0010) != 0
    val willFlag:     Boolean get() = (raw.toInt() and 0b0000_0100) != 0
    val willRetain:   Boolean get() = (raw.toInt() and 0b0010_0000) != 0
    val passwordFlag: Boolean get() = (raw.toInt() and 0b0100_0000) != 0
    val userNameFlag: Boolean get() = (raw.toInt() and 0b1000_0000) != 0
    val willQos:      Int     get() = (raw.toInt() ushr 3) and 0b11
}

/**
 * Stripped-down MQTT v3.1.1 CONNECT payload. Demonstrates @WhenTrue
 * dotted-path resolution against the bit-extracted properties of
 * [ConnectFlags]. Real CONNECT also carries protocolName, level,
 * keepAlive, and clientId — omitted here so the slice tests
 * conditional gating in isolation.
 */
@ProtocolMessage(wireOrder = Endianness.Big)
data class ConnectPayload(
    val flags: ConnectFlags,
    @LengthPrefixed @WhenTrue("flags.willFlag")     val willTopic:   String? = null,
    @LengthPrefixed @WhenTrue("flags.willFlag")     val willMessage: String? = null,
    @LengthPrefixed @WhenTrue("flags.userNameFlag") val userName:    String? = null,
    @LengthPrefixed @WhenTrue("flags.passwordFlag") val password:    String? = null,
)
```

Five fields, four non-trivial (conditional length-prefixed). Diagram
trigger fires.

**Sketched generated output.**

```kotlin
/**
 * Generated codec for [ConnectPayload] — wire-format details below.
 *
 * @see ConnectPayload
 * @see ConnectFlags
 *
 * ```text
 *  0
 *  0 1 2 3 4 5 6 7
 * +-+-+-+-+-+-+-+-+
 * |U|P|R| Q |W|C|0|  flags (1b) — userNameFlag/passwordFlag/willRetain/willQos/willFlag/cleanStart
 * +-+-+-+-+-+-+-+-+
 * | <prefix=Short BE> + <len> bytes — willTopic   [when flags.willFlag]     |
 * +-------------------------------------------------------------------------+
 * | <prefix=Short BE> + <len> bytes — willMessage [when flags.willFlag]     |
 * +-------------------------------------------------------------------------+
 * | <prefix=Short BE> + <len> bytes — userName    [when flags.userNameFlag] |
 * +-------------------------------------------------------------------------+
 * | <prefix=Short BE> + <len> bytes — password    [when flags.passwordFlag] |
 * +-------------------------------------------------------------------------+
 * ```
 *
 * Source documentation:
 *   Stripped-down MQTT v3.1.1 CONNECT payload. Demonstrates @WhenTrue
 *   dotted-path resolution against the bit-extracted properties of
 *   [ConnectFlags]. Real CONNECT also carries protocolName, level,
 *   keepAlive, and clientId — omitted here so the slice tests
 *   conditional gating in isolation.
 */
object ConnectPayloadCodec : Codec<ConnectPayload> {

    /**
     * Reads the flags byte, then conditionally reads each
     * length-prefixed UTF-8 string when its flag bit is set.
     */
    override fun decode(buffer: ReadBuffer, context: DecodeContext): ConnectPayload {
        val flags = ConnectFlagsCodec.decode(buffer, context)
        // Wire: each conditional field is fully absent when its gate is false
        val willTopic = if (flags.willFlag) {
            val n = buffer.readUShort().toInt()
            buffer.readString(n, Charset.UTF8).toString()
        } else null
        val willMessage = if (flags.willFlag) {
            val n = buffer.readUShort().toInt()
            buffer.readString(n, Charset.UTF8).toString()
        } else null
        val userName = if (flags.userNameFlag) {
            val n = buffer.readUShort().toInt()
            buffer.readString(n, Charset.UTF8).toString()
        } else null
        val password = if (flags.passwordFlag) {
            val n = buffer.readUShort().toInt()
            buffer.readString(n, Charset.UTF8).toString()
        } else null
        return ConnectPayload(flags, willTopic, willMessage, userName, password)
    }

    /**
     * Writes the flags byte, then conditionally writes each present
     * field. Compile-time consistency check has already verified that
     * the flag/field pairings can never be inconsistent (a present
     * field with its flag unset is a model bug; see encode-time guard).
     */
    override fun encode(buffer: WriteBuffer, value: ConnectPayload, context: EncodeContext) {
        ConnectFlagsCodec.encode(buffer, value.flags, context)
        // Wire: encode-time guards — flag must agree with field nullity
        if (value.flags.willFlag) {
            require(value.willTopic   != null) { "flags.willFlag set but willTopic is null" }
            require(value.willMessage != null) { "flags.willFlag set but willMessage is null" }
            writeLengthPrefixedShort(buffer, value.willTopic)
            writeLengthPrefixedShort(buffer, value.willMessage)
        } else {
            require(value.willTopic   == null) { "willTopic present but flags.willFlag is false" }
            require(value.willMessage == null) { "willMessage present but flags.willFlag is false" }
        }
        if (value.flags.userNameFlag) {
            require(value.userName != null) { "flags.userNameFlag set but userName is null" }
            writeLengthPrefixedShort(buffer, value.userName)
        } else {
            require(value.userName == null) { "userName present but flags.userNameFlag is false" }
        }
        if (value.flags.passwordFlag) {
            require(value.password != null) { "flags.passwordFlag set but password is null" }
            writeLengthPrefixedShort(buffer, value.password)
        } else {
            require(value.password == null) { "password present but flags.passwordFlag is false" }
        }
    }

    /**
     * `BackPatch` because conditional UTF-8 fields' byte counts are
     * data-dependent. The bridge handles the growable + writeGathered
     * path.
     */
    override fun wireSize(value: ConnectPayload, context: EncodeContext): WireSize =
        WireSize.BackPatch

    /**
     * Frame size = 1 (flags) + sum of (2 + len_i) for each present
     * conditional field. Peeks each prefix in turn, walking offsets
     * forward; gates are evaluated against the peeked flags byte.
     */
    override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
        if (stream.available() - baseOffset < 1) return PeekResult.NeedsMoreData
        val rawFlags = stream.peekByte(baseOffset).toInt() and 0xFF
        val willFlag     = (rawFlags and 0b0000_0100) != 0
        val userNameFlag = (rawFlags and 0b1000_0000) != 0
        val passwordFlag = (rawFlags and 0b0100_0000) != 0
        var off = baseOffset + 1
        // Wire: each present field contributes 2 (BE prefix) + prefix value bytes
        if (willFlag) {
            val r1 = peekLengthPrefixed(stream, off) ?: return PeekResult.NeedsMoreData
            off = r1
            val r2 = peekLengthPrefixed(stream, off) ?: return PeekResult.NeedsMoreData
            off = r2
        }
        if (userNameFlag) {
            val r = peekLengthPrefixed(stream, off) ?: return PeekResult.NeedsMoreData
            off = r
        }
        if (passwordFlag) {
            val r = peekLengthPrefixed(stream, off) ?: return PeekResult.NeedsMoreData
            off = r
        }
        return PeekResult.Complete(off - baseOffset)
    }

    // Helper emitted once per codec that uses LengthPrefixed Short fields:
    private fun writeLengthPrefixedShort(buffer: WriteBuffer, value: String) {
        val lenPos = buffer.position()
        buffer.writeUShort(0u)
        val before = buffer.position()
        buffer.writeString(value, Charset.UTF8)
        val written = buffer.position() - before
        require(written <= UShort.MAX_VALUE.toInt()) {
            "string exceeds 65535 UTF-8 bytes (got $written)"
        }
        buffer[lenPos]     = ((written ushr 8) and 0xFF).toByte()
        buffer[lenPos + 1] = (written and 0xFF).toByte()
    }

    // Helper emitted once per codec that uses peekFrameSize on Short prefixes:
    private fun peekLengthPrefixed(stream: StreamProcessor, off: Int): Int? {
        if (stream.available() - off < 2) return null
        val len = stream.peekShort(off).toUShort().toInt()
        val end = off + 2 + len
        return if (stream.available() - off >= 2 + len) end else null
    }
}
```

**Audit — zero-copy.** Same path as slice 3 for each present
length-prefixed string; absent fields cost nothing (no `readString`,
no allocation). The `flags` value class is `@JvmInline` so it boxes
only when needed at construction; field reads (`flags.willFlag` etc.)
are inline `UByte` bit ops with no boxing on every Kotlin backend.
`peekFrameSize` allocates nothing — just peek bytes and arithmetic.
The two private helpers are small enough that the JVM inlines them at
the call site; on Native they're regular function calls, but their
bodies are arithmetic + buffer ops, not allocation. ✓

**Audit — batching.** Three alternatives considered and ruled out:

1. *Generate one giant `if/else` ladder for every flag combination.*
   16 ladders for 4 bits, 32 for 5 — combinatorial blowup. Sequential
   `if (flag) { … }` blocks are linear in the number of conditional
   fields, and the JVM's branch predictor handles the predictable
   pattern well. **Ruled out — combinatorial.**
2. *Use a stateful "presence map" computed once at decode start.*
   Adds a stack-allocated `Int` and four bit ops up front, saves
   nothing because each field's gate is still evaluated once. The
   straight-line `if (flags.willFlag)` reads better. **Ruled out —
   no win.**
3. *Inline the peek length-prefix helper at every call site.* Three or
   four nearly-identical 4-line blocks vs. one private function call.
   The function call is one JVM bytecode (or one Kotlin/Native
   instruction); inlining costs reviewability. **Ruled out — DRY
   wins, the helper is a per-codec emission, not a runtime cross-codec
   dispatch.**

**Less-clever alternative ruled out on audit grounds.** ✓

**Audit — reviewability.**

- Field names are the literal MQTT v3.1.1 §3.1.3 names (`willTopic`, `willMessage`, `userName`, `password`). Flag-bit names match §3.1.2.3.
- The wire diagram explicitly carries the `[when flags.willFlag]` annotations inline per Section 10.2 — a reader sees which fields are conditional without scrolling to the encode/decode method.
- Two `// Wire:` comments — both flagging non-obvious behavior:
  - `decode` comment: "each conditional field is fully absent when its gate is false." Without this, a reader might wonder if absent fields take some other shape (zero-length prefix, default value, etc.).
  - `peekFrameSize` comment: explains the offset-walk pattern.
- The encode-time `require` guards turn what would otherwise be a silent wire-format violation into an actionable error. Phrased symmetrically (flag set → field present, flag unset → field absent) so the error always names the side that's wrong. **Lock the policy: every `@WhenTrue` field gets a paired set of `require` guards in encode that enforce flag/nullity consistency.**
- `ConnectFlagsCodec.decode` / `.encode` are referenced — the `@see ConnectFlags` link makes it findable. Slice 8 will exercise `@DispatchOn` over a similar value class; the `@JvmInline` codec generation must be consistent across slices. ✓
- Dotted path `flags.willFlag` resolves at compile time: the processor walks the field type's properties and validates `willFlag` exists, returns `Boolean`, and is declared on the `@ProtocolMessage`-annotated type. Bad path → compile error with field-path in the message. **Lock the resolution rule.**

**Audit — streaming.**

- `peekFrameSize` walks `NeedsMoreData → Complete` correctly across all 16 flag combinations:
  - 0 bytes → NeedsMoreData (no flags byte yet).
  - 1 byte (flags=0): no conditional fields, `Complete(1)` immediately.
  - 1 byte (flags with bits set), partial body → NeedsMoreData at the first incomplete prefix or string.
  - All bytes present → `Complete(off - baseOffset)`.
  Verified mentally for `flags = 0xC0` (userName + password set, no will): peek 0xC0 → off=1; userName prefix at off=1, len=N → off=3+N; password prefix at off=3+N, len=M → off=5+N+M. `Complete(4 + N + M)` — matches encode wire size. ✓
- Encode under `BackPatch` — bridge handles via growable + `writeGathered`. Each `writeLengthPrefixedShort` patches *within* its own placeholder + body span; never crosses chunk boundaries by the same logic as slice 3 (the placeholder is the first action of each present field's emit). ✓
- Terminal-field slice-bounding not strictly applicable — but `peekFrameSize` naturally produces a finite frame size that the bridge can use to bound the buffer for `decode`. The decode method itself doesn't need `setLimit` because each field's length is self-describing once the flags are known. ✓

**Findings.**

1. **Lock the `@WhenTrue` dotted-path resolution rule.** The
   expression is one of:
   - `"fieldName"` — references a `Boolean` field on the same message.
   - `"fieldName.property"` — references a `Boolean`-returning property
     on the field's type. The field's type must be a class declared
     `@ProtocolMessage` (or annotated as a value class for the
     dispatcher path); the property must be `Boolean`-typed and
     declared on that type.
   Compile-time validation: bad field name, bad property name, or
   wrong return type → compile error with the field-path in the message.
   Matches `Annotations.kt:215` documentation; promotes the doc rule
   to a load-bearing KSP check.
2. **Lock the encode-time consistency guard rule.** Every `@WhenTrue`
   field gets paired `require` guards in encode: one for "gate true →
   field non-null", one for "gate false → field null". Phrased to
   name which side is wrong. Surfaces model-side misuse as
   `IllegalArgumentException` instead of silent wire-format corruption.
3. **Lock the value-class transparent codec rule for backing-property-
   only value classes.** `ConnectFlagsCodec` is generated as a
   one-liner that reads/writes the backing `UByte` directly — no
   `Partial`, no extra structure. The computed properties
   (`willFlag`, `cleanStart`, etc.) are read-only Kotlin getters that
   are inlined at the call site. **Decision recorded against
   PHASE_10 §8.10 (value classes compose seamlessly).**
4. **Lock the per-codec helper emission rule.** When two or more
   fields in the same codec use the same prefix shape (e.g., three
   `@LengthPrefixed(LengthPrefix.Short)` fields), the processor emits
   a single private helper function (`writeLengthPrefixedShort`,
   `peekLengthPrefixedShort`) and calls it from each field's encode /
   peek branch. When only one field uses the shape, the body is
   inlined at the call site. Threshold: ≥ 2 uses → extract; 1 use →
   inline. Reviewability win, no allocation cost.
5. **Lock the conditional-field encode skip semantics.** When a
   `@WhenTrue` gate is false, the processor *also* asserts (via
   `require`) that the field is null, but emits **nothing** to the
   wire for that field. Symmetric with decode skipping the read.

**Slice status:** validated. Five locks recorded. Move on to slice 7.

### Slice 7 — `@PacketType` simple sealed dispatch (no `@DispatchOn`)

**Source vector.** WebSocket frame opcodes (RFC 6455 §5.2) are
*almost* a `@PacketType` fit, but the opcode shares its byte with the
FIN bit and RSVs — that's `@DispatchOn` territory (slice 8). The
cleanest match for *plain* `@PacketType` is a hand-rolled command
protocol the codec test suite was already exercising in the Phase 9
attempts: a stream of commands prefixed by a 1-byte type discriminator,
no flags, no shared bits. To make it spec-grounded, we model the
**SOCKS5 method-selection request** (RFC 1928 §3) — the server's
method-selection response is a 2-byte fixed shape, but the client's
*request* dispatches on a sub-opcode in some extensions. We keep it
synthetic but spec-style.

```kotlin
/**
 * Tiny command protocol — 1 byte discriminator followed by a per-
 * variant body. Vector for plain @PacketType dispatch (no
 * @DispatchOn — the discriminator owns its byte exclusively).
 */
@ProtocolMessage(wireOrder = Endianness.Big)
sealed interface Command {
    /** Empty heartbeat — body is zero bytes. */
    @ProtocolMessage @PacketType(0x01)
    data object Ping : Command

    /** Round-trip echo carrying a length-prefixed UTF-8 message. */
    @ProtocolMessage @PacketType(0x02)
    data class Echo(@LengthPrefixed val message: String) : Command

    /** Single 32-bit BE counter increment delta. */
    @ProtocolMessage @PacketType(0x03)
    data class Bump(val delta: Int) : Command

    /** Termination — body is zero bytes. */
    @ProtocolMessage @PacketType(0xFF)
    data object Bye : Command
}
```

Sealed parent with four variants — diagram trigger fires on the
discriminator (always emitted for sealed parents per Section 10.3),
and each variant gets its own per-variant codec emission as it would
in isolation.

**Sketched generated output (parent dispatcher).**

```kotlin
/**
 * Generated codec for [Command] — wire-format details below.
 *
 * @see Command
 * @see Command.Ping
 * @see Command.Echo
 * @see Command.Bump
 * @see Command.Bye
 *
 * ```text
 *  0
 *  0 1 2 3 4 5 6 7
 * +-+-+-+-+-+-+-+-+
 * |  type (1b BE) |  → 0x01 Ping | 0x02 Echo | 0x03 Bump | 0xFF Bye
 * +-+-+-+-+-+-+-+-+
 * |  variant body (per @PacketType — see per-variant codec)         |
 * +-----------------------------------------------------------------+
 * ```
 *
 * Source documentation:
 *   Tiny command protocol — 1 byte discriminator followed by a per-
 *   variant body. Vector for plain @PacketType dispatch (no
 *   @DispatchOn — the discriminator owns its byte exclusively).
 */
object CommandCodec : Codec<Command> {

    /** Reads the 1-byte type, dispatches to the matching variant codec. */
    override fun decode(buffer: ReadBuffer, context: DecodeContext): Command {
        val type = buffer.readUByte().toInt()
        return when (type) {
            0x01 -> Command.Ping
            0x02 -> Command.EchoCodec.decode(buffer, context)
            0x03 -> Command.BumpCodec.decode(buffer, context)
            0xFF -> Command.Bye
            else -> throw DecodeException(
                fieldPath  = "Command.<type>",
                bufferPosition = buffer.position() - 1,
                expected   = "one of 0x01, 0x02, 0x03, 0xFF",
                actual     = "0x${type.toString(16)}",
            )
        }
    }

    /** Writes the variant's wire byte, then the variant body. */
    override fun encode(buffer: WriteBuffer, value: Command, context: EncodeContext) {
        when (value) {
            Command.Ping       -> buffer.writeUByte(0x01u)
            is Command.Echo    -> { buffer.writeUByte(0x02u); Command.EchoCodec.encode(buffer, value, context) }
            is Command.Bump    -> { buffer.writeUByte(0x03u); Command.BumpCodec.encode(buffer, value, context) }
            Command.Bye        -> buffer.writeUByte(0xFFu)
        }
    }

    /**
     * Discriminator (1) + per-variant size. `Exact` when the chosen
     * variant is `Exact`; `BackPatch` when the chosen variant is
     * `BackPatch`. Compile-time per-variant analysis keeps this
     * cheap.
     */
    override fun wireSize(value: Command, context: EncodeContext): WireSize =
        when (value) {
            Command.Ping       -> WireSize.Exact(1)
            is Command.Echo    -> WireSize.BackPatch                              // Echo body is BackPatch
            is Command.Bump    -> WireSize.Exact(1 + 4)
            Command.Bye        -> WireSize.Exact(1)
        }

    /** Frame size = 1 + per-variant frame size. */
    override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
        if (stream.available() - baseOffset < 1) return PeekResult.NeedsMoreData
        val type = stream.peekByte(baseOffset).toInt() and 0xFF
        return when (type) {
            0x01 -> PeekResult.Complete(1)                                        // Ping is body-less
            0x02 -> when (val r = Command.EchoCodec.peekFrameSize(stream, baseOffset + 1)) {
                is PeekResult.Complete -> PeekResult.Complete(1 + r.bytes)
                PeekResult.NeedsMoreData -> PeekResult.NeedsMoreData
                PeekResult.NoFraming -> error("Echo variant codec returned NoFraming inside dispatcher")
            }
            0x03 ->
                if (stream.available() - baseOffset >= 5) PeekResult.Complete(5)
                else PeekResult.NeedsMoreData
            0xFF -> PeekResult.Complete(1)
            else ->
                // Wire: unknown discriminator — the framer cannot know how many
                // bytes the unknown variant would consume; surface as Complete(1)
                // and let decode raise the typed DecodeException.
                PeekResult.Complete(1)
        }
    }
}
```

**Audit — zero-copy.** Decode is a single byte read + a `when`
dispatch. Each variant's codec is invoked directly (no reflection, no
KClass lookup). `Command.Ping` and `Command.Bye` are `data object`
singletons — the decode branch returns the singleton instance without
allocating. Encode is a single byte write + variant body. `wireSize`
is a `when` over value-class returns. `peekFrameSize` is byte peek +
arithmetic + per-variant peek dispatch. No `ByteArray`, no boxing of
the discriminator. ✓

**Audit — batching.** Three alternatives considered and ruled out:

1. *Generate a `LongIntMap`-style discriminator → codec lookup table
   indexed by the type byte.* For 4 variants the `when` is faster
   than a hash lookup; the JVM compiles small `when (Int)` ladders to
   `tableswitch` bytecode (O(1)). For 256 variants a switch is still
   the right shape. **Ruled out — switch wins at every realistic
   variant count.**
2. *Avoid the per-variant `peekFrameSize` recursion by inlining each
   variant's known frame size constant.* Possible for fixed-size
   variants (`Bump = 5`, `Ping = 1`, `Bye = 1`) and we *do* inline
   those. For variable-size variants (`Echo`) we must call the
   variant's `peekFrameSize` because only it knows the prefix layout.
   The hybrid we have — inline constants for fixed variants, recurse
   for variable variants — is the smallest possible work. ✓
3. *Use sealed-interface exhaustiveness to elide the `else` branch
   in decode.* Can't — the wire byte is an unbounded `Int`; the
   `when (type)` ladder is over `Int`, not over `Command`. The
   `else` *must* throw because the wire could carry any byte. ✓

**Less-clever alternative ruled out on audit grounds.** ✓

**Audit — reviewability.**

- Variant names map to spec terminology where applicable (Ping/Echo are universal; Bump/Bye are this protocol's terms). The `@PacketType(0x01)` literal sits next to the variant declaration so a reviewer can map wire byte ↔ variant in one glance.
- The wire diagram lists all four discriminator values inline — no scrolling. **Lock the rule:** sealed dispatcher diagrams enumerate every known wire value next to the discriminator row.
- Per-variant codecs (`Command.EchoCodec`, `Command.BumpCodec`) are companion-positioned with their variant; the parent's `@see` block links each one. Companion placement decision: variant codec lives at `Command.EchoCodec` (companion of the variant), parent codec lives at `CommandCodec` (sibling of the sealed interface). **Lock companion placement.**
- The decode `else` raises a typed `DecodeException` with `fieldPath = "Command.<type>"` — the `<type>` placeholder is the convention for "the discriminator field of a sealed parent." Slice 8 will extend this pattern with `<header.packetType>` for `@DispatchOn` cases. **Lock the field-path convention for synthetic discriminator slots.**
- The `peekFrameSize` `else` branch returns `Complete(1)` for unknown discriminators — *not* `NeedsMoreData`. Reasoning: peek can't know what an unknown variant looks like, so reporting `NeedsMoreData` would deadlock the bridge (it would wait for bytes that never resolve the framing). Reporting `Complete(1)` lets the bridge call `decode(...)` which then throws a `DecodeException` immediately — surfacing the bad input at the right layer. **Lock the unknown-discriminator peek policy.**

**Audit — streaming.**

- `peekFrameSize` walks correctly across all four variants:
  - `Ping`: 1 byte → Complete(1); 0 bytes → NeedsMoreData.
  - `Bump`: 1–4 bytes → NeedsMoreData; 5 bytes → Complete(5).
  - `Echo`: 1 byte (just type) → recurse → NeedsMoreData; type + prefix only → NeedsMoreData; type + prefix + body → Complete(1 + 2 + len).
  - `Bye`: 1 byte → Complete(1).
  Verified mentally for partial-buffer inputs; baseOffset propagates. ✓
- Encode under per-variant `Exact` or `BackPatch` — the bridge picks the right path per call. The `wireSize` returns the variant's category, not a fold of "any variant could BackPatch." This means **a sealed parent's `wireSize` must be value-dependent** — it can't be a static property of the codec. That matches the existing `Encoder.wireSize` signature (`(value, context) → WireSize`); confirms the surface. ✓
- Terminal-field slice-bounding: `Echo`'s body is bounded by its own `@LengthPrefixed` prefix (slice 3 contract). Other variants are fixed-size. No new bounding pattern introduced. ✓

**Findings.**

1. **Lock the `@PacketType` discriminator wire shape.** 1-byte (`UByte`)
   discriminator. Larger discriminators are `@DispatchOn` territory
   (slice 8). `@PacketType(value: Int)` accepts `0..255` — values
   outside that range are a compile-time error.
2. **Lock the duplicate-`@PacketType` compile-time check.** Two
   variants of the same sealed parent declaring the same `value` (and
   the same `wire` if `@DispatchOn` is in play) → compile error.
   Already documented; promote to a load-bearing KSP check.
3. **Lock the sealed-dispatcher diagram convention.** Sealed parent
   codecs always emit a wire diagram of the discriminator alone (per
   Section 10.3 trigger), with every known wire value enumerated
   inline next to the discriminator row.
4. **Lock the companion placement convention.** Variant codecs live
   inside the variant's companion as `VariantName.Codec` (or
   `Command.EchoCodec` if the dispatcher is at `CommandCodec`); parent
   codecs are siblings of the sealed interface as `CommandCodec`.
   This resolves the PHASE_9_RESET deferred decision *"Companion-object
   placement (`MyMessage.Codec` vs sibling `MyMessageCodec`)"* —
   **decision: sibling for top-level codecs, companion for variants
   under a sealed parent.** Top-level matches `MyMessageCodec` for
   discoverability (global `MyMessageCodec.decode(...)`); variant
   companion makes the variant's codec land under the variant's name
   for sealed-aware tooling and IDE auto-import.
5. **Lock the synthetic-field-path convention for discriminators.**
   Decode failure on a sealed parent's discriminator slot uses
   `fieldPath = "<ParentName>.<type>"` (or `<header.packetType>` for
   `@DispatchOn` + `@DispatchValue` slot — see slice 8).
6. **Lock the unknown-discriminator peek policy.** `peekFrameSize`
   for an unknown discriminator returns `Complete(1)` so decode is
   invoked next and raises `DecodeException`. Never returns
   `NeedsMoreData` for an unknown discriminator — that would deadlock.
7. **Lock the `data object` codec emission rule.** A `data object`
   variant with no fields decodes to the singleton instance and
   encodes to nothing past the discriminator. No `Partial`, no codec
   object for the variant itself — the parent dispatcher handles both
   directions inline. Resolves the PHASE_9_RESET deferred decision
   *"`data object` vs empty `data class`"* in favor of *both*: the
   processor accepts both shapes, treats a `data object` as a sealed-
   tree leaf with no body codec, and treats an empty `data class`
   identically (allocating a new instance per decode rather than
   returning a singleton). **Decision: support both; `data object`
   yields a singleton, empty `data class` yields per-decode allocation
   (negligible — empty objects are scalar-replaced on the JVM).**

**Slice status:** validated. Seven locks recorded (including
resolution of two PHASE_9_RESET deferred decisions: companion
placement and `data object` vs empty `data class`). Move on to slice 8.

### Slice 8 — `@DispatchOn` + `@DispatchValue` over MQTT v3 fixed header

**Source vector.** MQTT v3.1.1 §2.2 Fixed Header: a single byte where
the high nibble is the `MQTT Control Packet type` and the low nibble
holds packet-type-specific flags. The packet-type values 0..15 each
identify a control packet variant; the flag bits carry per-variant
modifiers (DUP, QoS, RETAIN for PUBLISH; reserved-zero for most
others). The `@DispatchOn` + `@DispatchValue` pair is exactly built
for this: the dispatcher reads the value-class header, extracts a
4-bit packet-type from it via the `@DispatchValue` property, and
dispatches on the extracted value while the *full byte* (with flags
intact) gets passed into the variant codec for further interpretation.

We model a small but realistic v3 packet set — Connect (slice 6's
payload, restored to a real envelope), PingReq, Disconnect, plus a
mid-tier with a non-zero packetId (Subscribe). Variants without
payload bodies are `data object`s per slice 7's lock.

```kotlin
/**
 * MQTT v3.1.1 §2.2 Fixed Header. High nibble = control packet type,
 * low nibble = packet-type-specific flags.
 */
@JvmInline
@ProtocolMessage
value class MqttFixedHeader(val raw: UByte) {
    @DispatchValue
    val packetType: Int get() = (raw.toInt() ushr 4) and 0x0F

    val flags: UByte get() = (raw and 0x0Fu)
    val dup:    Boolean get() = (raw.toInt() and 0b0000_1000) != 0
    val qos:    Int     get() = (raw.toInt() ushr 1) and 0b11
    val retain: Boolean get() = (raw.toInt() and 0b0000_0001) != 0
}

/**
 * MQTT v3.1.1 control packet sealed parent. Dispatched on
 * MqttFixedHeader.packetType (the @DispatchValue property). Variants
 * carry the full header so they can read the flag bits when needed
 * (Publish in slices 9–10; Connect/PingReq/Disconnect ignore flags).
 *
 * Payload-typed Publish and Connect are added in slices 9–10; this
 * slice exercises @DispatchOn over body-less and prefix-only variants.
 */
@DispatchOn(MqttFixedHeader::class)
@ProtocolMessage(wireOrder = Endianness.Big)
sealed interface MqttControlPacket {
    /** §3.12 PINGREQ — zero remaining length. */
    @ProtocolMessage @PacketType(value = 12, wire = 0xC0)
    data object PingReq : MqttControlPacket

    /** §3.13 PINGRESP — zero remaining length. */
    @ProtocolMessage @PacketType(value = 13, wire = 0xD0)
    data object PingResp : MqttControlPacket

    /** §3.14 DISCONNECT — zero remaining length in v3. */
    @ProtocolMessage @PacketType(value = 14, wire = 0xE0)
    data object Disconnect : MqttControlPacket

    /** §3.4 PUBACK — header + 2-byte packet identifier. */
    @ProtocolMessage @PacketType(value = 4, wire = 0x40)
    data class PubAck(
        val header: MqttFixedHeader,
        val packetId: UShort,
    ) : MqttControlPacket
}
```

Sealed parent with mixed body-less + prefix-only variants. The
discriminator is bit-packed inside the header byte, and the `wire`
parameter on each `@PacketType` is the raw byte to write on encode
(`packetType << 4`, with the variant's reserved-zero flags set per
spec). `@PacketType(value = …, wire = …)` carries both — `value` is
the extracted dispatch value, `wire` is the raw byte that round-trips
through the value class.

**Sketched generated output (parent dispatcher).**

```kotlin
/**
 * Generated codec for [MqttControlPacket] — wire-format details below.
 *
 * @see MqttControlPacket
 * @see MqttFixedHeader
 * @see MqttControlPacket.PingReq
 * @see MqttControlPacket.PingResp
 * @see MqttControlPacket.Disconnect
 * @see MqttControlPacket.PubAck
 *
 * ```text
 *  0
 *  0 1 2 3 4 5 6 7
 * +-+-+-+-+-+-+-+-+
 * |  pktType  | flags | header (1b) — dispatch on packetType (high nibble)
 * +-+-+-+-+-+-+-+-+
 * | remaining length (variable byte int — see RemainingLengthCodec)         |
 * +-------------------------------------------------------------------------+
 * | variant body (per @PacketType — see per-variant codec)                  |
 * +-------------------------------------------------------------------------+
 *
 * Dispatch table (packetType → wire byte):
 *   12 (PingReq)    → 0xC0
 *   13 (PingResp)   → 0xD0
 *   14 (Disconnect) → 0xE0
 *    4 (PubAck)     → 0x40
 * ```
 *
 * Source documentation:
 *   MQTT v3.1.1 control packet sealed parent. Dispatched on
 *   MqttFixedHeader.packetType (the @DispatchValue property). Variants
 *   carry the full header so they can read the flag bits when needed
 *   (Publish in slices 9–10; Connect/PingReq/Disconnect ignore flags).
 *
 *   Payload-typed Publish and Connect are added in slices 9–10; this
 *   slice exercises @DispatchOn over body-less and prefix-only variants.
 */
object MqttControlPacketCodec : Codec<MqttControlPacket> {

    /**
     * Reads the fixed header, then the remaining-length VBI, then
     * dispatches on `header.packetType` to the per-variant codec.
     *
     * The header is forwarded to variant codecs via the buffer position
     * already advancing past it; variants that need it (PubAck, Publish)
     * receive it as the first parameter to their `decode` (the processor
     * arranges this — see per-variant generated codec).
     */
    override fun decode(buffer: ReadBuffer, context: DecodeContext): MqttControlPacket {
        val header = MqttFixedHeaderCodec.decode(buffer, context)
        val remainingLen = RemainingLengthCodec.decode(buffer, context)
        // Wire: bound the buffer to the variant body, then dispatch
        val outerLimit = buffer.limit()
        buffer.setLimit(buffer.position() + remainingLen)
        val packet = when (val type = header.packetType) {
            12 -> MqttControlPacket.PingReq
            13 -> MqttControlPacket.PingResp
            14 -> MqttControlPacket.Disconnect
            4  -> MqttControlPacket.PubAckCodec.decode(buffer, context, header)
            else -> throw DecodeException(
                fieldPath  = "MqttControlPacket.<header.packetType>",
                bufferPosition = buffer.position() - 1 - vbiBytesUsed(remainingLen),
                expected   = "one of 4, 12, 13, 14",
                actual     = type.toString(),
            )
        }
        buffer.setLimit(outerLimit)
        return packet
    }

    /** Writes the variant's wire byte, then the remaining-length VBI, then the body. */
    override fun encode(buffer: WriteBuffer, value: MqttControlPacket, context: EncodeContext) {
        when (value) {
            MqttControlPacket.PingReq -> {
                buffer.writeUByte(0xC0u); RemainingLengthCodec.encode(buffer, 0, context)
            }
            MqttControlPacket.PingResp -> {
                buffer.writeUByte(0xD0u); RemainingLengthCodec.encode(buffer, 0, context)
            }
            MqttControlPacket.Disconnect -> {
                buffer.writeUByte(0xE0u); RemainingLengthCodec.encode(buffer, 0, context)
            }
            is MqttControlPacket.PubAck -> {
                // Wire: encode-time consistency — header.packetType must equal 4
                require(value.header.packetType == 4) {
                    "PubAck encoded with header.packetType=${value.header.packetType}"
                }
                buffer.writeUByte(value.header.raw)
                RemainingLengthCodec.encode(buffer, 2, context)
                MqttControlPacket.PubAckCodec.encode(buffer, value, context)
            }
        }
    }

    /** Discriminator (1) + VBI (1..4) + per-variant body size. */
    override fun wireSize(value: MqttControlPacket, context: EncodeContext): WireSize =
        when (value) {
            MqttControlPacket.PingReq,
            MqttControlPacket.PingResp,
            MqttControlPacket.Disconnect -> WireSize.Exact(2)                     // 1 header + 1 VBI(0)
            is MqttControlPacket.PubAck  -> WireSize.Exact(1 + 1 + 2)             // 1 header + 1 VBI(2) + 2 packetId
        }

    /** Frame size = 1 (header) + VBI bytes + remainingLength value. */
    override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
        if (stream.available() - baseOffset < 1) return PeekResult.NeedsMoreData
        // Wire: peek the header, then decode the VBI in-place to learn the body size
        val rawHeader = stream.peekByte(baseOffset).toInt() and 0xFF
        val (vbiBytes, remainingLen) = peekVbi(stream, baseOffset + 1)
            ?: return PeekResult.NeedsMoreData
        val total = 1 + vbiBytes + remainingLen
        return if (stream.available() - baseOffset >= total) PeekResult.Complete(total)
        else PeekResult.NeedsMoreData
    }

    /** Number of VBI bytes consumed when encoding [value]; computed by class. */
    private fun vbiBytesUsed(value: Int): Int =
        when {
            value < 128       -> 1
            value < 16_384    -> 2
            value < 2_097_152 -> 3
            else              -> 4
        }

    /**
     * Decodes a VBI starting at the given stream offset without consuming.
     * Returns (bytesUsed, value) on success, null if more bytes are needed.
     */
    private fun peekVbi(stream: StreamProcessor, off: Int): Pair<Int, Int>? {
        var multiplier = 1
        var value = 0
        var i = 0
        while (i < 4) {
            if (stream.available() - off - i < 1) return null
            val byte = stream.peekByte(off + i).toInt() and 0xFF
            value += (byte and 0x7F) * multiplier
            i++
            if ((byte and 0x80) == 0) return i to value
            multiplier *= 128
        }
        // Wire: malformed VBI (>4 bytes); decode will raise the typed error
        return 4 to value
    }
}
```

The variant `data class PubAck` codec:

```kotlin
/**
 * Generated codec for [MqttControlPacket.PubAck] — invoked over a
 * pre-bounded buffer (limit = position + remainingLength). Reads the
 * 2-byte BE packetId and constructs the variant.
 */
object MqttControlPacket.PubAckCodec {
    fun decode(buffer: ReadBuffer, context: DecodeContext, header: MqttFixedHeader): MqttControlPacket.PubAck {
        val packetId = buffer.readUShort()
        return MqttControlPacket.PubAck(header, packetId)
    }
    fun encode(buffer: WriteBuffer, value: MqttControlPacket.PubAck, context: EncodeContext) {
        buffer.writeUShort(value.packetId)
    }
    // Variant codec has no wireSize / peekFrameSize at the top level — the
    // parent's wireSize / peekFrameSize handles framing for the whole packet.
}
```

The `MqttFixedHeaderCodec` and `RemainingLengthCodec` are leaf codecs:

```kotlin
/** Transparent codec for the @JvmInline value class — reads/writes the backing UByte. */
object MqttFixedHeaderCodec : Codec<MqttFixedHeader> {
    override fun decode(buffer: ReadBuffer, context: DecodeContext): MqttFixedHeader =
        MqttFixedHeader(buffer.readUByte())
    override fun encode(buffer: WriteBuffer, value: MqttFixedHeader, context: EncodeContext) {
        buffer.writeUByte(value.raw)
    }
    override fun wireSize(value: MqttFixedHeader, context: EncodeContext): WireSize =
        WireSize.Exact(1)
    override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult =
        if (stream.available() - baseOffset >= 1) PeekResult.Complete(1) else PeekResult.NeedsMoreData
}
```

(The `RemainingLengthCodec` is the MQTT VBI codec — body omitted; it
is structurally identical to the `peekVbi` helper above plus
straightforward encode.)

**Audit — zero-copy.** Header read is one byte. VBI read is at most 4
bytes plus integer arithmetic. `setLimit + restore` is two integer
field writes (slice 4 contract). `data object` variants return the
singleton — no allocation. `PubAck` allocates one instance per decode.
The value-class `MqttFixedHeader` boxes only when assigned to a non-
`MqttFixedHeader` typed slot (e.g., the `header` parameter of the
variant decode); the JVM and Kotlin/Native compilers eliminate the box
when the call site is monomorphic, which it always is here. ✓

**Audit — batching.** Three alternatives considered and ruled out:

1. *Read header + VBI as one fused operation.* The VBI's length
   depends on the bytes themselves; can't be folded without sacrificing
   correctness on max-length VBIs. **Ruled out — semantically
   different.**
2. *Avoid the `setLimit + restore` for fixed-size variants.* For
   `PingReq` / `Disconnect` (body length 0) and `PubAck` (body length
   2), the body size is known at compile time once the discriminator
   is matched. We could skip the bound for these. But: the bound is
   the only thing that catches a *malformed* packet declaring
   `remainingLen = 5` while saying `packetType = 12` (PingReq). The
   bound forces decode to see exactly the bytes the wire claims, and
   the `setLimit + restore` cost is two writes per packet — negligible.
   **Ruled out — defense in depth wins for two writes.**
3. *Cache the `vbiBytesUsed` value computed during decode for use in
   the error path's `bufferPosition` math.* The value is only read on
   the error path (already exceptional); recomputing from the value is
   one switch and saves a stack slot. **Ruled out — premature.**

**Less-clever alternative ruled out on audit grounds.** ✓

**Audit — reviewability.**

- Variant names map directly to MQTT v3.1.1 spec sections (`PingReq` = §3.12, etc.). The dispatch table in the diagram makes the bit-pattern visible without grepping.
- The fixed-header diagram bit-splits the byte into `[ pktType:4 | flags:4 ]` per Section 10.2 bit-packed rule — a reviewer sees exactly which bits drive dispatch.
- The `// Wire:` comments are sparse and load-bearing: setLimit, encode-time consistency guard, peek VBI fallback for malformed inputs.
- The `peekVbi` helper is private to `MqttControlPacketCodec` and named for what it does. A future codec that needs the same operation gets its own copy — KSP doesn't share helpers across codecs (they're emitted per-codec for closure of context). **Lock that helper-emission scope: per-codec, never shared.**
- The generated `PubAckCodec.decode` takes `header` as a parameter rather than re-reading the header from the buffer. This propagates the discriminator into the variant cleanly. **Lock the discriminator-pass-through rule: variant codecs that need the discriminator receive it as an explicit parameter; they do not re-read the discriminator byte.**
- The encode-time `require` on `PubAck` consistency is the same shape as slice 6's `@WhenTrue` guards. Symmetric across the framework. ✓

**Audit — streaming.**

- `peekFrameSize` walks correctly:
  - 0 bytes → NeedsMoreData (no header).
  - 1 byte → recurse into peekVbi at offset 1; byte unavailable → NeedsMoreData.
  - 2..5 bytes (header + partial/full VBI + remaining body partial) → progresses through NeedsMoreData → Complete as bytes arrive.
  - Verified for `PingReq` (1 + 1 + 0 = 2 bytes) and `PubAck` (1 + 1 + 2 = 4 bytes). ✓
- The peek must NOT consume bytes — `stream.peekByte(off)` is non-consuming, the VBI walk uses `peekByte(off + i)` not `readByte()`. ✓
- Encode: `PubAck` is `Exact(4)` — bridge takes the fast `pool.withBuffer(4)` path. `Ping*` / `Disconnect` are `Exact(2)`. None of the slice-8 variants are BackPatch (BackPatch shows up in slice 9–10 with the Publish payload and Connect's optional fields). ✓
- Terminal-field slice-bounding: the parent `setLimit + restore` bounds the variant body to exactly `remainingLen` bytes, so even a malformed `PubAck` claiming `remainingLen = 100` is caught at decode (the variant tries to read 2 bytes, then `setLimit` restores; if more bytes were claimed than the variant consumed, the parent's restore reseats the outer limit and the next packet picks up at the wrong position — **bug risk!**).

  Wait — that's a real issue. Let me re-examine. If `remainingLen = 100` is declared but `PubAck` only consumes 2 bytes, the parent's `setLimit(position + 100)` advances `limit` to `pos + 100`, the variant decodes (reads 2 bytes; position now `start + 2`), and the parent does `setLimit(outerLimit)` — but the *position* is still at `start + 2`, leaving 98 bytes that *should* have been part of this packet still in the buffer. The next outer call would try to decode them as a new packet header — wrong.

  **Fix:** the parent's `setLimit + restore` must also `seekToLimit` (i.e., advance position to the inner limit) before restoring. Add `buffer.position(buffer.limit())` between the variant decode and the limit restore. The variant having "left bytes on the table" is itself a wire-format violation worth surfacing as a typed `DecodeException`, but in the worst case (variant under-reads), the parent advancing position to the declared end keeps the outer stream consistent.

  **Lock the parent post-dispatch position-advance rule:** after a `@DispatchOn` parent decodes its variant, it advances the buffer's position to the inner limit before restoring the outer limit. Variant under-reads become buffer skips, not stream-corruption bugs. Optionally, the processor can emit a `require(buffer.position() == buffer.limit())` between the two calls when the variant codec is known to consume exactly its declared body length — defense in depth.

**Findings.**

1. **Lock the `wire` byte semantics.** `@PacketType(value = N, wire = M)`
   for a `@DispatchOn`-using sealed parent: `value` is the extracted
   `@DispatchValue` integer (the dispatch key), `wire` is the raw byte
   the value class would round-trip from on encode (header byte for
   that variant). The processor validates at compile time that
   `MqttFixedHeader(wire.toUByte()).packetType == value` (or the
   equivalent for the discriminator class) so the `wire` and `value`
   can never disagree. Already noted in `Annotations.kt` documentation
   for `@PacketType`; promote to a load-bearing KSP check.
2. **Lock the discriminator-pass-through rule.** Variant codecs that
   need the discriminator receive it as an explicit parameter to
   their `decode`. They never re-read it from the buffer.
3. **Lock the per-codec helper-emission scope.** Helper functions
   (`peekVbi`, `writeLengthPrefixedShort`, etc.) are private to the
   codec object that uses them. Two codecs that need the same helper
   get two copies. Reasoning: helpers close over context (annotation
   choices, byte order, variant set) that varies per codec; sharing
   would force a runtime dispatch on context. Per-codec emission
   keeps each helper monomorphic and inline-friendly.
4. **Lock the parent post-dispatch position-advance rule.** After a
   `@DispatchOn` parent invokes a variant codec inside a length-bounded
   region, it advances the buffer position to the inner limit before
   restoring the outer limit. Defends against under-reading variants.
   Optionally emit `require(position == limit)` for variants whose
   wire size is known to match the declared length exactly.
5. **Defers nothing further.** All slice-8 mechanics resolved.

**Slice status:** validated. Five locks recorded (including a real
bug fix surfaced by the streaming audit — under-read defense).
Move on to slice 9.

### Slices 9–10 — schedule

| # | Annotation                       | Source vector                                    | Status   |
|---|----------------------------------|--------------------------------------------------|----------|
| 1 | `@ProtocolMessage`               | RIFF chunk header                                | ✓ done   |
| 2 | `@WireOrder` + `@WireBytes`      | DNS header (BE) + BLE-ATT 3-byte LE length       | ✓ done   |
| 3 | `@LengthPrefixed`                | MQTT v5 PUBLISH topic + packetId                 | ✓ done   |
| 4 | `@LengthFrom`                    | RIFF chunk body via `@LengthFrom("chunkSize")`   | ✓ done   |
| 5 | `@RemainingBytes`                | WebSocket close-frame body                       | ✓ done   |
| 6 | `@WhenTrue`                      | MQTT v3.1.1 CONNECT optional payload fields      | ✓ done   |
| 7 | `@PacketType`                    | Tiny command protocol (4 variants, no DispatchOn)| ✓ done   |
| 8 | `@DispatchOn` + `@DispatchValue` | MQTT v3.1.1 fixed header value class             | ✓ done   |
| 3 | `@LengthPrefixed`                | WebSocket close-frame reason or MQTT v5 utf8     | pending  |
| 4 | `@LengthFrom`                    | RIFF chunk body via `@LengthFrom("chunkSize")`   | pending  |
| 5 | `@RemainingBytes`                | WebSocket text-frame trailing payload            | pending  |
| 6 | `@WhenTrue`                      | MQTT v3 CONNECT will/username/password gates     | pending  |
| 7 | `@PacketType`                    | Sealed dispatch (small sealed parent)            | pending  |
| 8 | `@DispatchOn` + `@DispatchValue` | MQTT v5 fixed header value class                 | pending  |
| 9 | `@UseCodec`                      | MQTT v5 PUBLISH typed payload via JpegImageCodec | pending  |
|10 | `Payload` SAM                    | Full MQTT v5 PUBLISH end-to-end                  | pending  |

Each pending row will be filled in the same shape as Slice 1 — source
vector, sketched generated output, four-axis audit (zero-copy /
batching / reviewability / streaming), findings — and locked one at a
time before any emitter code is written.

## Stage-A gate — `Connection<MqttControlPacket<…>>` integration test

After all ten slices are validated, the final gate before any emitter
code is a real `Connection<T>` round-trip. Sketch lives here once
slice 10 is locked. Outline (to be expanded):

- Paired in-memory `ByteStream` (two `Channel<ReadBuffer>` queues, one per direction).
- `Codec<MqttControlPacket<MyWill, MyAuth, MyPub>>.asConnection(transport, pool, …)` → `Connection<MqttControlPacket<MyWill, MyAuth, MyPub>>`.
- Send a typed-payload `Publish<JpegFrame>` through one side; collect on the other.
- Inject a synthetic chunk-splitting wrapper around the inbound `ByteStream` that returns the encoded bytes in arbitrary 1- to 17-byte chunks so `peekFrameSize` is forced through the `NeedsMoreData → Complete` progression at every byte boundary.
- Assert: byte-exact round-trip; `DecodeContext` propagates from caller to `JpegImageCodec`; the allocation tracker (Stage C dependency) reports zero `ByteArray` allocations on the hot path; closing one side completes the other side's flow normally.

## Decisions still pending (everything else)

These were on the queue but never reached:

- Generated code language: Kotlin source (assumed but not formally locked).
- Zero `ByteArray` in hot paths: PHASE_9_RESET item 3 says yes; mechanism
  to enforce it (allocation tracker hooked into `:buffer-codec-test`?)
  not designed.
- First-class user extension model (`@UseCodec` + `CodecContext`) — the
  zero-copy-from-original-buffer story for platform decoders. Mostly
  follows from locked decisions but worth a final confirmation.
- Annotation surface — deferred decisions per PHASE_9_RESET sketch:
  - `@LengthFrom("fieldName")` string-DSL vs property reference vs
    compile-time name resolution.
  - `@WhenTrue("flags.willFlag")` dotted-string DSL.
  - `LengthPrefix` enum: `Byte`/`Short`/`Int`/`Varint` vs `Fixed(Int)`/`Variable`.
  - `@WireOrder` + `@WireBytes` consolidation.
  - Companion-object placement (`MyMessage.Codec` vs `MyMessageCodec`).
  All deferred to "first concrete vector that exercises them" per the
  earlier discussion.
- PHASE_9_RESET.md restructuring — replace "What we're going for instead"
  + "Next steps" sections with: locked decisions → deferred decisions
  table → Stages A–H execution plan → carryover constraints. Not yet done.
- Stage 0 (strip `:buffer-codec-processor` to scaffolding): one commit
  that deletes every emitter file, all SPI wiring (`buffer-codec-test-spi/`
  goes), all generated-codec snapshots. Keep KSP entry point + annotations
  + `Codec` / `CodecContext` interfaces. After this commit
  `:buffer-codec-test` is broken by design until fixtures come back online
  per stage.
- Stages A–H structure (vertical slices, each with spec-vector tests
  driving the next processor capability).

## How to resume

1. Read this file + PHASE_9_RESET.md (still authoritative on the why
   and the constraints).
2. Reload locked decisions above as the design contract.
3. Continue the Q&A walk on the remaining pending decisions (annotation
   surface, allocation tracker, etc.).
4. Then restructure PHASE_9_RESET.md with locked decisions + deferred
   decisions table + Stages A–H.
5. Then Stage 0: strip the processor.
6. Then build out stages with TDD against spec vectors.

## Reference

- Original revert: `c03dac7`.
- Last Phase 9 commit (preserved in history): `ea3dbb5`.
- Merge-base with main: `676d53f`.
- 161 Phase 9 commits remain reachable via `git log 676d53f..ea3dbb5`.
- Prior plans: `~/.claude/plans/phase-9-*.md`.
- Constraints (carryover from PHASE_9_RESET):
  - Do not push commits until user confirms.
  - Do not skip git hooks (`--no-verify`, `--no-gpg-sign`).
  - mavenLocal republish is post-rewrite, not now.
