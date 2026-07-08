package com.uclone.restore.root

import com.uclone.restore.model.CheckResult
import com.uclone.restore.model.EnvironmentStatus
import com.uclone.restore.model.UCloneSettings

class RootEnvironmentChecker(private val shell: RootShellExecutor) {
    suspend fun check(settings: UCloneSettings): EnvironmentStatus {
        val id = shell.exec("id", timeoutSeconds = 15)
        val root = CheckResult(id.stdout.contains("uid=0"), id.stdout.ifBlank { id.stderr })
        val users = shell.exec("pm list users", timeoutSeconds = 20)
        val currentUser = shell.exec("am get-current-user", timeoutSeconds = 20)
        val cloneState = shell.exec("am get-started-user-state ${settings.cloneUserId}", timeoutSeconds = 20)
        val writable = shell.exec(
            "mkdir -p ${shellQuote(settings.rootDir)} && touch ${shellQuote(settings.rootDir + "/.uclone_write_test")} && rm -f ${shellQuote(settings.rootDir + "/.uclone_write_test")}",
            timeoutSeconds = 20,
        )
        val snapshotReady = shell.exec(
            "mkdir -p ${shellQuote(settings.rootDir + "/snapshots")} ${shellQuote(settings.rootDir + "/rollback")} ${shellQuote(settings.rootDir + "/logs")} ${shellQuote(settings.rootDir + "/tmp")}",
            timeoutSeconds = 20,
        )
        return EnvironmentStatus(
            root = root,
            currentUser = currentUser.stdout.trim().ifBlank { "未知" },
            user0Present = users.stdout.contains("UserInfo{${settings.mainUserId}:"),
            user10Present = users.stdout.contains("UserInfo{${settings.cloneUserId}:"),
            user10State = cloneState.stdout.trim().ifBlank { cloneState.stderr.trim().ifBlank { "未知" } },
            dataAdbWritable = CheckResult(writable.isSuccess, writable.stdout.ifBlank { writable.stderr }),
            snapshotDirReady = CheckResult(snapshotReady.isSuccess, snapshotReady.stdout.ifBlank { snapshotReady.stderr }),
        )
    }
}
