# ARCHITECTURE_V2.md — type-safe state transitions across the DitchOoM stack

**Status: draft for review. No code lands before this doc is approved.**

## Context

v2 stabilization surfaced three classes of bugs that all live at the type
level and cannot be eliminated by more tests:

1. **Write-before-handshake.** `ClientSocket.write()` is reachable on an
   object that hasn't finished its TLS handshake. Behavior is implicit
   (queue, block, or throw — platform-dependent).
2. **Unhandled disconnect mid-call.** `write()` / `send()` / `publish()`
   return `Int` or `Unit`; disconnect propagates as an `Exception` thrown
   from the coroutine and is routinely swallowed by `try { … } catch
   (_: Exception) {}` in caller loops.
3. **Stringly-typed error codes.** MQTT disconnect reasons are `UByte`
   constants. Malformed-packet detection returns a catch-all
   `MalformedPacketException`. Callers can't pattern-match on cause
   because cause is a number, not a type.

v3 addresses all three at compile time, by shifting three things:
phased-handshake **types** per layer, per-layer sealed `Result<T, E>` on
failable ops, and sealed **data** for malformed-packet and error variants.

## Scope

**In scope:**

- `HandshakingSocket` / `ConnectedSocket` split in `com.ditchoom:socket`
- `WebSocketHandshake` / `OpenWebSocket` split in `com.ditchoom:websocket`
- `MqttConnecting` / `ConnectedMqtt` split in `com.ditchoom:mqtt`
- Per-layer sealed `Result<T, E>` return type on write / send / publish /
  subscribe
- Sealed `MalformedPacket` hierarchy replacing
  `MalformedPacketException(reasonCode: Int)` in mqtt

**Explicitly out of scope:**

- `ReadBuffer.Owned` / `ReadBuffer.Borrowed` ownership types — dropped
  with rationale below
- Per-layer migration code — each repo gets a separate RFC cycle after
  this doc is approved
- `@MustBeFreed` / `@MustBeConsumed` annotations and a detekt rule for
  them — coupled to ownership types, dropped together

## The three legs

### Leg 1 — phased-handshake types, one per layer

Each layer already has an implicit handshake phase. Today it's tracked by
a state flow and readers are expected to `.first { it is Established }`.
v3 makes the transition a **type change** instead.

#### socket

```kotlin
// Before
suspend fun ClientSocket.Companion.connect(
    host: String, port: Int, tls: Boolean = false,
): ClientSocket       // can call write() on this, whether handshake finished or not

// After
suspend fun ClientSocket.Companion.connect(
    host: String, port: Int, tls: Boolean = false,
): HandshakingSocket  // cannot call write() here; only awaitConnected() or close()

interface HandshakingSocket : SuspendCloseable {
    val state: StateFlow<HandshakeProgress>  // observability, no I/O methods
    suspend fun awaitConnected(): ConnectedSocket  // throws on handshake failure
}

interface ConnectedSocket : Reader, Writer, SuspendCloseable {
    val remoteAddress: SocketAddress
    // read / write / writeGathered live here
}
```

#### websocket

```kotlin
// Before
suspend fun WebSocketClient.connect(url: String, ...): WebSocketClient

// After
suspend fun WebSocketClient.upgrade(
    socket: ConnectedSocket, path: String, headers: Map<String, String> = emptyMap(),
): WebSocketHandshake

interface WebSocketHandshake : SuspendCloseable {
    suspend fun awaitOpen(): OpenWebSocket
}

interface OpenWebSocket : SuspendCloseable {
    suspend fun send(frame: Frame): Result<Unit, WebSocketError>
    val incoming: Flow<Frame>
}
```

#### mqtt

```kotlin
// Before
suspend fun MqttClient.Companion.connect(...): MqttClient

// After
suspend fun MqttClient.Companion.connect(
    transport: OpenWebSocket | ConnectedSocket,  // sealed Transport type
    clientId: String, ...
): MqttConnecting

interface MqttConnecting : SuspendCloseable {
    suspend fun awaitConnack(): ConnectedMqtt    // throws on REJECT / timeout
}

interface ConnectedMqtt : SuspendCloseable {
    suspend fun publish(topic: String, payload: ReadBuffer, qos: Qos): Result<Unit, MqttError>
    suspend fun subscribe(filter: String, qos: Qos): Result<Flow<Message>, MqttError>
    suspend fun unsubscribe(filter: String): Result<Unit, MqttError>
}
```

