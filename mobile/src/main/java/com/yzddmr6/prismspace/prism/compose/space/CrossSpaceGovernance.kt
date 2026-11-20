package com.yzddmr6.prismspace.prism.compose.space

import android.app.admin.DevicePolicyManager
import android.content.Context
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import com.yzddmr6.prismspace.bridge.SpaceGovernanceReport
import com.yzddmr6.prismspace.util.DevicePolicies

/**
 * 跨空间数据治理: the restrictions that actually bound what can move between the two spaces.
 *
 * Only the profile owner can set these, and they must be applied from inside the profile it owns,
 * so the caller is the profile-side app. Every switch maps to one platform restriction; nothing
 * here is a PrismSpace invention, so the OS enforces them even if this app is not running.
 */
object CrossSpaceGovernance {

    /** One reviewable knob, matching one [DevicePolicyManager] restriction. */
    enum class Policy(val restriction: String, val defaultOn: Boolean) {
        /**
         * Clipboard is shared between spaces by default on many OEM builds. Off means a copied
         * password stays where it was copied.
         */
        BlockClipboard(DevicePolicyManager.DISALLOW_CROSS_PROFILE_COPY_PASTE, defaultOn = true),
        /** Only PrismSpace and the system installer may bring APKs into the space. */
        BlockUnknownInstall(DevicePolicyManager.DISALLOW_INSTALL_UNKNOWN_SOURCES, defaultOn = true),
        /** No USB file transfer or debugging of the space's data. */
        BlockUsbTransfer(DevicePolicyManager.DISALLOW_USB_FILE_TRANSFER, defaultOn = true),
        /** No debugging features inside the space (also blocks adb shell into the profile). */
        BlockDebugging(DevicePolicyManager.DISALLOW_DEBUGGING_FEATURES, defaultOn = true),
        /** No adding accounts inside the space (a work account belongs to the parent). */
        BlockAccountAdd(DevicePolicyManager.DISALLOW_MODIFY_ACCOUNTS, defaultOn = false),
    }

    /** Bridge entry: policies keyed by [Policy.name]; unknown names are ignored. */
    fun applyAll(context: Context, policies: Map<String, Boolean>): SpaceGovernanceReport {
        val requested = policies.mapNotNull { (name, on) ->
            Policy.values().firstOrNull { it.name == name }?.let { it to on }
        }.toMap()
        val refused = setAll(context, requested)
        return report(context, refused)
    }

    /** Bridge entry: current effective state. */
    fun report(context: Context, refused: List<Policy>): SpaceGovernanceReport =
        SpaceGovernanceReport(
            effective = current(context).mapKeys { it.key.name },
            refused = refused.map { it.name },
        )

    /** Applies [enabled] to every policy and returns the ones the platform refused. */
    fun setAll(context: Context, enabled: Map<Policy, Boolean>): List<Policy> {
        val policies = DevicePolicies(context)
        if (!runCatching { policies.isProfileOwner }.getOrDefault(false)) {
            DiagnosticLog.w(TAG, "governance skipped: not the profile owner")
            return enabled.keys.toList()
        }
        val refused = ArrayList<Policy>()
        enabled.forEach { (policy, on) ->
            val ok = runCatching { policy.set(context, on) }
                .onFailure { DiagnosticLog.w(TAG, "restriction ${policy.name} failed", it) }
                .isSuccess
            if (!ok) refused += policy
            // Verify against the platform instead of trusting the call.
            val effective = runCatching { current(context)[policy] }.getOrNull()
            if (ok && effective != on) refused += policy
            DiagnosticLog.i(TAG, "restriction ${policy.name}=$on effective=$effective")
        }
        return refused.distinct()
    }

    /** Current state per policy, read from the platform (never from a local preference). */
    fun current(context: Context): Map<Policy, Boolean> {
        val restrictions = runCatching {
            context.getSystemService(android.os.UserManager::class.java)?.userRestrictions
        }.getOrNull()
        return Policy.values().associateWith { policy ->
            restrictions?.getBoolean(policy.restriction) ?: policy.defaultOn
        }
    }

    private fun Policy.set(context: Context, enabled: Boolean) {
        DevicePolicies(context).setUserRestriction(restriction, enabled)
    }

    private const val TAG = "Prism.Governance"
}