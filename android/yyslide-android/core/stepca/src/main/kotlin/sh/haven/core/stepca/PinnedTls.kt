package sh.haven.core.stepca

import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Builds an [SSLSocketFactory] that trusts only the supplied PEM root
 * certificate. step-ca CAs are private — anchoring them in the public
 * Android trust store would invite TLS-MITM by anything with a publicly
 * trusted cert. Pinning to the user-provided PEM is the right model.
 */
internal object PinnedTls {

    data class Pinned(
        val socketFactory: SSLSocketFactory,
        val trustManager: X509TrustManager,
    )

    fun fromPem(rootCertPem: String): Pinned {
        val cert = parsePem(rootCertPem)
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("step-ca-root", cert)
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(ks)
        }
        val tm = tmf.trustManagers.first { it is X509TrustManager } as X509TrustManager
        val ctx = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<javax.net.ssl.TrustManager>(tm), null)
        }
        return Pinned(ctx.socketFactory, tm)
    }

    private fun parsePem(pem: String): X509Certificate {
        val cleaned = pem.trim()
        require(cleaned.startsWith("-----BEGIN CERTIFICATE-----")) {
            "Root cert PEM must start with -----BEGIN CERTIFICATE-----"
        }
        val cf = CertificateFactory.getInstance("X.509")
        return cleaned.byteInputStream(Charsets.US_ASCII).use { input ->
            cf.generateCertificate(input) as X509Certificate
        }
    }
}
