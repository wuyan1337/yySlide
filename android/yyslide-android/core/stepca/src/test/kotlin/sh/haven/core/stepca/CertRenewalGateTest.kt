package sh.haven.core.stepca

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import sh.haven.core.data.db.entities.SshKey
import sh.haven.core.data.db.entities.StepCaConfig
import sh.haven.core.data.repository.SshKeyRepository
import sh.haven.core.data.repository.StepCaConfigRepository
import java.io.ByteArrayOutputStream
import java.util.Base64

class CertRenewalGateTest {

    @Test
    fun `key with no caConfigId is returned unchanged`() = runTest {
        val gate = newGate()
        val key = baseKey().copy(
            caConfigId = null,
            certificateBytes = synthCertExpiringIn(secondsFromNow = -100), // already expired
        )
        assertSame(key, gate.ensureFresh(key))
    }

    @Test
    fun `key with no certificateBytes is returned unchanged`() = runTest {
        val gate = newGate()
        val key = baseKey().copy(certificateBytes = null)
        assertSame(key, gate.ensureFresh(key))
    }

    @Test
    fun `cert with leeway remaining is returned unchanged`() = runTest {
        val gate = newGate()
        val key = baseKey().copy(
            caConfigId = "ca-1",
            certificateBytes = synthCertExpiringIn(secondsFromNow = 3600),
        )
        assertSame(key, gate.ensureFresh(key, leewaySeconds = 300, nowSecondsProvider = { NOW }))
    }

    @Test
    fun `unparseable cert is returned unchanged`() = runTest {
        val gate = newGate()
        val key = baseKey().copy(
            caConfigId = "ca-1",
            certificateBytes = "not a cert".toByteArray(),
        )
        assertSame(key, gate.ensureFresh(key))
    }

    @Test
    fun `expiring cert triggers renewal, saves, returns new bytes`() = runTest {
        val signFlow = mockk<StepCaSignFlow>()
        val sshKeyRepository = mockk<SshKeyRepository>(relaxUnitFun = true)
        val stepCaConfigRepository = mockk<StepCaConfigRepository>()

        val newCertBytes = "renewed-cert-blob".toByteArray()
        coEvery { stepCaConfigRepository.getById("ca-1") } returns sampleCaConfig()
        coEvery { signFlow.run(any(), any(), any(), any()) } returns
            StepCaSignFlow.Result.Success(newCertBytes)

        val gate = CertRenewalGate(
            signFlow = signFlow,
            stepCaConfigRepository = stepCaConfigRepository,
            sshKeyRepository = sshKeyRepository,
        )

        val key = baseKey().copy(
            caConfigId = "ca-1",
            certificateBytes = synthCertExpiringIn(secondsFromNow = 60), // inside leeway
        )

        val result = gate.ensureFresh(
            key,
            leewaySeconds = 300,
            nowSecondsProvider = { NOW },
        )

        assertTrue(
            "result should carry the new cert blob",
            result.certificateBytes!!.contentEquals(newCertBytes),
        )
        // Returned in-memory copy retains plaintext private bytes — caller
        // (the connect path) hands them to a Keystore-routed re-fetch
        // before JSch sees them.
        assertEquals(key.id, result.id)
        assertEquals(key.label, result.label)

        coVerify(exactly = 1) { sshKeyRepository.save(any()) }

        // Renewal flag was toggled and reset.
        assertNull(gate.renewing.value)
    }

    @Test
    fun `deleted CA throws with a clear message`() = runTest {
        val sshKeyRepository = mockk<SshKeyRepository>(relaxUnitFun = true)
        val stepCaConfigRepository = mockk<StepCaConfigRepository>()
        val signFlow = mockk<StepCaSignFlow>()

        coEvery { stepCaConfigRepository.getById("ca-1") } returns null

        val gate = CertRenewalGate(signFlow, stepCaConfigRepository, sshKeyRepository)
        val key = baseKey().copy(
            caConfigId = "ca-1",
            certificateBytes = synthCertExpiringIn(secondsFromNow = 60),
        )

        try {
            gate.ensureFresh(key, leewaySeconds = 300, nowSecondsProvider = { NOW })
            fail("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(
                "message should mention the CA was removed: ${e.message}",
                e.message!!.contains("removed"),
            )
        }

        coVerify(exactly = 0) { sshKeyRepository.save(any()) }
    }

