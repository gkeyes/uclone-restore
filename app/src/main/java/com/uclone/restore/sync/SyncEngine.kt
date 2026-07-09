package com.uclone.restore.sync

import com.uclone.restore.model.AppRule
import com.uclone.restore.model.RestoreBackupEntry
import com.uclone.restore.model.StepStatus
import com.uclone.restore.model.TaskProgress
import com.uclone.restore.model.TaskRecord
import com.uclone.restore.model.TaskStatus
import com.uclone.restore.model.TaskStep
import com.uclone.restore.model.TaskType
import com.uclone.restore.model.UCloneSettings
import com.uclone.restore.root.RootEnvironmentChecker
import com.uclone.restore.root.RootShellExecutor
import com.uclone.restore.root.shellQuote

data class SnapshotMetadata(
    val updatedAt: Long,
    val sizeKb: Long?,
)

class SyncEngine(
    private val shell: RootShellExecutor,
    private val environmentChecker: RootEnvironmentChecker,
    private val logStore: TaskLogStore,
    private val appPackage: String,
) {
    suspend fun checkEnvironment(settings: UCloneSettings) = environmentChecker.check(settings)

    suspend fun captureSnapshot(
        packageName: String,
        rule: AppRule,
        settings: UCloneSettings,
        report: suspend (TaskProgress) -> Unit,
    ): TaskRecord = runScriptTask(
        type = TaskType.CAPTURE_SNAPSHOT_FROM_CLONE,
        packageName = packageName,
        settings = settings,
        labels = listOf("检查 root", "检查分身解锁", "停止分身 App", "复制数据", "采集权限/AppOps", "激活快照"),
        script = ShellScripts.capture(packageName, rule, settings, appPackage),
        report = report,
    )

    suspend fun restoreSnapshot(
        packageName: String,
        settings: UCloneSettings,
        report: suspend (TaskProgress) -> Unit,
    ): TaskRecord = runScriptTask(
        type = TaskType.RESTORE_SNAPSHOT_TO_MAIN,
        packageName = packageName,
        settings = settings,
        labels = listOf("检查 root", "读取主动备份", "生成被动备份", "覆盖目录内容", "恢复权限/AppOps", "完成"),
        script = ShellScripts.restore(packageName, settings, appPackage),
        report = report,
    )

    suspend fun restoreFromCloneLatest(
        packageName: String,
        rule: AppRule,
        settings: UCloneSettings,
        report: suspend (TaskProgress) -> Unit,
    ): TaskRecord {
        captureSnapshot(packageName, rule, settings, report)
        return restoreSnapshot(packageName, settings, report)
    }

    suspend fun switchToCloneState(
        packageName: String,
        rule: AppRule,
        settings: UCloneSettings,
        report: suspend (TaskProgress) -> Unit,
    ): TaskRecord {
        return runScriptTask(
            type = TaskType.SWITCH_TO_CLONE_STATE,
            packageName = packageName,
            settings = settings,
            labels = listOf("检查 root", "读取分身当前数据", "生成被动备份", "恢复分身态", "记录还原按钮", "完成"),
            script = ShellScripts.switchFromCloneLatest(packageName, rule, settings, appPackage),
            report = report,
        )
    }

    suspend fun restoreSwitchMainState(
        packageName: String,
        rollbackId: String,
        settings: UCloneSettings,
        report: suspend (TaskProgress) -> Unit,
    ): TaskRecord = runScriptTask(
        type = TaskType.RESTORE_SWITCH_MAIN_STATE,
        packageName = packageName,
        settings = settings,
        labels = listOf("检查 root", "读取切换前被动备份", "生成被动备份", "恢复主系统态", "清除切换标记", "完成"),
        script = ShellScripts.rollback(packageName, rollbackId, settings, appPackage, clearSwitchMarker = true),
        report = report,
    )

    suspend fun rollback(
        packageName: String,
        rollbackId: String,
        settings: UCloneSettings,
        report: suspend (TaskProgress) -> Unit,
    ): TaskRecord = runScriptTask(
        type = TaskType.ROLLBACK_MAIN_DATA,
        packageName = packageName,
        settings = settings,
        labels = listOf("检查 root", "读取被动备份", "停止相关进程", "恢复旧数据", "恢复权限/AppOps", "完成"),
        script = ShellScripts.rollback(packageName, rollbackId, settings, appPackage),
        report = report,
    )

    suspend fun deleteSnapshot(
        packageName: String,
        settings: UCloneSettings,
        report: suspend (TaskProgress) -> Unit,
    ): TaskRecord = runScriptTask(
        type = TaskType.DELETE_SNAPSHOT,
        packageName = packageName,
        settings = settings,
        labels = listOf("检查 root", "读取 active 快照", "统计大小", "删除 active", "确认清理"),
        script = ShellScripts.deleteSnapshot(packageName, settings, appPackage),
        report = report,
    )

    suspend fun deleteRestoreBackup(
        packageName: String,
        rollbackId: String,
        settings: UCloneSettings,
        report: suspend (TaskProgress) -> Unit,
    ): TaskRecord = runScriptTask(
        type = TaskType.DELETE_RESTORE_BACKUP,
        packageName = packageName,
        settings = settings,
        labels = listOf("检查 root", "读取被动备份", "删除备份目录", "清理切换标记", "完成"),
        script = ShellScripts.deleteRestoreBackup(packageName, rollbackId, settings, appPackage),
        report = report,
    )

    suspend fun latestSnapshotTime(packageName: String, settings: UCloneSettings): Long? {
        return latestSnapshotMetadata(packageName, settings)?.updatedAt
    }

    suspend fun latestSnapshotMetadata(packageName: String, settings: UCloneSettings): SnapshotMetadata? {
        return snapshotMetadata(settings).getValueOrNull(packageName)
    }

    suspend fun snapshotMetadata(settings: UCloneSettings): Map<String, SnapshotMetadata> {
        val root = "${settings.rootDir}/snapshots"
        val script = """
            [ -d ${shellQuote(root)} ] || exit 0
            for f in ${shellQuote(root)}/*/active/manifest.json; do
              [ -f "${'$'}f" ] || continue
              pkg=${'$'}(basename "${'$'}(dirname "${'$'}(dirname "${'$'}f")")")
              ts=${'$'}(stat -c %Y "${'$'}f" 2>/dev/null || echo 0)
              size=${'$'}(sed -n 's/.*"snapshotSizeKb":"\([0-9][0-9]*\)".*/\1/p' "${'$'}f" | head -1)
              [ -n "${'$'}size" ] || size=${'$'}(du -sk "${'$'}(dirname "${'$'}f")" 2>/dev/null | awk '{print ${'$'}1}')
              echo "${'$'}pkg ${'$'}ts ${'$'}size"
            done
        """.trimIndent()
        val result = shell.exec(script, 30)
        return result.stdout.lineSequence().mapNotNull { line ->
            val parts = line.trim().split(" ")
            val packageName = parts.firstOrNull() ?: return@mapNotNull null
            val millis = parts.getOrNull(1)?.toLongOrNull()?.times(1000) ?: return@mapNotNull null
            packageName to SnapshotMetadata(
                updatedAt = millis,
                sizeKb = parts.getOrNull(2)?.toLongOrNull(),
            )
        }.toMap()
    }

    suspend fun snapshotTimes(settings: UCloneSettings): Map<String, Long> =
        snapshotMetadata(settings).mapValues { it.value.updatedAt }

    suspend fun switchMarkerIds(settings: UCloneSettings): Map<String, String> {
        val root = settings.rootDir
        val switchRoot = "$root/switches"
        val script = """
            [ -d ${shellQuote(switchRoot)} ] || exit 0
            for f in ${shellQuote(switchRoot)}/*/active; do
              [ -f "${'$'}f" ] || continue
              pkg=${'$'}(basename "${'$'}(dirname "${'$'}f")")
              id=${'$'}(sed -n '1p' "${'$'}f" | tr -d '\r')
              [ -n "${'$'}pkg" ] && [ -n "${'$'}id" ] || continue
              [ -d ${shellQuote(root)}/rollback/"${'$'}pkg"/"${'$'}id" ] || continue
              echo "${'$'}pkg ${'$'}id"
            done
        """.trimIndent()
        val result = shell.exec(script, 20)
        return result.stdout.lineSequence().mapNotNull { line ->
            val parts = line.trim().split(" ", limit = 2)
            val packageName = parts.getOrNull(0)?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val rollbackId = parts.getOrNull(1)?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            packageName to rollbackId
        }.toMap()
    }

    suspend fun switchMarkerId(packageName: String, settings: UCloneSettings): String? =
        switchMarkerIds(settings)[packageName]

    suspend fun listRollbackIds(packageName: String, settings: UCloneSettings): List<String> {
        val path = "${settings.rootDir}/rollback/$packageName"
        val result = shell.exec("[ -d ${shellQuote(path)} ] && ls -1 ${shellQuote(path)} || true", 20)
        return result.stdout.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
    }

    suspend fun listRestoreBackups(settings: UCloneSettings): List<RestoreBackupEntry> {
        val rollbackRoot = "${settings.rootDir}/rollback"
        val switchRoot = "${settings.rootDir}/switches"
        val script = """
            ROLLBACK_ROOT=${shellQuote(rollbackRoot)}
            SWITCH_ROOT=${shellQuote(switchRoot)}
            [ -d "${'$'}ROLLBACK_ROOT" ] || exit 0
            for d in "${'$'}ROLLBACK_ROOT"/*/*; do
              [ -d "${'$'}d" ] || continue
              pkg=${'$'}(basename "${'$'}(dirname "${'$'}d")")
              id=${'$'}(basename "${'$'}d")
              ts=${'$'}(stat -c %Y "${'$'}d" 2>/dev/null || echo 0)
              size=${'$'}(du -sk "${'$'}d" 2>/dev/null | awk '{print ${'$'}1}')
              active=0
              if [ -f "${'$'}SWITCH_ROOT/${'$'}pkg/active" ] && [ "${'$'}(sed -n '1p' "${'$'}SWITCH_ROOT/${'$'}pkg/active" | tr -d '\r')" = "${'$'}id" ]; then
                active=1
              fi
              reason=""
              if [ -f "${'$'}d/manifest.json" ]; then
                reason=${'$'}(sed -n 's/.*"reason":"\([^"]*\)".*/\1/p' "${'$'}d/manifest.json" | head -1)
              fi
              if [ -z "${'$'}reason" ]; then
                case "${'$'}id" in
                  rollback_*) reason="恢复主系统备份前生成" ;;
                  *) if [ "${'$'}active" = "1" ]; then reason="切换到分身态前生成"; else reason="恢复或切换前生成"; fi ;;
                esac
              fi
              printf '%s\t%s\t%s\t%s\t%s\t%s\n' "${'$'}pkg" "${'$'}id" "${'$'}ts" "${'$'}size" "${'$'}active" "${'$'}reason"
            done
        """.trimIndent()
        val result = shell.exec(script, 30)
        return result.stdout.lineSequence().mapNotNull { line ->
            val parts = line.split("\t", limit = 6)
            val packageName = parts.getOrNull(0)?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val rollbackId = parts.getOrNull(1)?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val createdAt = parts.getOrNull(2)?.toLongOrNull()?.times(1000) ?: 0L
            RestoreBackupEntry(
                packageName = packageName,
                rollbackId = rollbackId,
                createdAt = createdAt,
                sizeKb = parts.getOrNull(3)?.toLongOrNull(),
                isActiveSwitchBackup = parts.getOrNull(4) == "1",
                reason = parts.getOrNull(5)?.takeIf(String::isNotBlank) ?: "被动备份",
            )
        }.sortedByDescending { it.createdAt }
            .distinctBy { it.packageName }
            .toList()
    }

    fun history(): List<TaskRecord> = logStore.all()

    suspend fun clearLogs(settings: UCloneSettings) =
        shell.exec(
            """
                LOG_DIR=${shellQuote("${settings.rootDir}/logs")}
                mkdir -p "${'$'}LOG_DIR" || exit 10
                COUNT=0
                for f in "${'$'}LOG_DIR"/*.log; do
                  [ -f "${'$'}f" ] || continue
                  rm -f "${'$'}f" || exit 11
                  COUNT=${'$'}((COUNT + 1))
                done
                echo "CLEARED_LOGS=${'$'}COUNT"
            """.trimIndent(),
            timeoutSeconds = 60,
        ).also { result ->
            if (result.isSuccess) {
                logStore.clear()
            }
        }

    suspend fun startCloneUser(settings: UCloneSettings) =
        shell.exec("am start-user -w ${settings.cloneUserId}", timeoutSeconds = 60)

    suspend fun switchToCloneUser(settings: UCloneSettings) =
        shell.exec("am switch-user ${settings.cloneUserId}", timeoutSeconds = 30)

    suspend fun stopCloneUser(settings: UCloneSettings) =
        shell.exec("am stop-user -w ${settings.cloneUserId}", timeoutSeconds = 60)

    private suspend fun runScriptTask(
        type: TaskType,
        packageName: String,
        settings: UCloneSettings,
        labels: List<String>,
        script: String,
        report: suspend (TaskProgress) -> Unit,
    ): TaskRecord {
        val timestamp = System.currentTimeMillis()
        val logPath = "${settings.rootDir}/logs/${type.name.lowercase()}_${packageName}_$timestamp.log"
        var steps = labels.mapIndexed { index, label ->
            TaskStep(label, if (index == 0) StepStatus.RUNNING else StepStatus.PENDING)
        }
        val task = logStore.running(type, packageName, logPath)
        report(TaskProgress(task, steps, ""))
        logStore.append(logPath, "TASK=$type\nPACKAGE=$packageName\nSTART=$timestamp\n")
        val root = shell.exec("id", timeoutSeconds = 15)
        if (!root.stdout.contains("uid=0")) {
            steps = failRemaining(steps, 0)
            val failed = logStore.finish(task, TaskStatus.FAILED, "Root 权限不可用")
            report(TaskProgress(failed, steps, root.stderr.ifBlank { root.stdout }))
            return failed
        }
        steps = mark(steps, 0, StepStatus.SUCCESS, 1, StepStatus.RUNNING)
        report(TaskProgress(task, steps, "Root OK\n"))
        logStore.append(logPath, "ROOT=${root.stdout}${root.stderr}\n")
        val result = shell.exec(script, timeoutSeconds = 900)
        val liveLog = "STDOUT:\n${result.stdout}\nSTDERR:\n${result.stderr}\nEXIT=${result.exitCode}\n"
        logStore.append(logPath, liveLog)
        val status = if (result.isSuccess) TaskStatus.SUCCESS else TaskStatus.FAILED
        steps = if (result.isSuccess) {
            steps.map { it.copy(status = StepStatus.SUCCESS) }
        } else {
            failRemaining(steps, steps.indexOfFirst { it.status == StepStatus.RUNNING }.coerceAtLeast(1))
        }
        val message = if (result.isSuccess) "完成" else result.stderr.ifBlank { "命令失败：${result.exitCode}" }
        val finished = logStore.finish(task, status, message)
        report(TaskProgress(finished, steps, liveLog))
        return finished
    }

    private fun mark(
        steps: List<TaskStep>,
        doneIndex: Int,
        doneStatus: StepStatus,
        nextIndex: Int,
        nextStatus: StepStatus,
    ): List<TaskStep> = steps.mapIndexed { index, step ->
        when (index) {
            doneIndex -> step.copy(status = doneStatus)
            nextIndex -> step.copy(status = nextStatus)
            else -> step
        }
    }

    private fun failRemaining(steps: List<TaskStep>, failedIndex: Int): List<TaskStep> =
        steps.mapIndexed { index, step ->
            when {
                index < failedIndex -> step.copy(status = StepStatus.SUCCESS)
                index == failedIndex -> step.copy(status = StepStatus.FAILED)
                else -> step.copy(status = StepStatus.PENDING)
            }
        }
}

private fun <K, V> Map<K, V>.getValueOrNull(key: K): V? = if (containsKey(key)) getValue(key) else null
