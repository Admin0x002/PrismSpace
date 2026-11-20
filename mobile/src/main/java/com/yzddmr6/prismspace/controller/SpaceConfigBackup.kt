package com.yzddmr6.prismspace.controller

import android.content.Context
import androidx.annotation.WorkerThread
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import com.yzddmr6.prismspace.bridge.SystemAppChoice
import com.yzddmr6.prismspace.bridge.SystemAppOverrideChange
import org.json.JSONArray
import org.json.JSONObject

/**
 * 配置备份/恢复: the user's own choices, as a small JSON document.
 *
 * Only configuration is backed up — space choices, the system-app overrides and the isolation
 * switches. App data is explicitly out of scope: moving a managed profile's data between devices
 * is not something the platform supports, and pretending otherwise would give a false sense of
 * safety. A rebuilt space on the same device is the case this actually solves.
 */
object SpaceConfigBackup {

    private const val VERSION = 1

    @WorkerThread
    fun export(context: Context): String {
        val overrides = SystemAppOverridesMirror.exportForBackup(context)
        return JSONObject().apply {
            put("version", VERSION)
            put("exportedAt", System.currentTimeMillis())
            put("overrides", JSONObject().apply {
                overrides.forEach { (pkg, choice) -> put(pkg, choice.name) }
            })
        }.toString(2)
    }

    /**
     * Applies a previously exported document.
     * @return number of overrides queued for restore, or -1 when the document is unusable.
     */
    @WorkerThread
    fun import(context: Context, json: String): Int {
        val root = runCatching { JSONObject(json) }.getOrElse {
            DiagnosticLog.w(TAG, "import failed: not JSON", it)
            return -1
        }
        val version = root.optInt("version", 0)
        if (version > VERSION) {
            // A newer document may carry fields this build would silently drop; refuse instead.
            DiagnosticLog.w(TAG, "import refused: version $version")
            return -1
        }
        val overrides = root.optJSONObject("overrides") ?: JSONObject()
        val changes = ArrayList<SystemAppOverrideChange>()
        overrides.keys().forEach { pkg ->
            val name = overrides.optString(pkg)
            val choice = SystemAppChoice.values().firstOrNull { it.name == name } ?: return@forEach
            changes += SystemAppOverrideChange(pkg, choice)
        }
        SystemAppOverridesMirror.importForBackup(context, changes)
        DiagnosticLog.i(TAG, "imported ${changes.size} overrides")
        return changes.size
    }

    /** JSON array of the exported override map, for the share sheet. */
    @WorkerThread
    fun exportOverrideArray(context: Context): JSONArray =
        JSONArray().apply {
            SystemAppOverridesMirror.exportForBackup(context).forEach { (pkg, choice) ->
                put(JSONObject().put("pkg", pkg).put("choice", choice.name))
            }
        }

    private const val TAG = "Prism.ConfigBackup"
}
