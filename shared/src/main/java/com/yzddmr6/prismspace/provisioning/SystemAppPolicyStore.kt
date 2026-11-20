package com.yzddmr6.prismspace.provisioning

import android.content.Context

/** Profile-side persisted policy state of one dual space (the single source of truth). */
data class SystemAppPolicyState(
    val status: SelectionStatus? = null,
    val overrides: Map<String, SystemAppOverride> = emptyMap(),
    val lastApplied: Map<String, SystemAppTarget> = emptyMap(),
)

interface SystemAppPolicyPersistence {
    fun read(): SystemAppPolicyState
    /** @return whether the state was durably written. */
    fun write(state: SystemAppPolicyState): Boolean
}

/** SharedPreferences("system_app_policy") inside the profile user the applier runs in. */
class SharedPrefsSystemAppPolicyPersistence(context: Context) : SystemAppPolicyPersistence {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun read(): SystemAppPolicyState {
        val status = prefs.getString(KEY_STATUS, null)?.let { name ->
            SelectionStatus.values().firstOrNull { it.name == name }
        }
        val overrides = LinkedHashMap<String, SystemAppOverride>()
        // Read every quadrant; a choice written by an older build still lands in its slot.
        SystemAppOverride.values().forEach { choice ->
            prefs.getStringSet(KEY_OVERRIDE_PREFIX + choice.name, emptySet()).orEmpty().forEach {
                overrides[it] = choice
            }
        }
        val lastApplied = LinkedHashMap<String, SystemAppTarget>()
        SystemAppTarget.values().forEach { target ->
            prefs.getStringSet(KEY_APPLIED_PREFIX + target.name, emptySet()).orEmpty().forEach {
                lastApplied[it] = target
            }
        }
        return SystemAppPolicyState(status, overrides, lastApplied)
    }

    override fun write(state: SystemAppPolicyState): Boolean {
        val editor = prefs.edit().putString(KEY_STATUS, state.status?.name)
        SystemAppOverride.values().forEach { choice ->
            editor.putStringSet(
                KEY_OVERRIDE_PREFIX + choice.name,
                state.overrides.filterValues { it == choice }.keys.toSet(),
            )
        }
        SystemAppTarget.values().forEach { target ->
            editor.putStringSet(
                KEY_APPLIED_PREFIX + target.name,
                state.lastApplied.filterValues { it == target }.keys.toSet(),
            )
        }
        // Legacy keys from before the quadrants existed must not resurrect as overrides.
        editor.remove(KEY_OVERRIDES_ON).remove(KEY_OVERRIDES_OFF)
        return editor.commit()
    }

    private companion object {
        const val FILE = "system_app_policy"
        const val KEY_STATUS = "status"
        const val KEY_OVERRIDE_PREFIX = "override_"
        const val KEY_APPLIED_PREFIX = "last_applied_"
        // Legacy (pre-quadrant) keys, cleared on the next write.
        const val KEY_OVERRIDES_ON = "overrides_on"
        const val KEY_OVERRIDES_OFF = "overrides_off"
    }
}
