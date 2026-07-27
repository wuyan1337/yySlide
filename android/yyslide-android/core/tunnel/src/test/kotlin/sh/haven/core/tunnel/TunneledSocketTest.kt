package sh.haven.core.tunnel

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress

class TunneledSocketTest {

    @Test
    fun inputStreamReadsFromConnection() {
        val payload = "hello".toByteArray()
        val s = TunneledSocket(FakeConn(payload), "example.com", 443)
        val read = s.getInputStream().readBytes()
        assertArrayEquals(payload, read)
    }

    @Test
    fun outputStreamWritesToConnection() {
        val sink = ByteArrayOutputStream()
        val s = TunneledSocket(FakeConn(output = sink), "example.com", 443)
        s.getOutputStream().write("world".toByteArray())
        assertArrayEquals("world".toByteArray(), sink.toByteArray())
    }

    @Test
    fun portMatchesConstructorArg() {
        val s = TunneledSocket(FakeConn(), "example.com", 1234)
        assertEquals(1234, s.port)
    }

    @Test
    fun inetAddressResolvesHostname() {
        val s = TunneledSocket(FakeConn(), "127.0.0.1", 80)
        assertNotNull(s.inetAddress)
        assertEquals("127.0.0.1", s.inetAddress?.hostAddress)
    }

    @Test
    fun closeIsIdempotent() {
        val conn = CountingConn()
        val s = TunneledSocket(conn, "h", 80)
        s.close()
        s.close()
        s.close()
        assertEquals(1, conn.closeCount)
        assertTrue(s.isClosed)
        assertFalse(s.isConnected)
    }

    @Test
    fun getInputStreamAfterCloseThrows() {
        val s = TunneledSocket(FakeConn(), "h", 80)
        s.close()
        try {
            s.getInputStream()
            fail("expected IOException after close")
        } catch (_: IOException) { /* expected */ }
    }

    @Test
    fun getOutputStreamAfterCloseThrows() {
        val s = TunneledSocket(FakeConn(), "h", 80)
        s.close()
        try {
            s.getOutputStream()
            fail("expected IOException after close")
        } catch (_: IOException) { /* expected */ }
    }

    @Test
    fun connectThrowsUnsupported() {
        val s = TunneledSocket(FakeConn(), "h", 80)
        try {
            s.connect(InetSocketAddress("x", 1))
            fail("expected UnsupportedOperationException")
        } catch (_: UnsupportedOperationException) { /* expected */ }
    }

    @Test
    fun bindThrowsUnsupported() {
        val s = TunneledSocket(FakeConn(), "h", 80)
        try {
            s.bind(InetSocketAddress("x", 1))
            fail("expected UnsupportedOperationException")
        } catch (_: UnsupportedOperationException) { /* expected */ }
    }

    private class FakeConn(
        input: ByteArray = ByteArray(0),
        output: OutputStream = ByteArrayOutputStream(),
    ) : TunneledConnection {
        override val inputStream: InputStream = ByteArrayInputStream(input)
        override val outputStream: OutputStream = output
        override fun close() {}
    }

    private class CountingConn : TunneledConnection {
        override val inputStream: InputStream = ByteArrayInputStream(ByteArray(0))
        override val outputStream: OutputStream = ByteArrayOutputStream()
        var closeCount: Int = 0
            private set
        override fun close() { closeCount++ }
    }
}
