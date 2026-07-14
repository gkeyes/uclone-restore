package com.uclone.restore.model

data class AppEntry(
    val packageName: String,
    val label: String,
    val user0Installed: Boolean,
    val user10Installed: Boolean,
    val user0Uid: Int?,
    val user10Uid: Int?,
    val isSystemApp: Boolean,
    val riskLevel: RiskLevel,
    val lastSnapshotAt: Long?,
    val snapshotSizeKb: Long?,
    val lastRestoreAt: Long?,
)

data class AppRule(
    val packageName: String,
    val includeCe: Boolean = true,
    val includeDe: Boolean = true,
    val includeExternal: Boolean = true,
    val includeMedia: Boolean = false,
    val includeObb: Boolean = false,
    val includePermissions: Boolean = true,
    val includeAppWebView: Boolean = true,
    val excludeCache: Boolean = true,
    val highRiskConfirm: Boolean = true,
)

data class EnvironmentStatus(
    val root: CheckResult,
    val currentUser: String,
    val user0Present: Boolean,
    val user10Present: Boolean,
    val user10State: String,
    val user10CeState: User10CeState = User10CeState.fromRaw(user10State, user10Present),
    val user10CeBaseReadable: CheckResult = CheckResult(false, "未检测"),
    val user10DeBaseReadable: CheckResult = CheckResult(false, "未检测"),
    val dataAdbWritable: CheckResult,
    val snapshotDirReady: CheckResult,
)

data class CheckResult(
    val ok: Boolean,
    val detail: String,
)

sealed class User10CeState {
    data object Unavailable : User10CeState()
    data object StartedLocked : User10CeState()
    data object RunningUnlocked : User10CeState()
    data object NotStarted : User10CeState()
    data class Unknown(val raw: String) : User10CeState()

    val allowsCeCapture: Boolean
        get() = this is RunningUnlocked

    val label: String
        get() = when (this) {
            Unavailable -> "用户不可用"
            StartedLocked -> "已启动但未解锁 CE"
            RunningUnlocked -> "RUNNING_UNLOCKED"
            NotStarted -> "未启动"
            is Unknown -> raw.ifBlank { "未知" }
        }

    companion object {
        fun fromRaw(raw: String, userPresent: Boolean = true): User10CeState {
            if (!userPresent) return Unavailable
            val normalized = raw.trim()
            if (normalized.isBlank()) return Unknown(raw)
            val upper = normalized.uppercase()
            return when {
                "RUNNING_UNLOCKED" in upper -> RunningUnlocked
                "RUNNING_LOCKED" in upper -> StartedLocked
                upper == "RUNNING" -> StartedLocked
                "USER IS NOT STARTED" in upper -> NotStarted
                "NOT STARTED" in upper -> NotStarted
                "SHUTDOWN" in upper -> NotStarted
                "STOPPING" in upper -> NotStarted
                else -> Unknown(normalized)
            }
        }
    }
}

data class TaskRecord(
    val id: Long,
    val requestId: String,
    val packageName: String,
    val type: TaskType,
    val startedAt: Long,
    val finishedAt: Long?,
    val status: TaskStatus,
    val logPath: String,
    val message: String,
    val currentStage: TaskStage? = null,
    val metrics: TaskMetrics = TaskMetrics(),
)

data class TaskMetrics(
    val rootProcessStarts: Int = 0,
    val rootCommandCount: Int = 0,
    val scannedFiles: Long = 0,
    val copiedFiles: Long = 0,
    val copiedBytes: Long = 0,
    val peakTemporaryBytes: Long = 0,
    val targetDowntimeMs: Long? = null,
    val stages: List<TaskStageMetric> = emptyList(),
)

data class TaskStageMetric(
    val stage: TaskStage,
    val startedAt: Long,
    val finishedAt: Long,
) {
    val durationMs: Long = (finishedAt - startedAt).coerceAtLeast(0)
}

enum class TaskStage {
    PRECHECK,
    SOURCE_PREPARE,
    TARGET_STOP,
    ROLLBACK_BACKUP,
    RESTORE_DATA,
    RESTORE_METADATA,
    RESTORE_PERMISSIONS,
    VERIFY,
    COMMIT,
    AUTO_ROLLBACK,
    CLEANUP,

    ;

    val displayLabel: String
        get() = when (this) {
            PRECHECK -> "检查运行条件"
            SOURCE_PREPARE -> "准备源数据"
            TARGET_STOP -> "停止目标 App"
            ROLLBACK_BACKUP -> "生成回滚备份"
            RESTORE_DATA -> "写入目标数据"
            RESTORE_METADATA -> "修复文件属性"
            RESTORE_PERMISSIONS -> "恢复权限/AppOps"
            VERIFY -> "验证恢复结果"
            COMMIT -> "提交任务结果"
            AUTO_ROLLBACK -> "自动回滚"
            CLEANUP -> "清理临时数据"
        }
}

