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

### Slices 2–10 — schedule

| # | Annotation                       | Source vector                                    | Status   |
|---|----------------------------------|--------------------------------------------------|----------|
| 1 | `@ProtocolMessage`               | RIFF chunk header                                | ✓ done   |
| 2 | `@WireOrder` + `@WireBytes`      | DNS header (BE) + BLE-ATT 3-byte LE length       | pending  |
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
