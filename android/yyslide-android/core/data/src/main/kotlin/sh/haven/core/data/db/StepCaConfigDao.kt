package sh.haven.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import sh.haven.core.data.db.entities.StepCaConfig

@Dao
interface StepCaConfigDao {

    @Query("SELECT * FROM step_ca_configs ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<StepCaConfig>>

    @Query("SELECT * FROM step_ca_configs ORDER BY createdAt DESC")
    suspend fun getAll(): List<StepCaConfig>

    @Query("SELECT * FROM step_ca_configs WHERE id = :id")
    suspend fun getById(id: String): StepCaConfig?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(config: StepCaConfig)

    @Query("DELETE FROM step_ca_configs WHERE id = :id")
    suspend fun deleteById(id: String)
}
