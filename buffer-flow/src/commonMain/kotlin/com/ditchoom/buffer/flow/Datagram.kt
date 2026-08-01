package com.ditchoom.buffer.flow

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import kotlin.jvm.JvmInline

// File-level so the [Ecn] entry constructors can reference them (an enum's entries are initialized
// before its companion object, so companion consts are off-limits in entry arguments).

/** Congestion Experienced (11) — the only in-range ECN codepoint above the ignore-listed 0/1/2. */
private const val CE_CODEPOINT = 3

/** Read-side sentinel codepoint for an unreported ECN value. */
private const val UNKNOWN_CODEPOINT = -1

/** Mask selecting the ECN field — the low 2 bits — of a TOS / Traffic-Class octet. */
private const val ECN_FIELD_MASK = 0x3

/** Read-side absent-state raw encoding for [HopLimit] (kept private; the type is the contract). */
private const val HOP_LIMIT_UNKNOWN_RAW = -1

/** Largest value of a TTL / Hop Limit octet. */
private const val HOP_LIMIT_MAX = 255

/**
 * The ECN (Explicit Congestion Notification) codepoint of a datagram — RFC 3168 / RFC 9331 (L4S).
 *
 * The two low bits of the IP TOS / Traffic Class octet. On the read side [Unknown] is the sentinel
 * for a platform that cannot report the received codepoint (per the §7.2 degradation policy); it is
 * never a valid *send* value.
 */
@ExperimentalDatagramApi
enum class Ecn(
    val codepoint: Int,
) {
    /** Not ECN-Capable Transport (00). */
    NotEct(0),

    /** ECN-Capable Transport, ECT(1) (01). */
    Ect1(1),

    /** ECN-Capable Transport, ECT(0) (10). */
    Ect0(2),

    /** Congestion Experienced (11). */
    Ce(CE_CODEPOINT),

    /** The received codepoint is unavailable on this platform (read-side sentinel only). */
    Unknown(UNKNOWN_CODEPOINT),
    ;

    @ExperimentalDatagramApi
    companion object {
        /** Map the low 2 bits of a TOS/TClass octet to an [Ecn]; [Unknown] for out-of-range input. */
        fun fromCodepoint(value: Int): Ecn {
            val field = value and ECN_FIELD_MASK
            return entries.firstOrNull { it.codepoint == field } ?: Unknown
        }
    }
}

/**
 * A received TTL / hop limit (RFC 791 §3.1 TTL / RFC 8200 §3 Hop Limit), or the typed absent state
 * [Unknown] when the platform cannot report it (the §7.2 degradation policy — availability is
 * advertised by [DatagramCapabilities.hopLimitReceive]).
 *
 * Zero-alloc by construction: `@JvmInline` over the raw octet, stored as a bare `Int` field in
 * [Datagram]. Never use `HopLimit?` or put it in a generic position — that boxes.
 */
@ExperimentalDatagramApi
@JvmInline
value class HopLimit private constructor(
    private val raw: Int,
) {
    /** Whether the platform reported a hop limit for this datagram. */
    val isKnown: Boolean get() = raw != HOP_LIMIT_UNKNOWN_RAW

    /** The reported hop limit (0..255). @throws IllegalStateException when [isKnown] is false. */
    val value: Int
        get() {
            check(isKnown) { "hop limit was not reported by the platform" }
            return raw
        }

    @ExperimentalDatagramApi
    companion object {
        /** The platform did not report a hop limit — the §7.2 typed absent state. */
        val Unknown: HopLimit = HopLimit(HOP_LIMIT_UNKNOWN_RAW)

        /** A reported hop limit; [value] must be a valid octet (0..255). */
        fun of(value: Int): HopLimit {
            require(value in 0..HOP_LIMIT_MAX) { "hop limit must be 0..255: $value" }
            return HopLimit(value)
        }
    }
}

