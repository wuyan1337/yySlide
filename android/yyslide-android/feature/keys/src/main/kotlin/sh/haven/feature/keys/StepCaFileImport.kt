package sh.haven.feature.keys

import android.util.Base64
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * File import + format conversion helpers for the Add/Edit CA dialog.
 * Pure JDK + Android Base64 — no extra deps.
 *
 * Root cert: accepts PEM text (`.pem`/`.crt`/`.cer` ASCII), DER binary
 * (`.cer`/`.der`/`.crt` raw), or a PEM bundle (returns the first
 * CERTIFICATE block — bundles with multiple roots are unusual for our
 * use case and the user can paste the rest if they want).
 *
 * SSH host CA pubkey: accepts OpenSSH `.pub` format
 * (`ssh-ed25519 AAAA… [comment]`). Strips comment, validates algorithm
 * prefix.
 */
internal object StepCaFileImport {

    fun readRootCertPem(bytes: ByteArray): String {
        if (bytes.isEmpty()) error("File is empty")
        // Try treating as text first.
        if (looksLikeText(bytes)) {
            val text = bytes.toString(Charsets.UTF_8)
            val begin = text.indexOf("-----BEGIN CERTIFICATE-----")
            val end = text.indexOf("-----END CERTIFICATE-----")
            if (begin >= 0 && end > begin) {
                return text.substring(begin, end + "-----END CERTIFICATE-----".length).trim()
            }
            // Text but no PEM markers — could be base64 alone. Try wrapping.
            val cleaned = text.replace(Regex("\\s+"), "")
            if (cleaned.matches(Regex("^[A-Za-z0-9+/=]+$"))) {
                val der = runCatching { Base64.decode(cleaned, Base64.DEFAULT) }.getOrNull()
                if (der != null) return derToPem(der)
            }
            error("File contained no -----BEGIN CERTIFICATE----- block")
        }
        // Treat as DER binary.
        return derToPem(bytes)
    }

    fun readSshHostCaPubkey(bytes: ByteArray): String {
        if (bytes.isEmpty()) error("File is empty")
        if (!looksLikeText(bytes)) {
            error("Not an OpenSSH public key (binary file)")
        }
        val firstLine = bytes.toString(Charsets.UTF_8)
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
            ?: error("File is empty")
        val parts = firstLine.split(Regex("\\s+"))
        if (parts.size < 2) error("Not an OpenSSH public key")
        val algo = parts[0]
        val acceptedPrefixes = listOf(
            "ssh-ed25519", "ssh-rsa", "ssh-dss",
            "ecdsa-sha2-nistp256", "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp521",
            "sk-ssh-ed25519@openssh.com", "sk-ecdsa-sha2-nistp256@openssh.com",
        )
        if (algo !in acceptedPrefixes) {
            error("Unrecognised key algorithm: $algo")
        }
        // Reject private keys disguised as pubkey selections.
        if (firstLine.contains("PRIVATE KEY")) error("That looks like a private key — pick the .pub file")
        // Drop optional trailing comment.
        return "${parts[0]} ${parts[1]}"
    }

    private fun derToPem(der: ByteArray): String {
        val cert = runCatching {
            CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        }.getOrElse {
            error("Not a valid X.509 certificate: ${it.message ?: it::class.simpleName}")
        }
        val b64 = Base64.encodeToString(cert.encoded, Base64.NO_WRAP)
        val chunked = b64.chunked(64).joinToString("\n")
        return "-----BEGIN CERTIFICATE-----\n$chunked\n-----END CERTIFICATE-----"
    }

    /** Heuristic: treat as text if first 512 bytes are mostly printable ASCII. */
    private fun looksLikeText(bytes: ByteArray): Boolean {
        val sample = bytes.take(512)
        if (sample.isEmpty()) return false
        val printable = sample.count { b ->
            val c = b.toInt() and 0xFF
            c == 9 || c == 10 || c == 13 || (c in 32..126)
        }
        return printable * 100 / sample.size >= 90
    }
}
