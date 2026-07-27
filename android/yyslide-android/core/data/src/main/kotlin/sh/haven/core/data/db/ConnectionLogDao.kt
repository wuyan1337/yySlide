package sh.haven.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import sh.haven.core.data.db.entities.ConnectionLog
import sh.haven.core.data.db.entities.ConnectionLogSummary

@Dao
interface ConnectionLogDao {

    @Insert
    suspend fun insert(log: ConnectionLog)

    @Query(
        "SELECT * FROM connection_logs WHERE profileId = :profileId " +
        "ORDER BY timestamp DESC LIMIT :limit"
    )
    fun observeForProfile(profileId: String, limit: Int = 50): Flow<List<ConnectionLog>>

    /** List query — excludes verboseLog to keep memory low. */
    @Query(
        "SELECT id, profileId, timestamp, durationMs, status, details " +
        "FROM connection_logs ORDER BY timestamp DESC LIMIT :limit"
    )
    fun observeAllSummary(limit: Int = 200): Flow<List<ConnectionLogSummary>>

    /** Full row including verboseLog — loaded on demand when user expands. */
    @Query("SELECT * FROM connection_logs WHERE id = :id")
    suspend fun getById(id: Long): ConnectionLog?

    @Query("DELETE FROM connection_logs WHERE timestamp < :olderThan")
    suspend fun deleteOlderThan(olderThan: Long)

    @Query("DELETE FROM connection_logs")
    suspend fun deleteAll()
}