/**
 * A maybe-known local IP endpoint — the typed absent state that replaces `SocketAddress?` on the
 * read side. Two uses:
 * - per-datagram [Datagram.localAddress]: which local IP received the packet (IP_PKTINFO), absent when
 *   the platform cannot report it ([DatagramCapabilities.localAddressReceive], §7.2);
 * - per-endpoint [ConnectedDatagramSource.localAddress]: absent when the transport does not surface
 *   its local UDP endpoint at all (a QUIC datagram flow).
 *
 * Zero-alloc by construction: `@JvmInline` over the nullable reference, stored as a bare
 * `SocketAddress` reference field. Never use `LocalAddress?` or a generic position — that boxes
 * (nullable-of-value-class-over-nullable-underlying cannot stay flat). One more boxing position is
 * easy to miss: the **default-arguments bridge** of a constructor that defaults a `LocalAddress`
 * parameter routes it through a transient box (HopLimit's `Int` underlying stays flat there;
 * a nullable underlying cannot). HotSpot's escape analysis eliminates it, but JS and Native make no
 * such promise — so hot-path [Datagram] constructions pass every argument explicitly rather than
 * leaning on defaults.
 */
@ExperimentalDatagramApi
@JvmInline
value class LocalAddress private constructor(
    private val ref: SocketAddress?,
) {
    /** Whether the local address is known. */
    val isKnown: Boolean get() = ref != null

    /** The known local address, or `null` — the single explicit escape hatch to nullable-land. */
    fun orNull(): SocketAddress? = ref

    @ExperimentalDatagramApi
    companion object {
        /** The local address is not known / not surfaced — the §7.2 typed absent state. */
        val Unknown: LocalAddress = LocalAddress(null)

        /** A known local address. */
        fun of(address: SocketAddress): LocalAddress = LocalAddress(address)
    }
}

/**
 * One received datagram: a zero-copy [payload] plus who sent it plus the read-side control plane.
 *
 * **Ownership:** [payload] transfers to the caller exactly like a [ReadResult.Data] buffer — release
 * or pool it as usual. Each [DatagramSource.receive] returns exactly one whole message; datagram
 * boundaries are never dissolved (contrast the byte-stream shape, where reads are an unframed river).
 *
 * Control-plane read fields ([ecn], [localAddress], [hopLimit]) carry the §7.2 **typed absent states**
 * ([Ecn.Unknown], [LocalAddress.Unknown], [HopLimit.Unknown]) when the platform cannot report them —
 * consumers query [HopLimit.isKnown] / [LocalAddress.isKnown], never assume. Whether a field is *ever*
 * populated is advertised by [DatagramSource.capabilities]. The absent states are flat (bare `Int` /
 * bare reference fields): a received datagram still costs exactly one allocation — provided the
 * receive path passes all five constructor arguments explicitly; a defaulted [localAddress] rides
 * the default-arguments bridge, which boxes it transiently (see [LocalAddress]'s KDoc).
 */
@ExperimentalDatagramApi
class Datagram(
    /** The message bytes; ownership transfers to the caller. */
    val payload: PlatformBuffer,
    /** The source endpoint. On a connected source this equals [ConnectedDatagramSource.peer]. */
    val peer: SocketAddress,
    /** Received ECN codepoint, or [Ecn.Unknown] if the platform cannot report it. */
    val ecn: Ecn = Ecn.Unknown,
    /** Which local IP received this datagram (IP_PKTINFO), or [LocalAddress.Unknown]. */
    val localAddress: LocalAddress = LocalAddress.Unknown,
    /** Received TTL / hop limit, or [HopLimit.Unknown]. */
    val hopLimit: HopLimit = HopLimit.Unknown,
)

/**
 * The send-side control plane. Every field defaults to "unset / OS default". A field's real effect
 * is bounded by [DatagramSink.capabilities]: an **advisory** cap the platform lacks ([dscp],
 * [hopLimit]) is a documented no-op; a **correctness-critical** cap it lacks ([dontFragment]) is
 * advertised absent and never silently no-op'd (see §7.2). Reuse a single instance across sends to
 * stay zero-alloc; [Default] is the shared "everything unset" value.
 */
@ExperimentalDatagramApi
class DatagramSendOptions(
    /** ECN codepoint to stamp on the outgoing datagram. [Ecn.Unknown] means "leave OS default". */
    val ecn: Ecn = Ecn.Unknown,
    /** DiffServ codepoint (0..63, the upper 6 TOS bits); `-1` leaves the OS default. Advisory. */
    val dscp: Int = -1,
    /** Set the IP Don't-Fragment bit (quiche sets this for PMTU). Correctness-critical. */
    val dontFragment: Boolean = false,
    /** TTL / hop limit; `-1` leaves the OS default. Advisory. */
    val hopLimit: Int = -1,
    /** Pin the source local IP (IP_PKTINFO) for a multi-homed reply, or `null` for OS routing. */
    val fromLocal: SocketAddress? = null,
) {
    @ExperimentalDatagramApi
    companion object {
        /** The shared "everything unset / OS default" options — the zero-alloc default send path. */
        val Default: DatagramSendOptions = DatagramSendOptions()
    }
}

