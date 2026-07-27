package sh.haven.core.knock

/**
 * A single packet to send during port knocking.
 *
 * `knockd` and friends watch for SYNs (TCP) or datagrams (UDP) on
 * specific ports in a specific order; this models one such hit.
 */
data class KnockStep(
    val port: Int,
    val protocol: Protocol,
) {
    enum class Protocol { TCP, UDP }

    init {
        require(port in 1..65535) { "port out of range: $port" }
    }

    override fun toString(): String = "$port/${protocol.name.lowercase()}"
}

/**
 * Ordered sequence of knock steps plus the gap to wait between them.
 * Empty / null user input parses to `null`, which means "knocking
 * disabled for this profile".
 */
data class KnockSequence(
    val steps: List<KnockStep>,
    val interKnockDelayMs: Int,
) {
    init {
        require(steps.isNotEmpty()) { "empty knock sequence" }
        require(interKnockDelayMs in 0..60_000) {
            "delay out of range: $interKnockDelayMs"
        }
    }

    /** Render the sequence in the same syntax `parse` accepts. */
    fun format(): String = steps.joinToString(" ") { it.toString() }

    companion object {
        /** Default gap between packets — enough for `knockd`'s default `seq_timeout`. */
        const val DEFAULT_DELAY_MS = 100

        /**
         * Parse a user-supplied knock string.
         *
         * Format: tokens separated by whitespace and/or commas. Each token is
         * either `port` (defaults to TCP) or `port/proto` where `proto` is
         * `tcp` or `udp` (case-insensitive).
         *
         * Returns `Result.success(null)` for blank/null input ("knocking off").
         * Returns `Result.failure(IllegalArgumentException)` for any malformed
         * token, out-of-range port, or unknown protocol.
         */
        fun parse(raw: String?, delayMs: Int = DEFAULT_DELAY_MS): Result<KnockSequence?> {
            val trimmed = raw?.trim().orEmpty()
            if (trimmed.isEmpty()) return Result.success(null)
            return runCatching {
                val tokens = trimmed.split(',', ' ', '\t', '\n')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                require(tokens.isNotEmpty()) { "no knock tokens" }
                val steps = tokens.map { parseToken(it) }
                KnockSequence(steps, delayMs)
            }
        }

        private fun parseToken(token: String): KnockStep {
            val (portPart, protoPart) = when {
                '/' in token -> token.substringBefore('/') to token.substringAfter('/')
                ':' in token -> token.substringBefore(':') to token.substringAfter(':')
                else -> token to "tcp"
            }
            val port = portPart.toIntOrNull()
                ?: throw IllegalArgumentException("not a port: '$portPart' in '$token'")
            require(port in 1..65535) { "port out of range: $port (token '$token')" }
            val proto = when (protoPart.trim().lowercase()) {
                "tcp" -> KnockStep.Protocol.TCP
                "udp" -> KnockStep.Protocol.UDP
                else -> throw IllegalArgumentException(
                    "unknown protocol '$protoPart' in '$token' (expected tcp or udp)"
                )
            }
            return KnockStep(port, proto)
        }
    }
}
