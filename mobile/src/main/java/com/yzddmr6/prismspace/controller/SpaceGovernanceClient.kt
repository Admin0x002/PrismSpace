package com.yzddmr6.prismspace.controller

import android.content.Context
import androidx.annotation.WorkerThread
import com.yzddmr6.prismspace.bridge.ApplySpaceGovernance
import com.yzddmr6.prismspace.bridge.BridgeTargets
import com.yzddmr6.prismspace.bridge.QuerySpaceGovernance
import com.yzddmr6.prismspace.bridge.SpaceGovernanceReport
import com.yzddmr6.prismspace.prism.service.ProfileBridgeResult
import com.yzddmr6.prismspace.prism.service.runProfileBridgeOperation

/**
 * Main-space client of 跨空间隔离策略. The profile owner is the only side allowed to set these
 * restrictions, so every read and write goes over the bridge into the space.
 */
internal object SpaceGovernanceClient {

    @WorkerThread
    fun read(context: Context, userId: Int): ProfileBridgeResult<SpaceGovernanceReport> =
        govern(context, userId, "query space governance", QuerySpaceGovernance)

    @WorkerThread
    fun apply(context: Context, userId: Int, policies: Map<String, Boolean>): ProfileBridgeResult<SpaceGovernanceReport> =
        govern(context, userId, "apply space governance changes=${policies.size}", ApplySpaceGovernance(policies))

    private fun govern(
        context: Context,
        userId: Int,
        label: String,
        command: com.yzddmr6.prismspace.bridge.ProfileCommand<SpaceGovernanceReport>,
    ): ProfileBridgeResult<SpaceGovernanceReport> {
        val result = runProfileBridgeOperation(
            context, TAG, label,
            target = BridgeTargets.profile(userId),
            command = command,
        )
        return (result as? ProfileBridgeResult.Value<SpaceGovernanceReport>)?.takeIf { it.value != null }
            ?: result.failure()
    }

    /** Narrows any non-value result into the same shape the callers already handle. */
    private fun <T> ProfileBridgeResult<*>.failure(): ProfileBridgeResult<T> = when (this) {
        is ProfileBridgeResult.SpaceMissing -> ProfileBridgeResult.SpaceMissing
        is ProfileBridgeResult.SpaceInactive -> ProfileBridgeResult.SpaceInactive(reason)
        is ProfileBridgeResult.BridgeNotReady -> ProfileBridgeResult.BridgeNotReady(cause)
        is ProfileBridgeResult.TimedOut -> ProfileBridgeResult.TimedOut
        is ProfileBridgeResult.Failed -> ProfileBridgeResult.Failed(error)
        is ProfileBridgeResult.Value<*> -> ProfileBridgeResult.Failed(IllegalStateException("Missing governance payload"))
    }

    private const val TAG = "Prism.Governance"
}
