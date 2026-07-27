package sh.haven.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import sh.haven.core.data.db.entities.TotpSecret

@Dao
interface TotpSecretDao {

    @Query("SELECT * FROM totp_secrets ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<TotpSecret>>

    @Query("SELECT * FROM totp_secrets ORDER BY createdAt DESC")
    suspend fun getAll(): List<TotpSecret>

    @Query("SELECT * FROM totp_secrets WHERE id = :id")
    suspend fun getById(id: String): TotpSecret?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(secret: TotpSecret)

    @Query("DELETE FROM totp_secrets WHERE id = :id")
    suspend fun deleteById(id: String)
}
