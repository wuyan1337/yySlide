package sh.haven.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import sh.haven.core.data.db.entities.ConnectionLog
import sh.haven.core.data.repository.ConnectionLogRepository
import sh.haven.core.data.repository.ConnectionRepository
import javax.inject.Inject

data class LogDisplayItem(
    val id: Long,
    val profileLabel: String,
    val host: String,
    val timestamp: Long,
    val status: ConnectionLog.Status,
    val durationMs: Long,
    val details: String?,
    val hasVerboseLog: Boolean,
)

@HiltViewModel
class AuditLogViewModel @Inject constructor(
    private val connectionLogRepository: ConnectionLogRepository,
    private val connectionRepository: ConnectionRepository,
) : ViewModel() {

    private val _filterProfileId = MutableStateFlow<String?>(null)
    val filterProfileId: StateFlow<String?> = _filterProfileId.asStateFlow()

    val logs: StateFlow<List<LogDisplayItem>> = combine(
        connectionLogRepository.observeAllSummary(),
        connectionRepository.observeAll(),
        _filterProfileId,
    ) { logs, profiles, filter ->
        val profileMap = profiles.associateBy { it.id }
        logs
            .filter { filter == null || it.profileId == filter }
            .map { log ->
                val profile = profileMap[log.profileId]
                LogDisplayItem(
                    id = log.id,
                    profileLabel = profile?.label ?: "Deleted",
                    host = profile?.host ?: "",
                    timestamp = log.timestamp,
                    status = log.status,
                    durationMs = log.durationMs,
                    details = log.details,
                    hasVerboseLog = false, // summary query doesn't include verboseLog
                )
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val availableProfiles: StateFlow<List<Pair<String, String>>> =
        connectionRepository.observeAll()
            .combine(connectionLogRepository.observeAllSummary()) { profiles, logs ->
                val loggedProfileIds = logs.map { it.profileId }.toSet()
                profiles
                    .filter { it.id in loggedProfileIds }
                    .map { it.id to it.label }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Load verbose log on demand for a specific log entry. */
    private val _verboseLog = MutableStateFlow<Pair<Long, String>?>(null)
    val verboseLog: StateFlow<Pair<Long, String>?> = _verboseLog.asStateFlow()

    fun loadVerboseLog(logId: Long) {
        viewModelScope.launch {
            val entry = connectionLogRepository.getById(logId)
            _verboseLog.value = entry?.verboseLog?.let { logId to it }
        }
    }

    fun clearVerboseLog() {
        _verboseLog.value = null
    }

    fun setFilter(profileId: String?) {
        _filterProfileId.value = profileId
    }

    fun clearLogs() {
        viewModelScope.launch { connectionLogRepository.clearAll() }
    }
}
