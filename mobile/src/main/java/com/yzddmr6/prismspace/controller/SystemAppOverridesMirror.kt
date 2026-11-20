package com.yzddmr6.prismspace.controller

import android.content.Context
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import com.yzddmr6.prismspace.bridge.SystemAppChoice
import com.yzddmr6.prismspace.bridge.SystemAppOverrideChange

/**
 * 主空间侧的 override 镜像.
 *
 * The system-app policy state lives inside the profile (`SharedPrefsSystemAppPolicyPersistence`),
 * which means deleting the space throws every choice away — a rebuild silently reverts the whole
 * blocklist to its defaults. The parent space is the only durable place to keep this, and it
 * already sees every change, because it is the side that calls `ApplySystemAppSelection`.
 *
 * So: record each applied change here, and replay it into a newly created space (identified by a
 * different profile user id) once. Nothing else is mirrored: package facts and `lastApplied` are
 * derived state that the space rebuilds on its own.
 */
object SystemAppOverridesMirror {
    private const val FILE = "system_app_overrides_mirror"
    private const val KEY_USER_ID = "profile_user_id"
    private const val KEY_ON = "overrides_on"
    private const val KEY_OFF = "overrides_off"
    private const val TAG = "Prism.OverridesMirror"

    /** Records the changes the profile accepted. Call after a successful apply. */
    fun record(context: Context, userId: Int, changes: List<SystemAppOverrideChange>) {
        if (changes.isEmpty()) return
        val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_USER_ID, -1) != userId) {
            // A different space: its state is not what we mirrored, so start over instead of
            // replaying another space's choices into it.
            DiagnosticLog.i(TAG, "mirror reset: user $userId replaces ${prefs.getInt(KEY_USER_ID, -1)}")
            prefs.edit().clear().putInt(KEY_USER_ID, userId).commit()
        }
        val on = prefs.getStringSet(KEY_ON, emptySet()).orEmpty().toMutableSet()
        val off = prefs.getStringSet(KEY_OFF, emptySet()).orEmpty().toMutableSet()
        changes.forEach { change ->
            when (change.choice) {
                SystemAppChoice.Enabled -> { off.remove(change.pkg); on.add(change.pkg) }
                SystemAppChoice.Disabled -> { on.remove(change.pkg); off.add(change.pkg) }
                SystemAppChoice.Clear -> { on.remove(change.pkg); off.remove(change.pkg) }
            }
        }
        prefs.edit()
            .putInt(KEY_USER_ID, userId)
            .putStringSet(KEY_ON, on)
            .putStringSet(KEY_OFF, off)
            .commit()
    }

    /**
     * Changes to replay into [userId] when it is a space the mirror has not seen yet.
     * @return the replay list, or null when there is nothing to do.
     */
    fun takePendingRestore(context: Context, userId: Int): List<SystemAppOverrideChange>? {
        val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val mirroredUser = prefs.getInt(KEY_USER_ID, -1)
        val on = prefs.getStringSet(KEY_ON, emptySet()).orEmpty()
        val off = prefs.getStringSet(KEY_OFF, emptySet()).orEmpty()
        if (on.isEmpty() && off.isEmpty()) return null
        if (mirroredUser == userId) return null      // same space: its own state is authoritative
        DiagnosticLog.i(TAG, "restoring ${on.size + off.size} overrides into user $userId (mirrored for $mirroredUser)")
        return buildList {
            on.forEach { add(SystemAppOverrideChange(it, SystemAppChoice.Enabled)) }
            off.forEach { add(SystemAppOverrideChange(it, SystemAppChoice.Disabled)) }
        }
    }

    /** Marks the mirror as belonging to [userId] after a successful restore. */
    fun confirmRestored(context: Context, userId: Int) {
        val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_USER_ID, userId).commit()
        DiagnosticLog.i(TAG, "restore confirmed for user $userId")
    }
}