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
    private const val TAG = "Prism.OverridesMirror"

    /** Recorded choice per package; every [SystemAppChoice] except Clear has its own bucket. */
    private fun bucketKey(choice: SystemAppChoice) = "overrides_${choice.name}"

    private fun readableChoices() = SystemAppChoice.values().filterNot { it == SystemAppChoice.Clear }

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
        val buckets = readableChoices().associateWith { choice ->
            prefs.getStringSet(bucketKey(choice), emptySet()).orEmpty().toMutableSet()
        }.toMutableMap()
        changes.forEach { change ->
            // A package belongs to exactly one bucket: moving it means leaving every other.
            buckets.values.forEach { it.remove(change.pkg) }
            if (change.choice != SystemAppChoice.Clear) buckets.getValue(change.choice).add(change.pkg)
        }
        val editor = prefs.edit().putInt(KEY_USER_ID, userId)
        buckets.forEach { (choice, set) -> editor.putStringSet(bucketKey(choice), set) }
        editor.commit()
    }

    /**
     * Changes to replay into [userId] when it is a space the mirror has not seen yet.
     * @return the replay list, or null when there is nothing to do.
     */
    fun takePendingRestore(context: Context, userId: Int): List<SystemAppOverrideChange>? {
        val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val mirroredUser = prefs.getInt(KEY_USER_ID, -1)
        val recorded = readableChoices().flatMap { choice ->
            prefs.getStringSet(bucketKey(choice), emptySet()).orEmpty().map { it to choice }
        }
        if (recorded.isEmpty()) return null
        if (mirroredUser == userId) return null      // same space: its own state is authoritative
        DiagnosticLog.i(TAG, "restoring ${recorded.size} overrides into user $userId (mirrored for $mirroredUser)")
        return recorded.map { (pkg, choice) -> SystemAppOverrideChange(pkg, choice) }
    }

    /** Full recorded map, for the config backup export. */
    fun exportForBackup(context: Context): Map<String, SystemAppChoice> {
        val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return readableChoices().flatMap { choice ->
            prefs.getStringSet(bucketKey(choice), emptySet()).orEmpty().map { it to choice }
        }.toMap()
    }

    /**
     * Writes overrides from an imported document without touching the recorded profile id, so the
     * next space start replays them through [takePendingRestore] like any other change.
     */
    fun importForBackup(context: Context, changes: List<SystemAppOverrideChange>) {
        if (changes.isEmpty()) return
        val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val buckets = readableChoices().associateWith { choice ->
            prefs.getStringSet(bucketKey(choice), emptySet()).orEmpty().toMutableSet()
        }.toMutableMap()
        changes.forEach { change ->
            buckets.values.forEach { it.remove(change.pkg) }
            if (change.choice != SystemAppChoice.Clear) buckets.getValue(change.choice).add(change.pkg)
        }
        // Bumping the recorded user to -1 marks the mirror as "not yet applied" for whatever space
        // exists next; the restore path picks it up.
        val editor = prefs.edit().putInt(KEY_USER_ID, -1)
        buckets.forEach { (choice, set) -> editor.putStringSet(bucketKey(choice), set) }
        editor.commit()
        DiagnosticLog.i(TAG, "import backup: ${changes.size} overrides pending restore")
    }

    /** Marks the mirror as belonging to [userId] after a successful restore. */
    fun confirmRestored(context: Context, userId: Int) {
        val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_USER_ID, userId).commit()
        DiagnosticLog.i(TAG, "restore confirmed for user $userId")
    }
}