package com.ditchoom.buffer.flow

/**
 * The control-plane capabilities a datagram endpoint actually supports on its platform.
 *
 * Decision §10.2: the full control-plane **surface** ships everywhere ([Datagram] read fields,
 * [DatagramSendOptions] send fields), but each field is implemented to the platform's real ceiling
 * and the ceiling is advertised here. Consumers (quiche, ICE) read this **once in commonMain and
 * branch once** — the same code auto-optimizes per platform because the actual's capability set
 * differs. There is no per-platform consumer code.
 *
 * Each flag's absence degrades to a **correct** mode (§7.2):
 * - **Advisory** ([dscpSend], [hopLimitSend]) absent → the send field is a documented no-op
 *   (unmarked traffic), never wrong bytes.
 * - **Correctness-critical** ([dontFragment]) absent → advertised absent here, **never silently
 *   no-op'd**; quiche only grows datagram size past the 1200-byte floor when DF is truly present.
 * - **Read fields** ([ecnReceive], [hopLimitReceive], [localAddressReceive]) absent → the
 *   [Datagram] carries the sentinel ([Ecn.Unknown] / `-1` / `null`).
 * - [localAddressReceive] / [sourceAddressSelect] (IP_PKTINFO) absence is the only *functional*
 *   limit, and only for a multi-homed single-socket server; single-homed servers and ICE
 *   (socket-per-candidate) don't need it, and high-scale relays deploy where it exists.
 *
 * The empirically-measured platform values (used to seed platform actuals in `:socket-udp`) are:
 *
 * | Feature | Linux native | JVM/Android NIO | Apple POSIX srv | Apple NW client | Node dgram |
 * |---|---|---|---|---|---|
 * | ecnSend | ✅ | ✅ (IP_TOS, unmasked) | ✅ | ❌ | ❌ |
 * | ecnReceive | ✅ | ❌ | ✅ | ❌ | ❌ |
 * | dscpSend | ✅ | ✅ | ✅ | ⚠️ svc-class | ❌ |
 * | dontFragment | ✅ (IP_MTU_DISCOVER v4 / IPV6_DONTFRAG v6) | ✅ (JDK 19+) | ✅ | ❌ | ❌ |
 * | hopLimitSend | ✅ | ❌ (only multicast TTL) | ✅ | ⚠️ | ✅ (setTTL) |
 * | hopLimitReceive | ✅ | ❌ | ✅ | ❌ | ❌ |
 * | localAddressReceive | ✅ | ❌ | ⚠️/✅ | ❌ | ❌ |
 * | sourceAddressSelect | ✅ | ❌ | ⚠️ | ❌ | ❌ |
 */
@ExperimentalDatagramApi
class DatagramCapabilities(
    /** Can stamp an ECN codepoint on outgoing datagrams ([DatagramSendOptions.ecn]). */
    val ecnSend: Boolean = false,
    /** Can report the received ECN codepoint ([Datagram.ecn]). */
    val ecnReceive: Boolean = false,
    /** Can set the DiffServ codepoint on outgoing datagrams ([DatagramSendOptions.dscp]). Advisory. */
    val dscpSend: Boolean = false,
    /**
     * Can set the IP Don't-Fragment bit ([DatagramSendOptions.dontFragment]). **Correctness-critical:**
     * when `false`, a consumer must assume DF is unavailable and hold a conservative MTU floor.
     */
    val dontFragment: Boolean = false,
    /** Can set the outgoing TTL / hop limit ([DatagramSendOptions.hopLimit]). Advisory. */
    val hopLimitSend: Boolean = false,
    /** Can report the received TTL / hop limit ([Datagram.hopLimit]). */
    val hopLimitReceive: Boolean = false,
    /** Can report which local IP received a datagram ([Datagram.localAddress], IP_PKTINFO). */
    val localAddressReceive: Boolean = false,
    /** Can pin the source local IP per send ([DatagramSendOptions.fromLocal], IP_PKTINFO). */
    val sourceAddressSelect: Boolean = false,
    /**
     * Multicast join/leave is available. Decision §10.3 is **design-for, defer impl** — no signature
     * precludes multicast, but the shipped default is `false` until a later additive minor.
     */
    val multicast: Boolean = false,
) {
    @ExperimentalDatagramApi
    companion object {
        /**
         * No control-plane capabilities — the conservative default. A minimal or in-memory endpoint
         * advertises this; every control-plane read yields a sentinel and every advisory send field
         * is a no-op. Correct on every platform.
         */
        val None: DatagramCapabilities = DatagramCapabilities()
    }
}
