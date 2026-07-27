package sh.haven.core.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One observable event from the agent transport (currently MCP). Records
 * what was called, when, by whom (where "whom" is whatever client name
 * the peer announced in the MCP `initialize.clientInfo`), and how it
 * resolved.
 *
 * Stored separately from `connection_logs` because the lifecycle is
 * different (no profile FK, shorter retention) and because "wipe agent
 * history" should be a one-table delete the user can trust.
 *
 * Privacy contract: argsJson is run through a redactor before insertion
 * so password/token/secret-shaped fields never reach disk. resultSummary
 * is a one-line synopsis ("12 connections") rather than the full payload
 * — the audit log is a record of *what was asked*, not a mirror of
 * everything the agent ever read.
 */
@Entity(
    tableName = "agent_audit_events",
    indices = [Index("timestamp")],
)
data class AgentAuditEvent(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    /** MCP `initialize.clientInfo.name`, or null if the client never sent one. */
    val clientHint: String? = null,
    /** JSON-RPC method: `initialize`, `tools/list`, `tools/call`, etc. */
    val method: String,
    /** Populated for `tools/call`; null otherwise. */
    val toolName: String? = null,
    /** Redacted argument JSON. May be null for methods that take no args. */
    val argsJson: String? = null,
    /** One-line synopsis of the result, never the full payload. */
    val resultSummary: String? = null,
    val durationMs: Long = 0,
    val outcome: Outcome = Outcome.OK,
    /** Populated when [outcome] is ERROR or DENIED. */
    val errorMessage: String? = null,
) {
    enum class Outcome { OK, DENIED, ERROR }
}
