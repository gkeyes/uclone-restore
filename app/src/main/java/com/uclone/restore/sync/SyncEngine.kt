package com.uclone.restore.sync

import com.uclone.restore.model.AppRule
import com.uclone.restore.model.RestoreBackupEntry
import com.uclone.restore.model.StepStatus
import com.uclone.restore.model.TaskProgress
import com.uclone.restore.model.TaskRecord
import com.uclone.restore.model.TaskStage
import com.uclone.restore.model.TaskStatus
import com.uclone.restore.model.TaskStep
import com.uclone.restore.model.TaskType
import com.uclone.restore.model.UCloneSettings
import com.uclone.restore.root.RootEnvironmentChecker
import com.uclone.restore.root.RootShellExecutor
import com.uclone.restore.root.ShellResult
import com.uclone.restore.root.ShellStream
import com.uclone.restore.root.shellQuote
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class SnapshotMetadata(
    val updatedAt: Long,
    val sizeKb: Long?,
)

class SyncEngine(
    private val shell: RootShellExecutor,
    private val environmentChecker: RootEnvironmentChecker,
    private val logStore: TaskRepository,
    private val appPackage: String,
) {
    suspend fun checkEnvironment(settings: UCloneSettings) = environmentChecker.check(settings)

    suspend fun captureSnapshot(
        packageName: String,
        rule: AppRule,
        settings: UCloneSettings,
        report: (TaskProgress) -> Unit,
        requestId: String = newRequestId(),
    ): TaskRecord = captureSnapshotTask(
        type = TaskType.CAPTURE_SNAPSHOT_FROM_CLONE,
        packageName = packageName,
        rule = rule,
        settings = settings,
        report = report,
        requestId = requestId,
    )

    suspend fun restoreSnapshot(
        packageName: String,
        settings: UCloneSettings,
        report: (TaskProgress) -> Unit,
        requestId: String = newRequestId(),
    ): TaskRecord = restoreSnapshotTask(
        type = TaskType.RESTORE_SNAPSHOT_TO_MAIN,
        packageName = packageName,
        settings = settings,
        report = report,
        requestId = requestId,
    )

    suspend fun restoreFromCloneLatest(
        packageName: String,
        rule: AppRule,
        settings: UCloneSettings,
        report: (TaskProgress) -> Unit,
        requestId: String = newRequestId(),
    ): TaskRecord = runScriptTask(
        type = TaskType.RESTORE_FROM_CLONE_LATEST,
        packageName = packageName,
        settings = settings,
        labels = listOf(
            "检查 root",
            "读取分身当前数据",
            "更新主动备份",
            "生成被动备份",
            "恢复到主系统",
            "验证结果",
        ),
        script = ShellScripts.restoreFromCloneLatest(packageName, rule, settings, appPackage),
        report = report,
        requestId = requestId,
    )

    private suspend fun captureSnapshotTask(
        type: TaskType,
        packageName: String,
        rule: AppRule,
        settings: UCloneSettings,
        report: (TaskProgress) -> Unit,
        requestId: String,
    ): TaskRecord = runScriptTask(
        type = type,
        packageName = packageName,
        settings = settings,
        labels = listOf("检查 root", "检查分身解锁", "停止分身 App", "复制数据", "采集权限/AppOps", "激活快照"),
        script = ShellScripts.capture(packageName, rule, settings, appPackage),
        report = report,
        requestId = requestId,
    )

    private suspend fun restoreSnapshotTask(
        type: TaskType,
        packageName: String,
        settings: UCloneSettings,
        report: (TaskProgress) -> Unit,
        requestId: String,
    ): TaskRecord = runScriptTask(
        type = type,
        packageName = packageName,
        settings = settings,
        labels = listOf("检查 root", "读取主动备份", "生成被动备份", "覆盖目录内容", "恢复权限/AppOps", "完成"),
        script = ShellScripts.restore(packageName, settings, appPackage),
        report = report,
        requestId = requestId,
    )

    suspend fun switchToCloneState(
        packageName: String,
        rule: AppRule,
        settings: UCloneSettings,
        report: (TaskProgress) -> Unit,
        requestId: String = newRequestId(),
    ): TaskRecord {
        return runScriptTask(
            type = TaskType.SWITCH_TO_CLONE_STATE,
            packageName = packageName,
            settings = settings,
            labels = listOf("检查 root", "读取分身当前数据", "生成被动备份", "恢复分身态", "记录还原按钮", "完成"),
            script = ShellScripts.switchFromCloneLatest(packageName, rule, settings, appPackage),
            report = report,
            requestId = requestId,
        )
    }

    suspend fun pushMainToClone(
        packageName: String,
        rule: AppRule,
        settings: UCloneSettings,
        report: (TaskProgress) -> Unit,
        requestId: String = newRequestId(),
    ): TaskRecord {
        return runScriptTask(
            type = TaskType.PUSH_MAIN_TO_CLONE,
            packageName = packageName,
            settings = settings,
            labels = listOf("检查 root", "准备分身目标", "读取主系统数据", "备份分身数据", "推送到分身", "完成"),
            script = ShellScripts.pushMainToClone(packageName, rule, settings, appPackage),
            report = report,
            requestId = requestId,
        )
    }

    suspend fun restoreCloneRollback(
        packageName: String,
        settings: UCloneSettings,
        report: (TaskProgress) -> Unit,
        requestId: String = newRequestId(),
    ): TaskRecord = runScriptTask(
        type = TaskType.RESTORE_CLONE_ROLLBACK_TO_CLONE,
        packageName = packageName,
        settings = settings,
        labels = listOf("检查 root", "读取分身回滚", "备份分身当前数据", "恢复到分身", "恢复权限/AppOps", "完成"),
        script = ShellScripts.restoreCloneRollback(packageName, settings, appPackage),
        report = report,
        requestId = requestId,
    )

    suspend fun restoreSwitchMainState(
        packageName: String,
        rollbackId: String,
        settings: UCloneSettings,
        report: (TaskProgress) -> Unit,
        requestId: String = newRequestId(),
    ): TaskRecord = runScriptTask(
        type = TaskType.RESTORE_SWITCH_MAIN_STATE,
        packageName = packageName,
        settings = settings,
        labels = listOf("检查 root", "读取切换前被动备份", "生成被动备份", "恢复主系统态", "清除切换标记", "完成"),
        script = ShellScripts.rollback(packageName, rollbackId, settings, appPackage, clearSwitchMarker = true),
        report = report,
        requestId = requestId,
    )

    suspend fun rollback(
        packageName: String,
        rollbackId: String,
        settings: UCloneSettings,
        report: (TaskProgress) -> Unit,
        requestId: String = newRequestId(),
    ): TaskRecord = runScriptTask(
        type = TaskType.ROLLBACK_MAIN_DATA,
        packageName = packageName,
        settings = settings,
        labels = listOf("检查 root", "读取被动备份", "停止相关进程", "恢复旧数据", "恢复权限/AppOps", "完成"),
        script = ShellScripts.rollback(packageName, rollbackId, settings, appPackage),
        report = report,
        requestId = requestId,
    )

    suspend fun deleteSnapshot(
        packageName: String,
        settings: UCloneSettings,
        report: (TaskProgress) -> Unit,
        requestId: String = newRequestId(),
    ): TaskRecord = runScriptTask(
        type = TaskType.DELETE_SNAPSHOT,
        packageName = packageName,
        settings = settings,
        labels = listOf("检查 root", "读取 active 快照", "统计大小", "删除 active", "确认清理"),
        script = ShellScripts.deleteSnapshot(packageName, settings, appPackage),
        report = report,
        requestId = requestId,
    )

    suspend fun deleteRestoreBackup(
        packageName: String,
        rollbackId: String,
        settings: UCloneSettings,
        report: (TaskProgress) -> Unit,
        requestId: String = newRequestId(),
    ): TaskRecord = runScriptTask(
        type = TaskType.DELETE_RESTORE_BACKUP,
        packageName = packageName,
        settings = settings,
        labels = listOf("检查 root", "读取被动备份", "删除备份目录", "清理切换标记", "完成"),
        script = ShellScripts.deleteRestoreBackup(packageName, rollbackId, settings, appPackage),
        report = report,
        requestId = requestId,
    )

    suspend fun probeCloneCe(
        settings: UCloneSettings,
        report: (TaskProgress) -> Unit,
        requestId: String = newRequestId(),
    ): TaskRecord = runScriptTask(
        type = TaskType.PROBE_CLONE_CE,
        packageName = "user${settings.cloneUserId}",
        settings = settings,
        labels = listOf("检查 root", "读取状态", "探测 CE/DE", "输出结论"),
        script = ShellScripts.probeCloneCe(settings),
        report = report,
        requestId = requestId,
    )

    suspend fun unlockCloneWithCredential(
        settings: UCloneSettings,
        report: (TaskProgress) -> Unit,
        requestId: String = newRequestId(),
    ): TaskRecord = runScriptTask(
        type = TaskType.UNLOCK_CLONE_WITH_CREDENTIAL,
        packageName = "user${settings.cloneUserId}",
        settings = settings,
        labels = listOf("检查 root", "后台启动分身", "验证 PIN", "等待 CE 解锁", "保持分身运行", "完成"),
        script = ShellScripts.unlockCloneWithCredential(settings),
        report = report,
        requestId = requestId,
    )

    suspend fun debugCloneSystem(
        settings: UCloneSettings,
        report: (TaskProgress) -> Unit,
        requestId: String = newRequestId(),
    ): TaskRecord = runScriptTask(
        type = TaskType.DEBUG_CLONE_SYSTEM,
        packageName = "user${settings.cloneUserId}",
        settings = settings,
        labels = listOf("检查 root", "采集用户状态", "采集包/UID", "采集路径", "采集权限入口", "输出 0.2 结论"),
        script = ShellScripts.debugCloneSystem(settings, appPackage),
        report = report,
        requestId = requestId,
    )

    suspend fun auditRestoreConsistency(
        packageName: String,
        settings: UCloneSettings,
        report: (TaskProgress) -> Unit,
        requestId: String = newRequestId(),
    ): TaskRecord = runScriptTask(
        type = TaskType.AUDIT_RESTORE_CONSISTENCY,
        packageName = packageName,
        settings = settings,
        labels = listOf("检查 root", "创建审计目录", "采集文件树", "采集 UID/SELinux", "采集权限/AppOps", "写入 summary"),
        script = ShellScripts.auditRestoreConsistency(packageName, settings, appPackage),
        report = report,
        requestId = requestId,
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
              [ -f ${shellQuote(root)}/rollback/"${'$'}pkg"/"${'$'}id"/manifest.json ] || continue
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
        val result = shell.exec(
            """
                [ -d ${shellQuote(path)} ] || exit 0
                for d in ${shellQuote(path)}/*; do
                  [ -f "${'$'}d/manifest.json" ] || continue
                  basename "${'$'}d"
                done
            """.trimIndent(),
            20,
        )
        return result.stdout.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
    }

    suspend fun listRestoreBackups(settings: UCloneSettings): List<RestoreBackupEntry> {
        val rollbackRoot = "${settings.rootDir}/rollback"
        val switchRoot = "${settings.rootDir}/switches"
        val script = restoreBackupListScript(rollbackRoot, switchRoot)
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

    suspend fun listCloneRollbackBackups(settings: UCloneSettings): List<RestoreBackupEntry> {
        val root = "${settings.rootDir}/clone_rollback"
        val script = """
            CLONE_ROLLBACK_ROOT=${shellQuote(root)}
            [ -d "${'$'}CLONE_ROLLBACK_ROOT" ] || exit 0
            for d in "${'$'}CLONE_ROLLBACK_ROOT"/*/latest; do
              [ -d "${'$'}d" ] || continue
              [ -f "${'$'}d/manifest.json" ] || continue
              pkg=${'$'}(basename "${'$'}(dirname "${'$'}d")")
              ts=${'$'}(stat -c %Y "${'$'}d" 2>/dev/null || echo 0)
              size=${'$'}(du -sk "${'$'}d" 2>/dev/null | awk '{print ${'$'}1}')
              reason=""
              if [ -f "${'$'}d/manifest.json" ]; then
                reason=${'$'}(sed -n 's/.*"reason":"\([^"]*\)".*/\1/p' "${'$'}d/manifest.json" | head -1)
              fi
              [ -n "${'$'}reason" ] || reason="推送到分身前生成"
              printf '%s\t%s\t%s\t%s\t%s\n' "${'$'}pkg" "latest" "${'$'}ts" "${'$'}size" "${'$'}reason"
            done
        """.trimIndent()
        val result = shell.exec(script, 30)
        return result.stdout.lineSequence().mapNotNull { line ->
            val parts = line.split("\t", limit = 5)
            val packageName = parts.getOrNull(0)?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            RestoreBackupEntry(
                packageName = packageName,
                rollbackId = parts.getOrNull(1)?.takeIf(String::isNotBlank) ?: "latest",
                createdAt = parts.getOrNull(2)?.toLongOrNull()?.times(1000) ?: 0L,
                sizeKb = parts.getOrNull(3)?.toLongOrNull(),
                reason = parts.getOrNull(4)?.takeIf(String::isNotBlank) ?: "推送到分身前生成",
                isActiveSwitchBackup = false,
                isCloneRollback = true,
            )
        }.sortedByDescending { it.createdAt }
            .toList()
    }

    fun history(): List<TaskRecord> = logStore.all()

    suspend fun clearLogs(settings: UCloneSettings, clearHistory: Boolean = true) =
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
            if (result.isSuccess && clearHistory) {
                logStore.clear()
            }
        }

    suspend fun resetWorkspace(settings: UCloneSettings, clearHistory: Boolean = true): ShellResult =
        shell.exec(
            ShellScripts.resetWorkspace(settings),
            timeoutSeconds = 120,
        ).also { result ->
            if (result.isSuccess && clearHistory) {
                logStore.clear()
            }
        }

    suspend fun startCloneUser(settings: UCloneSettings) =
        shell.exec("am start-user -w ${settings.cloneUserId}", timeoutSeconds = 60)

    suspend fun switchToCloneUser(settings: UCloneSettings) =
        shell.exec("am switch-user ${settings.cloneUserId}", timeoutSeconds = 30)

    suspend fun stopCloneUserByExplicitUserRequest(settings: UCloneSettings) =
        shell.exec(ShellScripts.stopCloneUser(settings), timeoutSeconds = 15)

    private suspend fun runScriptTask(
        type: TaskType,
        packageName: String,
        settings: UCloneSettings,
        labels: List<String>,
        script: String,
        report: (TaskProgress) -> Unit,
        requestId: String = newRequestId(),
    ): TaskRecord {
        val timestamp = System.currentTimeMillis()
        val logPath = "${settings.rootDir}/logs/${type.name.lowercase()}_${packageName}_$timestamp.log"
        var steps = labels.mapIndexed { index, label ->
            TaskStep(label, if (index == 0) StepStatus.RUNNING else StepStatus.PENDING)
        }
        val task = logStore.running(type, packageName, logPath, requestId)
        report(TaskProgress(task, steps, ""))
        val liveTail = LiveLogTail()
        val throttle = TaskProgressThrottle()
        var progressTask = task
        val progressLock = Any()
        val command = RootTaskScript.wrap(
            logPath = logPath,
            header = "TASK=$type\nREQUEST_ID=$requestId\nPACKAGE=$packageName\nSTART=$timestamp\nSTART_LOCAL=${formatLocalTime(timestamp)}\n",
            body = script,
            startedAt = timestamp,
        )
        val result = shell.execStreaming(command, timeoutSeconds = taskHostTimeoutSeconds(type)) { output ->
            val line = if (output.stream == ShellStream.STDERR) "STDERR: ${output.line}" else output.line
            liveTail.append(line)
            var force = false
            synchronized(progressLock) {
                if (output.line.startsWith("ROOT=") && output.line.contains("uid=0")) {
                    steps = mark(steps, 0, StepStatus.SUCCESS, 1, StepStatus.RUNNING)
                    force = true
                }
                if (output.line.startsWith("UCLONE_STAGE_BEGIN:")) {
                    val stageName = output.line.substringAfter(':').trim()
                    runCatching { TaskStage.valueOf(stageName) }.getOrNull()?.let { stage ->
                        progressTask = progressTask.copy(currentStage = stage, message = stage.displayLabel)
                        force = true
                    }
                }
                if (throttle.shouldEmit(force)) {
                    report(TaskProgress(progressTask, steps, liveTail.value()))
                }
            }
        }
        val endedAt = System.currentTimeMillis()
        val liveLog = "OUTPUT:\n${result.stdout}\nSTDERR:\n${result.stderr}\nOUTPUT_TRUNCATED=${if (result.outputTruncated) 1 else 0}\nEXIT=${result.exitCode}\nEND=$endedAt\nEND_LOCAL=${formatLocalTime(endedAt)}\nDURATION_MS=${endedAt - timestamp}\n"
        val baseStatus = TaskOutcome.status(result)
        val status = if (result.outputTruncated && baseStatus == TaskStatus.SUCCESS) {
            TaskStatus.SUCCESS_WITH_WARNINGS
        } else {
            baseStatus
        }
        val rootUnavailable = "ERR_ROOT_UNAVAILABLE:" in result.stdout || "ERR_ROOT_UNAVAILABLE:" in result.stderr
        steps = if (rootUnavailable) {
            failRemaining(steps, 0)
        } else if (status.isSuccessful) {
            steps.map { it.copy(status = StepStatus.SUCCESS) }
        } else {
            failRemaining(steps, steps.indexOfFirst { it.status == StepStatus.RUNNING }.coerceAtLeast(1))
        }
        val message = when (status) {
            TaskStatus.SUCCESS,
            TaskStatus.SUCCESS_WITH_WARNINGS,
            -> TaskResultMessages.successMessage(liveLog) + if (result.outputTruncated) "；运行输出过长，完整内容请查看任务日志" else ""
            else -> TaskOutcome.failureMessage(status) ?: taskFailureMessage(result)
        }
        val metrics = TaskMetricsParser.parse(
            output = result.stdout + "\n" + result.stderr,
            rootProcessStarts = result.processStarts,
            rootCommandCount = 1,
        )
        val finished = logStore.finish(task, status, message, metrics)
        report(TaskProgress(finished, steps, liveLog))
        return finished
    }

    private fun taskFailureMessage(result: ShellResult): String {
        val output = result.stderr + "\n" + result.stdout
        return when {
            "ERR_ROOT_UNAVAILABLE:" in output -> "Root 权限不可用"
            "ERR_INSUFFICIENT_SPACE:" in output -> "存储空间不足，任务未停止目标 App，也未写入数据"
            "ERR_SPACE_UNKNOWN:" in output || "ERR_SPACE_ESTIMATE:" in output -> "无法确认可用空间，任务未执行"
            "ERR_CLONE_AUTO_UNLOCK_DISABLED" in output -> "分身未解锁，请开启分身自动解锁或先手动解锁"
            "ERR_CLONE_PIN_MISSING" in output -> "请先在设置中填写分身锁屏 PIN/密码"
            "ERR_CLONE_PIN_VERIFY_FAILED:BAD_CREDENTIAL" in output -> "分身 PIN 验证失败"
            "ERR_CLONE_PIN_VERIFY_FAILED:THROTTLED" in output -> "PIN 验证被系统限流，请稍后再试"
            "ERR_CLONE_PIN_VERIFY_FAILED:UNIFIED_CHALLENGE_UNSUPPORTED" in output -> "分身使用统一锁屏凭据，当前系统不支持后台验证"
            "ERR_CLONE_PIN_VERIFY_FAILED:UNSUPPORTED" in output -> "当前系统不支持命令行验证分身 PIN"
            "ERR_CLONE_UNLOCK_TIMEOUT" in output -> "分身解锁超时，请确认 PIN 和系统状态"
            "ERR_PACKAGE_NOT_LISTED_TARGET" in output -> "分身系统未安装此 App，无法推送"
            "ERR_PACKAGE_NOT_LISTED_SOURCE" in output -> "主系统未安装此 App，无法推送"
            "ERR_PACKAGE_NOT_LISTED" in output -> "分身系统未安装此 App，未执行备份或切换"
            "ERR_PUSH_CE_MISSING" in output -> "主系统 CE 数据缺失，未执行推送"
            "ERR_NOTHING_PUSHED" in output || "ERR_NOTHING_COPIED" in output -> "没有找到可推送的数据"
            else -> result.stderr.ifBlank { "命令失败：${result.exitCode}" }
        }
    }

    private fun formatLocalTime(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date(millis))

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

internal fun taskHostTimeoutSeconds(type: TaskType): Long =
    if (type in TRANSACTIONAL_TASK_TYPES) 0 else 900

private val TRANSACTIONAL_TASK_TYPES = setOf(
    TaskType.RESTORE_SNAPSHOT_TO_MAIN,
    TaskType.ROLLBACK_MAIN_DATA,
    TaskType.RESTORE_FROM_CLONE_LATEST,
    TaskType.SWITCH_TO_CLONE_STATE,
    TaskType.PUSH_MAIN_TO_CLONE,
    TaskType.RESTORE_CLONE_ROLLBACK_TO_CLONE,
    TaskType.RESTORE_SWITCH_MAIN_STATE,
)

private fun <K, V> Map<K, V>.getValueOrNull(key: K): V? = if (containsKey(key)) getValue(key) else null

private fun newRequestId(): String = UUID.randomUUID().toString()

internal fun restoreBackupListScript(rollbackRoot: String, switchRoot: String): String = """
    ROLLBACK_ROOT=${shellQuote(rollbackRoot)}
    SWITCH_ROOT=${shellQuote(switchRoot)}
    [ -d "${'$'}ROLLBACK_ROOT" ] || exit 0
    for d in "${'$'}ROLLBACK_ROOT"/*/*; do
      [ -d "${'$'}d" ] || continue
      [ -f "${'$'}d/manifest.json" ] || continue
      pkg=${'$'}(basename "${'$'}(dirname "${'$'}d")")
      id=${'$'}(basename "${'$'}d")
      ts=${'$'}(stat -c %Y "${'$'}d" 2>/dev/null || echo 0)
      size=${'$'}(du -sk "${'$'}d" 2>/dev/null | awk '{print ${'$'}1}')
      active=0
      if [ -f "${'$'}SWITCH_ROOT/${'$'}pkg/active" ] && [ "${'$'}(sed -n '1p' "${'$'}SWITCH_ROOT/${'$'}pkg/active" | tr -d '\r')" = "${'$'}id" ]; then
        active=1
      fi
      reason=${'$'}(sed -n 's/.*"reason":"\([^"]*\)".*/\1/p' "${'$'}d/manifest.json" | head -1)
      if [ -z "${'$'}reason" ]; then
        case "${'$'}id" in
          rollback_*) reason="恢复主系统备份前生成" ;;
          *) if [ "${'$'}active" = "1" ]; then reason="切换到分身态前生成"; else reason="恢复或切换前生成"; fi ;;
        esac
      fi
      printf '%s\t%s\t%s\t%s\t%s\t%s\n' "${'$'}pkg" "${'$'}id" "${'$'}ts" "${'$'}size" "${'$'}active" "${'$'}reason"
    done
""".trimIndent()
