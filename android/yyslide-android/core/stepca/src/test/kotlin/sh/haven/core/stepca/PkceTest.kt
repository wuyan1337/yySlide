package sh.haven.core.stepca

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

class PkceTest {

    @Test
    fun `verifier is base64url length 43 with no padding`() {
        repeat(20) {
            val (verifier, _) = Pkce.generate()
            assertEquals("PKCE verifier should be 43 chars", 43, verifier.length)
            assertTrue(
                "PKCE verifier should be base64url-safe: $verifier",
                verifier.all { it.isLetterOrDigit() || it == '-' || it == '_' },
            )
        }
    }

    @Test
    fun `challenge is BASE64URL of SHA256 of verifier ascii bytes`() {
        repeat(20) {
            val (verifier, challenge) = Pkce.generate()
            val recomputed = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(
                    MessageDigest.getInstance("SHA-256")
                        .digest(verifier.toByteArray(Charsets.US_ASCII)),
                )
            assertEquals(
                "challenge should match RFC 7636 S256 of verifier",
                recomputed,
                challenge,
            )
        }
    }

    @Test
    fun `RFC 7636 appendix B test vector`() {
        // From https://datatracker.ietf.org/doc/html/rfc7636#appendix-B
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        val expectedChallenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
        val challenge = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(
                MessageDigest.getInstance("SHA-256")
                    .digest(verifier.toByteArray(Charsets.US_ASCII)),
            )
        assertEquals(expectedChallenge, challenge)
    }

    @Test
    fun `successive calls produce different verifiers`() {
        val (v1, _) = Pkce.generate()
        val (v2, _) = Pkce.generate()
        assertNotEquals("PKCE generator must not produce identical verifiers", v1, v2)
    }
}
