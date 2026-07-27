package sh.haven.core.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import sh.haven.core.data.db.entities.ConnectionGroup

@Dao
interface ConnectionGroupDao {
    @Query("SELECT * FROM connection_groups ORDER BY sortOrder ASC, label ASC")
    fun observeAll(): Flow<List<ConnectionGroup>>

    @Query("SELECT * FROM connection_groups ORDER BY sortOrder ASC, label ASC")
    suspend fun getAll(): List<ConnectionGroup>

    @Query("SELECT * FROM connection_groups WHERE id = :id")
    suspend fun getById(id: String): ConnectionGroup?

    @Upsert
    suspend fun upsert(group: ConnectionGroup)

    @Query("DELETE FROM connection_groups WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE connection_groups SET collapsed = :collapsed WHERE id = :id")
    suspend fun updateCollapsed(id: String, collapsed: Boolean)

    @Query("UPDATE connection_groups SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun updateSortOrder(id: String, sortOrder: Int)
}
