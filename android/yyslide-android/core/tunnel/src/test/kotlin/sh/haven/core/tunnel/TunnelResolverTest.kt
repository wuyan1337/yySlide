package sh.haven.core.tunnel

import com.jcraft.jsch.ProxyHTTP
import com.jcraft.jsch.ProxySOCKS4
import com.jcraft.jsch.ProxySOCKS5
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.data.db.entities.ConnectionProfile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

class TunnelResolverTest {

    @Test
    fun dialReturnsNullWhenProfileHasNoTunnelConfigId() = runTest {
        val resolver = TunnelResolver(mockk(relaxed = true))
        assertNull(resolver.dial(profile(tunnelConfigId = null), "h", 80, 30_000))
    }

    @Test
    fun dialReturnsNullWhenTunnelConfigDeleted() = runTest {
        val mgr = mockk<TunnelManager> {
            coEvery { acquire("missing", any()) } returns null
        }
        val resolver = TunnelResolver(mgr)
        assertNull(resolver.dial(profile(tunnelConfigId = "missing"), "h", 80, 30_000))
    }

    @Test
    fun dialDelegatesToTunnelWhenConfigPresent() = runTest {
        val conn = stubConn()
        val tunnel = mockk<Tunnel> {
            every { dial("example.com", 443, 5_000) } returns conn
        }
        val mgr = mockk<TunnelManager> {
            coEvery { acquire("tid", any()) } returns tunnel
        }
        val resolver = TunnelResolver(mgr)

        val result = resolver.dial(profile(tunnelConfigId = "tid"), "example.com", 443, 5_000)

        assertSame(conn, result)
    }

    @Test
    fun socketFactoryReturnsNullWhenNoTunnel() = runTest {
        val resolver = TunnelResolver(mockk(relaxed = true))
        assertNull(resolver.socketFactory(profile(tunnelConfigId = null)))
    }

    @Test
    fun socketFactoryReturnsTunnelSocketFactoryWhenConfigPresent() = runTest {
        val tunnel = mockk<Tunnel>(relaxed = true)
        val mgr = mockk<TunnelManager> {
            coEvery { acquire("tid", any()) } returns tunnel
        }
        val resolver = TunnelResolver(mgr)

        val factory = resolver.socketFactory(profile(tunnelConfigId = "tid"))

        assertNotNull(factory)
        assertTrue(factory is TunnelSocketFactory)
    }

    @Test
    fun socksEndpointReturnsNullWhenProfileHasNoTunnel() = runTest {
        val resolver = TunnelResolver(mockk(relaxed = true))
        assertNull(resolver.socksEndpoint(profile(tunnelConfigId = null)))
    }

    @Test
    fun socksEndpointForwardsToTunnelSocksAddress() = runTest {
        val expected = SocksEndpoint(
            address = java.net.InetSocketAddress("127.0.0.1", 41234),
            username = "yyslide",
            password = "test-password",
        )
        val tunnel = mockk<Tunnel> {
            every { socksAddress() } returns expected
        }
        val mgr = mockk<TunnelManager> {
            coEvery { acquire("tid", any()) } returns tunnel
        }
        val resolver = TunnelResolver(mgr)

        val addr = resolver.socksEndpoint(profile(tunnelConfigId = "tid"))

        assertSame(expected, addr)
    }

    @Test
    fun socksEndpointReturnsNullWhenTunnelOptsOut() = runTest {
        val tunnel = mockk<Tunnel> {
            every { socksAddress() } returns null
        }
        val mgr = mockk<TunnelManager> {
            coEvery { acquire("tid", any()) } returns tunnel
        }
        val resolver = TunnelResolver(mgr)
        assertNull(resolver.socksEndpoint(profile(tunnelConfigId = "tid")))
    }

    @Test
    fun jschProxyReturnsTunnelProxyWhenConfigPresent() = runTest {
        val tunnel = mockk<Tunnel>(relaxed = true)
        val mgr = mockk<TunnelManager> {
            coEvery { acquire("tid", any()) } returns tunnel
        }
        val resolver = TunnelResolver(mgr)

        val proxy = resolver.jschProxy(profile(tunnelConfigId = "tid"))

        assertNotNull(proxy)
        assertTrue(proxy is TunnelProxy)
    }

