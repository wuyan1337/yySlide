package sh.haven.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OtpAuthUriTest {

    @Test
    fun parsesFullUri() {
        val p = OtpAuthUri.parse(
            "otpauth://totp/ACME%20Co:alice@acme.com" +
                "?secret=GEZDGNBVGY3TQOJQ&issuer=ACME%20Co&algorithm=SHA256&digits=8&period=60",
        )!!
        assertEquals("GEZDGNBVGY3TQOJQ", p.secret)
        assertEquals("ACME Co", p.issuer)
        assertEquals("alice@acme.com", p.accountName)
        assertEquals(Totp.Algorithm.SHA256, p.algorithm)
        assertEquals(8, p.digits)
        assertEquals(60, p.periodSeconds)
        assertEquals("ACME Co (alice@acme.com)", p.label)
    }

    @Test
    fun appliesOtpauthDefaults() {
        val p = OtpAuthUri.parse("otpauth://totp/alice?secret=GEZDGNBVGY3TQOJQ")!!
        assertEquals(Totp.Algorithm.SHA1, p.algorithm)
        assertEquals(6, p.digits)
        assertEquals(30, p.periodSeconds)
        assertNull(p.issuer)
        assertEquals("alice", p.accountName)
    }

    @Test
    fun issuerParamWinsOverLabelPrefix() {
        val p = OtpAuthUri.parse("otpauth://totp/Wrong:bob?secret=GEZDGNBVGY3TQOJQ&issuer=Right")!!
        assertEquals("Right", p.issuer)
        assertEquals("bob", p.accountName)
    }

    @Test
    fun acceptsBareBase32() {
        val p = OtpAuthUri.parse("gezd gnbv gy3t qojq")!!
        assertEquals("gezdgnbvgy3tqojq", p.secret)
        assertNull(p.issuer)
        assertEquals(Totp.Algorithm.SHA1, p.algorithm)
        assertEquals("Authenticator", p.label)
    }

    @Test
    fun rejectsHotp() {
        assertNull(OtpAuthUri.parse("otpauth://hotp/alice?secret=GEZDGNBVGY3TQOJQ&counter=0"))
    }

    @Test
    fun rejectsMissingOrInvalidSecret() {
        assertNull(OtpAuthUri.parse("otpauth://totp/alice?issuer=ACME"))
        assertNull(OtpAuthUri.parse("otpauth://totp/alice?secret=!!!notbase32!!!"))
        assertNull(OtpAuthUri.parse(""))
        assertNull(OtpAuthUri.parse("https://example.com"))
    }
}
