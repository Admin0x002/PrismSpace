package com.yzddmr6.prismspace.prism.compose.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import com.yzddmr6.prismspace.bridge.BlockableAppEntry
import com.yzddmr6.prismspace.bridge.SystemAppApplyReportDto
import com.yzddmr6.prismspace.bridge.SystemAppChoice
import com.yzddmr6.prismspace.bridge.SystemAppOverrideChange
import com.yzddmr6.prismspace.controller.SystemAppSelectionClient
import com.yzddmr6.prismspace.mobile.R
import com.yzddmr6.prismspace.prism.compose.space.ProfileProcessInspector
import com.yzddmr6.prismspace.prism.service.ProfileBridgeResult
import com.yzddmr6.prismspace.util.Users
import com.yzddmr6.prismspace.util.Users.Companion.toId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「空间应用」: which system packages may list and/or run inside the space, plus what is running
 * there right now.
 *
 * Hide and suspend are independent platform switches, so every row carries both; the current
 * policy target decides the initial state of each control. The process list is privileged-only and
 * says so instead of pretending when root/Shizuku is missing.
 */
class SpaceTrimViewModel(app: Application) : AndroidViewModel(app) {

    data class Row(
        val pkg: String,
        val label: String,
        val initiallyHidden: Boolean,
        val initiallySuspended: Boolean,
        val inBlocklist: Boolean,
        val hidden: Boolean,
        val suspended: Boolean,
    ) {
        /** The quadrant this row currently asks for; drives the tag. */
        val quadrant: Quadrant
            get() = when {
                hidden && suspended -> Quadrant.HiddenSuspended
                hidden -> Quadrant.HiddenOnly
                suspended -> Quadrant.SuspendedOnly
                else -> Quadrant.Normal
            }
    }

    enum class Quadrant { Normal, HiddenOnly, SuspendedOnly, HiddenSuspended }

    sealed interface Processes {
        data object Loading : Processes
        data object NoPrivilege : Processes
        data class Loaded(val rows: List<ProfileProcessInspector.ProcessRow>) : Processes
        data class Failed(val detail: String) : Processes
    }

    data class UiState(
        val loading: Boolean = true,
        val saving: Boolean = false,
        val rows: List<Row> = emptyList(),
        val filter: String = "",
        val error: String? = null,
        val feedback: String? = null,
        val feedbackIsError: Boolean = false,
        val processes: Processes = Processes.Loading,
    ) {
        val visible: List<Row>
            get() {
                val needle = filter.trim().lowercase()
                return rows
                    .filter { needle.isEmpty() || it.label.lowercase().contains(needle) || it.pkg.contains(needle) }
                    .sortedWith(compareByDescending<Row> { it.inBlocklist }.thenBy { it.label.lowercase() })
            }

        val changedCount: Int
            get() = rows.count { it.hidden != it.initiallyHidden || it.suspended != it.initiallySuspended }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private val appContext = app.applicationContext

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val userId = Users.profile?.toId()
            if (userId == null) {
                _state.update { it.copy(loading = false, error = str(R.string.lz_trim_error_no_space)) }
                return@launch
            }
            loadProcesses(userId)
            when (val result = withContext(Dispatchers.IO) { SystemAppSelectionClient.readBlockableApps(appContext, userId) }) {
                is ProfileBridgeResult.Value<List<BlockableAppEntry>> -> {
                    val rows = result.value.orEmpty().map {
                        Row(
                            pkg = it.pkg,
                            label = it.label,
                            initiallyHidden = it.hidden,
                            initiallySuspended = it.suspended,
                            inBlocklist = it.inBlocklist,
                            hidden = it.hidden,
                            suspended = it.suspended,
                        )
                    }
                    _state.update { it.copy(loading = false, rows = rows, error = null) }
                }
                else -> _state.update { it.copy(loading = false, error = str(R.string.lz_trim_error_bridge)) }
            }
        }
    }

    fun loadProcesses(userId: Int) {
        viewModelScope.launch {
            _state.update { it.copy(processes = Processes.Loading) }
            val result = withContext(Dispatchers.IO) { ProfileProcessInspector.inspect(appContext, userId) }
            val processes = when (result) {
                is ProfileProcessInspector.Result.Ok -> Processes.Loaded(result.rows)
                ProfileProcessInspector.Result.NoPrivilege -> Processes.NoPrivilege
                is ProfileProcessInspector.Result.Failed -> Processes.Failed(result.detail)
            }
            _state.update { it.copy(processes = processes) }
        }
    }

    fun refreshProcesses() {
        val userId = Users.profile?.toId() ?: return
        loadProcesses(userId)
    }

    /** Flips one dimension. The two switches are independent, so each toggle keeps the other one. */
    fun toggle(pkg: String, hidden: Boolean? = null, suspended: Boolean? = null) {
        _state.update { state ->
            state.copy(rows = state.rows.map { row ->
                if (row.pkg != pkg) row
                else row.copy(
                    hidden = hidden ?: row.hidden,
                    suspended = suspended ?: row.suspended,
                )
            })
        }
    }

    fun setFilter(value: String) {
        _state.update { it.copy(filter = value) }
    }

    fun save() {
        val current = _state.value
        if (current.saving) return
        val changes = current.rows.mapNotNull { row ->
            if (row.hidden == row.initiallyHidden && row.suspended == row.initiallySuspended) null
            else SystemAppOverrideChange(row.pkg, choiceFor(row.hidden, row.suspended))
        }
        if (changes.isEmpty()) {
            _state.update { it.copy(feedback = str(R.string.lz_trim_no_changes), feedbackIsError = false) }
            return
        }
        viewModelScope.launch {
            val userId = Users.profile?.toId()
            if (userId == null) {
                _state.update { it.copy(feedback = str(R.string.lz_trim_error_no_space), feedbackIsError = true) }
                return@launch
            }
            _state.update { it.copy(saving = true, feedback = null) }
            val result = withContext(Dispatchers.IO) {
                SystemAppSelectionClient.apply(appContext, userId, changes, finish = null)
            }
            DiagnosticLog.i(TAG, "space trim save changes=${changes.size} result=${result.javaClass.simpleName}")
            when (result) {
                is ProfileBridgeResult.Value<SystemAppApplyReportDto> -> {
                    val report = result.value
                    if (report == null) {
                        _state.update { it.copy(saving = false, feedback = str(R.string.lz_trim_apply_failed), feedbackIsError = true) }
                        return@launch
                    }
                    val restricted = report.unavailable.size + report.absent.size
                    val restored = report.available.size
                    _state.update {
                        it.copy(
                            saving = false,
                            feedback = str(R.string.lz_trim_saved, restricted, restored, report.failed.size),
                            feedbackIsError = report.failed.isNotEmpty(),
                        )
                    }
                    load()
                }
                else -> _state.update {
                    it.copy(saving = false, feedback = str(R.string.lz_trim_apply_failed), feedbackIsError = true)
                }
            }
        }
    }

    /** Maps a (hidden, suspended) pair onto the choice the profile understands. */
    internal fun choiceFor(hidden: Boolean, suspended: Boolean): SystemAppChoice = when {
        hidden && suspended -> SystemAppChoice.Disabled
        hidden -> SystemAppChoice.HiddenOnly
        suspended -> SystemAppChoice.SuspendedOnly
        else -> SystemAppChoice.Enabled
    }

    private fun str(id: Int, vararg args: Any): String =
        if (args.isEmpty()) getApplication<Application>().getString(id)
        else getApplication<Application>().getString(id, *args)

    private companion object { const val TAG = "Prism.SpaceTrim" }
}