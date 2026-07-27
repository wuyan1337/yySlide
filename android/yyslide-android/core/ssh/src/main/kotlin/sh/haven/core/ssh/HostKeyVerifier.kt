package sh.haven.core.ssh

import android.util.Log
import sh.haven.core.data.db.KnownHostDao
import sh.haven.core.data.db.entities.KnownHost
import sh.haven.core.data.repository.StepCaConfigRepository
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

sealed class HostKeyResult {
    data object Trusted : HostKeyResult()
    data class NewHost(val entry: KnownHostEntry) : HostKeyResult()
    data class KeyChanged(
        val old: KnownHost,
        val new: KnownHostEntry,
    ) : HostKeyResult()
}

@Singleton
class HostKeyVerifier @Inject constructor(
    private val knownHostDao: KnownHostDao,
    private val stepCaConfigRepository: StepCaConfigRepository,
) {

    suspend fun verify(entry: KnownHostEntry): HostKeyResult {
        // CA-signed host cert path. If the server presents an
        // ssh-*-cert-v01@openssh.com host cert and the issuing CA matches
        // a registered StepCaConfig.sshHostCaPublicKey, accept silently
        // (provided the cert is currently valid and the hostname is in
        // its principals). Otherwise fall through to TOFU. (#133 phase 2b)
        if (entry.keyType.endsWith("-cert-v01@openssh.com") &&
            isTrustedCaSignedHostCert(entry)
        ) {
            return HostKeyResult.Trusted
        }

        val stored = knownHostDao.findByHostPort(entry.hostname, entry.port)
            ?: return HostKeyResult.NewHost(entry)

        return if (stored.publicKeyBase64 == entry.publicKeyBase64) {
            HostKeyResult.Trusted
        } else {
            HostKeyResult.KeyChanged(old = stored, new = entry)
        }
    }

    private suspend fun isTrustedCaSignedHostCert(entry: KnownHostEntry): Boolean {
        val certBlob = try {
            Base64.getDecoder().decode(entry.publicKeyBase64)
        } catch (_: Throwable) {
            return false
        }
        val cert = OpenSshCertificate.parseOrNull(certBlob) ?: return false
        if (cert.certType != OpenSshCertificate.HOST_CERT_TYPE) return false
        val now = System.currentTimeMillis() / 1000
        if (!cert.isValidAt(now)) return false
        if (entry.hostname !in cert.validPrincipals) return false

        val signatureKeyB64 = Base64.getEncoder().encodeToString(cert.signatureKey)
        val matched = stepCaConfigRepository.getAll().any { ca ->
            val raw = ca.sshHostCaPublicKey ?: return@any false
            extractWireFormatBase64(raw) == signatureKeyB64
        }
        if (matched) {
            Log.d(TAG, "Trusting CA-signed host cert for ${entry.hostname}")
        }
        return matched
    }

    /**
     * Pull the wire-format base64 out of an OpenSSH single-line public
     * key (`ssh-ed25519 AAAA... [comment]`). Falls through to the raw
     * input if it doesn't look like a single-line key — lets a user
     * paste either form and have the comparison work.
     */
    private fun extractWireFormatBase64(input: String): String {
        val parts = input.trim().split(Regex("\\s+"), limit = 3)
        return if (parts.size >= 2) parts[1] else input.trim()
    }

    suspend fun accept(entry: KnownHostEntry) {
        // Delete any existing entry for this host:port, then insert the new one
        knownHostDao.deleteByHostPort(entry.hostname, entry.port)
        knownHostDao.upsert(
            KnownHost(
                hostname = entry.hostname,
                port = entry.port,
                keyType = entry.keyType,
                publicKeyBase64 = entry.publicKeyBase64,
                fingerprint = entry.fingerprint(),
            ),
        )
    }

    companion object {
        private const val TAG = "HostKeyVerifier"
    }
}
