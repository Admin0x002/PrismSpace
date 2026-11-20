package com.yzddmr6.prismspace.bridge

import android.os.Bundle
import android.os.Parcelable
import com.yzddmr6.prismspace.provisioning.SelectionStatus
import com.yzddmr6.prismspace.provisioning.SystemAppOverride
import com.yzddmr6.prismspace.provisioning.SystemAppTarget
import kotlinx.parcelize.Parcelize

/** Upper bound of override changes per [ApplySystemAppSelection]; callers chunk larger selections. */
const val MAX_SYSTEM_APP_SELECTION_CHANGES = 128

enum class SystemAppChoice { Enabled, Disabled, Clear, HiddenOnly, SuspendedOnly }

enum class SelectionFinish { Confirm, Defer }

@Parcelize
data class SystemAppOverrideChange(val pkg: String, val choice: SystemAppChoice) : Parcelable {
    init {
        require(pkg.isNotBlank()) { "Blank package in system app selection" }
    }
}

@Parcelize
data class SystemAppSelectionEntry(
    val pkg: String,
    val override: SystemAppOverride?,
    /** Current policy target; null = the policy does not manage this package. */
    val target: SystemAppTarget?,
    val critical: Boolean,
    val inDefault: Boolean,
    val installed: Boolean,
    val hasLauncherEntry: Boolean,
) : Parcelable

@Parcelize
data class SystemAppSelectionPage(
    val status: SelectionStatus?,
    val entries: List<SystemAppSelectionEntry>,
    val hasMore: Boolean,
) : Parcelable

@Parcelize
data class SystemAppApplyReportDto(
    val available: List<String>,
    val unavailable: List<String>,
    val absent: List<String>,
    val failed: List<String>,
    val ignoredCritical: List<String>,
) : Parcelable

/** One row of the "disable in the space" screen; labels are resolved in the profile. */
@Parcelize
data class BlockableAppEntry(
    val pkg: String,
    val label: String,
    /** Hidden inside the space right now (absent from its app list). */
    val hidden: Boolean,
    /** Suspended inside the space right now (no component can start). */
    val suspended: Boolean,
    /** Member of the fork's default blocklist. */
    val inBlocklist: Boolean,
) : Parcelable

@Parcelize
data class BlockableAppsPage(val entries: List<BlockableAppEntry>, val hasMore: Boolean) : Parcelable

/** Lists every installed, non-critical system package of the space, ordered by package name. */
@Parcelize
data class QueryBlockableAppsPage(
    val pageIndex: Int,
    val pageSize: Int = DEFAULT_PROFILE_APP_PAGE_SIZE,
) : ProfileCommand<BlockableAppsPage> {
    override val id get() = "sysapps.query_blockable_page"
    override fun encodeResult(result: BlockableAppsPage, out: Bundle) = out.putParcelable(RESULT, result)
    override fun decodeResult(src: Bundle): BlockableAppsPage = src.requireSystemAppResult()
}

/** Reads the profile-side selection state; the page size is clamped by the handler. */
@Parcelize
data class QuerySystemAppSelectionPage(
    val pageIndex: Int,
    val pageSize: Int = DEFAULT_PROFILE_APP_PAGE_SIZE,
) : ProfileCommand<SystemAppSelectionPage> {
    override val id get() = "sysapps.query_selection_page"
    override fun encodeResult(result: SystemAppSelectionPage, out: Bundle) = out.putParcelable(RESULT, result)
    override fun decodeResult(src: Bundle): SystemAppSelectionPage = src.requireSystemAppResult()
}

/** Writes user overrides and converges the policy; only the last chunk of a selection carries [finish]. */
@Parcelize
data class ApplySystemAppSelection(
    val changes: List<SystemAppOverrideChange>,
    val finish: SelectionFinish?,
) : ProfileCommand<SystemAppApplyReportDto> {
    init {
        require(changes.size <= MAX_SYSTEM_APP_SELECTION_CHANGES) {
            "System app selection exceeds $MAX_SYSTEM_APP_SELECTION_CHANGES changes"
        }
    }

    override val id get() = "sysapps.apply_selection"
    override fun encodeResult(result: SystemAppApplyReportDto, out: Bundle) = out.putParcelable(RESULT, result)
    override fun decodeResult(src: Bundle): SystemAppApplyReportDto = src.requireSystemAppResult()
}

internal val SYSTEM_APP_COMMAND_SAMPLES: List<BridgeCommand<*>> = listOf(
    QuerySystemAppSelectionPage(0),
    QueryBlockableAppsPage(0),
    ApplySystemAppSelection(listOf(SystemAppOverrideChange("pkg", SystemAppChoice.Enabled)), SelectionFinish.Confirm),
)

private inline fun <reified T : Parcelable> Bundle.requireSystemAppResult(): T {
    @Suppress("DEPRECATION") val value: T? = getParcelable(RESULT)
    return requireNotNull(value) { "Missing ${T::class.java.simpleName} bridge result" }
}
