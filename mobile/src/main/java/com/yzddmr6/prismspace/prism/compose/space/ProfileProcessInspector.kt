package com.yzddmr6.prismspace.prism.compose.space

import android.content.Context
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 空间内进程列表 — only reachable through a privileged shell.
 *
 * Android hides other users' processes from apps (`getRunningAppProcesses` reports the caller's
 * own processes since API 22), so there is no unprivileged way to answer "what is running inside
 * the space". Rather than showing an approximation that looks authoritative, this reports
 * [Result.NoPrivilege] and the screen says so.
 */
object ProfileProcessInspector {

    data class ProcessRow(
        val name: String,
        val pid: Int,
        /** Resident set size in kilobytes, as `ps` reports it (shared pages included). */
        val rssKb: Long,
    )

    sealed interface Result {
        data class Ok(val rows: List<ProcessRow>) : Result
        /** No root and no Shizuku: the caller must tell the user to switch transport. */
        data object NoPrivilege : Result
        data class Failed(val detail: String) : Result
    }

    suspend fun inspect(context: Context, userId: Int): Result = withContext(Dispatchers.IO) {
        val shell = runCatching { resolvePrivilegedShell(context) }.getOrNull()
            ?: return@withContext Result.NoPrivilege
        val lines = shell.run(PS_COMMAND)
        if (lines == null) {
            DiagnosticLog.w(TAG, "process list unavailable transport=${shell.name} user=$userId")
            return@withContext Result.Failed(shell.name)
        }
        val rows = parseProcessList(lines, userPrefix(userId))
        DiagnosticLog.i(TAG, "process list user=$userId transport=${shell.name} rows=${rows.size}")
        Result.Ok(rows)
    }

    /** `u11_` for user 11: the per-user process-name prefix Android uses for `ps`. */
    internal fun userPrefix(userId: Int): String = "u${userId}_"

    /**
     * Parses `ps -A -o USER,PID,RSS,NAME` output for rows belonging to [prefix].
     * Header and malformed rows are skipped; the result is ordered by memory, largest first.
     */
    internal fun parseProcessList(lines: List<String>, prefix: String): List<ProcessRow> =
        lines.asSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() }
            .filter { it.startsWith(prefix) }
            .mapNotNull { line ->
                val parts = line.split(Regex("\\s+"), limit = 4)
                if (parts.size < 4) return@mapNotNull null
                val pid = parts[1].toIntOrNull() ?: return@mapNotNull null
                val rss = parts[2].toLongOrNull() ?: return@mapNotNull null
                ProcessRow(name = parts[3], pid = pid, rssKb = rss)
            }
            .sortedByDescending { it.rssKb }
            .toList()

    private const val TAG = "Prism.Procs"
    private const val PS_COMMAND = "ps -A -o USER,PID,RSS,NAME"
}