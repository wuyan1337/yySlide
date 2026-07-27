package sh.haven.integration

import com.google.protobuf.ExtensionRegistryLite
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import sh.haven.mosh.crypto.MoshCrypto
import sh.haven.mosh.network.MoshConnection
import sh.haven.mosh.proto.Hostinput
import sh.haven.mosh.proto.Userinput
import sh.haven.mosh.proto.Transportinstruction.Instruction as TransportInstruction

/**
 * Low-level mosh connectivity test — bypasses the transport to test
 * the connection + crypto layer directly.
 */
class MoshConnectivityTest {

    @Before
    fun setUp() {
        TestServer.requireConfigured()
    }

    @Test
    fun `mosh key parses to valid AES-128 key`() {
        val session = TestServer.sshConnect()
        try {
            val result = TestServer.exec(session, "mosh-server new -s -c 256 -l LANG=en_US.UTF-8")
            val output = result.stdout + "\n" + result.stderr
            val connectLine = output.lines().first { it.startsWith("MOSH CONNECT") }
            val key = connectLine.split(" ")[3]

            println("Key: $key (${key.length} chars)")
            val crypto = MoshCrypto(key)
            assertNotNull("Crypto should initialise", crypto)
        } finally {
            session.disconnect()
        }
    }

    @Test
    fun `mosh connection sends and receives first packet`() {
        val session = TestServer.sshConnect()
        try {
            val result = TestServer.exec(session, "mosh-server new -s -c 256 -l LANG=en_US.UTF-8")
            val output = result.stdout + "\n" + result.stderr
            val connectLine = output.lines().first { it.startsWith("MOSH CONNECT") }
            val parts = connectLine.split(" ")
            val moshPort = parts[2].toInt()
            val moshKey = parts[3]

            println("Connecting to ${TestServer.host}:$moshPort")

            val crypto = MoshCrypto(moshKey)
            val conn = MoshConnection(TestServer.host, moshPort, crypto)

            // Send an empty keepalive instruction (what the transport sends initially)
            val keepalive = TransportInstruction.newBuilder().apply {
                oldNum = 0
                newNum = 0
                ackNum = 0
            }.build()
            conn.sendInstruction(keepalive)
            println("Sent initial keepalive")

            // Try to receive server's response
            val response = conn.receiveInstruction(5000)
            assertNotNull("Should receive response from mosh-server within 5s", response)
            println("Received instruction: oldNum=${response!!.oldNum} newNum=${response.newNum} ackNum=${response.ackNum}")
            assertTrue("Server should have newNum > 0", response.newNum > 0)

            conn.close()
        } finally {
            session.disconnect()
        }
    }

    /**
     * Low-level protocol test: sends an initial resize (which triggers mosh-server's
     * child release) and verifies terminal output arrives in subsequent diffs.
     * Without the resize, mosh-server never releases the shell and all diffs are empty.
     */
    @Test
    fun `mosh initial resize triggers terminal output`() {
        val session = TestServer.sshConnect()
        try {
            val result = TestServer.exec(session, "mosh-server new -s -c 256 -l LANG=en_US.UTF-8")
            val output = result.stdout + "\n" + result.stderr
            val connectLine = output.lines().first { it.startsWith("MOSH CONNECT") }
            val parts = connectLine.split(" ")
            val moshPort = parts[2].toInt()
            val moshKey = parts[3]

            val crypto = MoshCrypto(moshKey)
            val conn = MoshConnection(TestServer.host, moshPort, crypto)
            val registry = ExtensionRegistryLite.newInstance().also {
                Hostinput.registerAllExtensions(it)
            }

            // Build a resize diff (UserMessage with one Resize instruction)
            val resizeMsg = Userinput.UserMessage.newBuilder()
                .addInstruction(
                    Userinput.Instruction.newBuilder()
                        .setExtension(Userinput.resize,
                            Userinput.ResizeMessage.newBuilder()
                                .setWidth(80).setHeight(24).build())
                )
                .build()
            val resizeDiff = resizeMsg.toByteArray()

            // Send instruction with newNum=1 (resize makes state advance from 0 to 1)
            val inst = TransportInstruction.newBuilder()
                .setProtocolVersion(2)
                .setOldNum(0).setNewNum(1).setAckNum(0).setThrowawayNum(0)
                .setDiff(com.google.protobuf.ByteString.copyFrom(resizeDiff))
                .build()
            conn.sendInstruction(inst)
            println("Sent initial resize instruction")

            var remoteStateNum = 0L
            var foundTerminalOutput = false

            for (round in 1..20) {
                // Send ack with current remoteStateNum
                val ack = TransportInstruction.newBuilder()
                    .setProtocolVersion(2)
                    .setOldNum(0).setNewNum(1).setAckNum(remoteStateNum).setThrowawayNum(0)
                    .setDiff(com.google.protobuf.ByteString.copyFrom(resizeDiff))
                    .build()
                if (round > 1) conn.sendInstruction(ack)

                val response = conn.receiveInstruction(2000) ?: continue

                if (response.newNum > remoteStateNum) {
                    remoteStateNum = response.newNum
                }

                if (response.hasDiff() && !response.diff.isEmpty) {
                    val hostMsg = Hostinput.HostMessage.parseFrom(response.diff, registry)
                    for (hi in hostMsg.instructionList) {
                        if (hi.hasExtension(Hostinput.hostbytes)) {
                            val text = hi.getExtension(Hostinput.hostbytes).hoststring.toStringUtf8()
                            println("Round $round: terminal output (${text.length} chars): ${text.take(80).replace("\n", "\\n")}")
                            if (text.isNotEmpty()) foundTerminalOutput = true
                        }
                    }
                } else {
                    println("Round $round: newNum=${response.newNum} (no diff)")
                }

                if (foundTerminalOutput) break
            }

            assertTrue("Should receive terminal output after sending initial resize", foundTerminalOutput)
            conn.close()
        } finally {
            session.disconnect()
        }
    }

}
