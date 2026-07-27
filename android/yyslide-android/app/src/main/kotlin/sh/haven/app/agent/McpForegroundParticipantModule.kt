package sh.haven.app.agent

import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import sh.haven.core.ssh.ForegroundKeepAlive
import sh.haven.core.ssh.ForegroundSessionInfo
import sh.haven.core.ssh.ForegroundSessionParticipant

private data class McpEndpointSession(
    override val profileId: String,
    override val label: String,
) : ForegroundSessionInfo

/**
 * Contributes the MCP agent endpoint as a foreground "session" so the
 * SshConnectionService stays alive (and the user sees the endpoint in
 * the notification) for as long as [McpServer.isRunning].
 *
 * Why: McpServer otherwise lives only in the Application process. When
 * Haven backgrounds and the OS reclaims that process, the server's
 * accept loop dies with no notice to connected clients. Making MCP a
 * participant ties its lifetime to the existing `specialUse` FGS used
 * for SSH/Mosh/VNC/RDP/SFTP, which is the same Android-recognised
 * "long-lived user connection" the MCP endpoint also represents.
 *
 * [disconnectAll] calls into McpServer.stop() so the "Disconnect All"
 * notification action stops the endpoint too — consistent with the
 * other transports.
 */
@Module
@InstallIn(SingletonComponent::class)
object McpForegroundParticipantModule {

    // McpServer is injected lazily because McpServer itself depends on
    // SessionManagerRegistry (for the inspect_proot MCP tool), and
    // SessionManagerRegistry now depends on the Set<ForegroundKeepAlive>
    // contributed below. Lazy<T> breaks the construction cycle: the
    // FGS only ever reads server.isRunning at runtime, never during
    // graph construction.

    @Provides
    @IntoSet
    fun mcp(server: Lazy<McpServer>): ForegroundSessionParticipant =
        object : ForegroundSessionParticipant {
            override val activeSessions: List<ForegroundSessionInfo>
                get() = if (server.get().isRunning) {
                    listOf(
                        McpEndpointSession(
                            profileId = "mcp-agent-endpoint",
                            label = "MCP agent endpoint",
                        ),
                    )
                } else {
                    emptyList()
                }

            override fun disconnectAll() {
                server.get().stop()
            }
        }

    /**
     * Keep-alive contributor read by [SessionManagerRegistry.hasActiveSessions]
     * so an SSH disconnect doesn't tear down the FGS while the MCP
     * endpoint is still serving.
     */
    @Provides
    @IntoSet
    fun mcpKeepAlive(server: Lazy<McpServer>): ForegroundKeepAlive =
        object : ForegroundKeepAlive {
            override val isActive: Boolean get() = server.get().isRunning
        }
}
