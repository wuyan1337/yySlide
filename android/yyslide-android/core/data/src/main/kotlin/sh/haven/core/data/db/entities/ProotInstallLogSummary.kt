package sh.haven.core.data.db.entities

/**
 * Projection of [ProotInstallLog] excluding [ProotInstallLog.logTail]
 * for list queries. Keeps memory low when rendering long install
 * histories — a failed install can carry 1.5KB of tail per row.
 */
data class ProotInstallLogSummary(
    val id: Long,
    val distroId: String,
    val timestamp: Long,
    val phase: String,
    val deId: String?,
    val exit: Int?,
    val ok: Boolean,
    val message: String?,
)
