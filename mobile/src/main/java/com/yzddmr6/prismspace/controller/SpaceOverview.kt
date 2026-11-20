package com.yzddmr6.prismspace.controller

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.WorkerThread
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import com.yzddmr6.prismspace.prism.compose.space.ProfileProcessInspector
import com.yzddmr6.prismspace.prism.compose.space.resolvePrivilegedShell

/**
 * 轻量只读概览: counts and sizes, nothing about what the user did with which app.
 *
 * Deliberately not an audit log. This answers "what is inside the space right now" — how many
 * packages, how much storage, how many processes — which is what a user needs to decide whether
 * the space is healthy. Package *usage* statistics are not collected: they answer a compliance
 * question this app has no business asking.
 */
object SpaceOverview {

    data class Snapshot(
        val packageCount: Int,
        val userAppCount: Int,
        /** Bytes under the profile's data dir, as reported by `du`; null without a privileged shell. */
        val dataBytes: Long?,
        val processCount: Int?,
        val runningApps: List<String>,
        val note: String? = null,
    )

    @WorkerThread
    suspend fun collect(context: Context, userId: Int): Snapshot {
        val packages = runCatching {
            context.packageManager.getInstalledPackages(PackageManager.MATCH_UNINSTALLED_PACKAGES)
        }.getOrDefault(emptyList())
        // The caller is the parent space, so it can only count what it can see; the real per-space
        // counts come from the profile over the bridge for the app screen. Storage and processes do
        // need the privileged shell either way.
        val processes = runCatching { ProfileProcessInspector.inspect(context, userId) }.getOrNull()
        val rows = (processes as? ProfileProcessInspector.Result.Ok)?.rows.orEmpty()
        return Snapshot(
            packageCount = packages.size,
            userAppCount = packages.count { it.applicationInfo?.let { info ->
                (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0
            } == true },
            dataBytes = profileDataBytes(context, userId),
            processCount = rows.size.takeIf { processes is ProfileProcessInspector.Result.Ok },
            runningApps = rows.take(MAX_RUNNING).map { it.name },
            note = when (processes) {
                ProfileProcessInspector.Result.NoPrivilege -> "no_privilege"
                is ProfileProcessInspector.Result.Failed -> "failed"
                else -> null
            },
        )
    }

    /** Total size of the space's data directory; only a privileged shell can measure it. */
    private fun profileDataBytes(context: Context, userId: Int): Long? {
        val shell = runCatching { resolvePrivilegedShell(context) }.getOrNull() ?: return null
        val lines = shell.run("du -sk /data/user/$userId 2>/dev/null") ?: return null
        val kilobytes = lines.firstOrNull()?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.toLongOrNull()
        return kilobytes?.let { it * 1024 }
    }

    /**
     * Whether the platform lets us read usage statistics at all (the user grants "usage access"
     * in system settings). Reported, never required.
     */
    fun hasUsageAccess(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val manager = context.getSystemService(UsageStatsManager::class.java) ?: return false
        val usage = runCatching { manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, 0L, 0L) }.getOrNull()
        DiagnosticLog.d(TAG, "usage access readable=${usage != null}")
        return usage != null
    }

    fun formatBytes(bytes: Long?): String? = bytes?.let {
        val mb = it / (1024L * 1024L)
        if (mb >= 1024) "%.1f GB".format(mb / 1024.0) else "$mb MB"
    }

    private const val TAG = "Prism.Overview"
    private const val MAX_RUNNING = 6
}
