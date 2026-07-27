package sh.haven.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import sh.haven.core.data.db.entities.ProotInstallLog
import sh.haven.core.data.db.entities.ProotInstallLogSummary

@Dao
interface ProotInstallLogDao {

    @Insert
    suspend fun insert(log: ProotInstallLog): Long

    @Query(
        "SELECT * FROM proot_install_log WHERE distroId = :distroId " +
            "ORDER BY timestamp DESC LIMIT :limit",
    )
    fun observeForDistro(distroId: String, limit: Int = 100): Flow<List<ProotInstallLog>>

    /** List query — excludes logTail to keep memory low. */
    @Query(
        "SELECT id, distroId, timestamp, phase, deId, exit, ok, message " +
            "FROM proot_install_log ORDER BY timestamp DESC LIMIT :limit",
    )
    fun observeAllSummary(limit: Int = 200): Flow<List<ProotInstallLogSummary>>

    /** Same as observeAllSummary but filtered to one distro. */
    @Query(
        "SELECT id, distroId, timestamp, phase, deId, exit, ok, message " +
            "FROM proot_install_log WHERE distroId = :distroId " +
            "ORDER BY timestamp DESC LIMIT :limit",
    )
    fun observeSummaryForDistro(distroId: String, limit: Int = 200): Flow<List<ProotInstallLogSummary>>

    /** One-shot snapshot suitable for an MCP read. Returns oldest-first so an
     * agent polling with `sinceMs` sees events in chronological order. */
    @Query(
        "SELECT * FROM proot_install_log " +
            "WHERE (:distroId IS NULL OR distroId = :distroId) " +
            "AND timestamp > :sinceMs " +
            "ORDER BY timestamp ASC LIMIT :limit",
    )
    suspend fun querySince(distroId: String?, sinceMs: Long, limit: Int): List<ProotInstallLog>

    /** Full row including logTail — loaded on demand when user expands. */
    @Query("SELECT * FROM proot_install_log WHERE id = :id")
    suspend fun getById(id: Long): ProotInstallLog?

    @Query("DELETE FROM proot_install_log WHERE timestamp < :olderThan")
    suspend fun deleteOlderThan(olderThan: Long)

    @Query("DELETE FROM proot_install_log WHERE distroId = :distroId")
    suspend fun deleteForDistro(distroId: String)

    @Query("DELETE FROM proot_install_log")
    suspend fun deleteAll()
}
