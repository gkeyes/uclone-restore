package com.uclone.restore.sync

import com.uclone.restore.model.AppRule
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
        labels = listOf("检查 root", "检查分身解锁", "停止分身 App", "复制 CE/DE 数据", "写入 manifest", "激活快照"),
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
        labels = listOf("检查 root", "读取黄金快照", "备份主系统旧数据", "恢复到 user0", "修正 UID/GID", "执行 restorecon"),
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

    suspend fun rollback(
        packageName: String,
        rollbackId: String,
        settings: UCloneSettings,
        report: suspend (TaskProgress) -> Unit,
    ): TaskRecord = runScriptTask(
        type = TaskType.ROLLBACK_MAIN_DATA,
        packageName = packageName,
        settings = settings,
        labels = listOf("检查 root", "读取回滚点", "停止主系统 App", "恢复旧数据", "修正权限", "完成"),
        script = ShellScripts.rollback(packageName, rollbackId, settings, appPackage),
        report = report,
    )

    suspend fun latestSnapshotTime(packageName: String, settings: UCloneSettings): Long? {
        val path = "${settings.rootDir}/snapshots/$packageName/active/manifest.json"
        val result = shell.exec("[ -f ${shellQuote(path)} ] && stat -c %Y ${shellQuote(path)} || true", 20)
        return result.stdout.trim().toLongOrNull()?.times(1000)
    }

    suspend fun snapshotTimes(settings: UCloneSettings): Map<String, Long> {
        val root = "${settings.rootDir}/snapshots"
        val script = """
            [ -d ${shellQuote(root)} ] || exit 0
            for f in ${shellQuote(root)}/*/active/manifest.json; do
              [ -f "${'$'}f" ] || continue
              pkg=${'$'}(basename "${'$'}(dirname "${'$'}(dirname "${'$'}f")")")
              ts=${'$'}(stat -c %Y "${'$'}f" 2>/dev/null || echo 0)
              echo "${'$'}pkg ${'$'}ts"
            done
        """.trimIndent()
        val result = shell.exec(script, 30)
        return result.stdout.lineSequence().mapNotNull { line ->
            val parts = line.trim().split(" ")
            val millis = parts.getOrNull(1)?.toLongOrNull()?.times(1000) ?: return@mapNotNull null
            parts.firstOrNull()?.let { it to millis }
        }.toMap()
    }

    suspend fun listRollbackIds(packageName: String, settings: UCloneSettings): List<String> {
        val path = "${settings.rootDir}/rollback/$packageName"
        val result = shell.exec("[ -d ${shellQuote(path)} ] && ls -1 ${shellQuote(path)} || true", 20)
        return result.stdout.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
    }

    fun history(): List<TaskRecord> = logStore.all()

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
