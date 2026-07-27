package sh.haven.core.ssh

import android.util.Log
import com.jcraft.jsch.Channel
import com.jcraft.jsch.Proxy
import com.jcraft.jsch.Session
import com.jcraft.jsch.SocketFactory
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

private const val TAG = "ProxyJump"

/**
 * JSch [Proxy] implementation that tunnels through an existing SSH session
 * using a `direct-tcpip` channel, equivalent to `ssh -J` (ProxyJump).
 *
 * The jump host [Session] must already be connected before this proxy is used.
 */
class ProxyJump(private val jumpSession: Session) : Proxy {

    private var channel: Channel? = null

    override fun connect(factory: SocketFactory?, host: String, port: Int, timeout: Int) {
        Log.d(TAG, "Opening direct-tcpip channel to $host:$port (timeout=${timeout}ms, jumpConnected=${jumpSession.isConnected})")
        channel = jumpSession.getStreamForwarder(host, port).also {
            it.connect(timeout)
        }
        Log.d(TAG, "Channel connected to $host:$port")
    }

    override fun getInputStream(): InputStream = channel!!.inputStream

    override fun getOutputStream(): OutputStream = channel!!.outputStream

    override fun getSocket(): Socket? = null

    override fun close() {
        channel?.disconnect()
        channel = null
    }
}
