package com.uclone.restore.sync

import com.uclone.restore.root.RootShellExecutor
import com.uclone.restore.root.shellQuote

internal data class ActiveRootTaskState(
    val requestId: String,
    val isLive: Boolean,
)

internal class ActiveRootTaskProbe(private val shell: RootShellExecutor) {
    suspend fun probe(rootDir: String): ActiveRootTaskState? {
        val result = shell.exec(
            """
                STATE=${shellQuote("$rootDir/locks/active_task/state")}
                [ -f "${'$'}STATE" ] || exit 0
                value() { awk -F= -v key="${'$'}1" '${'$'}1 == key { sub(/^[^=]*=/, ""); print; exit }' "${'$'}STATE"; }
                REQUEST_ID=${'$'}(value requestId)
                PID=${'$'}(value pid)
                BOOT_ID=${'$'}(value bootId)
                START_TICKS=${'$'}(value pidStartTicks)
                CURRENT_BOOT=${'$'}(cat /proc/sys/kernel/random/boot_id 2>/dev/null || echo unknown)
                LIVE_TICKS=""
                case "${'$'}PID" in ''|*[!0-9]*) PID=0 ;; esac
                if [ "${'$'}PID" -gt 1 ] && [ -r "/proc/${'$'}PID/stat" ]; then
                  LIVE_TICKS=${'$'}(awk '{print ${'$'}22}' "/proc/${'$'}PID/stat" 2>/dev/null || true)
                fi
                LIVE=0
                if [ -n "${'$'}REQUEST_ID" ] && [ "${'$'}BOOT_ID" = "${'$'}CURRENT_BOOT" ] &&
                   [ -n "${'$'}START_TICKS" ] && [ "${'$'}START_TICKS" = "${'$'}LIVE_TICKS" ] &&
                   kill -0 "${'$'}PID" 2>/dev/null; then
                  LIVE=1
                fi
                printf 'ACTIVE_ROOT_TASK\t%s\t%s\n' "${'$'}REQUEST_ID" "${'$'}LIVE"
            """.trimIndent(),
            timeoutSeconds = 15,
        )
        val parts = result.stdout.lineSequence().firstOrNull { it.startsWith("ACTIVE_ROOT_TASK\t") }
            ?.split('\t', limit = 3)
            ?: return null
        val requestId = parts.getOrNull(1)?.takeIf(String::isNotBlank) ?: return null
        return ActiveRootTaskState(requestId, parts.getOrNull(2) == "1")
    }
}
