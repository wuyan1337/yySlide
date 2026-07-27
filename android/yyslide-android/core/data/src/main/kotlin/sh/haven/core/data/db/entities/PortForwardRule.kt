package sh.haven.core.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "port_forward_rules",
    foreignKeys = [
        ForeignKey(
            entity = ConnectionProfile::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("profileId")],
)
data class PortForwardRule(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val profileId: String,
    val type: Type,
    val bindAddress: String = "127.0.0.1",
    val bindPort: Int,
    /** Ignored for DYNAMIC type (SOCKS proxy server has no single target). */
    val targetHost: String = "localhost",
    /** Ignored for DYNAMIC type. */
    val targetPort: Int = 0,
    val enabled: Boolean = true,
) {
    /**
     * LOCAL  — `-L`: local port → remote host:port (forward into the tunnel)
     * REMOTE — `-R`: remote port → local host:port (forward out of the tunnel)
     * DYNAMIC — `-D`: local port becomes a SOCKS proxy server (tunnel to arbitrary hosts)
     */
    enum class Type { LOCAL, REMOTE, DYNAMIC }
}
