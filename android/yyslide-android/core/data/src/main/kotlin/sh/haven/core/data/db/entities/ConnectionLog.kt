package sh.haven.core.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "connection_logs",
    foreignKeys = [
        ForeignKey(
            entity = ConnectionProfile::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("profileId")],
)
data class ConnectionLog(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val profileId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val durationMs: Long = 0,
    val status: Status = Status.CONNECTED,
    val details: String? = null,
    val verboseLog: String? = null,
) {
    enum class Status {
        CONNECTED,
        DISCONNECTED,
        FAILED,
        TIMEOUT,

        // rclone sync result statuses (#158). Reusing CONNECTED/FAILED for
        // sync events overloaded the UI ("Connected (Mirror: 1 deleted)"
        // is jarring), and the audit-log row icon would be wrong. Adding
        // dedicated values is safe — Room serialises enums by .name and
        // existing rows still deserialise to the original four values.
        SYNC_OK,
        SYNC_FAILED,
    }
}
