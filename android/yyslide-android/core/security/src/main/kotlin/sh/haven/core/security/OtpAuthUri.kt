package sh.haven.core.security

import java.net.URI
import java.net.URLDecoder

/**
 * Parser for `otpauth://totp/...` URIs (the Key Uri Format used by Google
 * Authenticator and every compatible app / QR code) and for bare base32
 * secrets pasted on their own.
 *
 * Returns a transport [Parsed] struct rather than a Room entity —
 * `core/security` is dependency-leaf and intentionally can't reach
 * `core/data` types; the caller maps [Parsed] onto its storage row.
 *
 * Format: `otpauth://totp/LABEL?secret=BASE32&issuer=…&algorithm=…&digits=…&period=…`
 * where `LABEL` is usually `Issuer:account`. Only `secret` is required.
 * `hotp://` and missing/empty secrets are rejected (return null).
 */
object OtpAuthUri {

    data class Parsed(
        val secret: String,
        val issuer: String?,
        val accountName: String?,
        val algorithm: Totp.Algorithm,
        val digits: Int,
        val periodSeconds: Int,
    ) {
        /** Display label: "issuer (account)" / "issuer" / "account" / "Authenticator". */
        val label: String
            get() = when {
                !issuer.isNullOrBlank() && !accountName.isNullOrBlank() -> "$issuer ($accountName)"
                !issuer.isNullOrBlank() -> issuer
                !accountName.isNullOrBlank() -> accountName
                else -> "Authenticator"
            }
    }

    /**
     * Parse [input] as either an `otpauth://totp/...` URI or a bare base32
     * secret. Returns null when the input is neither a valid TOTP URI nor
     * a decodable base32 string. Never throws.
     */
    fun parse(input: String): Parsed? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        return if (trimmed.startsWith("otpauth://", ignoreCase = true)) {
            parseUri(trimmed)
        } else {
            // Bare secret paste — accept only if it decodes as base32.
            if (Totp.isValidSecret(trimmed)) {
                Parsed(
                    secret = trimmed.replace(" ", ""),
                    issuer = null,
                    accountName = null,
                    algorithm = Totp.Algorithm.SHA1,
                    digits = 6,
                    periodSeconds = 30,
                )
            } else {
                null
            }
        }
    }

    private fun parseUri(raw: String): Parsed? {
        val uri = try {
            URI(raw)
        } catch (_: Throwable) {
            return null
        }
        // type is the authority/host: otpauth://totp/... — reject hotp and anything else.
        if (!uri.host.equals("totp", ignoreCase = true)) return null

        val query = parseQuery(uri.rawQuery)
        val secret = query["secret"]?.takeIf { Totp.isValidSecret(it) } ?: return null

        // Label path: "/Issuer:account" (issuer prefix optional).
        val label = uri.path?.trimStart('/')?.let {
            try {
                URLDecoder.decode(it, "UTF-8")
            } catch (_: Throwable) {
                it
            }
        }.orEmpty()
        val labelIssuer = label.substringBefore(':', missingDelimiterValue = "").trim().ifEmpty { null }
        val account = label.substringAfter(':', missingDelimiterValue = label).trim().ifEmpty { null }

        // Explicit issuer= param wins over the label prefix.
        val issuer = query["issuer"]?.trim()?.ifEmpty { null } ?: labelIssuer

        return Parsed(
            secret = secret.replace(" ", ""),
            issuer = issuer,
            accountName = account,
            algorithm = Totp.Algorithm.fromLabel(query["algorithm"]),
            digits = query["digits"]?.toIntOrNull()?.takeIf { it in 1..9 } ?: 6,
            periodSeconds = query["period"]?.toIntOrNull()?.takeIf { it > 0 } ?: 30,
        )
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrEmpty()) return emptyMap()
        return rawQuery.split('&').mapNotNull { pair ->
            val eq = pair.indexOf('=')
            if (eq < 0) return@mapNotNull null
            val key = pair.substring(0, eq)
            val value = try {
                URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
            } catch (_: Throwable) {
                pair.substring(eq + 1)
            }
            key to value
        }.toMap()
    }
}
