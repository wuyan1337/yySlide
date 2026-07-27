package sh.haven.core.ssh

import com.jcraft.jsch.Session

/**
 * Applies per-connection SSH Options (parsed by
 * [ConnectionConfig.parseSshOptions]) to a JSch [Session].
 *
 * Translates the OpenSSH directive names users naturally type
 * (`KexAlgorithms`, `Ciphers`, `MACs`, `HostKeyAlgorithms`,
 * `PubkeyAcceptedAlgorithms`, `PubkeyAuthentication`) into the
 * internal config keys mwiede/jsch actually reads (`kex`,
 * `cipher.c2s`/`cipher.s2c`, `mac.c2s`/`mac.s2c`, `server_host_key`,
 * `PubkeyAcceptedAlgorithms`, `PreferredAuthentications`), and honours
 * the `+` (append), `-` (remove, with `*`/`?` glob), `^` (prepend)
 * list prefix conventions against the session's current value so users
 * keep Haven's defaults instead of accidentally replacing them.
 *
 * Unknown keys fall through to a raw `setConfig` so users already
 * familiar with the JSch-native names (`ServerAliveInterval`, etc.)
 * keep working.
 */
object SshOptionsApplier {

    interface Target {
        fun getConfig(key: String): String?
        fun setConfig(key: String, value: String)
    }

    private val OPENSSH_TO_JSCH: Map<String, List<String>> = mapOf(
        "kexalgorithms" to listOf("kex"),
        "ciphers" to listOf("cipher.c2s", "cipher.s2c"),
        "macs" to listOf("mac.c2s", "mac.s2c"),
        "hostkeyalgorithms" to listOf("server_host_key"),
        "pubkeyacceptedalgorithms" to listOf("PubkeyAcceptedAlgorithms"),
        "pubkeyacceptedkeytypes" to listOf("PubkeyAcceptedAlgorithms"),
        "casignaturealgorithms" to listOf("CASignatureAlgorithms"),
    )

    // Haven pins StrictHostKeyChecking=no because it runs its own TOFU
    // (see SshClient.kt:69). Letting a user flip it would silently
    // disable that path, so we drop any override.
    private val RESERVED_KEYS = setOf("stricthostkeychecking")

    fun apply(session: Session, options: Map<String, String>) {
        apply(targetOf(session), options)
    }

    fun apply(target: Target, options: Map<String, String>) {
        for ((rawKey, rawValue) in options) {
            val key = rawKey.trim()
            val value = rawValue.trim()
            if (key.isEmpty() || value.isEmpty()) continue

            val lower = key.lowercase()
            if (lower in RESERVED_KEYS) continue

            val jschKeys = OPENSSH_TO_JSCH[lower]
            when {
                jschKeys != null -> for (jschKey in jschKeys) {
                    val base = target.getConfig(jschKey).orEmpty()
                    target.setConfig(jschKey, mergeAlgorithmList(base, value))
                }
                lower == "pubkeyauthentication" -> applyPubkeyAuthentication(target, value)
                else -> target.setConfig(key, value)
            }
        }
    }

    private fun targetOf(session: Session): Target = object : Target {
        override fun getConfig(key: String): String? = session.getConfig(key)
        override fun setConfig(key: String, value: String) { session.setConfig(key, value) }
    }

    private fun applyPubkeyAuthentication(target: Target, value: String) {
        val enable = when (value.lowercase()) {
            "yes", "true", "on", "1" -> true
            "no", "false", "off", "0" -> false
            else -> return
        }
        val current = target.getConfig("PreferredAuthentications").orEmpty()
        val items = current.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        if (enable) {
            if ("publickey" !in items) items.add(0, "publickey")
        } else {
            items.removeAll { it == "publickey" }
        }
        target.setConfig("PreferredAuthentications", items.joinToString(","))
    }

    /**
     * Merge a user value (with optional `+`/`-`/`^` prefix) into a
     * comma-separated [base] algorithm list. No prefix = replace.
     * `-` accepts OpenSSH `*`/`?` glob patterns.
     */
    internal fun mergeAlgorithmList(base: String, userValue: String): String {
        val raw = userValue.trim()
        if (raw.isEmpty()) return base
        val baseItems = base.split(",").map { it.trim() }.filter { it.isNotEmpty() }

        val (op, body) = when (raw[0]) {
            '+' -> '+' to raw.substring(1)
            '-' -> '-' to raw.substring(1)
            '^' -> '^' to raw.substring(1)
            else -> '=' to raw
        }
        val userItems = body.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (userItems.isEmpty()) return base

        return when (op) {
            '+' -> {
                val merged = baseItems.toMutableList()
                for (item in userItems) if (item !in merged) merged.add(item)
                merged.joinToString(",")
            }
            '-' -> baseItems
                .filter { existing -> userItems.none { matchesPattern(existing, it) } }
                .joinToString(",")
            '^' -> {
                val merged = mutableListOf<String>()
                for (item in userItems) if (item !in merged) merged.add(item)
                for (item in baseItems) if (item !in merged) merged.add(item)
                merged.joinToString(",")
            }
            else -> userItems.joinToString(",")
        }
    }

    private fun matchesPattern(value: String, pattern: String): Boolean {
        if ('*' !in pattern && '?' !in pattern) return value == pattern
        val regex = buildString {
            append('^')
            for (ch in pattern) when (ch) {
                '*' -> append(".*")
                '?' -> append('.')
                else -> append(Regex.escape(ch.toString()))
            }
            append('$')
        }
        return Regex(regex).matches(value)
    }
}