### Leg 2 — per-layer sealed `Result<T, E>`

#### Why not `kotlin.Result<T>`

- Error variant is `Throwable`; you lose named error types
- No `when` exhaustiveness over error cases
- `Result.failure(IOException(...))` vs `Result.failure(SocketClosedException(...))`
  forces callers to use `is` checks, which detekt can't enforce

#### Proposed shape

One per-layer sealed class. The layer's error types live next to its
public API, not in a shared generic module.

```kotlin
// com.ditchoom.socket
sealed interface SocketError {
    data object Closed : SocketError
    data object Timeout : SocketError
    data object Cancelled : SocketError
    @JvmInline value class Reset(val cause: Throwable?) : SocketError
    @JvmInline value class Io(val cause: Throwable) : SocketError
}

// com.ditchoom.websocket
sealed interface WebSocketError {
    // two-val carriers stay data class
    data class TransportClosed(val code: Int?, val reason: String?) : WebSocketError
    @JvmInline value class ProtocolViolation(val kind: Kind) : WebSocketError
    data object CompressionError : WebSocketError
    @JvmInline value class Underlying(val socket: SocketError) : WebSocketError
}

// com.ditchoom.mqtt
sealed interface MqttError {
    @JvmInline value class ServerRejected(val reason: ConnectReasonCode) : MqttError
    @JvmInline value class Disconnected(val reason: DisconnectReasonCode?) : MqttError
    @JvmInline value class QuotaExceeded(val kind: Quota) : MqttError
    @JvmInline value class Malformed(val detail: MalformedPacket) : MqttError  // see leg 3
    // Transport is a sealed interface of ConnectedSocket | OpenWebSocket carriers
    @JvmInline value class Underlying(val transport: TransportError) : MqttError
}

// Common Result type, one file, no dependencies
sealed interface Result<out T, out E> {
    @JvmInline value class Success<out T>(val value: T) : Result<T, Nothing>
    @JvmInline value class Failure<out E>(val err: E) : Result<Nothing, E>
}

// Operations are extension functions so sealed-interface variance stays clean
inline fun <T, E, U> Result<T, E>.map(f: (T) -> U): Result<U, E> = when (this) {
    is Result.Success -> Result.Success(f(value))
    is Result.Failure -> this
}

inline fun <T, E, F> Result<T, E>.mapError(f: (E) -> F): Result<T, F> = when (this) {
    is Result.Success -> this
    is Result.Failure -> Result.Failure(f(err))
}
```

Callers now get exhaustive `when` over named error types:

```kotlin
when (val r = mqtt.publish(topic, buffer, qos = 1)) {
    is Success -> ...
    is Failure -> when (val e = r.err) {
        is ServerRejected -> ...      // data carries ConnectReasonCode
        is Disconnected -> ...        // data carries DisconnectReasonCode?
        is QuotaExceeded -> ...
        is MalformedPacket -> ...
        is Underlying -> when (e.transport) { ... }  // nested, still exhaustive
    }
}
```

### Leg 3 — sealed `MalformedPacket` hierarchy

Today mqtt's malformed-packet detection funnels through
`MalformedPacketException(reasonCode: UByte)`. Callers see a single
exception type with a numeric reason. Nothing enforces that every
detection site maps to a distinct reason, and impossible combinations
are representable (e.g., `CONNECT_WITHOUT_CLIENT_ID_IN_SESSION_EXPIRY_ABSENCE`
could be constructed with arbitrary byte positions).

```kotlin
sealed interface MalformedPacket {
    // two-val carriers keep data class
    data class RemainingLengthExceedsAvailableData(val declared: Int, val available: Int) : MalformedPacket
    data class InvalidUtf8(val position: Int, val byte: UByte) : MalformedPacket

    // single-val carriers are value classes
    @JvmInline value class ConnectWithoutClientIdInSessionExpiryAbsence(val position: Int) : MalformedPacket
    @JvmInline value class ReservedOpcode(val opcode: UByte) : MalformedPacket
    // ... one variant per detection site
}
```

Benefits:

