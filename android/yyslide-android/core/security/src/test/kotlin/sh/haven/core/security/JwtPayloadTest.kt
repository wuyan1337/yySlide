package sh.haven.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

class JwtPayloadTest {

    /** Build a JWT-shaped string with the given payload claims. Signature is filler. */
    private fun jwt(payload: String): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        val header = enc.encodeToString("""{"alg":"none"}""".toByteArray())
        val body = enc.encodeToString(payload.toByteArray())
        val sig = enc.encodeToString("sig".toByteArray())
        return "$header.$body.$sig"
    }

    @Test
    fun `parses exp and email claims`() {
        val token = jwt("""{"exp":1737934800,"email":"alice@example.com","sub":"abc"}""")
        val parsed = JwtPayload.parse(token)!!
        assertEquals(1737934800L, parsed.expiresAtSeconds)
        assertEquals("alice@example.com", parsed.email)
    }

    @Test
    fun `missing exp returns zero, missing email returns null`() {
        val token = jwt("""{"sub":"abc"}""")
        val parsed = JwtPayload.parse(token)!!
        assertEquals(0L, parsed.expiresAtSeconds)
        assertNull(parsed.email)
    }

    @Test
    fun `unparseable JWT returns null instead of throwing`() {
        assertNull(JwtPayload.parse(""))
        assertNull(JwtPayload.parse("only-one-segment"))
        assertNull(JwtPayload.parse("not.valid.b64!"))
        assertNull(JwtPayload.parse("aGVsbG8.bm90LWpzb24.sig"))  // payload base64-decodes to "not-json"
    }

    @Test
    fun `tolerates trailing padding on base64url segments`() {
        // Some JWT producers include padding even though the spec omits it.
        val enc = Base64.getUrlEncoder()  // with padding
        val header = enc.encodeToString("""{"alg":"none"}""".toByteArray())
        val body = enc.encodeToString("""{"exp":42}""".toByteArray())
        val token = "$header.$body.sig"
        val parsed = JwtPayload.parse(token)!!
        assertEquals(42L, parsed.expiresAtSeconds)
    }
}
