package com.yzddmr6.prismspace.prism.health

import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import com.yzddmr6.prismspace.mobile.R
import com.yzddmr6.prismspace.notification.NotificationIds
import com.yzddmr6.prismspace.prism.compose.space.ProfileUnlocker
import com.yzddmr6.prismspace.prism.compose.space.ProfileUnlockOutcome
import com.yzddmr6.prismspace.prism.compose.space.SpaceSnapshot
import com.yzddmr6.prismspace.prism.compose.space.SpaceStateRepository
import com.yzddmr6.prismspace.prism.compose.space.spaceUsabilityFromState
import com.yzddmr6.prismspace.prism.compose.space.SpaceUsability
import com.yzddmr6.prismspace.space.SpaceState
import com.yzddmr6.prismspace.util.Users
import com.yzddmr6.prismspace.util.Users.Companion.toId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 开机自检: the dual space fails silently today — a paused or CE-locked profile only shows up
 * when the user opens PrismSpace and notices an empty list. This receiver runs after boot (and
 * after a fork build replaces the app), repairs what it can on its own, and otherwise posts one
 * ongoing notification naming the state and the action that fixes it.
 *
 * Repair is deliberately narrow: only a CE lock has an automatic remedy (a privileged
 * `am start-user` + `am unlock-user`), and only when root/Shizuku is available. Everything else
 * needs a user decision (Android's "工作应用" switch, a reboot, or the system settings page), so
 * the receiver reports instead of acting.
 */
class SpaceHealthSelfCheck : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        // Only the parent space can query and fix the profile; the profile side has its own boot path.
        if (!Users.isParentProfile()) return
        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                check(appContext)
            } catch (e: Throwable) {
                DiagnosticLog.w(TAG, "self check failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun check(context: Context) {
        val repo = SpaceStateRepository(context)
        if (!repo.refresh(REASON)) {
            DiagnosticLog.i(TAG, "self check skipped: state refresh failed")
            return
        }
        val state = (repo.state.value as? SpaceSnapshot.Loaded)?.state ?: return
        val userId = state.userId
        DiagnosticLog.i(TAG, "self check state=$state user=${userId ?: Users.NULL_ID}")
        when (state) {
            is SpaceState.Locked -> {
                // Automatic repair: ask Android to unlock, which only a privileged shell can do.
                val outcome = ProfileUnlocker.unlock(context, userId ?: return)
                DiagnosticLog.i(TAG, "self check unlock outcome=$outcome")
                if (outcome != ProfileUnlockOutcome.Unlocked) notify(context, R.string.notification_space_health_locked, R.string.notification_space_health_locked_body)
            }
            is SpaceState.Inactive -> notify(context, R.string.notification_space_health_paused, R.string.notification_space_health_paused_body)
            is SpaceState.BridgeDown -> notify(context, R.string.notification_space_health_bridge, R.string.notification_space_health_bridge_body)
            is SpaceState.Healthy -> {
                cancel(context)
                // A rebuilt space starts from the defaults; replay what the parent space remembers.
                userId?.let { com.yzddmr6.prismspace.controller.SystemAppSelectionClient.restoreMirrorIfNeeded(context, it) }
            }
            else -> cancel(context)
        }
    }

    private fun notify(context: Context, title: Int, body: Int) {
        if (NotificationIds.SpaceHealth.isBlocked(context)) return
        val intent = Intent(context, com.yzddmr6.prismspace.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
        NotificationIds.SpaceHealth.post(context) {
            setSmallIcon(com.yzddmr6.prismspace.shared.R.drawable.ic_landscape_black_24dp)
            setColor(context.getColor(com.yzddmr6.prismspace.shared.R.color.primary))
            setContentTitle(context.getString(title))
            setContentText(context.getString(body))
            setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(body)))
            setContentIntent(PendingIntent.getActivity(context, 0, intent, flags))
            setAutoCancel(false)
            setOnlyAlertOnce(true)
            setCategory(Notification.CATEGORY_STATUS)
        }
    }

    private fun cancel(context: Context) = NotificationIds.SpaceHealth.cancel(context)

    private companion object {
        const val TAG = "Prism.SelfCheck"
        const val REASON = "boot_self_check"
        val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.MANAGED_PROFILE_ADDED",
        )
    }
}

/** Pure mapping used by the settings screen and tests: which usability state deserves a warning. */
fun needsAttention(state: SpaceState?, userId: Int): Boolean =
    state != null && state.userId == userId && when (spaceUsabilityFromState(state, userId)) {
        SpaceUsability.LockedNeedsUnlock, SpaceUsability.Suspended, SpaceUsability.BridgeNotReady -> true
        else -> false
    }