package sh.haven.core.ssh

import com.jcraft.jsch.Identity

/**
 * Wraps any [Identity] (typically [FidoIdentity]) so JSch advertises a
 * cert-key-type to the server while signing still goes through the
 * underlying identity (hardware authenticator or software key).
 *
 * Why this exists: JSch ships [com.jcraft.jsch.OpenSshCertificateAwareIdentityFile]
 * for software keys + cert pairs, but FIDO2 SK keys never become an
 * `IdentityFile` — they're an in-memory [FidoIdentity] that delegates to
 * the authenticator. The cert path therefore can't piggy-back on the
 * library's wrapper; we expose the cert blob via [getPublicKeyBlob] and
 * the cert key type via [getAlgName] ourselves, then forward signing to
 * the wrapped identity.
 *
 * [getSignature] strips the `-cert-v01@openssh.com` suffix from the
 * algorithm name JSch passes back: the wrapped FIDO2 identity signs with
 * the bare `sk-ssh-ed25519@openssh.com` / `sk-ecdsa-sha2-nistp256@openssh.com`
 * algorithm — only the public-key advertisement carries the cert form.
 */
class CertificateWrappedIdentity(
    private val delegate: Identity,
    private val certBlob: ByteArray,
    private val certKeyType: String,
) : Identity {

    override fun getAlgName(): String = certKeyType

    override fun getName(): String = delegate.name

    override fun getPublicKeyBlob(): ByteArray = certBlob

    override fun isEncrypted(): Boolean = delegate.isEncrypted

    override fun setPassphrase(passphrase: ByteArray?): Boolean = delegate.setPassphrase(passphrase)

    override fun decrypt(): Boolean = delegate.decrypt()

    override fun clear() = delegate.clear()

    override fun getSignature(data: ByteArray): ByteArray = delegate.getSignature(data)

    override fun getSignature(data: ByteArray, alg: String): ByteArray {
        val baseAlg = if (alg.endsWith(CERT_SUFFIX)) alg.removeSuffix(CERT_SUFFIX) else alg
        return delegate.getSignature(data, baseAlg)
    }

    private companion object {
        const val CERT_SUFFIX = "-cert-v01@openssh.com"
    }
}