data class TaskStep(
    val label: String,
    val status: StepStatus,
)

data class TaskProgress(
    val task: TaskRecord?,
    val steps: List<TaskStep> = emptyList(),
    val liveLog: String = "",
)

data class RestoreBackupEntry(
    val packageName: String,
    val rollbackId: String,
    val createdAt: Long,
    val sizeKb: Long?,
    val reason: String,
    val isActiveSwitchBackup: Boolean,
    val isCloneRollback: Boolean = false,
    val stateKind: PassiveBackupStateKind? = null,
    val isPersistentStateBackup: Boolean = false,
)

enum class PassiveBackupStateKind {
    MAIN,
    CLONE,
}

data class WorkspaceOwnershipReport(
    val canonicalRoot: String,
    val totalEntries: Long,
    val nonRootEntries: Long,
    val totalSizeKb: Long,
    val scannedRootDir: String = canonicalRoot,
    val scannedMainUserId: Int = 0,
    val scannedCloneUserId: Int = 10,
    val scannedAt: Long = 0L,
)

data class UCloneSettings(
    val mainUserId: Int = 0,
    val cloneUserId: Int = 10,
    val rootDir: String = "/data/adb/uclone",
    val includeCe: Boolean = true,
    val includeDe: Boolean = true,
    val includeExternal: Boolean = true,
    val includeMedia: Boolean = false,
    val includeObb: Boolean = false,
    val includePermissions: Boolean = true,
    val excludeCache: Boolean = true,
    val stopCloneAfterTask: Boolean = true,
    val autoUnlockClone: Boolean = false,
    val allowModuleControl: Boolean = false,
    val mainReturnPointPolicy: MainReturnPointPolicy = MainReturnPointPolicy.FIXED,
    val cloneSessionPolicy: CloneSessionPolicy = CloneSessionPolicy.SYNC_TO_CLONE_USER,
    val switchSafetyMode: SwitchSafetyMode = SwitchSafetyMode.SAFE,
    val favoritePackages: Set<String> = emptySet(),
    val cloneUnlockCredential: String = "",
)

enum class MainReturnPointPolicy {
    FIXED,
    REFRESH_ON_MAIN_EXIT,
}

enum class CloneSessionPolicy {
    SYNC_TO_CLONE_USER,
    DISCARD_ON_MAIN_RETURN,
}

enum class SwitchSafetyMode {
    SAFE,
    DANGEROUS_FAST,
}

enum class RiskLevel {
    NORMAL,
    HIGH,
    SYSTEM,
}

enum class TaskType {
    CAPTURE_SNAPSHOT_FROM_CLONE,
    RESTORE_SNAPSHOT_TO_MAIN,
    ROLLBACK_MAIN_DATA,
    RESTORE_FROM_CLONE_LATEST,
    SWITCH_TO_CLONE_STATE,
    PUSH_MAIN_TO_CLONE,
    RESTORE_CLONE_ROLLBACK_TO_CLONE,
    RESTORE_SWITCH_MAIN_STATE,
    UPDATE_MAIN_RETURN_POINT,
    DELETE_SNAPSHOT,
    DELETE_RESTORE_BACKUP,
    PROBE_CLONE_CE,
    UNLOCK_CLONE_WITH_CREDENTIAL,
    DEBUG_CLONE_SYSTEM,
    AUDIT_RESTORE_CONSISTENCY,
    CLEAR_LOGS,
    RESET_WORKSPACE,
    SCAN_WORKSPACE_OWNERSHIP,
    REPAIR_WORKSPACE_OWNERSHIP,
    INSTALL_TO_OTHER_USER,
    INSTALL_WITH_PERMISSIONS_TO_OTHER_USER,
    INSTALL_AND_SYNC_TO_OTHER_USER,
    START_CLONE_USER,
    SWITCH_TO_CLONE_USER,
    STOP_CLONE_USER,
}

enum class CrossUserInstallMode {
    INSTALL_ONLY,
    INSTALL_WITH_PERMISSIONS,
    INSTALL_AND_SYNC,
}

enum class TaskStatus {
    ACCEPTED,
    RUNNING,
    AUTO_ROLLING_BACK,
    ROLLED_BACK,
    SUCCESS,
    SUCCESS_WITH_WARNINGS,
    FAILED,
    FAILED_FATAL,
    INTERRUPTED,

    ;

    val isSuccessful: Boolean
        get() = this == SUCCESS || this == SUCCESS_WITH_WARNINGS

    val isTerminal: Boolean
        get() = when (this) {
            ACCEPTED, RUNNING, AUTO_ROLLING_BACK -> false
            else -> true
        }
}

enum class StepStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
}