- Each detection site constructs exactly one variant. Callers pattern-match.
- Variants can't be constructed out of nothing — they carry positional
  data that proves the diagnosis was valid.
- Adding a new detection site = adding a new variant, which is a
  compile-time prompt to every exhaustive `when`.

## End-to-end consumer walkthrough

MQTT over WebSocket over TLS, as an app would use it after v3:

```kotlin
val transport: ConnectedSocket =
    ClientSocket.allocate()
        .connect(host = "broker.example", port = 443, tls = true)
        .awaitConnected()

val ws: OpenWebSocket =
    WebSocketClient.upgrade(transport, path = "/mqtt")
        .awaitOpen()

val mqtt: ConnectedMqtt =
    MqttClient.connect(ws, clientId = "device-42", keepAlive = 30.seconds)
        .awaitConnack()

when (val r = mqtt.publish("telemetry/cpu", payload, qos = Qos.AtLeastOnce)) {
    is Success -> logger.debug("published")
    is Failure -> when (val e = r.err) {
        is Disconnected -> scheduleReconnect(e.reason)
        is Timeout -> retryWithBackoff()
        is QuotaExceeded -> backpressure(e.kind)
        is MalformedPacket -> logger.error("protocol bug", e.detail)
        is ServerRejected -> throw IllegalStateException("impossible here")
        is Underlying -> handleTransport(e.transport)
    }
}
```

Three `awaitX()` calls, one per layer. Each returns a strictly
more-capable type. Every failable call returns `Result<T, E>`; `E` is a
sealed type with named variants.

## What this does NOT fix

Honest accounting of limitations.

- **Aliasing escapes the type system.** Stash a `ConnectedSocket` in a
  `Map<K, ConnectedSocket>`, close it in one code path, call `write()` on
  the cached reference in another. The type still says `ConnectedSocket`;
  the runtime returns `Result.Failure(Closed)`. No static guarantee
  against this without a borrow checker Kotlin doesn't have.
- **Disconnect mid-call is a race.** `ConnectedSocket.write()` can be in
  flight when the peer resets. The type doesn't catch this; the
  `Result<Unit, SocketError>` return **is** the catch. Leg 1 and leg 2
  are complementary — neither is sufficient alone.
- **Reconnection is caller responsibility.** After `Result.Failure(Closed)`,
  the app re-runs the connect chain. The types don't encode a
  reconnect loop (that would require a `DisconnectedSocket` type and a
  reconnect API, which is unjustified complexity for the common case).
- **Handshake timeouts** are a caller parameter
  (`awaitConnected(timeout = ...)`) backed by coroutine timeouts.
  Exceeded timeout → handshake type is closed and throws. Not encoded
  in `Result` at this layer because there's nothing for the caller to
  do other than give up or retry.

## Explicitly dropped: `ReadBuffer.Owned` / `ReadBuffer.Borrowed`

Prior drafts of this plan proposed ownership types with a monotonic
"consumed" flag to prevent double-send and double-free. After
investigation, dropped. Rationale:

- **Kotlin has no borrow checker.** `Owned<T>` is a value class around
  an underlying buffer with a `consumed: AtomicBoolean`. The type
  system can't prevent a second call to `write(owned)` at compile time;
  it can only make the second call fail at runtime via CAS on the flag.
- **Detekt catches only trivial misuse.** Straight-line
  `val x = owned; write(x); write(x)` is catchable. Aliasing through
  a `Map<K, Owned<Buffer>>`, capturing in a closure, passing through
  two functions — all defeat the static check. The catch rate is low
  enough that the runtime behavior (the CAS) is doing the real work.
- **Runtime check is cheap to add without the type.** Callers already
  track "did I free this?" implicitly; a leak-tracking decorator
  catches the common bugs. `LeakTrackingBufferFactory` (landed in
  Phase 2) already diagnoses forgotten frees at test-session end with
  allocation stack traces. The ownership type duplicates this
  enforcement at a much higher churn cost (every `write(ReadBuffer)` in
  4 repos + downstream apps becomes `write(Owned<ReadBuffer>)`).
- **Double-send protection is better served by `Result`.** If a buffer
  really must be single-use, the failing call can return
  `Result.Failure(AlreadyConsumed)` instead of throwing. Same guarantee,
  no viral type parameter.

