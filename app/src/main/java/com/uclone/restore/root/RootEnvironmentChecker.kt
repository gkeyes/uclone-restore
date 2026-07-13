package com.uclone.restore.root

import com.uclone.restore.model.CheckResult
import com.uclone.restore.model.EnvironmentStatus
import com.uclone.restore.model.UCloneSettings
import com.uclone.restore.model.User10CeState
import com.uclone.restore.sync.ShellScripts
import com.uclone.restore.sync.WorkspacePathGuard

class RootEnvironmentChecker(private val shell: RootShellExecutor) {
    suspend fun validateSettingsTarget(settings: UCloneSettings): CheckResult {
        val result = shell.exec(
            """
                USERS=${'$'}(/system/bin/pm list users 2>&1) || {
                  echo "ERR_SETTINGS_USERS_UNAVAILABLE:${'$'}USERS" >&2
                  exit 76
                }
                case "${'$'}USERS" in
                  *"UserInfo{${settings.mainUserId}:"*) ;;
                  *) echo "ERR_SETTINGS_MAIN_USER_MISSING:${settings.mainUserId}" >&2; exit 76 ;;
                esac
                case "${'$'}USERS" in
                  *"UserInfo{${settings.cloneUserId}:"*) ;;
                  *) echo "ERR_SETTINGS_CLONE_USER_MISSING:${settings.cloneUserId}" >&2; exit 76 ;;
                esac
                ${WorkspacePathGuard.inspect(settings.rootDir)}
                echo "SETTINGS_TARGET_VALID:main=${settings.mainUserId} clone=${settings.cloneUserId} root=${'$'}ROOT_REAL"
            """.trimIndent(),
            timeoutSeconds = 20,
        )
        val output = result.stderr + "\n" + result.stdout
        val detail = when {
            "ERR_SETTINGS_MAIN_USER_MISSING:" in output -> "主系统 user${settings.mainUserId} 不存在"
            "ERR_SETTINGS_CLONE_USER_MISSING:" in output -> "分身系统 user${settings.cloneUserId} 不存在"
            "ERR_SETTINGS_USERS_UNAVAILABLE:" in output -> "无法读取 Android 用户列表"
            !result.isSuccess -> "工作目录未通过安全校验：${result.stderr.lineSequence().firstOrNull(String::isNotBlank) ?: result.exitCode}"
            else -> "用户和工作目录校验通过"
        }
        return CheckResult(result.isSuccess, detail)
    }

    suspend fun check(settings: UCloneSettings): EnvironmentStatus {
        val id = shell.exec("id", timeoutSeconds = 15)
        val root = CheckResult(id.stdout.contains("uid=0"), id.stdout.ifBlank { id.stderr })
        val users = shell.exec("pm list users", timeoutSeconds = 20)
        val currentUser = shell.exec("am get-current-user", timeoutSeconds = 20)
        val cloneState = shell.exec(
            ShellScripts.boundedUserStateProbe(settings.cloneUserId),
            timeoutSeconds = 5,
        )
        val cloneStateRaw = cloneState.stdout.trim().ifBlank { cloneState.stderr.trim().ifBlank { "未知" } }
        val user0Present = users.stdout.contains("UserInfo{${settings.mainUserId}:")
        val user10Present = users.stdout.contains("UserInfo{${settings.cloneUserId}:")
        val writable = shell.exec(
            workspaceInitializationScript(settings.rootDir),
            timeoutSeconds = 20,
        )
        val snapshotReady = shell.exec(
            """
                ${WorkspacePathGuard.require(settings.rootDir)}
                mkdir -p "${'$'}ROOT/snapshots" "${'$'}ROOT/rollback" "${'$'}ROOT/logs" "${'$'}ROOT/tmp"
            """.trimIndent(),
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

    private fun workspaceInitializationScript(rootDir: String): String =
        "${WorkspacePathGuard.require(rootDir)}\necho \"WRITABLE:${'$'}ROOT\""

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
