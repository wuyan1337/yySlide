package sh.haven.core.stepca

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import sh.haven.core.data.db.entities.StepCaConfig
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Talks to a step-ca instance over its HTTPS API. Pins TLS to the
 * user-supplied root cert PEM via [PinnedTls] — never falls back to
 * the system trust store.
 *
 * Phase 2a uses two endpoints:
 *  - `GET  /health`        — for the Settings "Test connection" affordance.
 *  - `POST /1.0/sign-ssh`  — to mint a signed SSH cert.
 */
@Singleton
class StepCaApiClient @Inject constructor() {

    /**
     * Fetch the SSH user/host CA public keys from step-ca's
     * `/1.0/ssh/config` endpoint. Used by the Settings dialog's
     * "Discover host CA" button so the user doesn't have to paste the
     * key by hand. Some step-ca deployments don't expose this endpoint
     * to unauthenticated clients — manual paste is the fallback.
     * (#133 phase 2b)
     */
    suspend fun fetchSshConfig(caConfig: StepCaConfig): SshConfigResult = withContext(Dispatchers.IO) {
        val pinned = try {
            PinnedTls.fromPem(caConfig.rootCertPem)
        } catch (e: Throwable) {
            return@withContext SshConfigResult.Failure("Invalid root cert PEM: ${e.message}")
        }
        val url = URL(caConfig.caUrl.trimEnd('/') + "/1.0/ssh/config")
        val conn = (url.openConnection() as HttpsURLConnection).apply {
            sslSocketFactory = pinned.socketFactory
            connectTimeout = 10_000
            readTimeout = 10_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
        }
        try {
            val rc = conn.responseCode
            if (rc !in 200..299) {
                val err = (conn.errorStream ?: conn.inputStream).bufferedReader().use { it.readText() }
                return@withContext SshConfigResult.Failure("HTTP $rc: ${err.take(300)}")
            }
            val resp = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(resp)
            // step-ca returns hostKey/userKey as either base64 strings or
            // OpenSSH single-line text — be flexible. Most installs emit
            // PEM-shaped values.
            val hostKey = json.optString("hostKey", "").ifEmpty { null }
            val userKey = json.optString("userKey", "").ifEmpty { null }
            if (hostKey == null && userKey == null) {
                return@withContext SshConfigResult.Failure(
                    "step-ca /1.0/ssh/config returned no hostKey or userKey",
                )
            }
            SshConfigResult.Success(hostKey = hostKey, userKey = userKey)
        } catch (e: Throwable) {
            SshConfigResult.Failure("Network error: ${e.message}")
        } finally {
            conn.disconnect()
        }
    }