The leg-1 phased-handshake split and leg-2 `Result` type deliver the
compile-time value ownership types were trying to deliver, at a much
lower blast radius. Ownership types stay dropped unless a future design
cycle produces evidence they catch bugs the other legs miss.

## Migration strategy

Per-repo RFC cycle after this doc is approved. One RFC per repo,
ordered buffer → socket → websocket → mqtt (same as v2 dependency
order — each repo consumes the ones above via mavenLocal).

**buffer:** no direct changes. Depends on `Result<T, E>` living here
vs. in a new `com.ditchoom:core` module. Recommend living in
`buffer/src/commonMain/kotlin/com/ditchoom/data/Result.kt` since
`Reader` / `Writer` already live under `com.ditchoom.data`.

**socket:** single RFC. Shape:
- Phase A: introduce `HandshakingSocket` / `ConnectedSocket` alongside
  existing `ClientSocket`. `ClientSocket` becomes a type alias or
  thin wrapper during transition.
- Phase B: migrate `connect()` to return `HandshakingSocket`.
  `Result<Unit, SocketError>` on `write()`. Callers in-tree updated.
- Phase C: remove the compatibility alias. Major version bump.

**websocket:** same shape. `WebSocketHandshake` / `OpenWebSocket`,
mqtt as the only in-tree consumer to migrate.

**mqtt:** same shape. Also folds in the sealed `MalformedPacket`
migration (leg 3). Biggest consumer blast radius —
`mqtt-android-service-example-app` and other downstream apps get the
first-wave migration notes.

Each RFC is sized so it can be reviewed and landed independently. No
flag-guarded half-transitions.

## Review gate

- **What this doc is:** the sign-off point for *direction*.
- **What approval means:** the three legs are the v3 contract across
  four repos; per-repo RFCs can start.
- **What approval does not mean:** implementation begins immediately.
  Per the v2 stabilization plan, Phase 4 is gated behind Phase 3
  shipping (`socket 3.2.0` + full quiche matrix + `runBlocking` →
  `runTest` migration). If Phase 3 is still open when this doc is
  approved, the first per-repo RFC waits.
- **Review scope:** shape and naming of types, `Result<T, E>` vs
  alternatives, the explicitly-dropped items, per-layer migration
  ordering. Consumer-facing API sketches in this doc are illustrative;
  details tune in the per-repo RFCs.

## Decisions recorded

- **Pervasive split.** Every layer gets its own Handshaking/Connected
  (or equivalent) pair. Socket-only split was considered and rejected:
  the mqtt blast radius is where the compile-time win lives.
- **Per-layer sealed `Result<T, E>`, not `kotlin.Result`.** Named
  variants + exhaustive `when` beats `Throwable`-typed errors.
- **`sealed interface` over `sealed class`.** All closed hierarchies use
  `sealed interface` — no constructor ceremony, cleaner `: Foo` syntax
  at each variant site, identical exhaustive-`when` guarantees.
- **`@JvmInline value class` for single-val wrappers.** Every variant
  that wraps exactly one `val` is a value class. Variants carrying two
  or more fields stay `data class`. Goal: no boxing on hot paths; the
  newtype pattern is signaled at the declaration.
- **Ownership types dropped.** Runtime CAS doesn't justify viral type
  parameters when `LeakTrackingBufferFactory` + `Result<Unit, Error>`
  deliver the same bugs caught.
- **`Transport` is a sealed type in mqtt.** `ConnectedSocket` and
  `OpenWebSocket` are both valid transports; mqtt's `connect(transport,
  ...)` accepts either. No adapter indirection.
- **Handshake types are `SuspendCloseable`.** Bailing out of a pending
  handshake closes the underlying transport.

## Out of scope for v3

- Reconnection as a typed operation (`DisconnectedSocket.reconnect()`)
  — caller runs the chain again; deferred.
- `QuicEngine` split — QUIC already has `QuicConnectionState.{Handshaking,
  Established, Closed}` sealed. The existing shape is closer to v3
  than socket/websocket/mqtt are. QUIC's public API gets the `Result`
  treatment but not a new Handshaking/Connected pair; its stream API
  stays flow-shaped.
- Browser WebTransport — separate engine, future work.
- Windows support — out of scope across all DitchOoM repos.
