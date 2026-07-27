package sh.haven.app.agent

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import sh.haven.core.data.agent.AgentConsentManager
import sh.haven.core.data.agent.ConsentDecision
import java.io.File
import java.net.URI
import java.security.MessageDigest

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class McpApkInstallSecurityTest {

    private inline fun <reified T : Throwable> expectThrows(block: () -> Unit): T {
        try {
            block()
        } catch (t: Throwable) {
            if (t is T) return t
            throw t
        }
        fail("Expected ${T::class.java.simpleName}")
        throw AssertionError("unreachable")
    }

    @Test
    fun `APK URL policy allows HTTPS and loopback HTTP only`() {
        assertEquals("https", validateAgentApkDownloadUri("https://example.com/app.apk").scheme)
        for (url in listOf(
            "http://localhost/app.apk",
            "http://dev.localhost/app.apk",
            "http://127.0.0.1/app.apk",
            "http://127.42.0.9/app.apk",
            "http://[::1]/app.apk",
        )) {
            assertEquals("http", validateAgentApkDownloadUri(url).scheme)
        }
        for (url in listOf(
            "http://example.com/app.apk",
            "http://192.168.1.5/app.apk",
            "ftp://localhost/app.apk",
            "file:///tmp/app.apk",
        )) {
            expectThrows<IllegalArgumentException> { validateAgentApkDownloadUri(url) }
        }
    }

    @Test
    fun `redirect policy validates every target and caps hops`() {
        val initial = URI("https://example.com/releases/app.apk")
        assertEquals(
            URI("https://cdn.example.com/app.apk"),
            resolveAgentApkRedirect(initial, "https://cdn.example.com/app.apk", 0),
        )
        assertEquals(
            URI("http://127.0.0.1/app.apk"),
            resolveAgentApkRedirect(initial, "http://127.0.0.1/app.apk", 1),
        )
        expectThrows<IllegalArgumentException> {
            resolveAgentApkRedirect(initial, "http://cdn.example.com/app.apk", 1)
        }
        expectThrows<IllegalArgumentException> {
            resolveAgentApkRedirect(initial, "/next.apk", redirectsAlreadyFollowed = 5)
        }
    }

    @Test
    fun `sha256 mismatch fails closed`() {
        val expected = parseExpectedAgentApkSha256("00".repeat(32))
        val actual = MessageDigest.getInstance("SHA-256").digest("different apk".toByteArray())
        val error = expectThrows<McpError> { verifyAgentApkSha256(expected, actual) }
        assertTrue(error.message.orEmpty().contains("mismatch"))
        expectThrows<McpError> { parseExpectedAgentApkSha256("abc") }
    }

    @Test
    fun `unparseable APK fails closed`() {
        val packageManager = mockk<PackageManager>()
        val apk = File("unparseable.apk").absoluteFile
        every {
            packageManager.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNATURES)
        } returns null

        val error = expectThrows<McpError> { inspectAgentApk(packageManager, apk, sdkInt = 26) }
        assertTrue(error.message.orEmpty().contains("could not be parsed"))
    }

    @Test
    fun `update with different signer fails closed`() {
        val packageManager = mockk<PackageManager>()
        val apk = File("update.apk").absoluteFile
        val archiveSigner = mockk<Signature>()
        val installedSigner = mockk<Signature>()
        every { archiveSigner.toByteArray() } returns byteArrayOf(1, 2, 3)
        every { installedSigner.toByteArray() } returns byteArrayOf(9, 8, 7)

        val archive = mockk<PackageInfo>(relaxed = true).apply {
            packageName = "com.example.app"
            versionName = "2.0"
            versionCode = 2
            signatures = arrayOf(archiveSigner)
        }
        val installed = mockk<PackageInfo>(relaxed = true).apply {
            packageName = "com.example.app"
            versionName = "1.0"
            versionCode = 1
            signatures = arrayOf(installedSigner)
        }
        every {
            packageManager.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNATURES)
        } returns archive
        every {
            packageManager.getPackageInfo("com.example.app", PackageManager.GET_SIGNATURES)
        } returns installed

        val error = expectThrows<McpError> { inspectAgentApk(packageManager, apk, sdkInt = 26) }
        assertTrue(error.message.orEmpty().contains("does not match"))
    }

    @Test
    fun `Android 11 package visibility is reported as unknown not new install`() {
        assertEquals(
            AgentApkInstallStatus.NEW_INSTALL,
            missingAgentPackageInstallStatus(sdkInt = 29),
        )
        assertEquals(
            AgentApkInstallStatus.UNKNOWN_VISIBILITY,
            missingAgentPackageInstallStatus(sdkInt = 30),
        )
        val summary = AgentApkInstallReview(
            source = "Backend local: /tmp/app.apk",
            fileSha256 = "ab".repeat(32),
            metadata = AgentApkMetadata(
                packageName = "com.example.hidden",
                versionName = "1.0",
                versionCode = 1,
                signingCertificateSha256 = listOf("cd".repeat(32)),
                installStatus = AgentApkInstallStatus.UNKNOWN_VISIBILITY,
                installedVersion = null,
            ),
        ).consentSummary()
        assertTrue(summary.contains("UNKNOWN"))
        assertTrue(summary.contains("package visibility"))
        assertTrue(summary.contains("signing-certificate continuity"))
    }

    @Test
    fun `Shizuku install command keeps platform signature checks enabled`() {
        val command = agentPmInstallCommand(1234)
        assertEquals("pm install -r -S 1234", command)
        for (forbidden in listOf(" -d", " -t", "--force", "--bypass", "--disable-verification")) {
            assertFalse("unexpected install bypass flag: $command", command.contains(forbidden))
        }
    }

    @Test
    fun `final review denial blocks install and includes all security fields`() = runTest {
        val manager = AgentConsentManager().apply { setForegroundActive(true) }
        val review = AgentApkInstallReview(
            source = "URL (after redirects): https://cdn.example/app.apk",
            fileSha256 = "ab".repeat(32),
            metadata = AgentApkMetadata(
                packageName = "com.example.app",
                versionName = "2.0",
                versionCode = 20,
                signingCertificateSha256 = listOf("cd".repeat(32)),
                installStatus = AgentApkInstallStatus.UPDATE,
                installedVersion = "1.0 (code 10)",
            ),
        )
        val staged = File.createTempFile("apk-review-deny-", ".apk").apply {
            writeBytes(byteArrayOf(0x50, 0x4b, 0x03, 0x04))
        }
        var installReached = false
        val result = async {
            runCatching {
                withAgentApkStagingCleanup(staged) {
                    requireAgentApkFinalConsent(manager, review, timeoutMs = 5_000)
                    installReached = true
                    JSONObject().put("installed", true)
                }
            }
        }
        runCurrent()

        val pending = manager.pending.value.single()
        assertEquals("confirm_apk_install", pending.toolName)
        assertTrue(pending.clientHint == null)
        for (detail in listOf(
            "https://cdn.example/app.apk",
            review.fileSha256,
            "com.example.app",
            "2.0 (code 20)",
            review.metadata.signingCertificateSha256.single(),
            "Updates installed app: YES",
        )) {
            assertTrue("missing detail: $detail", pending.summary.contains(detail))
        }
        manager.respond(pending.id, ConsentDecision.DENY)

        val failure = result.await().exceptionOrNull()
        assertTrue(failure is ConsentDeniedError)
        assertFalse(installReached)
        assertFalse(staged.exists())
        assertFalse(manager.pending.value.isNotEmpty())
    }
}
