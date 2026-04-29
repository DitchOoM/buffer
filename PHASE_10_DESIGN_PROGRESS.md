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

- HEAD: `5cf04d2 PHASE_9_RESET: models are negotiable; specs are not`
- Revert commit: `c03dac7` (resets all Phase 9 changes back to merge-base
  shape from `676d53f`).
- `:buffer-codec-test:jvmTest` is green at HEAD (verified).
- mavenLocal stale jar still blocks downstream mqtt repo — republish
  is post-rewrite, not now.

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