    /** Lightweight reachability check for the Settings UI. */
    suspend fun testConnection(caConfig: StepCaConfig): TestResult = withContext(Dispatchers.IO) {
        val pinned = try {
            PinnedTls.fromPem(caConfig.rootCertPem)
        } catch (e: Throwable) {
            return@withContext TestResult.BadRootCert(e.message ?: "Invalid root cert PEM")
        }
        val url = URL(caConfig.caUrl.trimEnd('/') + "/health")
        val conn = (url.openConnection() as HttpsURLConnection).apply {
            sslSocketFactory = pinned.socketFactory
            connectTimeout = 10_000
            readTimeout = 10_000
            requestMethod = "GET"
        }
        try {
            val rc = conn.responseCode
            if (rc in 200..299) TestResult.Ok else TestResult.HttpError(rc, conn.responseMessage ?: "")
        } catch (e: Throwable) {
            TestResult.NetworkError(e.message ?: "Network error")
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Mint a signed SSH user certificate. Returns the cert bytes ready
     * to drop into [sh.haven.core.data.db.entities.SshKey.certificateBytes].
     */
    suspend fun signSsh(
        caConfig: StepCaConfig,
        idToken: String,
        publicKeyOpenSsh: String,
        keyId: String,
        principalsOverride: List<String>? = null,
    ): SignSshResult = withContext(Dispatchers.IO) {
        val pinned = try {
            PinnedTls.fromPem(caConfig.rootCertPem)
        } catch (e: Throwable) {
            return@withContext SignSshResult.Failure("Invalid root cert PEM: ${e.message}")
        }

        val publicKeyB64 = extractOpenSshPublicKeyBase64(publicKeyOpenSsh)
            ?: return@withContext SignSshResult.Failure("Public key is not OpenSSH format")

        val principals = principalsOverride
            ?: caConfig.defaultPrincipals.split(',').map { it.trim() }.filter { it.isNotEmpty() }

        val body = JSONObject().apply {
            put("ott", idToken)
            put("publicKey", publicKeyB64)
            put("certType", "user")
            put("keyID", keyId)
            if (principals.isNotEmpty()) {
                put("principals", JSONArray().apply { principals.forEach { put(it) } })
            }
        }.toString()

        val url = URL(caConfig.caUrl.trimEnd('/') + "/1.0/sign-ssh")
        val conn = (url.openConnection() as HttpsURLConnection).apply {
            sslSocketFactory = pinned.socketFactory
            connectTimeout = 15_000
            readTimeout = 15_000
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val rc = conn.responseCode
            if (rc !in 200..299) {
                val err = (conn.errorStream ?: conn.inputStream).bufferedReader().use { it.readText() }
                return@withContext SignSshResult.Failure("step-ca HTTP $rc: ${err.take(500)}")
            }
            val resp = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(resp)
            val crt = json.optString("crt", "")
            if (crt.isEmpty()) {
                return@withContext SignSshResult.Failure("step-ca response missing 'crt' field")
            }
            SignSshResult.Success(crt.toByteArray(Charsets.US_ASCII))
        } catch (e: Throwable) {
            SignSshResult.Failure("step-ca request failed: ${e.message}")
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Pull the base64 wire-format public key out of an OpenSSH single-line
     * key (`ssh-ed25519 AAAA... [comment]`). Returns null if the input
     * isn't recognisably OpenSSH.
     */
    private fun extractOpenSshPublicKeyBase64(openSsh: String): String? {
        val parts = openSsh.trim().split(Regex("\\s+"), limit = 3)
        if (parts.size < 2) return null
        return parts[1].takeIf { it.isNotEmpty() }
    }

    /**
     * Equivalent of `step ca bootstrap --ca-url <caUrl> --fingerprint <fp>`.
     *
     * Two-hop discovery: first an unverified HTTPS GET for `/roots.pem`
     * (we can't trust the chain yet — `/roots.pem` is what we'd verify
     * *against*), then a fingerprint check, then a pinned-TLS GET against
     * `/provisioners` so the user doesn't have to type OIDC URLs +
     * client ID + secret. Each OIDC entry's `configurationEndpoint` is
     * resolved via [OidcDiscovery] to fill in `issuer`/`auth`/`token`.
     *
     * On any failure — fingerprint mismatch, transport error, no OIDC
     * provisioner — returns [BootstrapResult.Failure] with a
     * human-readable message; the UI shows it next to the bootstrap form.
     */
    suspend fun bootstrap(caUrl: String, fingerprintHex: String): BootstrapResult =
        withContext(Dispatchers.IO) {
            val trimmedCa = caUrl.trimEnd('/')
            val rootsPem = try {
                fetchRootsPemUnverified(trimmedCa)
            } catch (e: Throwable) {
                return@withContext BootstrapResult.Failure(
                    "Couldn't reach $trimmedCa/roots.pem: ${e.message}",
                )
            }

            try {
                CaFingerprint.verifyPem(rootsPem, fingerprintHex)
            } catch (e: CaFingerprint.FingerprintMismatch) {
                return@withContext BootstrapResult.Failure(
                    "Root cert fingerprint mismatch. Expected ${e.expected}, server returned ${e.actual}.",
                )
            } catch (e: Throwable) {
                return@withContext BootstrapResult.Failure(e.message ?: "Invalid fingerprint")
            }

            val pinned = try {
                PinnedTls.fromPem(rootsPem)
            } catch (e: Throwable) {
                return@withContext BootstrapResult.Failure(
                    "Couldn't load downloaded /roots.pem: ${e.message}",
                )
            }

            val provisionersBody = try {
                pinnedGet("$trimmedCa/provisioners", pinned)
            } catch (e: Throwable) {
                return@withContext BootstrapResult.Failure(
                    "Couldn't fetch /provisioners: ${e.message}",
                )
            }

            val provisioners = try {
                Provisioners.parseOidc(provisionersBody)
            } catch (e: Throwable) {
                return@withContext BootstrapResult.Failure(
                    "Couldn't parse /provisioners JSON: ${e.message}",
                )
            }
            if (provisioners.isEmpty()) {
                return@withContext BootstrapResult.Failure(
                    "This CA exposes no OIDC provisioner. Use Manual entry for JWK/X5C/ACME-only deployments.",
                )
            }

            val resolved = provisioners.map { prov ->
                val endpoints = try {
                    OidcDiscovery.parse(plainGet(OidcDiscovery.discoveryUrl(prov.configurationEndpoint)))
                } catch (e: Throwable) {
                    return@withContext BootstrapResult.Failure(
                        "OIDC discovery failed for provisioner ${prov.name}: ${e.message}",
                    )
                }
                BootstrapResult.ResolvedProvisioner(
                    provisioner = prov,
                    endpoints = endpoints,
                )
            }

            BootstrapResult.Success(rootsPem = rootsPem, provisioners = resolved)
        }

    /**
     * `/roots.pem` without TLS verification — we're downloading what we'd
     * verify against. After the response body comes back, the caller
     * fingerprint-checks it; the connection itself is throwaway.
     */
    private fun fetchRootsPemUnverified(caUrlTrimmed: String): String {
        val url = URL("$caUrlTrimmed/roots.pem")
        val ctx = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(BlindTrustManager), SecureRandom())
        }
        val conn = (url.openConnection() as HttpsURLConnection).apply {
            sslSocketFactory = ctx.socketFactory
            hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
            connectTimeout = 10_000
            readTimeout = 10_000
            requestMethod = "GET"
        }
        try {
            val rc = conn.responseCode
            if (rc !in 200..299) {
                val err = (conn.errorStream ?: conn.inputStream).bufferedReader().use { it.readText() }
                error("HTTP $rc: ${err.take(200)}")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun pinnedGet(url: String, pinned: PinnedTls.Pinned): String {
        val conn = (URL(url).openConnection() as HttpsURLConnection).apply {
            sslSocketFactory = pinned.socketFactory
            connectTimeout = 10_000
            readTimeout = 10_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
        }
        try {
            val rc = conn.responseCode
            if (rc !in 200..299) {
                val err = (conn.errorStream ?: conn.inputStream).bufferedReader().use { it.readText() }
                error("HTTP $rc: ${err.take(200)}")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Plain HTTPS GET against the OIDC discovery endpoint, which lives on
     * the IdP — *not* the step-ca CA. The IdP is publicly trusted (Authentik
     * behind LE, Google, Okta) so we use the system trust store here.
     */
    private fun plainGet(url: String): String {
        val conn = (URL(url).openConnection() as HttpsURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
        }
        try {
            val rc = conn.responseCode
            if (rc !in 200..299) {
                val err = (conn.errorStream ?: conn.inputStream).bufferedReader().use { it.readText() }
                error("HTTP $rc: ${err.take(200)}")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Accepts any chain — only safe for the throwaway `/roots.pem` fetch,
     * which the caller fingerprint-checks before doing anything else with
     * the bytes.
     */
    private object BlindTrustManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
    }

    sealed interface BootstrapResult {
        data class Success(
            val rootsPem: String,
            val provisioners: List<ResolvedProvisioner>,
        ) : BootstrapResult

        data class Failure(val message: String) : BootstrapResult

        data class ResolvedProvisioner(
            val provisioner: Provisioners.OidcProvisioner,
            val endpoints: OidcDiscovery.Endpoints,
        )
    }

    sealed interface TestResult {
        data object Ok : TestResult
        data class BadRootCert(val reason: String) : TestResult
        data class HttpError(val code: Int, val message: String) : TestResult
        data class NetworkError(val message: String) : TestResult
    }

    sealed interface SshConfigResult {
        data class Success(val hostKey: String?, val userKey: String?) : SshConfigResult
        data class Failure(val message: String) : SshConfigResult
    }

    sealed interface SignSshResult {
        data class Success(val certBytes: ByteArray) : SignSshResult {
            override fun equals(other: Any?): Boolean =
                other is Success && certBytes.contentEquals(other.certBytes)

            override fun hashCode(): Int = certBytes.contentHashCode()
        }
        data class Failure(val message: String) : SignSshResult
    }
}
