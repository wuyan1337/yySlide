package sh.haven.core.data.repository

import kotlinx.coroutines.flow.Flow
import sh.haven.core.data.db.StepCaConfigDao
import sh.haven.core.data.db.entities.StepCaConfig
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores [StepCaConfig] rows. No encryption layer — every stored field is
 * a public/non-secret value (CA URL, issuer URL, public OIDC client ID,
 * root cert PEM). The OIDC ID token is the only secret in the sign flow
 * and it's never persisted; it lives in memory for one HTTP round-trip.
 */
@Singleton
class StepCaConfigRepository @Inject constructor(
    private val dao: StepCaConfigDao,
) {
    fun observeAll(): Flow<List<StepCaConfig>> = dao.observeAll()

    suspend fun getAll(): List<StepCaConfig> = dao.getAll()

    suspend fun getById(id: String): StepCaConfig? = dao.getById(id)

    suspend fun save(config: StepCaConfig) = dao.upsert(config)

    suspend fun delete(id: String) = dao.deleteById(id)
}
