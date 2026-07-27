package sh.haven.core.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import sh.haven.core.data.db.ProotInstallLogDao
import sh.haven.core.data.db.entities.ProotInstallLog
import sh.haven.core.data.db.entities.ProotInstallLogSummary
import sh.haven.core.data.preferences.UserPreferencesRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Durable per-phase install log for the proot distro/DE pipeline.
 * Mirrors [ConnectionLogRepository] line-for-line — same diagnostic
 * preference gate, same observable summary, same on-demand row fetch.
 *
 * Survives logcat rotation (Android 14+ restricts third-party-PID
 * filtering for non-debuggable apps) and Haven process restarts.
 */
@Singleton
class ProotInstallLogRepository @Inject constructor(
    private val dao: ProotInstallLogDao,
    private val preferencesRepository: UserPreferencesRepository,
) {
    /**
     * Append one event. Silently no-ops when diagnostic logging is
     * disabled — same gate as connection logs, so the user toggles
     * "logging" once.
     */
    suspend fun logEvent(
        distroId: String,
        phase: String,
        deId: String? = null,
        exit: Int? = null,
        ok: Boolean = true,
        message: String? = null,
        logTail: String? = null,
    ): Long {
        if (!preferencesRepository.connectionLoggingEnabled.first()) return -1
        return dao.insert(
            ProotInstallLog(
                distroId = distroId,
                phase = phase,
                deId = deId,
                exit = exit,
                ok = ok,
                message = message,
                logTail = logTail,
            ),
        )
    }

    fun observeAllSummary(limit: Int = 200): Flow<List<ProotInstallLogSummary>> =
        dao.observeAllSummary(limit)

    fun observeSummaryForDistro(distroId: String, limit: Int = 200): Flow<List<ProotInstallLogSummary>> =
        dao.observeSummaryForDistro(distroId, limit)

    fun observeForDistro(distroId: String, limit: Int = 100): Flow<List<ProotInstallLog>> =
        dao.observeForDistro(distroId, limit)

    suspend fun querySince(distroId: String?, sinceMs: Long, limit: Int): List<ProotInstallLog> =
        dao.querySince(distroId, sinceMs, limit)

    suspend fun getById(id: Long): ProotInstallLog? = dao.getById(id)

    suspend fun deleteForDistro(distroId: String) = dao.deleteForDistro(distroId)

    suspend fun clearAll() = dao.deleteAll()
}
