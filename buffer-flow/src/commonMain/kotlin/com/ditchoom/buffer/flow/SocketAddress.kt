package com.ditchoom.buffer.flow

/** The IP address family of a [SocketAddress]. */
@ExperimentalDatagramApi
enum class AddressFamily {
    IPv4,
    IPv6,
}

/**
 * A **resolved** network endpoint that **owns its platform representation** from construction.
 *
 * This is the make-or-break type of the datagram substrate. Three pulls must hold at once:
 *
 * 1. **Inspectable** — [host] / [port] / [family] are readable for ICE candidate gathering and
 *    logging.
 * 2. **Zero-alloc handoff to a native engine** — a platform actual (quiche's `send_info.to` /
 *    `recv_info.from` FFI) reads the backing `sockaddr` pointer without re-resolving, via a
 *    platform SPI extension (e.g. `internal fun SocketAddress.nativeSockAddr()`), not part of this
 *    common surface.
 * 3. **Zero-alloc reuse as a send target across MANY distinct peers** — `send(payload, to)` on a
 *    relay fanning out to N peers must not allocate, even when `to` differs every call. Because a
 *    [SocketAddress] carries its resolved form (a pinned `sockaddr` on native, an interned
 *    `InetSocketAddress` on JVM) from construction, the send path is a pointer/field read, never a
 *    resolve-and-pin. This deletes the `PathKey → InetSocketAddress` reconstruction that today's
 *    1-entry `lastDest` cache exists to amortize.
 *
 * **Resolved-only (decision §10.1):** DNS happens once, out of band, via [Companion.resolve] — the
 * sole suspending entry point — or [Companion.ofLiteral] for numeric IPs. A [send][DatagramSink.send]
 * therefore never suspends on resolution and stays zero-alloc; ICE gathers pre-resolved candidates.
 *
 * **Value semantics:** implementations must be usable as a map key (a demux routing table keys by
 * peer), so [equals] / [hashCode] compare the resolved endpoint, not object identity.
 */
@ExperimentalDatagramApi
interface SocketAddress {
    /** The address as text — an IP literal (numeric), inspectable for ICE and logging. */
    val host: String

    /** The UDP port. */
    val port: Int

    /** The IP address family. */
    val family: AddressFamily

    @ExperimentalDatagramApi
    companion object {
        /**
         * Wrap an already-numeric [ip]:[port] with **no DNS** — the ICE / literal-IP fast path.
         *
         * @throws IllegalArgumentException if [ip] is not a valid IPv4 or IPv6 literal, or [port]
         *   is out of range.
         */
        fun ofLiteral(
            ip: String,
            port: Int,
        ): SocketAddress = LiteralSocketAddress.parse(ip, port)

        /**
         * Resolve [host]:[port] to a [SocketAddress]. The **only** suspending / DNS entry point.
         *
         * Numeric literals resolve synchronously here (delegating to [ofLiteral]); real hostname
         * resolution is provided by a platform resolver installed by `:socket-udp`. Until one is
         * installed, resolving a non-literal host throws — buffer-flow itself performs no I/O.
         */
        suspend fun resolve(
            host: String,
            port: Int,
        ): SocketAddress = resolver.resolve(host, port)

        /**
         * Install the platform hostname [resolver] that backs [resolve]. `:socket-udp` calls this once
         * with a DNS resolver for its platform; until then [resolve] handles numeric literals only and
         * throws for hostnames (buffer-flow performs no I/O). Last install wins; idempotent to re-install
         * the same resolver.
         */
        fun installResolver(resolver: SocketAddressResolver) {
            this.resolver = resolver
        }

        /**
         * Pluggable hostname resolver. Defaults to a literal-only resolver; `:socket-udp` installs a
         * platform DNS resolver via [installResolver]. The backing field is internal so it is not part of
         * the locked public/ABI surface — [installResolver] is the only public mutator.
         */
        internal var resolver: SocketAddressResolver = LiteralOnlyResolver
    }
}

/**
 * Resolves a host/port to a [SocketAddress]. Implemented and installed by the platform socket module
 * (`:socket-udp`) via [SocketAddress.installResolver]; buffer-flow ships only [LiteralOnlyResolver].
 */
@ExperimentalDatagramApi
fun interface SocketAddressResolver {
    suspend fun resolve(
        host: String,
        port: Int,
    ): SocketAddress
}

/** The default resolver: numeric literals only. Real DNS arrives with `:socket-udp`. */
@ExperimentalDatagramApi
internal object LiteralOnlyResolver : SocketAddressResolver {
    override suspend fun resolve(
        host: String,
        port: Int,
    ): SocketAddress =
        LiteralSocketAddress.parseOrNull(host, port)
            ?: throw UnsupportedOperationException(
                "buffer-flow resolves only numeric IP literals; install a DNS resolver via :socket-udp to " +
                    "resolve host '$host'.",
            )
}

