package sh.haven.core.data.repository

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import sh.haven.core.data.db.ConnectionLogDao
import sh.haven.core.data.db.entities.ConnectionLog
import sh.haven.core.data.db.entities.ConnectionLogSummary
import sh.haven.core.data.preferences.UserPreferencesRepository
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ConnectionLogRepository"

@Singleton
class ConnectionLogRepository @Inject constructor(
    private val connectionLogDao: ConnectionLogDao,
    private val preferencesRepository: UserPreferencesRepository,
) {
    suspend fun logEvent(
        profileId: String,
        status: ConnectionLog.Status,
        durationMs: Long = 0,
        details: String? = null,
        verboseLog: String? = null,
    ) {
        if (!preferencesRepository.connectionLoggingEnabled.first()) return
        // Audit logging must never take the app down. The connection_logs
        // table has a foreign key to connection_profiles, so an insert with
        // a profileId that has no matching profile throws
        // SQLITE_CONSTRAINT_FOREIGNKEY — which, uncaught on a main
        // dispatcher, crashed the whole app when desktop start failures
        // logged a synthetic "desktop:<distro>:<de>" id (regression from the
        // #169/#162-B error-surfacing work). Callers shouldn't pass orphan
        // ids, but a logging call is non-critical and should degrade to a
        // no-op rather than crash if one slips through.
        try {
            connectionLogDao.insert(
                ConnectionLog(
                    profileId = profileId,
                    status = status,
                    durationMs = durationMs,
                    details = details,
                    verboseLog = verboseLog,
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "logEvent dropped for profileId=$profileId: ${e.message}")
        }
    }

    fun observeAllSummary(limit: Int = 200): Flow<List<ConnectionLogSummary>> =
        connectionLogDao.observeAllSummary(limit)

    fun observeForProfile(profileId: String, limit: Int = 50): Flow<List<ConnectionLog>> =
        connectionLogDao.observeForProfile(profileId, limit)

    suspend fun getById(id: Long): ConnectionLog? =
        connectionLogDao.getById(id)

    suspend fun clearAll() = connectionLogDao.deleteAll()
}
