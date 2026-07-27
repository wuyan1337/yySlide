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
import sh.haven.core.data.db.entities.ProotInstallLogSummary
import sh.haven.core.data.repository.ProotInstallLogRepository
import javax.inject.Inject

data class ProotInstallLogItem(
    val id: Long,
    val distroId: String,
    val timestamp: Long,
    val phase: String,
    val deId: String?,
    val exit: Int?,
    val ok: Boolean,
    val message: String?,
)

/**
 * Backing model for the diagnostic page that surfaces the install log
 * stored by [ProotInstallLogRepository]. Mirrors [AuditLogViewModel]'s
 * shape so users navigating between the two pages get the same
 * filter / expand / clear affordances.
 */
@HiltViewModel
class ProotInstallLogViewModel @Inject constructor(
    private val installLogRepository: ProotInstallLogRepository,
) : ViewModel() {

    private val _filterDistroId = MutableStateFlow<String?>(null)
    val filterDistroId: StateFlow<String?> = _filterDistroId.asStateFlow()

    val items: StateFlow<List<ProotInstallLogItem>> = combine(
        installLogRepository.observeAllSummary(),
        _filterDistroId,
    ) { events, filter ->
        events
            .filter { filter == null || it.distroId == filter }
            .map { it.toItem() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Distros that appear in the log (so the filter chips never list
     *  an unused distro). */
    val availableDistros: StateFlow<List<String>> =
        installLogRepository.observeAllSummary()
            .combine(_filterDistroId) { events, _ ->
                events.map { it.distroId }.distinct().sorted()
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Load the full row (including logTail) when the user expands. */
    private val _logTail = MutableStateFlow<Pair<Long, String>?>(null)
    val logTail: StateFlow<Pair<Long, String>?> = _logTail.asStateFlow()

    fun loadLogTail(logId: Long) {
        viewModelScope.launch {
            val entry = installLogRepository.getById(logId)
            _logTail.value = entry?.logTail?.let { logId to it }
        }
    }

    fun clearLogTail() {
        _logTail.value = null
    }

    fun setFilter(distroId: String?) {
        _filterDistroId.value = distroId
    }

    fun clearAll() {
        viewModelScope.launch { installLogRepository.clearAll() }
    }

    private fun ProotInstallLogSummary.toItem() = ProotInstallLogItem(
        id = id,
        distroId = distroId,
        timestamp = timestamp,
        phase = phase,
        deId = deId,
        exit = exit,
        ok = ok,
        message = message,
    )
}
