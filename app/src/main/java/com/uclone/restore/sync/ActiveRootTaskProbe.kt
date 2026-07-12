package com.uclone.restore.sync

import com.uclone.restore.root.RootShellExecutor

internal data class ActiveRootTaskState(
    val requestId: String,
    val isLive: Boolean,
    val recoveryTransactionId: String? = null,
)

internal class ActiveRootTaskProbe(private val shell: RootShellExecutor) {
    suspend fun probe(rootDir: String): ActiveRootTaskState? {
        val result = shell.exec(
            """
                ${WorkspacePathGuard.inspect(rootDir)}
                [ "${'$'}UCLONE_WORKSPACE_MISSING" = "0" ] || exit 0
                CLAIM_STATE="${'$'}ROOT/locks/active_task.claim"
                LEGACY_STATE="${'$'}ROOT/locks/active_task/state"
                [ ! -L "${'$'}CLAIM_STATE" ] || {
                  echo "ERR_ACTIVE_ROOT_TASK_UNSAFE_CLAIM:${'$'}CLAIM_STATE" >&2
                  exit 75
                }
                if [ -f "${'$'}CLAIM_STATE" ] && [ ! -L "${'$'}CLAIM_STATE" ]; then
                  STATE="${'$'}CLAIM_STATE"
                else
                  STATE="${'$'}LEGACY_STATE"
                fi
                [ -f "${'$'}STATE" ] || exit 0
                value() { awk -F= -v key="${'$'}1" '${'$'}1 == key { sub(/^[^=]*=/, ""); print; exit }' "${'$'}STATE"; }
                REQUEST_ID=${'$'}(value requestId)
                RECOVERY_TRANSACTION_ID=${'$'}(value recoveryTransactionId)
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
                printf 'ACTIVE_ROOT_TASK\t%s\t%s\t%s\n' "${'$'}REQUEST_ID" "${'$'}LIVE" "${'$'}RECOVERY_TRANSACTION_ID"
            """.trimIndent(),
            timeoutSeconds = 15,
        )
        check(result.isSuccess) {
            result.stderr.lineSequence().firstOrNull(String::isNotBlank)
                ?: "Root 任务锁检查失败（exit=${result.exitCode}）"
        }
        val parts = result.stdout.lineSequence().firstOrNull { it.startsWith("ACTIVE_ROOT_TASK\t") }
            ?.split('\t', limit = 4)
            ?: return null
        val requestId = parts.getOrNull(1)?.takeIf(String::isNotBlank) ?: return null
        return ActiveRootTaskState(
            requestId = requestId,
            isLive = parts.getOrNull(2) == "1",
            recoveryTransactionId = parts.getOrNull(3)?.takeIf { it.matches(SAFE_ID) },
        )
    }

    private companion object {
        val SAFE_ID = Regex("[A-Za-z0-9_.-]{1,128}")
    }
}
