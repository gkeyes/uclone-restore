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
    val lastRestoreAt: Long?,
)

data class AppRule(
    val packageName: String,
    val includeCe: Boolean = true,
    val includeDe: Boolean = true,
    val includeExternal: Boolean = false,
    val includeMedia: Boolean = false,
    val includeObb: Boolean = false,
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
    val dataAdbWritable: CheckResult,
    val snapshotDirReady: CheckResult,
)

data class CheckResult(
    val ok: Boolean,
    val detail: String,
)

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

data class UCloneSettings(
    val mainUserId: Int = 0,
    val cloneUserId: Int = 10,
    val rootDir: String = "/data/adb/uclone",
    val includeCe: Boolean = true,
    val includeDe: Boolean = true,
    val includeExternal: Boolean = false,
    val includeMedia: Boolean = false,
    val includeObb: Boolean = false,
    val excludeCache: Boolean = true,
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
