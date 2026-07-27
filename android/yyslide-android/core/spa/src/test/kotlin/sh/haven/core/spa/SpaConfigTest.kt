package sh.haven.core.spa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpaConfigTest {

    private fun parse(
        key: String? = "secret",
        keyB64: Boolean = false,
        hmac: String? = null,
        hmacB64: Boolean = false,
        access: String? = "tcp/22",
        mode: String? = "SOURCE",
        ip: String? = null,
        port: Int = SpaConfig.DEFAULT_SPA_PORT,
    ) = SpaConfig.parse(key, keyB64, hmac, hmacB64, access, mode, ip, port)

    @Test
    fun `blank key disables SPA`() {
        assertNull(parse(key = "").getOrThrow())
        assertNull(parse(key = null).getOrThrow())
        assertNull(parse(key = "   ").getOrThrow())
    }

    @Test
    fun `blank access spec disables SPA`() {
        assertNull(parse(access = "").getOrThrow())
        assertNull(parse(access = null).getOrThrow())
    }

    @Test
    fun `valid minimal config parses`() {
        val cfg = parse().getOrThrow()!!
        assertEquals("secret", cfg.key)
        assertEquals("tcp/22", cfg.accessSpec)
        assertEquals(SpaConfig.AllowMode.SOURCE, cfg.allowMode)
        assertEquals(SpaConfig.DEFAULT_SPA_PORT, cfg.spaPort)
    }

    @Test
    fun `multi-port access spec is accepted`() {
        val cfg = parse(access = "tcp/22,udp/53").getOrThrow()!!
        assertEquals("tcp/22,udp/53", cfg.accessSpec)
        assertEquals("0.0.0.0,tcp/22,udp/53", cfg.accessMessage())
    }

    @Test
    fun `bad protocol fails`() {
        assertTrue(parse(access = "sctp/22").isFailure)
    }

    @Test
    fun `bad port fails`() {
        assertTrue(parse(access = "tcp/0").isFailure)
        assertTrue(parse(access = "tcp/70000").isFailure)
        assertTrue(parse(access = "tcp/abc").isFailure)
        assertTrue(parse(access = "tcp").isFailure)
    }

    @Test
    fun `explicit mode requires an ip`() {
        assertTrue(parse(mode = "EXPLICIT", ip = null).isFailure)
        val cfg = parse(mode = "EXPLICIT", ip = "203.0.113.7").getOrThrow()!!
        assertEquals("203.0.113.7,tcp/22", cfg.accessMessage())
    }

    @Test
    fun `unknown allow mode fails`() {
        assertTrue(parse(mode = "BOGUS").isFailure)
    }

    @Test
    fun `invalid base64 key fails`() {
        assertTrue(parse(key = "not valid base64!!!", keyB64 = true).isFailure)
    }

    @Test
    fun `out-of-range spa port falls back to default`() {
        assertEquals(SpaConfig.DEFAULT_SPA_PORT, parse(port = 0).getOrThrow()!!.spaPort)
        assertEquals(SpaConfig.DEFAULT_SPA_PORT, parse(port = 99999).getOrThrow()!!.spaPort)
    }

    @Test
    fun `resolve mode needs resolved ip at message time`() {
        val cfg = parse(mode = "RESOLVE").getOrThrow()!!
        assertEquals("198.51.100.4,tcp/22", cfg.accessMessage("198.51.100.4"))
        assertTrue(runCatching { cfg.accessMessage(null) }.isFailure)
    }
}
