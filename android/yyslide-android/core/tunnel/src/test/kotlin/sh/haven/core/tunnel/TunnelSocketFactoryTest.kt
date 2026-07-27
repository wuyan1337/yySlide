package sh.haven.core.tunnel

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress

class TunnelSocketFactoryTest {

    @Test
    fun createSocketDialsTunnelAndWrapsResult() {
        val conn = stubConn()
        val tunnel = mockk<Tunnel> {
            every { dial("example.com", 443, any()) } returns conn
        }
        val factory = TunnelSocketFactory(tunnel)

        val s = factory.createSocket("example.com", 443)

        assertTrue("createSocket must return a TunneledSocket", s is TunneledSocket)
        assertEquals(443, s.port)
        verify { tunnel.dial("example.com", 443, 30_000) }
    }

    @Test
    fun customTimeoutPassedThrough() {
        val conn = stubConn()
        val tunnel = mockk<Tunnel> {
            every { dial(any(), any(), 5_000) } returns conn
        }
        val factory = TunnelSocketFactory(tunnel, dialTimeoutMs = 5_000)

        factory.createSocket("h", 1)

        verify { tunnel.dial("h", 1, 5_000) }
    }

    @Test
    fun localBindFormDelegatesToHostPortForm() {
        val conn = stubConn()
        val tunnel = mockk<Tunnel> {
            every { dial("h", 80, any()) } returns conn
        }
        val factory = TunnelSocketFactory(tunnel)

        factory.createSocket("h", 80, InetAddress.getLoopbackAddress(), 12345)

        verify(exactly = 1) { tunnel.dial("h", 80, 30_000) }
    }

    @Test
    fun inetAddressFormResolvesToHostString() {
        val conn = stubConn()
        val tunnel = mockk<Tunnel> {
            every { dial("127.0.0.1", 22, any()) } returns conn
        }
        val factory = TunnelSocketFactory(tunnel)

        factory.createSocket(InetAddress.getByName("127.0.0.1"), 22)

        verify { tunnel.dial("127.0.0.1", 22, 30_000) }
    }

    private fun stubConn(): TunneledConnection = object : TunneledConnection {
        override val inputStream: InputStream = ByteArrayInputStream(ByteArray(0))
        override val outputStream: OutputStream = ByteArrayOutputStream()
        override fun close() {}
    }
}
