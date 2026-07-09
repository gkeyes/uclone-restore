package com.uclone.restore.root

import com.uclone.restore.model.CheckResult
import com.uclone.restore.model.EnvironmentStatus
import com.uclone.restore.model.UCloneSettings
import com.uclone.restore.model.User10CeState

class RootEnvironmentChecker(private val shell: RootShellExecutor) {
    suspend fun check(settings: UCloneSettings): EnvironmentStatus {
        val id = shell.exec("id", timeoutSeconds = 15)
        val root = CheckResult(id.stdout.contains("uid=0"), id.stdout.ifBlank { id.stderr })
        val users = shell.exec("pm list users", timeoutSeconds = 20)
        val currentUser = shell.exec("am get-current-user", timeoutSeconds = 20)
        val cloneState = shell.exec("am get-started-user-state ${settings.cloneUserId}", timeoutSeconds = 20)
        val cloneStateRaw = cloneState.stdout.trim().ifBlank { cloneState.stderr.trim().ifBlank { "未知" } }
        val user0Present = users.stdout.contains("UserInfo{${settings.mainUserId}:")
        val user10Present = users.stdout.contains("UserInfo{${settings.cloneUserId}:")
        val writable = shell.exec(
            "mkdir -p ${shellQuote(settings.rootDir)} && [ -d ${shellQuote(settings.rootDir)} ] && [ -w ${shellQuote(settings.rootDir)} ] && echo WRITABLE || { echo NOT_WRITABLE:${shellQuote(settings.rootDir)}; exit 1; }",
            timeoutSeconds = 20,
        )
        val snapshotReady = shell.exec(
            "mkdir -p ${shellQuote(settings.rootDir + "/snapshots")} ${shellQuote(settings.rootDir + "/rollback")} ${shellQuote(settings.rootDir + "/logs")} ${shellQuote(settings.rootDir + "/tmp")}",
            timeoutSeconds = 20,
        )
        val ceBase = if (user10Present) {
            probeBasePath("/data/user/${settings.cloneUserId}")
        } else {
            CheckResult(false, "user${settings.cloneUserId} 不存在")
        }
        val deBase = if (user10Present) {
            probeBasePath("/data/user_de/${settings.cloneUserId}")
        } else {
            CheckResult(false, "user${settings.cloneUserId} 不存在")
        }
        return EnvironmentStatus(
            root = root,
            currentUser = currentUser.stdout.trim().ifBlank { "未知" },
            user0Present = user0Present,
            user10Present = user10Present,
            user10State = cloneStateRaw,
            user10CeState = User10CeState.fromRaw(cloneStateRaw, user10Present),
            user10CeBaseReadable = ceBase,
            user10DeBaseReadable = deBase,
            dataAdbWritable = CheckResult(writable.isSuccess, writable.stdout.ifBlank { writable.stderr }),
            snapshotDirReady = CheckResult(snapshotReady.isSuccess, snapshotReady.stdout.ifBlank { snapshotReady.stderr }),
        )
    }

    private suspend fun probeBasePath(path: String): CheckResult {
        val quoted = shellQuote(path)
        val result = shell.exec(
            """
                P=$quoted
                if [ -d "${'$'}P" ]; then
                  ITEMS=${'$'}(find "${'$'}P" -mindepth 1 -maxdepth 1 2>/dev/null | wc -l | tr -d ' ')
                  echo "READABLE items=${'$'}ITEMS path=${'$'}P"
                else
                  echo "MISSING path=${'$'}P"
                  exit 1
                fi
            """.trimIndent(),
            timeoutSeconds = 20,
        )
        return CheckResult(result.isSuccess, result.stdout.trim().ifBlank { result.stderr.trim() })
    }
}
