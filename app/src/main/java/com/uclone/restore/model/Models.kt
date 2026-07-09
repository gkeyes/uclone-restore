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
    val includeExternal: Boolean = false,
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
    val packageName: String,
    val type: TaskType,
    val startedAt: Long,
    val finishedAt: Long?,
    val status: TaskStatus,
    val logPath: String,
    val message: String,
)

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
)

data class UCloneSettings(
    val mainUserId: Int = 0,
    val cloneUserId: Int = 10,
    val rootDir: String = "/data/adb/uclone",
    val includeCe: Boolean = true,
    val includeDe: Boolean = true,
    val includeExternal: Boolean = false,
    val includeMedia: Boolean = false,
    val includeObb: Boolean = false,
    val includePermissions: Boolean = true,
    val excludeCache: Boolean = true,
    val favoritePackages: Set<String> = emptySet(),
)

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
    RESTORE_SWITCH_MAIN_STATE,
    DELETE_SNAPSHOT,
    DELETE_RESTORE_BACKUP,
    PROBE_CLONE_CE,
    AUDIT_RESTORE_CONSISTENCY,
}

enum class TaskStatus {
    RUNNING,
    SUCCESS,
    FAILED,
}

enum class StepStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
}
