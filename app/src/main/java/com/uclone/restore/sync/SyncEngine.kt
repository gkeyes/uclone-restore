package com.uclone.restore.sync

import com.uclone.restore.model.AppRule
import com.uclone.restore.model.CloneReturnPlan
import com.uclone.restore.model.CrossUserInstallMode
import com.uclone.restore.model.RestoreBackupEntry
import com.uclone.restore.model.StepStatus
import com.uclone.restore.model.TaskProgress
import com.uclone.restore.model.TaskRecord
import com.uclone.restore.model.TaskStage
import com.uclone.restore.model.TaskStatus
import com.uclone.restore.model.TaskStep
import com.uclone.restore.model.TaskType
import com.uclone.restore.model.UCloneSettings
import com.uclone.restore.model.cloneReturnPlan
import com.uclone.restore.root.RootEnvironmentChecker
import com.uclone.restore.root.RootShellExecutor
import com.uclone.restore.root.ShellOutput
import com.uclone.restore.root.ShellResult
import com.uclone.restore.root.ShellStream
import com.uclone.restore.root.shellQuote
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class SyncEngine(
    private val shell: RootShellExecutor,
    private val environmentChecker: RootEnvironmentChecker,
    private val logStore: TaskRepository,
    private val appPackage: String,
) {
    suspend fun checkEnvironment(settings: UCloneSettings) = environmentChecker.check(settings)

    suspend fun scanWorkspaceOwnership(
        settings: UCloneSettings,
        report: (TaskProgress) -> Unit,
        requestId: String = newRequestId(),
    ): TaskRecord = runScriptTask(
        type = TaskType.SCAN_WORKSPACE_OWNERSHIP,
        packageName = "workspace",
        settings = settings,
        labels = listOf("检查工作区", "扫描文件归属", "汇总容量"),
        script = WorkspaceOwnershipScripts.scan(settings.rootDir),
        report = report,
        requestId = requestId,
    )

    suspend fun repairWorkspaceOwnership(
        settings: UCloneSettings,
        expectedCanonicalRoot: String,
        report: (TaskProgress) -> Unit,
        requestId: String = newRequestId(),
    ): TaskRecord = runScriptTask(
        type = TaskType.REPAIR_WORKSPACE_OWNERSHIP,
        packageName = "workspace",
        settings = settings,
        labels = listOf("检查工作区", "扫描文件归属", "分批修复归属", "验证结果"),
        script = WorkspaceOwnershipScripts.repair(settings.rootDir, expectedCanonicalRoot),
        report = report,
        requestId = requestId,
    )

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

    suspend fun installAcrossUsers(
        packageName: String,
        targetUserId: Int,
        mode: CrossUserInstallMode,
        rule: AppRule,
        settings: UCloneSettings,
        report: (TaskProgress) -> Unit,
        requestId: String = newRequestId(),
    ): TaskRecord {
        val type = when (mode) {
            CrossUserInstallMode.INSTALL_ONLY -> TaskType.INSTALL_TO_OTHER_USER
            CrossUserInstallMode.INSTALL_WITH_PERMISSIONS -> TaskType.INSTALL_WITH_PERMISSIONS_TO_OTHER_USER
            CrossUserInstallMode.INSTALL_AND_SYNC -> TaskType.INSTALL_AND_SYNC_TO_OTHER_USER
        }
        val labels = when (mode) {
            CrossUserInstallMode.INSTALL_ONLY ->
                listOf("检查安装条件", "启用另一侧 App", "验证安装结果", "完成")
            CrossUserInstallMode.INSTALL_WITH_PERMISSIONS ->
                listOf("检查安装条件", "启用另一侧 App", "迁移权限/AppOps", "验证安装结果", "完成")
            CrossUserInstallMode.INSTALL_AND_SYNC ->
                listOf("检查安装条件", "启用另一侧 App", "准备源数据", "同步目标数据", "验证结果", "完成")
        }
        return runScriptTask(
            type = type,
            packageName = packageName,
            settings = settings,
            labels = labels,
            script = CrossUserInstallScripts.build(packageName, targetUserId, mode, rule, settings, appPackage),
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
        rule: AppRule,
        settings: UCloneSettings,
        report: (TaskProgress) -> Unit,
        requestId: String = newRequestId(),
    ): TaskRecord {
        val plan = settings.cloneReturnPlan()
        return runScriptTask(
            type = TaskType.RESTORE_SWITCH_MAIN_STATE,
            packageName = packageName,
            settings = settings,
            labels = when (plan) {
                CloneReturnPlan.SYNC_SAFE ->
                    listOf("检查切换状态", "保存当前分数据检查点", "同步分数据到 user${settings.cloneUserId}", "恢复固定 MAIN", "提交主数据状态", "完成")
                CloneReturnPlan.SYNC_FAST ->
                    listOf("检查切换状态", "同步分数据到 user${settings.cloneUserId}", "恢复固定 MAIN", "提交主数据状态", "完成")
                CloneReturnPlan.DISCARD_SAFE ->
                    listOf("检查切换状态", "保存临时分数据检查点", "恢复固定 MAIN", "提交主数据状态", "完成")
                CloneReturnPlan.DISCARD_FAST ->
                    listOf("检查危险返回", "直接恢复固定 MAIN", "提交主数据状态", "完成")
            },
            script = ShellScripts.pushMainToCloneThenRestoreMain(packageName, rollbackId, rule, settings, appPackage),
            report = report,
            requestId = requestId,
        )
    }

    suspend fun updateMainReturnPoint(
        packageName: String,
        settings: UCloneSettings,
        report: (TaskProgress) -> Unit,
        requestId: String = newRequestId(),
    ): TaskRecord = runScriptTask(
        type = TaskType.UPDATE_MAIN_RETURN_POINT,
        packageName = packageName,
        settings = settings,
        labels = listOf("检查 MAIN 状态", "读取 user${settings.mainUserId} 当前数据", "验证新返回点", "替换 MAIN 返回点", "完成"),
        script = ShellScripts.updateMainReturnPoint(packageName, settings, appPackage),
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
        return loadWorkspaceIndex(settings).snapshots.getValueOrNull(packageName)
    }

    suspend fun snapshotMetadata(settings: UCloneSettings): Map<String, SnapshotMetadata> {
        return loadWorkspaceIndex(settings).snapshots
    }

    suspend fun snapshotTimes(settings: UCloneSettings): Map<String, Long> =
        snapshotMetadata(settings).mapValues { it.value.updatedAt }

    suspend fun switchMarkerIds(settings: UCloneSettings): Map<String, String> {
        return loadWorkspaceIndex(settings).switchMarkers
    }

    suspend fun switchMarkerId(packageName: String, settings: UCloneSettings): String? =
        switchMarkerIds(settings)[packageName]

    suspend fun appDataState(packageName: String, settings: UCloneSettings): AppDataState =
        loadWorkspaceIndex(settings).dataState(packageName)

    suspend fun loadWorkspaceIndex(settings: UCloneSettings): WorkspaceIndex {
        val result = shell.exec(workspaceIndexScript(settings.rootDir), 60)
        if (!result.isSuccess || result.outputTruncated) return WorkspaceIndex(readSucceeded = false)
        return WorkspaceIndexParser.parse(result.stdout)
    }

    suspend fun listRollbackIds(packageName: String, settings: UCloneSettings): List<String> {
        return loadWorkspaceIndex(settings).rollbackIds(packageName)
    }

    suspend fun listRestoreBackups(settings: UCloneSettings): List<RestoreBackupEntry> {
        return loadWorkspaceIndex(settings).restoreBackups
    }

    suspend fun listCloneRollbackBackups(settings: UCloneSettings): List<RestoreBackupEntry> {
        return loadWorkspaceIndex(settings).cloneRollbackBackups
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
        shell.exec(ShellScripts.startCloneUser(settings), timeoutSeconds = 15)

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
        val handleOutput: (ShellOutput) -> Unit = { output ->
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
        val protectedInput = settings.cloneUnlockCredential.trim().takeIf(String::isNotEmpty)?.plus("\n")
        val timeoutSeconds = taskHostTimeoutSeconds(type)
        val result = if (protectedInput == null) {
            shell.execStreaming(command, timeoutSeconds, handleOutput)
        } else {
            shell.execStreamingWithInput(command, protectedInput, timeoutSeconds, handleOutput)
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
        val partialInstallSync = "INSTALL_PARTIAL_SUCCESS" in result.stdout || "INSTALL_PARTIAL_SUCCESS" in result.stderr
        steps = if (rootUnavailable) {
            failRemaining(steps, 0)
        } else if (partialInstallSync) {
            val failedIndex = labels.indexOf("同步目标数据").takeIf { it >= 0 } ?: (labels.lastIndex - 1).coerceAtLeast(0)
            failRemaining(steps, failedIndex)
        } else if (status.isSuccessful) {
            steps.map { it.copy(status = StepStatus.SUCCESS) }
        } else {
            failRemaining(steps, steps.indexOfFirst { it.status == StepStatus.RUNNING }.coerceAtLeast(1))
        }
        val message = when (status) {
            TaskStatus.SUCCESS,
            TaskStatus.SUCCESS_WITH_WARNINGS,
            -> TaskResultMessages.successMessage(liveLog) + if (result.outputTruncated) "；运行输出过长，完整内容请查看任务日志" else ""
            else -> TaskOutcome.failureMessage(status, liveLog) ?: taskFailureMessage(result)
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
            "ERR_FORCE_STOP_FAILED" in output -> "无法停止分身 App，未读取或写入数据"
            "ERR_NOTHING_PUSHED" in output || "ERR_NOTHING_COPIED" in output -> "没有找到可推送的数据"
            result.exitCode in 129..192 -> "Root 执行被信号 ${result.exitCode - 128} 中断，请查看任务日志"
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
    TaskType.UPDATE_MAIN_RETURN_POINT,
    TaskType.INSTALL_AND_SYNC_TO_OTHER_USER,
    TaskType.REPAIR_WORKSPACE_OWNERSHIP,
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
