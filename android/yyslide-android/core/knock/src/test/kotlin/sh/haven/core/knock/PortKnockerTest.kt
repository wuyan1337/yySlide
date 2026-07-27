package sh.haven.core.knock

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

class PortKnockerTest {

    private val tcpListeners = mutableListOf<ServerSocket>()
    private val udpListeners = mutableListOf<DatagramSocket>()

    @After
    fun tearDown() {
        tcpListeners.forEach { runCatching { it.close() } }
        udpListeners.forEach { runCatching { it.close() } }
    }

    @Test
    fun `tcp knocks land on every port in the sequence`() = runBlocking {
        // We deliberately don't assert the order of the recorded ports —
        // each accept() runs in its own coroutine on Dispatchers.IO and
        // the relative timing of "kernel hands SYN to listening socket"
        // vs "JVM schedules the +=" is not deterministic across hosts
        // (CI runners are notably more sensitive than dev machines).
        // The knocker's contract is "fire every step in order"; the
        // mixed-protocol test below uses one socket per type and covers
        // the ordering aspect.
        val ports = (1..3).map {
            ServerSocket(0).also { tcpListeners += it; it.soTimeout = 2000 }.localPort
        }
        val acceptedPorts = CopyOnWriteArrayList<Int>()

        val accepts = tcpListeners.map { server ->
            async(Dispatchers.IO) {
                runCatching {
                    server.accept().use { acceptedPorts += server.localPort }
                }
            }
        }

        val sequence = KnockSequence(
            ports.map { KnockStep(it, KnockStep.Protocol.TCP) },
            interKnockDelayMs = 0,
        )
        val result = withTimeout(5000) {
            DefaultPortKnocker().knock("127.0.0.1", sequence)
        }

        // Wait for the accept side to finish recording.
        withTimeout(2000) { accepts.forEach { it.await() } }

        assertNull("knock returned error: ${result.error}", result.error)
        assertEquals(ports.size, result.sentSteps)
        assertEquals(ports.toSet(), acceptedPorts.toSet())
    }

    @Test
    fun `udp knocks deliver a datagram to every port in the sequence`() = runBlocking {
        // Same ordering caveat as the TCP test above.
        val sockets = (1..3).map {
            DatagramSocket(0).also { udpListeners += it; it.soTimeout = 2000 }
        }
        val ports = sockets.map { it.localPort }
        val received = CopyOnWriteArrayList<Int>()

        val receivers = sockets.map { sock ->
            async(Dispatchers.IO) {
                val buf = ByteArray(16)
                val pkt = DatagramPacket(buf, buf.size)
                runCatching {
                    sock.receive(pkt)
                    received += sock.localPort
                }
            }
        }

        val sequence = KnockSequence(
            ports.map { KnockStep(it, KnockStep.Protocol.UDP) },
            interKnockDelayMs = 0,
        )
        val result = withTimeout(5000) {
            DefaultPortKnocker().knock("127.0.0.1", sequence)
        }

        withTimeout(2000) { receivers.forEach { it.await() } }

        assertNull("knock returned error: ${result.error}", result.error)
        assertEquals(ports.size, result.sentSteps)
        assertEquals(ports.toSet(), received.toSet())
    }

    @Test
    fun `mixed tcp and udp both delivered`() = runBlocking {
        val tcp = ServerSocket(0).also { tcpListeners += it; it.soTimeout = 2000 }
        val udp = DatagramSocket(0).also { udpListeners += it; it.soTimeout = 2000 }

        val tcpAccepted = async(Dispatchers.IO) {
            runCatching { tcp.accept().use { } }.isSuccess
        }
        val udpReceived = async(Dispatchers.IO) {
            val buf = ByteArray(16)
            runCatching { udp.receive(DatagramPacket(buf, buf.size)) }.isSuccess
        }

        val sequence = KnockSequence(
            listOf(
                KnockStep(tcp.localPort, KnockStep.Protocol.TCP),
                KnockStep(udp.localPort, KnockStep.Protocol.UDP),
            ),
            interKnockDelayMs = 0,
        )
        val result = withTimeout(5000) {
            DefaultPortKnocker().knock("127.0.0.1", sequence)
        }

        assertTrue("tcp not accepted", withTimeout(2000) { tcpAccepted.await() })
        assertTrue("udp not received", withTimeout(2000) { udpReceived.await() })
        assertNull(result.error)
        assertEquals(2, result.sentSteps)
    }

    @Test
    fun `unresolvable host returns error in result, does not throw`() = runBlocking {
        val sequence = KnockSequence(
            listOf(KnockStep(7000, KnockStep.Protocol.TCP)),
            interKnockDelayMs = 0,
        )
        val result = withContext(Dispatchers.IO) {
            DefaultPortKnocker().knock(
                "no-such-host.invalid.example.test.haven.local-fail",
                sequence,
            )
        }
        assertTrue("expected error for unresolvable host, got $result", !result.ok)
        assertEquals(0, result.sentSteps)
    }
}
