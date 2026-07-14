package com.ditchoom.buffer.flow

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer

// File-level so the [Ecn] entry constructors can reference them (an enum's entries are initialized
// before its companion object, so companion consts are off-limits in entry arguments).

/** Congestion Experienced (11) — the only in-range ECN codepoint above the ignore-listed 0/1/2. */
private const val CE_CODEPOINT = 3

/** Read-side sentinel codepoint for an unreported ECN value. */
private const val UNKNOWN_CODEPOINT = -1

/** Mask selecting the ECN field — the low 2 bits — of a TOS / Traffic-Class octet. */
private const val ECN_FIELD_MASK = 0x3

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
 * One received datagram: a zero-copy [payload] plus who sent it plus the read-side control plane.
 *
 * **Ownership:** [payload] transfers to the caller exactly like a [ReadResult.Data] buffer — release
 * or pool it as usual. Each [DatagramSource.receive] returns exactly one whole message; datagram
 * boundaries are never dissolved (contrast the byte-stream shape, where reads are an unframed river).
 *
 * Control-plane read fields ([ecn], [localAddress], [hopLimit]) carry the §7.2 **sentinels** when the
 * platform cannot report them ([Ecn.Unknown], `null`, `-1`) — consumers query, never assume. Whether a
 * field is *ever* populated is advertised by [DatagramSource.capabilities].
 */
@ExperimentalDatagramApi
class Datagram(
    /** The message bytes; ownership transfers to the caller. */
    val payload: PlatformBuffer,
    /** The source endpoint. For a connected source this is the fixed peer. */
    val peer: SocketAddress,
    /** Received ECN codepoint, or [Ecn.Unknown] if the platform cannot report it. */
    val ecn: Ecn = Ecn.Unknown,
    /** Which local IP received this datagram (IP_PKTINFO), or `null` if unavailable. */
    val localAddress: SocketAddress? = null,
    /** Received TTL / hop limit, or `-1` if unavailable. */
    val hopLimit: Int = -1,
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
 * The unified result of a datagram read — the §2.1 datagram analogue of [ReadResult].
 *
 * [Received] carries the whole [Datagram]; [Closed] signals the source will yield no more datagrams
 * (the socket/connection ended), optionally with a structured [Closed.reason] the consumer can
 * downcast (e.g. a QUIC error). This is the one result type that replaces both raw UDP's ad-hoc
 * signalling and QUIC's hand-rolled `DatagramReceiveResult (Received | ConnectionClosed)`.
 */
@ExperimentalDatagramApi
sealed interface DatagramReadResult {
    /** A datagram was received. */
    @ExperimentalDatagramApi
    class Received(
        val datagram: Datagram,
    ) : DatagramReadResult

    /** The source is closed and will produce no further datagrams. [reason] is optional structured detail. */
    @ExperimentalDatagramApi
    class Closed(
        val reason: Any? = null,
    ) : DatagramReadResult
}

/**
 * An outbound datagram for [DatagramSink.sendBatch]: payload, optional destination (`null` on a
 * connected sink), and its control plane. Mirrors the arguments of [DatagramSink.send].
 */
@ExperimentalDatagramApi
class OutboundDatagram(
    val payload: ReadBuffer,
    val to: SocketAddress? = null,
    val options: DatagramSendOptions = DatagramSendOptions.Default,
)

/**
 * A decoded message tagged with the peer that sent it — the result element of
 * [DatagramSource.typedAddressed]. Exactly what an SFU/ICE stack wants: `Addressed<StunMessage>`,
 * `Addressed<RtpPacket>`.
 */
@ExperimentalDatagramApi
class Addressed<out T>(
    val value: T,
    val peer: SocketAddress,
)