/**
 * The common, platform-independent [SocketAddress]: a normalized IP literal that owns its resolved
 * byte form. Reusing it as a send target is a field read (zero-alloc). Platform actuals (`:socket-udp`)
 * supply subtypes that additionally carry a pinned `sockaddr` / interned `InetSocketAddress` exposed
 * through a platform SPI; this one is sufficient for the in-memory channel, the conformance suite, and
 * [DatagramMux] routing.
 */
@ExperimentalDatagramApi
internal class LiteralSocketAddress private constructor(
    override val host: String,
    override val port: Int,
    override val family: AddressFamily,
    /**
     * Normalized address, packed into two longs (IPv4 → low 32 bits of [lo], [hi]=0; IPv6 → 16
     * bytes across [hi]+[lo]) — the equality/hash basis. Primitive storage keeps reuse as a send
     * target zero-alloc and mirrors the existing quiche `PathKey` (family, hi, lo) encoding.
     */
    private val hi: Long,
    private val lo: Long,
) : SocketAddress {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LiteralSocketAddress) return false
        return port == other.port && family == other.family && hi == other.hi && lo == other.lo
    }

    override fun hashCode(): Int {
        var h = (hi xor lo).hashCode()
        h = 31 * h + port
        h = 31 * h + family.ordinal
        return h
    }

    override fun toString(): String = if (family == AddressFamily.IPv6) "[$host]:$port" else "$host:$port"

    companion object {
        fun parse(
            ip: String,
            port: Int,
        ): SocketAddress =
            parseOrNull(ip, port)
                ?: throw IllegalArgumentException("Not a valid IP literal: '$ip'")

        fun parseOrNull(
            ip: String,
            port: Int,
        ): SocketAddress? {
            require(port in 0..PORT_MAX) { "port out of range: $port" }
            parseIpv4(ip)?.let { return LiteralSocketAddress(ip, port, AddressFamily.IPv4, 0L, packBytes(it, 0)) }
            return parseIpv6(ip)?.let {
                val hi = packBytes(it, 0)
                val lo = packBytes(it, IPV6_LOW_HALF_OFFSET)
                LiteralSocketAddress(ip, port, AddressFamily.IPv6, hi, lo)
            }
        }
    }
}

// ── IP-literal parsing (file-private) ─────────────────────────────────────────────────────────────
// Pure String→bytes helpers, kept at file scope so each is its own small, independently analyzable
// function (the parser was one 26-branch method before). No I/O, no experimental types.

/** Highest valid UDP port. */
private const val PORT_MAX = 65535

/** Bytes in an IPv4 address / a dotted-quad's group count. */
private const val IPV4_BYTE_COUNT = 4

/** Bytes in an IPv6 address. */
private const val IPV6_BYTE_COUNT = 16

/** 16-bit groups (hextets) in an IPv6 address. */
private const val IPV6_WORD_COUNT = 8

/** Byte offset of an IPv6 address's low 8 bytes (packed into the `lo` long). */
private const val IPV6_LOW_HALF_OFFSET = 8

/** Bytes packed into a single long by [packBytes]. */
private const val LONG_PACK_BYTES = 8

/** Bits in a byte — the shift between a byte and its place in a word/long. */
private const val BITS_PER_BYTE = 8

/** Bytes per 16-bit IPv6 word. */
private const val BYTES_PER_WORD = 2

/** Low-8-bits mask. */
private const val BYTE_MASK = 0xff

/** Largest value of an IPv4 octet. */
private const val OCTET_MAX = 255

/** Maximum decimal digits in an IPv4 octet. */
private const val MAX_OCTET_DIGITS = 3

/** Maximum hex digits in an IPv6 group. */
private const val MAX_HEX_GROUP_DIGITS = 4

/** Radix of an IPv6 hex group. */
private const val HEX_RADIX = 16

/** The valid characters of an IPv6 hex group. */
private const val HEX_DIGITS = "0123456789abcdefABCDEF"

/** Pack up to [LONG_PACK_BYTES] bytes starting at [offset] (big-endian) into a long; IPv4 uses 4. */
private fun packBytes(
    bytes: ByteArray,
    offset: Int,
): Long {
    val n = if (bytes.size == IPV4_BYTE_COUNT) IPV4_BYTE_COUNT else LONG_PACK_BYTES
    var v = 0L
    for (i in 0 until n) {
        v = (v shl BITS_PER_BYTE) or (bytes[offset + i].toLong() and BYTE_MASK.toLong())
    }
    return v
}