    @Test
    fun `sign-ssh failure surfaces as exception, no save`() = runTest {
        val sshKeyRepository = mockk<SshKeyRepository>(relaxUnitFun = true)
        val stepCaConfigRepository = mockk<StepCaConfigRepository>()
        val signFlow = mockk<StepCaSignFlow>()

        coEvery { stepCaConfigRepository.getById("ca-1") } returns sampleCaConfig()
        coEvery { signFlow.run(any(), any(), any(), any()) } returns
            StepCaSignFlow.Result.Failure("CA unreachable")

        val gate = CertRenewalGate(signFlow, stepCaConfigRepository, sshKeyRepository)
        val key = baseKey().copy(
            caConfigId = "ca-1",
            certificateBytes = synthCertExpiringIn(secondsFromNow = 60),
        )

        try {
            gate.ensureFresh(key, leewaySeconds = 300, nowSecondsProvider = { NOW })
            fail("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(
                "message should mention CA error: ${e.message}",
                e.message!!.contains("CA unreachable"),
            )
        }
        coVerify(exactly = 0) { sshKeyRepository.save(any()) }
        assertNull(gate.renewing.value)
    }

    // ---- helpers ----

    private fun newGate(): CertRenewalGate = CertRenewalGate(
        signFlow = mockk(relaxed = true),
        stepCaConfigRepository = mockk(relaxed = true),
        sshKeyRepository = mockk(relaxUnitFun = true, relaxed = true),
    )

    private fun baseKey() = SshKey(
        id = "key-1",
        label = "alice",
        keyType = "ssh-ed25519",
        privateKeyBytes = ByteArray(32) { 0x11 },
        publicKeyOpenSsh = "ssh-ed25519 AAAA",
        fingerprintSha256 = "fp",
    )

    private fun sampleCaConfig() = StepCaConfig(
        id = "ca-1",
        name = "Work step-ca",
        caUrl = "https://ca.example.com",
        oidcIssuer = "https://idp.example.com",
        oidcAuthUrl = "https://idp.example.com/authorize",
        oidcTokenUrl = "https://idp.example.com/token",
        oidcClientId = "haven",
        provisioner = "oidc",
        defaultPrincipals = "alice",
        rootCertPem = "-----BEGIN CERTIFICATE-----\nfake\n-----END CERTIFICATE-----",
    )

    /**
     * Build a synthetic ed25519 user cert whose validBefore is exactly
     * [secondsFromNow] seconds after [NOW]. Negative values produce a
     * cert that's already expired.
     */
    private fun synthCertExpiringIn(secondsFromNow: Long): ByteArray {
        val out = ByteArrayOutputStream()
        writeString(out, "ssh-ed25519-cert-v01@openssh.com")
        writeBytes(out, ByteArray(32) { (it + 1).toByte() }) // nonce
        writeBytes(out, ByteArray(32) { 0xAA.toByte() }) // pubkey
        writeUint64(out, 1L) // serial
        writeUint32(out, 1) // user cert
        writeString(out, "key-id")
        writeBytes(out, byteArrayOf()) // empty principals
        writeUint64(out, 0L) // valid after = epoch
        writeUint64(out, NOW + secondsFromNow) // valid before
        writeBytes(out, byteArrayOf()) // critical opts
        writeBytes(out, byteArrayOf()) // extensions
        writeBytes(out, byteArrayOf()) // reserved
        // Signature key blob: type "ssh-ed25519" + 32 bytes
        val sigKey = ByteArrayOutputStream().also {
            writeString(it, "ssh-ed25519")
            writeBytes(it, ByteArray(32) { 0xCC.toByte() })
        }.toByteArray()
        writeBytes(out, sigKey)
        // Signature blob (not consumed by parser but expected to be present)
        val sig = ByteArrayOutputStream().also {
            writeString(it, "ssh-ed25519")
            writeBytes(it, ByteArray(64) { 0xDD.toByte() })
        }.toByteArray()
        writeBytes(out, sig)

        // Wrap in OpenSSH text-line form so the parser exercises the
        // text-detection branch the production code uses.
        return ("ssh-ed25519-cert-v01@openssh.com " +
            Base64.getEncoder().encodeToString(out.toByteArray()) +
            " test@host\n").toByteArray()
    }

    private fun writeUint32(out: ByteArrayOutputStream, v: Int) {
        out.write((v ushr 24) and 0xFF)
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write(v and 0xFF)
    }

    private fun writeUint64(out: ByteArrayOutputStream, v: Long) {
        for (i in 7 downTo 0) {
            out.write(((v ushr (i * 8)) and 0xFF).toInt())
        }
    }

    private fun writeBytes(out: ByteArrayOutputStream, b: ByteArray) {
        writeUint32(out, b.size)
        out.write(b)
    }

    private fun writeString(out: ByteArrayOutputStream, s: String) {
        writeBytes(out, s.toByteArray(Charsets.US_ASCII))
    }

    companion object {
        /** Synthetic "now" anchor — January 2026. */
        private const val NOW: Long = 1_767_312_000L
    }
}
