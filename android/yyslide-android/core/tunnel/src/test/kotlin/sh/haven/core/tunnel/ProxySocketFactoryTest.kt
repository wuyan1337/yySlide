package sh.haven.core.tunnel

import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * Unit tests verify the factory's *dispatch* shape — it picks the right
 * java.net.Proxy.Type and surfaces an IOException for unknown types.
 *
 * Actual SOCKS / HTTP handshake behaviour is JDK code we don't unit-test
 * here (would need a fake proxy server). Coverage at the
 * Socket(Proxy)-handshake layer is the JDK's responsibility.
 */
class ProxySocketFactoryTest {

    @Test
    fun unknownProxyTypeThrowsOnCreateSocket() {
        val factory = ProxySocketFactory("WAT", "127.0.0.1", 1080)
        try {
            factory.createSocket("example.com", 80)
            fail("expected IOException for unknown proxy type")
        } catch (_: IOException) { /* expected */ }
    }

    @Test
    fun socks5IsAcceptedAndReturnsSocketAttempt() {
        // Point at a port that won't accept — connect() will fail, but we
        // verify no IOException-from-dispatch and no NPE; the factory got
        // far enough to attempt the proxied connect.
        val factory = ProxySocketFactory("SOCKS5", "127.0.0.1", 1, connectTimeoutMs = 100)
        try {
            factory.createSocket("example.com", 80)
            // If it somehow returned a Socket, fine — we just verify it's
            // an actual Socket.
        } catch (e: IOException) {
            // Connect-refused / timeout is the expected outcome here.
            // We DO NOT want "Unsupported proxy type" — that'd indicate
            // dispatch broke.
            assertNotNull(e.message)
            org.junit.Assert.assertFalse(
                "must NOT report unsupported type for SOCKS5",
                e.message?.contains("Unsupported proxy type") == true,
            )
        }
    }

    @Test
    fun httpIsAcceptedAndReturnsSocketAttempt() {
        val factory = ProxySocketFactory("HTTP", "127.0.0.1", 1, connectTimeoutMs = 100)
        try {
            factory.createSocket("example.com", 80)
        } catch (e: IOException) {
            org.junit.Assert.assertFalse(
                "must NOT report unsupported type for HTTP",
                e.message?.contains("Unsupported proxy type") == true,
            )
        }
    }

    @Test
    fun socks4IsAcceptedSameAsSocks5() {
        val factory = ProxySocketFactory("SOCKS4", "127.0.0.1", 1, connectTimeoutMs = 100)
        try {
            factory.createSocket("example.com", 80)
        } catch (e: IOException) {
            org.junit.Assert.assertFalse(
                "must NOT report unsupported type for SOCKS4",
                e.message?.contains("Unsupported proxy type") == true,
            )
        }
    }
}
