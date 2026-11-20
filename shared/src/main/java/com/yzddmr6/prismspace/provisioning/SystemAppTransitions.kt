package com.yzddmr6.prismspace.provisioning

/** Provision state at or above which the initial profile provisioning has completed once. */
const val FIRST_COMPLETED_POST_PROVISION_STATE = 3

enum class SystemAppStep { Enable, Unhide, Unsuspend, Hide, Suspend }

data class PackagePlan(
    val pkg: String,
    val target: SystemAppTarget,
    val from: PackageState?,
    val steps: List<SystemAppStep>,
)

/**
 * Steps that move one package from [current] to [target]; empty when already there.
 * Available: Enable (when missing for this user) → Unhide → Unsuspend, the PR-A order for critical
 * packages. Unavailable: Hide → Suspend, both dimensions in one pass; nothing for a package that is
 * not installed (enabling it just to hide it would only add side effects).
 */
/** Steps for one target, in the order that never leaves the package in a half-applied state. */
fun stepsFor(target: TargetState, current: PackageState?): List<SystemAppStep> {
    // A package that is not installed here is only ever enabled; hiding/suspending it is a no-op.
    if (target != TargetState.AVAILABLE && (current == null || !current.installed)) return emptyList()
    return buildList {
        if (current == null || !current.installed) add(SystemAppStep.Enable)
        // Unhide before unsuspend: a package must be restoreable even if only one dimension is set.
        if (target.hidden) {
            if (current?.hidden == false) add(SystemAppStep.Hide)
        } else if (current?.hidden == true) add(SystemAppStep.Unhide)
        if (target.suspended) {
            if (current?.suspended == false) add(SystemAppStep.Suspend)
        } else if (current?.suspended == true) add(SystemAppStep.Unsuspend)
    }
}

/**
 * Packages whose state must be checked in this pass. Critical packages always converge. Any other
 * package is only written when its target changed since the last applied pass or when the user's
 * current selection touched it, so convergence never thaws a package the user froze afterwards.
 */
fun packagesToConverge(
    targets: Map<String, TargetState>,
    lastApplied: Map<String, SystemAppTarget>,
    critical: Set<String>,
    force: Set<String>,
): Set<String> = targets.filterTo(LinkedHashMap()) { (pkg, target) ->
    pkg in critical || pkg in force || lastApplied[pkg] != target.kind()
}.keys

/** Pure transition plan; [current] only needs entries for [packagesToConverge]. */
fun planSystemAppTransitions(
    targets: Map<String, TargetState>,
    current: Map<String, PackageState?>,
    lastApplied: Map<String, SystemAppTarget>,
    critical: Set<String>,
    force: Set<String>,
): List<PackagePlan> = packagesToConverge(targets, lastApplied, critical, force).map { pkg ->
    val target = targets.getValue(pkg)
    PackagePlan(pkg, target.kind(), current[pkg], stepsFor(target, current[pkg]))
}

/** Existing completed spaces get a zero-diff seeding instead of the creation-time defaults. */
fun needsPolicySeeding(provisionState: Int, storedStatus: SelectionStatus?): Boolean =
    provisionState >= FIRST_COMPLETED_POST_PROVISION_STATE && storedStatus == null

/** True when [state] already satisfies [target]. */
fun PackageState?.satisfies(target: TargetState): Boolean = when {
    // Making a package available needs it installed with both switches off.
    target == TargetState.AVAILABLE -> this != null && installed && !hidden && !suspended
    // An uninstalled package is already "removed": nothing to hide or suspend.
    this == null || !installed -> true
    else -> hidden == target.hidden && suspended == target.suspended
}
