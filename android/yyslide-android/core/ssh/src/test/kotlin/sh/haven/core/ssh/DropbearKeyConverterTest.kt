package sh.haven.core.ssh

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class DropbearKeyConverterTest {

    // Throwaway Dropbear RSA 2048-bit key for test fixture only.
    // Generated with: dropbearkey -t rsa -s 2048 -f /tmp/key
    // This key must never be used for real authentication.
    private val dropbearRsa = Base64.getDecoder().decode(
        "AAAAB3NzaC1yc2EAAAADAQABAAABAQCWkV/sb6I/p499uFMZCew0lTjWgIS6QuZl5oKBYQFxzwGwKN4H" +
        "tG/IMkWUtE4puhpFSUMRpqL88SJQVALtrs1CGF5I7L0XhLw1ZgZ4l2/71peI8WOS1/Kxaf53dUrZ+C08" +
        "agRbh0gIKFm8l96skMDDrCCc1fwEHQ+YzBDzHVdQQSmECadlsbqePnp8s9e8/0YH4uR5cYTC6KgXtj6v" +
        "ad2Fo5e0D1/jCQ2pB4qpyy9358Ij10xPm9G+N/iLbUShcKThPi9n21Gx9AYfHjj5+ZPJuVFwzXzN9PJh" +
        "Z+wuoVw/jRWcCoIvnG2yj7Y9pOK+FcsJ0iYnh/tdwcS0J4Mf1OdrAAABABi8rJz3NZmt/p9MkRZ222BD" +
        "jebWjuKHyuY99KirQSYAuQnHRRHQqAwk5Oqf+YwrH6rMymCULIilre9dm2zlAE2rcZNwlZvPZRkuzcGy" +
        "kPripYXMXCwhdNAyFlUWUk8KUkBFOLm4OZzhfZDEW2364DilnGa3/04btNbJMWsHzIhffSpDCXEEZjqT" +
        "gV+mIAs3sFbo+x/xOCMOFF/tWIKsky4URUhdkDsF3zZ2ifpPH5NzIKUZf0nvKmdYve0+Is/CnHZnQwLb" +
        "Anh5XP5wBHrkxYbNkLuykcHM6gmSluC3KXiByWuHeVgwcoEaWEjnaZinvcQcYFS7bp9q9eLzi27ukEUA" +
        "AACBAO+wniN28BHP+GDNIs2mvcAIqW6cu2wMuJSrNmYNhOS9iZkaaaYSTV8lSbTJQruSdVO5ZCH+WrMY" +
        "o5wR4mnpJ7vOa3jWaIgIhA6T7l4iiQxcKLlN0L2AfUdoSSaC7EZypDP1xdSDMLfsAN6OJnJDqzqgLwtM" +
        "ba4AKqoVLD0W2df3AAAAgQCg0EHGl18jQ7nAe4lsK6sqrM4olotOSMoSFZO7e4mq5VHGujNKLFTmgs/w" +
        "NWkvojk2T5x/oJR/fnfyRpbG2Hmxh0zfSyuIAkKuseHP6B7QFu9RI6hgFDPwGiRcVftrA3UjQXn5rGz6" +
        "O8Der4NTSJwEHTOuHSNkXU+WJbSxfsNXLQ=="
    )

    // Throwaway Dropbear Ed25519 key for test fixture only.
    // Generated with: dropbearkey -t ed25519 -f /tmp/key
    private val dropbearEd25519 = Base64.getDecoder().decode(
        "AAAAC3NzaC1lZDI1NTE5AAAAQMSBhSqrImEsCRYp/z9HcqN11hLikwW2BAmQ9vSrw1KcnDt9VUR1" +
        "baUxrdCLbS4gHPvB8L6FQt96NTlvmhV6M/c="
    )

    // Throwaway Dropbear ECDSA P-256 key for test fixture only.
    // Generated with: dropbearkey -t ecdsa -s 256 -f /tmp/key
    private val dropbearEcdsa = Base64.getDecoder().decode(
        "AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBIha1G0VE1/MPTg3JVMBwfUm" +
        "udeWxK3T0A6Ln1caz1FhPQQHcutM3lsDoaKsH0hwRfwcB95lIcNSUeSVreMO3eQAAAAgGeXx51xH" +
        "psJWUp2BdSHn1FUtZR/bi+r+lLvr3lU7XuY="
    )

    // ---- Detection ----

    @Test
    fun `isDropbearKey detects RSA key`() {
        assertTrue(DropbearKeyConverter.isDropbearKey(dropbearRsa))
    }

    @Test
    fun `isDropbearKey detects Ed25519 key`() {
        assertTrue(DropbearKeyConverter.isDropbearKey(dropbearEd25519))
    }

    @Test
    fun `isDropbearKey detects ECDSA key`() {
        assertTrue(DropbearKeyConverter.isDropbearKey(dropbearEcdsa))
    }

    @Test
    fun `isDropbearKey rejects OpenSSH PEM`() {
        val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\ntest\n-----END OPENSSH PRIVATE KEY-----".toByteArray()
        assertFalse(DropbearKeyConverter.isDropbearKey(pem))
    }

    @Test
    fun `isDropbearKey rejects empty bytes`() {
        assertFalse(DropbearKeyConverter.isDropbearKey(byteArrayOf()))
    }

    @Test
    fun `isDropbearKey rejects garbage bytes`() {
        assertFalse(DropbearKeyConverter.isDropbearKey(ByteArray(256) { it.toByte() }))
    }

    // ---- RSA conversion ----

    @Test
    fun `RSA conversion produces valid OpenSSH key loadable by JSch`() {
        val openssh = DropbearKeyConverter.toOpenSsh(dropbearRsa)
        val kpair = KeyPair.load(JSch(), openssh, null)
        assertNotNull(kpair)
        kpair.dispose()
    }

    @Test
    fun `RSA conversion preserves fingerprint`() {
        val result = SshKeyImporter.import(dropbearRsa)
        assertEquals("SHA256:FeY9QrYbaFlrkfOK2U7+GAv++yBks/hGLQa0u0PbZxg", result.fingerprintSha256)
    }

    @Test
    fun `RSA conversion produces ssh-rsa key type`() {
        val result = SshKeyImporter.import(dropbearRsa)
        assertEquals("ssh-rsa", result.keyType)
    }

    // ---- Ed25519 conversion ----

    @Test
    fun `Ed25519 conversion produces valid OpenSSH key loadable by JSch`() {
        val openssh = DropbearKeyConverter.toOpenSsh(dropbearEd25519)
        val kpair = KeyPair.load(JSch(), openssh, null)
        assertNotNull(kpair)
        kpair.dispose()
    }

    @Test
    fun `Ed25519 conversion preserves fingerprint`() {
        val result = SshKeyImporter.import(dropbearEd25519)
        assertEquals("SHA256:PqAwNtIy+tzeztSxdjalmvfnhR1i64SolZ1hjOmLQzM", result.fingerprintSha256)
    }

    @Test
    fun `Ed25519 conversion produces ssh-ed25519 key type`() {
        val result = SshKeyImporter.import(dropbearEd25519)
        assertEquals("ssh-ed25519", result.keyType)
    }

    // ---- ECDSA conversion ----

    @Test
    fun `ECDSA conversion produces valid OpenSSH key loadable by JSch`() {
        val openssh = DropbearKeyConverter.toOpenSsh(dropbearEcdsa)
        val kpair = KeyPair.load(JSch(), openssh, null)
        assertNotNull(kpair)
        kpair.dispose()
    }

    @Test
    fun `ECDSA conversion preserves fingerprint`() {
        val result = SshKeyImporter.import(dropbearEcdsa)
        assertEquals("SHA256:M9iq0f9duGDqG/3zpTOi2Eq2iptnfpysXqTiWqmeSug", result.fingerprintSha256)
    }

    @Test
    fun `ECDSA conversion produces ecdsa key type`() {
        val result = SshKeyImporter.import(dropbearEcdsa)
        assertEquals("ecdsa-sha2-nistp256", result.keyType)
    }

    // ---- Round-trip via SshKeyImporter ----

    @Test
    fun `SshKeyImporter handles Dropbear RSA key`() {
        val result = SshKeyImporter.import(dropbearRsa)
        assertNotNull(result)
        assertTrue(result.publicKeyOpenSsh.startsWith("ssh-rsa "))
    }

    @Test
    fun `SshKeyImporter handles Dropbear Ed25519 key`() {
        val result = SshKeyImporter.import(dropbearEd25519)
        assertNotNull(result)
        assertTrue(result.publicKeyOpenSsh.startsWith("ssh-ed25519 "))
    }

    @Test
    fun `SshKeyImporter handles Dropbear ECDSA key`() {
        val result = SshKeyImporter.import(dropbearEcdsa)
        assertNotNull(result)
        assertTrue(result.publicKeyOpenSsh.startsWith("ecdsa-sha2-nistp256 "))
    }

    @Test
    fun `imported Dropbear key stored bytes are loadable by JSch`() {
        val result = SshKeyImporter.import(dropbearRsa)
        // Stored bytes should be OpenSSH format, not Dropbear
        val kpair = KeyPair.load(JSch(), result.privateKeyBytes, null)
        assertNotNull(kpair)
        kpair.dispose()
    }
}