/**
 * Why a datagram source stopped producing — the typed, non-null [DatagramReadResult.Closed.reason].
 *
 * **Deliberately an open interface, not sealed:** downstream transports participate by supertyping —
 * e.g. `:socket-quic` declares `sealed interface QuicError : DatagramCloseReason`, so a QUIC datagram
 * flow's close reason IS the connection's structured close error and `reason as? QuicError` is the
 * downcast. buffer-flow never needs to know the downstream taxonomy; it only ships the two generic
 * arms every raw-socket implementation needs.
 */
@ExperimentalDatagramApi
interface DatagramCloseReason {
    /**
     * Orderly shutdown: the endpoint was closed by its owner or drained after a local close.
     * Carries no error. The default reason — a bare `Closed()` means exactly this.
     */
    @ExperimentalDatagramApi
    object Normal : DatagramCloseReason {
        override fun toString(): String = "Normal"
    }

    /**
     * The OS reported a terminal error identified by a raw platform code — e.g. a negative errno
     * from `recvfrom`, or an io_uring CQE `res` (-EBADF / -ECANCELED). Terminal path, once per
     * socket, so the allocation is irrelevant.
     */
    @ExperimentalDatagramApi
    class OsError(
        val code: Int,
    ) : DatagramCloseReason {
        override fun equals(other: Any?): Boolean = other is OsError && other.code == code

        override fun hashCode(): Int = code

        override fun toString(): String = "OsError(code=$code)"
    }
}

/**
 * The unified result of a datagram read — the §2.1 datagram analogue of [ReadResult].
 *
 * [Received] carries the whole [Datagram]; [Closed] signals the source will yield no more datagrams
 * (the socket/connection ended), always with a typed [Closed.reason] the consumer can downcast
 * (e.g. a QUIC error). This is the one result type that replaces both raw UDP's ad-hoc
 * signalling and QUIC's hand-rolled `DatagramReceiveResult (Received | ConnectionClosed)`.
 */
@ExperimentalDatagramApi
sealed interface DatagramReadResult {
    /** A datagram was received. */
    @ExperimentalDatagramApi
    class Received(
        val datagram: Datagram,
    ) : DatagramReadResult

    /**
     * The source is closed and will produce no further datagrams. [reason] is always non-null and
     * typed: [DatagramCloseReason.Normal] for an orderly close (the default — bare `Closed()` stays
     * source-compatible), [DatagramCloseReason.OsError] for a raw platform failure, or a downstream
     * taxonomy arm (e.g. a QUIC error) via [DatagramCloseReason] supertyping. Implementations may
     * cache their terminal instance; repeated post-close receives may return the same object.
     */
    @ExperimentalDatagramApi
    class Closed(
        val reason: DatagramCloseReason = DatagramCloseReason.Normal,
    ) : DatagramReadResult
}

/**
 * An outbound datagram for [AddressedDatagramSink.sendBatch]: payload, its REQUIRED destination, and
 * its control plane. Mirrors the arguments of [AddressedDatagramSink.send]; a connected batch has no
 * per-element type at all (see [ConnectedDatagramSink.sendBatch]) because a fixed-peer batch has
 * nothing per-element left but the payload.
 */
@ExperimentalDatagramApi
class OutboundDatagram(
    val payload: ReadBuffer,
    val to: SocketAddress,
    val options: DatagramSendOptions = DatagramSendOptions.Default,
)

/**
 * A decoded message tagged with the peer that sent it — the result element of
 * [AddressedDatagramSource.typedAddressed]. Exactly what an SFU/ICE stack wants: `Addressed<StunMessage>`,
 * `Addressed<RtpPacket>`.
 */
@ExperimentalDatagramApi
class Addressed<out T>(
    val value: T,
    val peer: SocketAddress,
)
