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
 * 「空间精简」: pick which system packages stay unavailable (hidden + suspended) inside the space.
 *
 * The list is built in the profile — it owns both the package facts and the labels — and the
 * choices are written back through the same [SystemAppSelectionClient] call the system-app picker
 * uses. Checked means "disabled in the space"; the default check state is the policy's current
 * target, so the fork's blocklist arrives pre-checked.
 */
class SpaceTrimViewModel(app: Application) : AndroidViewModel(app) {

    data class Row(
        val pkg: String,
        val label: String,
        /** Checked when the page loaded; only a difference from this produces a write. */
        val initiallyBlocked: Boolean,
        val inBlocklist: Boolean,
        val checked: Boolean,
    )

    data class UiState(
        val loading: Boolean = true,
        val saving: Boolean = false,
        val rows: List<Row> = emptyList(),
        val filter: String = "",
        val error: String? = null,
        val feedback: String? = null,
        val feedbackIsError: Boolean = false,
    ) {
        /** Rows the UI shows for the current filter, blocklist defaults first. */
        val visible: List<Row>
            get() {
                val needle = filter.trim().lowercase()
                return rows
                    .filter { needle.isEmpty() || it.label.lowercase().contains(needle) || it.pkg.contains(needle) }
                    .sortedWith(compareByDescending<Row> { it.inBlocklist }.thenBy { it.label.lowercase() })
            }
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
            when (val result = withContext(Dispatchers.IO) { SystemAppSelectionClient.readBlockableApps(appContext, userId) }) {
                is ProfileBridgeResult.Value<List<BlockableAppEntry>> -> {
                    val rows = result.value.orEmpty().map {
                        Row(
                            pkg = it.pkg,
                            label = it.label,
                            initiallyBlocked = it.checked,
                            inBlocklist = it.inBlocklist,
                            checked = it.checked,
                        )
                    }
                    _state.update { it.copy(loading = false, rows = rows, error = null) }
                }
                else -> _state.update { it.copy(loading = false, error = str(R.string.lz_trim_error_bridge)) }
            }
        }
    }

    fun toggle(pkg: String) {
        _state.update { state ->
            state.copy(rows = state.rows.map { if (it.pkg == pkg) it.copy(checked = !it.checked) else it })
        }
    }

    fun setFilter(value: String) {
        _state.update { it.copy(filter = value) }
    }

    fun save() {
        val current = _state.value
        if (current.saving) return
        val changes = current.rows.mapNotNull { row ->
            when {
                row.checked == row.initiallyBlocked -> null
                row.checked -> SystemAppOverrideChange(row.pkg, SystemAppChoice.Disabled)
                else -> SystemAppOverrideChange(row.pkg, SystemAppChoice.Enabled)
            }
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
                    val report = result.value ?: return@launch
                    // "恢复"可以是因为勾选被取消,也可能只是解除了原先的禁用;统一按目标数报。
                    val disabled = report.unavailable.size + report.absent.size
                    val restored = report.available.size
                    val failed = report.failed.size
                    _state.update {
                        it.copy(
                            saving = false,
                            feedback = str(R.string.lz_trim_saved, disabled, restored, failed),
                            feedbackIsError = failed > 0,
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

    private fun str(id: Int, vararg args: Any): String =
        if (args.isEmpty()) getApplication<Application>().getString(id)
        else getApplication<Application>().getString(id, *args)

    private companion object { const val TAG = "Prism.SpaceTrim" }
}
