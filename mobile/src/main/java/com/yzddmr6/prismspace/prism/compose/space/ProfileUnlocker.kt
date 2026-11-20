package com.yzddmr6.prismspace.prism.compose.space

import android.content.Context
import android.os.SystemClock
import android.os.UserManager
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import com.yzddmr6.prismspace.util.UserHandles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Outcome of asking Android to unlock a work profile's credential-encrypted storage. */
sealed interface ProfileUnlockOutcome {
    /** The profile is unlocked and its spaces are usable again. */
    data object Unlocked : ProfileUnlockOutcome

    /** No su/Shizuku shell. Unlocking is a system privilege, so only the device or a reboot can. */
    data object NoPrivilegedShell : ProfileUnlockOutcome

    /** The shell ran but the system still reports the profile as locked (ROM-level vold failure). */
    data object StillLocked : ProfileUnlockOutcome
}

/**
 * Unlocks a work profile's credential-encrypted (CE) storage from the parent side.
 *
 * Why this is not simply "open the profile entry": the profile entry is direct-boot aware, so
 * Android happily starts it inside a CE-locked profile where every CE read throws. Launching it
 * can never clear the lock — it only crashed the profile process. Unlocking another user's CE
 * storage is a privileged operation, reachable only through `am start-user` (bring the user up)
 * plus `am unlock-user` (clear CE). Both are needed: start alone leaves the user locked.
 *
 * On ROMs whose vold cannot unlock a profile started after boot, the call fails honestly with
 * [ProfileUnlockOutcome.StillLocked]; a reboot is then the only recovery and [reboot] performs it
 * once the user confirms.
 */
object ProfileUnlocker {
    /** Pure and unit-tested: start the user, then ask the system to unlock its CE storage. */
    fun unlockCommand(userId: Int): String = "am start-user $userId; am unlock-user $userId"

    /** Pure and unit-tested: the one reboot command both transports accept. */
    fun rebootCommand(): String = "svc power reboot"

    suspend fun unlock(context: Context, userId: Int): ProfileUnlockOutcome = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        if (isUnlocked(appContext, userId)) return@withContext ProfileUnlockOutcome.Unlocked
        val shell = resolvePrivilegedShell(appContext) ?: return@withContext ProfileUnlockOutcome.NoPrivilegedShell
        DiagnosticLog.i(TAG, "privileged unlock user=$userId transport=${shell.name}")
        shell.run(unlockCommand(userId))
        val deadline = SystemClock.elapsedRealtime() + UNLOCK_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (isUnlocked(appContext, userId)) {
                DiagnosticLog.i(TAG, "privileged unlock succeeded user=$userId transport=${shell.name}")
                return@withContext ProfileUnlockOutcome.Unlocked
            }
            delay(POLL_INTERVAL_MS)
        }
        DiagnosticLog.w(TAG, "privileged unlock still locked user=$userId transport=${shell.name}")
        ProfileUnlockOutcome.StillLocked
    }

    /** @return true only when the device is actually going down; false means the user must reboot. */
    suspend fun reboot(context: Context): Boolean = withContext(Dispatchers.IO) {
        val shell = resolvePrivilegedShell(context.applicationContext)
            ?: return@withContext false
        DiagnosticLog.i(TAG, "privileged reboot requested transport=${shell.name}")
        shell.run(rebootCommand())
        // A working request tears this process down within a second or two. Surviving the wait
        // means the shell lacked the REBOOT permission, so report a failure instead of a false "ok".
        delay(REBOOT_VERIFY_MS)
        DiagnosticLog.w(TAG, "reboot request did not take effect transport=${shell.name}")
        false
    }

    private fun isUnlocked(context: Context, userId: Int): Boolean = runCatching {
        context.getSystemService(UserManager::class.java)?.isUserUnlocked(UserHandles.of(userId)) == true
    }.getOrDefault(false)

    private const val UNLOCK_TIMEOUT_MS = 4_000L
    private const val REBOOT_VERIFY_MS = 6_000L
    private const val POLL_INTERVAL_MS = 200L
    private const val TAG = "Prism.Unlock"
}
