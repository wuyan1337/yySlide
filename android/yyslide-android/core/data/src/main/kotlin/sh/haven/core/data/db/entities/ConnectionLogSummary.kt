package sh.haven.core.data.db.entities

/**
 * Projection of [ConnectionLog] excluding verboseLog for list queries.
 * Keeps memory low when displaying the audit log list.
 */
data class ConnectionLogSummary(
    val id: Long,
    val profileId: String,
    val timestamp: Long,
    val durationMs: Long,
    val status: ConnectionLog.Status,
    val details: String?,
)