/** Parse dotted-quad IPv4 into 4 bytes, or null if not a valid IPv4 literal. */
private fun parseIpv4(s: String): ByteArray? {
    val parts = s.split('.')
    if (parts.size != IPV4_BYTE_COUNT) return null
    val octets = parts.mapNotNull { parseOctet(it) }
    return if (octets.size == IPV4_BYTE_COUNT) ByteArray(IPV4_BYTE_COUNT) { octets[it].toByte() } else null
}

/** Parse a single decimal IPv4 octet (0..255, no more than 3 digits), or null if invalid. */
private fun parseOctet(p: String): Int? {
    if (p.isEmpty() || p.length > MAX_OCTET_DIGITS || p.any { it !in '0'..'9' }) return null
    val v = p.toIntOrNull()
    return if (v == null || v > OCTET_MAX) null else v
}

/**
 * Parse an IPv6 literal (with `::` compression and optional embedded IPv4 tail) into 16 bytes, or
 * null if not a valid IPv6 literal. Zone ids (`%eth0`) are stripped.
 */
private fun parseIpv6(raw: String): ByteArray? {
    val s = raw.substringBefore('%')
    val doubleColon = s.indexOf("::")
    // Must contain ':' and at most one "::".
    val malformed = !s.contains(':') || (doubleColon >= 0 && s.indexOf("::", doubleColon + 1) >= 0)
    if (malformed) return null
    val head = if (doubleColon >= 0) s.substring(0, doubleColon) else s
    val tail = if (doubleColon >= 0) s.substring(doubleColon + BYTES_PER_WORD) else ""
    val words =
        parseHexGroups(head)?.let { headWords ->
            parseHexGroups(tail)?.let { tailWords -> combineIpv6Words(headWords, tailWords, doubleColon >= 0) }
        }
    return words?.toIpv6Bytes()
}

/**
 * Parse the colon-separated groups of one IPv6 half (before/after `::`) into 16-bit words. An empty
 * part yields no words; an empty group (e.g. a stray `:`) or any invalid group yields null.
 */
private fun parseHexGroups(part: String): List<Int>? {
    if (part.isEmpty()) return emptyList()
    val groups = part.split(':')
    return if (groups.any { it.isEmpty() }) {
        null
    } else {
        val parsed = groups.mapIndexed { idx, g -> parseIpv6Group(g, isLast = idx == groups.size - 1) }
        if (parsed.any { it == null }) null else parsed.filterNotNull().flatten()
    }
}

/**
 * Parse one IPv6 group into its 16-bit word(s): a hex group is one word; a dotted-quad in the last
 * group (e.g. `::ffff:1.2.3.4`) is two words. Null if the group is invalid.
 */
private fun parseIpv6Group(
    g: String,
    isLast: Boolean,
): List<Int>? =
    when {
        isLast && g.contains('.') ->
            parseIpv4(g)?.toList()?.chunked(BYTES_PER_WORD)?.map { bytesToWord(it[0], it[1]) }
        g.length > MAX_HEX_GROUP_DIGITS || g.any { it !in HEX_DIGITS } -> null
        else -> listOf(g.toInt(HEX_RADIX))
    }

/** Combine a big-endian byte pair into a 16-bit word. */
private fun bytesToWord(
    hi: Byte,
    lo: Byte,
): Int = ((hi.toInt() and BYTE_MASK) shl BITS_PER_BYTE) or (lo.toInt() and BYTE_MASK)

/**
 * Assemble the final 8 IPv6 words from the head/tail halves. When [compressed] (a `::` was present),
 * splice the missing zero-words between them; otherwise require exactly 8 words and no tail. Null if
 * the group count can't form a valid address.
 */
private fun combineIpv6Words(
    head: List<Int>,
    tail: List<Int>,
    compressed: Boolean,
): List<Int>? {
    if (!compressed) {
        return if (head.size == IPV6_WORD_COUNT && tail.isEmpty()) head else null
    }
    val zeros = IPV6_WORD_COUNT - head.size - tail.size
    return if (zeros < 0) null else head + List(zeros) { 0 } + tail
}

/** Serialize exactly [IPV6_WORD_COUNT] words (big-endian) into 16 bytes. */
private fun List<Int>.toIpv6Bytes(): ByteArray {
    val out = ByteArray(IPV6_BYTE_COUNT)
    for (i in indices) {
        out[i * BYTES_PER_WORD] = (this[i] ushr BITS_PER_BYTE).toByte()
        out[i * BYTES_PER_WORD + 1] = this[i].toByte()
    }
    return out
}