    @Test
    fun jschProxyReturnsSocks5WhenSet() = runTest {
        val resolver = TunnelResolver(mockk(relaxed = true))
        val proxy = resolver.jschProxy(profile(proxyType = "SOCKS5", proxyHost = "127.0.0.1", proxyPort = 1080))
        assertTrue(proxy is ProxySOCKS5)
    }

    @Test
    fun jschProxyReturnsSocks4WhenSet() = runTest {
        val resolver = TunnelResolver(mockk(relaxed = true))
        val proxy = resolver.jschProxy(profile(proxyType = "SOCKS4", proxyHost = "127.0.0.1", proxyPort = 1080))
        assertTrue(proxy is ProxySOCKS4)
    }

    @Test
    fun jschProxyReturnsHttpWhenSet() = runTest {
        val resolver = TunnelResolver(mockk(relaxed = true))
        val proxy = resolver.jschProxy(profile(proxyType = "HTTP", proxyHost = "127.0.0.1", proxyPort = 8080))
        assertTrue(proxy is ProxyHTTP)
    }

    @Test
    fun jschProxyTunnelTakesPrecedenceOverSocks() = runTest {
        val tunnel = mockk<Tunnel>(relaxed = true)
        val mgr = mockk<TunnelManager> {
            coEvery { acquire("tid", any()) } returns tunnel
        }
        val resolver = TunnelResolver(mgr)
        val p = profile(tunnelConfigId = "tid", proxyType = "SOCKS5", proxyHost = "127.0.0.1", proxyPort = 1080)

        val proxy = resolver.jschProxy(p)

        assertTrue("tunnel must take precedence over legacy SOCKS proxy", proxy is TunnelProxy)
    }

    @Test
    fun jschProxyReturnsNullForDirectProfile() = runTest {
        val resolver = TunnelResolver(mockk(relaxed = true))
        assertNull(resolver.jschProxy(profile()))
    }

    @Test
    fun jschProxyReturnsNullForUnknownProxyType() = runTest {
        val resolver = TunnelResolver(mockk(relaxed = true))
        assertNull(resolver.jschProxy(profile(proxyType = "WAT", proxyHost = "x", proxyPort = 1)))
    }

    @Test
    fun socketFactoryReturnsProxyFactoryWhenOnlyProxySet() = runTest {
        val resolver = TunnelResolver(mockk(relaxed = true))
        val factory = resolver.socketFactory(
            profile(proxyType = "SOCKS5", proxyHost = "127.0.0.1", proxyPort = 1080),
        )
        assertNotNull(factory)
        assertTrue(factory is ProxySocketFactory)
    }

    @Test
    fun socketFactoryPrefersTunnelOverProxy() = runTest {
        val tunnel = mockk<Tunnel>(relaxed = true)
        val mgr = mockk<TunnelManager> {
            coEvery { acquire("tid", any()) } returns tunnel
        }
        val resolver = TunnelResolver(mgr)
        val p = profile(tunnelConfigId = "tid", proxyType = "SOCKS5", proxyHost = "127.0.0.1", proxyPort = 1080)

        val factory = resolver.socketFactory(p)

        assertTrue("tunnel must take precedence", factory is TunnelSocketFactory)
    }

    @Test
    fun socketFactoryReturnsNullWhenNeitherTunnelNorProxy() = runTest {
        val resolver = TunnelResolver(mockk(relaxed = true))
        assertNull(resolver.socketFactory(profile()))
    }

    @Test
    fun releaseForwardsToManager() = runTest {
        val mgr = mockk<TunnelManager>(relaxed = true)
        val resolver = TunnelResolver(mgr)

        resolver.release("profile-A")

        coVerify { mgr.release("profile-A") }
    }

    private fun profile(
        tunnelConfigId: String? = null,
        proxyType: String? = null,
        proxyHost: String? = null,
        proxyPort: Int = 0,
    ): ConnectionProfile = ConnectionProfile(
        label = "test",
        host = "example.com",
        username = "user",
        tunnelConfigId = tunnelConfigId,
        proxyType = proxyType,
        proxyHost = proxyHost,
        proxyPort = proxyPort,
    )

    private fun stubConn(): TunneledConnection = object : TunneledConnection {
        override val inputStream: InputStream = ByteArrayInputStream(ByteArray(0))
        override val outputStream: OutputStream = ByteArrayOutputStream()
        override fun close() {}
    }
}
