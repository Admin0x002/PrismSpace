package com.yzddmr6.prismspace.provisioning

/**
 * A user's explicit per-space choice for one system package. The two middle values are the split
 * hide/suspend quadrants the space screen can set independently.
 */
enum class SystemAppOverride { Enabled, Disabled, HiddenOnly, SuspendedOnly }

/** Target a choice asks for; [SystemAppOverride.Disabled] keeps the provisioning "removed" semantics. */
fun SystemAppOverride.target(): TargetState = when (this) {
    SystemAppOverride.Enabled -> TargetState.AVAILABLE
    SystemAppOverride.Disabled -> TargetState.UNAVAILABLE
    SystemAppOverride.HiddenOnly -> TargetState.HIDDEN_ONLY
    SystemAppOverride.SuspendedOnly -> TargetState.SUSPENDED_ONLY
}

/** Profile-side selection progress. Gates whether the built-in default set is in effect. */
enum class SelectionStatus { Pending, Deferred, Confirmed }

/** Platform facts about one package, read inside the profile the policy applies to. */
data class PackageFact(
    val pkg: String,
    /** ApplicationInfo.FLAG_SYSTEM, read in this profile. */
    val isSystem: Boolean,
    /** Declares MAIN/LAUNCHER, disabled components included (same scope as AOSP provisioning). */
    val hasLauncherEntry: Boolean,
    /** ApplicationInfo.FLAG_INSTALLED for this profile user. */
    val installed: Boolean,
)

data class PolicyInputs(
    /** Default set already gated by [SelectionStatus], see [effectiveDefaultSet]. */
    val defaultSet: Set<String>,
    /** Critical packages (PR-A invariant): always enabled, unhidden and unsuspended. */
    val criticalSet: Set<String>,
    /** System input methods: never hidden or suspended by the policy, and never forced on. */
    val exemptSet: Set<String>,
    val overrides: Map<String, SystemAppOverride>,
    val facts: List<PackageFact>,
    /** Packages that must stay unavailable in the space whatever the selection says. */
    val blockedSet: Set<String> = emptySet(),
)

data class TargetState(val enabled: Boolean, val hidden: Boolean, val suspended: Boolean) {
    companion object {
        val AVAILABLE = TargetState(enabled = true, hidden = false, suspended = false)
        /** Not listed in the space, but its components may still run in the background. */
        val HIDDEN_ONLY = TargetState(enabled = true, hidden = true, suspended = false)
        /** Listed but completely unable to run: every component is blocked by the platform. */
        val SUSPENDED_ONLY = TargetState(enabled = true, hidden = false, suspended = true)
        /** "Removed" semantics of managed-profile provisioning: hidden + suspended; enabled=false means "do not install". */
        val UNAVAILABLE = TargetState(enabled = false, hidden = true, suspended = true)
    }
}

/**
 * Persisted, user-facing form of a [TargetState]. Four quadrants rather than available/unavailable:
 * hiding (listed or not) and suspending (able to run or not) are independent platform switches, and
 * the space screen exposes both — a package can be kept visible yet unable to run, or out of the
 * list while still serving background work.
 */
enum class SystemAppTarget { Available, Hidden, Suspended, Unavailable }

fun TargetState.kind(): SystemAppTarget = when {
    this == TargetState.HIDDEN_ONLY -> SystemAppTarget.Hidden
    this == TargetState.SUSPENDED_ONLY -> SystemAppTarget.Suspended
    this == TargetState.AVAILABLE -> SystemAppTarget.Available
    else -> SystemAppTarget.Unavailable
}

fun SystemAppTarget.state(): TargetState = when (this) {
    SystemAppTarget.Available -> TargetState.AVAILABLE
    SystemAppTarget.Hidden -> TargetState.HIDDEN_ONLY
    SystemAppTarget.Suspended -> TargetState.SUSPENDED_ONLY
    SystemAppTarget.Unavailable -> TargetState.UNAVAILABLE
}

/**
 * The single source of the per-package target state of system apps in a dual space.
 * Rules are evaluated by priority, the first match wins (design §1.1, R1–R8).
 */
object SystemAppPolicy {

    /** Only packages the policy manages are returned; an absent package means "do not touch". */
    fun evaluate(inputs: PolicyInputs): Map<String, TargetState> {
        val facts = inputs.facts.associateBy(PackageFact::pkg)
        val candidates = LinkedHashSet<String>().apply {
            addAll(inputs.criticalSet)
            addAll(inputs.blockedSet)
            addAll(inputs.overrides.keys)
            addAll(inputs.defaultSet)
            addAll(facts.keys)
        }
        val result = LinkedHashMap<String, TargetState>()
        for (pkg in candidates) {
            val fact = facts[pkg]
            val target = when {
                pkg in inputs.criticalSet -> TargetState.AVAILABLE                                      // R1
                // Blocked vendor services: kept out of every space, installed here or not. A hidden +
                // suspended package cannot start its components, so its crash loop ends with it.
                // An explicit choice is the user overriding the default on the space screen, so it
                // must win here — otherwise that screen could not undo a default.
                pkg in inputs.blockedSet && inputs.overrides[pkg] != SystemAppOverride.Enabled ->
                    if (fact?.installed == true) TargetState.UNAVAILABLE else null                            // R1b
                fact != null && !fact.isSystem -> null                                                  // R2
                // An explicit choice wins over the IME exemption and the default set, and carries the
                // exact quadrant the user asked for (hide and suspend are independent switches).
                inputs.overrides[pkg] != null -> inputs.overrides.getValue(pkg).target()                 // R3
                pkg in inputs.exemptSet -> null                                                         // R4
                pkg in inputs.defaultSet -> TargetState.AVAILABLE                                       // R6
                fact != null && fact.isSystem && fact.hasLauncherEntry && fact.installed ->            // R7
                    TargetState.UNAVAILABLE
                else -> null                                                                            // R8
            }
            if (target != null) result[pkg] = target
        }
        return result
    }
}
