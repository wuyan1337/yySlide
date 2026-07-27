package sh.haven.core.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * A saved rclone sync configuration the user can recall from the SFTP
 * folder-sync dialog. Mirrors `sh.haven.core.rclone.SyncConfig` plus
 * `SyncFilters`, but stored independently of those classes so `core/data`
 * doesn't gain a dependency on `core/rclone`.
 *
 * Mode is stored as a String of `"COPY" | "SYNC" | "MOVE"` (matching
 * `SyncMode.name`); include/exclude pattern lists are joined with `\n`
 * because the dialog already accepts/exposes them that way.
 *
 * Not foreign-keyed to any profile: a saved config can reference rclone
 * remotes that aren't yet imported as `ConnectionProfile` rows (cross-
 * remote syncs, fresh device, etc.).
 *
 * Filed as #159.
 */
@Entity(tableName = "sync_profiles")
data class SyncProfile(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val srcFs: String,
    val dstFs: String,
    /** "COPY" | "SYNC" | "MOVE" — matches `sh.haven.core.rclone.SyncMode.name`. */
    val mode: String,
    val includePatterns: String = "",
    val excludePatterns: String = "",
    val minSize: String? = null,
    val maxSize: String? = null,
    val bandwidthLimit: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val lastRunAt: Long? = null,
)
