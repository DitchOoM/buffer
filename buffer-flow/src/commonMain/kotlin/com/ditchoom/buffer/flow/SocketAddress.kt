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
         * Pluggable hostname resolver. Defaults to a literal-only resolver; `:socket-udp` installs a
         * platform DNS resolver. Internal so it is not part of the locked public/ABI surface.
         */
        internal var resolver: SocketAddressResolver = LiteralOnlyResolver
    }
}

/**
 * Resolves a host/port to a [SocketAddress]. Installed by the platform socket module (`:socket-udp`);
 * buffer-flow ships only [LiteralOnlyResolver].
 */
@ExperimentalDatagramApi
internal fun interface SocketAddressResolver {
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
            if (port < 0 || port > 65535) throw IllegalArgumentException("port out of range: $port")
            parseIpv4(ip)?.let { return LiteralSocketAddress(ip, port, AddressFamily.IPv4, 0L, packLo(it, 0)) }
            parseIpv6(ip)?.let {
                return LiteralSocketAddress(ip, port, AddressFamily.IPv6, packLo(it, 0), packLo(it, 8))
            }
            return null
        }

        /** Pack 8 bytes starting at [offset] (big-endian) into a long; IPv4 uses only 4 bytes. */
        private fun packLo(
            bytes: ByteArray,
            offset: Int,
        ): Long {
            var v = 0L
            val n = if (bytes.size == 4) 4 else 8
            for (i in 0 until n) v = (v shl 8) or (bytes[offset + i].toLong() and 0xff)
            return v
        }

        /** Parse dotted-quad IPv4 into 4 bytes, or null if not a valid IPv4 literal. */
        private fun parseIpv4(s: String): ByteArray? {
            val parts = s.split('.')
            if (parts.size != 4) return null
            val out = ByteArray(4)
            for (i in 0 until 4) {
                val p = parts[i]
                if (p.isEmpty() || p.length > 3) return null
                if (!p.all { it in '0'..'9' }) return null
                val v = p.toIntOrNull() ?: return null
                if (v > 255) return null
                out[i] = v.toByte()
            }
            return out
        }

        /**
         * Parse an IPv6 literal (with `::` compression and optional embedded IPv4 tail) into 16
         * bytes, or null if not a valid IPv6 literal. Zone ids (`%eth0`) are stripped.
         */
        private fun parseIpv6(raw: String): ByteArray? {
            val s = raw.substringBefore('%')
            if (!s.contains(':')) return null
            val doubleColon = s.indexOf("::")
            if (s.indexOf("::", doubleColon + 1) >= 0) return null // at most one "::"

            val head: String
            val tail: String
            if (doubleColon >= 0) {
                head = s.substring(0, doubleColon)
                tail = s.substring(doubleColon + 2)
            } else {
                head = s
                tail = ""
            }

            fun groups(part: String): List<String>? {
                if (part.isEmpty()) return emptyList()
                val g = part.split(':')
                return if (g.any { it.isEmpty() }) null else g
            }

            val headGroups = groups(head) ?: return null
            val tailGroupsRaw = groups(tail) ?: return null

            // An embedded IPv4 tail (e.g. ::ffff:1.2.3.4) counts as two 16-bit groups.
            val out = ByteArray(16)
            val words = ArrayList<Int>()

            fun appendGroups(gs: List<String>): Boolean {
                for ((idx, g) in gs.withIndex()) {
                    val isLast = idx == gs.size - 1
                    if (isLast && g.contains('.')) {
                        val v4 = parseIpv4(g) ?: return false
                        words.add(((v4[0].toInt() and 0xff) shl 8) or (v4[1].toInt() and 0xff))
                        words.add(((v4[2].toInt() and 0xff) shl 8) or (v4[3].toInt() and 0xff))
                    } else {
                        if (g.length > 4 || !g.all { it in "0123456789abcdefABCDEF" }) return false
                        words.add(g.toInt(16))
                    }
                }
                return true
            }

            val headWordsStart = words.size
            if (!appendGroups(headGroups)) return null
            val headWordCount = words.size - headWordsStart
            if (!appendGroups(tailGroupsRaw)) return null
            val tailWordCount = words.size - headWordsStart - headWordCount

            if (doubleColon >= 0) {
                val zeros = 8 - (headWordCount + tailWordCount)
                if (zeros < 0) return null
                // words currently = head... tail...; splice `zeros` zero-words between them.
                val final = ArrayList<Int>(8)
                for (i in 0 until headWordCount) final.add(words[i])
                repeat(zeros) { final.add(0) }
                for (i in 0 until tailWordCount) final.add(words[headWordCount + i])
                if (final.size != 8) return null
                for (i in 0 until 8) {
                    out[i * 2] = (final[i] ushr 8).toByte()
                    out[i * 2 + 1] = final[i].toByte()
                }
            } else {
                if (words.size != 8) return null
                for (i in 0 until 8) {
                    out[i * 2] = (words[i] ushr 8).toByte()
                    out[i * 2 + 1] = words[i].toByte()
                }
            }
            return out
        }
    }
}
