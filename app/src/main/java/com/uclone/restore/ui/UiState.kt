package com.uclone.restore.ui

import com.uclone.restore.model.AppEntry
import com.uclone.restore.model.AppRule
import com.uclone.restore.model.EnvironmentStatus
import com.uclone.restore.model.RestoreBackupEntry
import com.uclone.restore.model.TaskProgress
import com.uclone.restore.model.TaskRecord
import com.uclone.restore.model.UCloneSettings
import com.uclone.restore.model.WorkspaceOwnershipReport
import com.uclone.restore.external.ExternalRequestEvent
import com.uclone.restore.sync.AppDataState
import com.uclone.restore.sync.TransactionRecoveryState

data class UiState(
    val settings: UCloneSettings = UCloneSettings(),
    val environment: EnvironmentStatus? = null,
    val apps: List<AppEntry> = emptyList(),
    val selectedPackage: String? = null,
    val search: String = "",
    val busy: Boolean = false,
    val currentTask: TaskProgress = TaskProgress(null),
    val history: List<TaskRecord> = emptyList(),
    val rollbackIds: List<String> = emptyList(),
    val restoreBackups: List<RestoreBackupEntry> = emptyList(),
    val cloneRollbackBackups: List<RestoreBackupEntry> = emptyList(),
    val switchRollbackIds: Map<String, String> = emptyMap(),
    val unknownStatePackages: Set<String> = emptySet(),
    val workspaceOwnership: WorkspaceOwnershipReport? = null,
    val externalRequests: List<ExternalRequestEvent> = emptyList(),
    val transactionRecovery: TransactionRecoveryState = TransactionRecoveryState.Scanning,
    val message: String? = null,
) {
    val selectedApp: AppEntry? = apps.firstOrNull { it.packageName == selectedPackage }
    val selectedSwitchRollbackId: String? = selectedPackage?.let(switchRollbackIds::get)
    val selectedDataState: AppDataState? = selectedPackage?.let(::dataStateFor)
    val favoriteApps: List<AppEntry> = apps.filter { it.packageName in settings.favoritePackages }
    val selectedRule: AppRule?
        get() = selectedPackage?.let {
            AppRule(
                packageName = it,
                includeCe = settings.includeCe,
                includeDe = settings.includeDe,
                includeExternal = settings.includeExternal,
                includeMedia = settings.includeMedia,
                includeObb = settings.includeObb,
                includePermissions = settings.includePermissions,
                excludeCache = settings.excludeCache,
            )
        }

    fun dataStateFor(packageName: String): AppDataState = when {
        packageName in unknownStatePackages -> AppDataState.Unknown
        else -> switchRollbackIds[packageName]?.let(AppDataState::Clone) ?: AppDataState.Main
    }

    fun restoreBackupDeletionBlockReason(packageName: String, rollbackId: String): String? {
        val backup = restoreBackups.firstOrNull {
            it.packageName == packageName && it.rollbackId == rollbackId
        }
        return if (backup?.isActiveSwitchBackup == true) {
            "当前主数据返回点不能删除。请先还原主系统，或在 App 详情将状态设为未知后再删除。"
        } else {
            null
        }
    }
}

internal enum class HomePrimaryAction {
    SWITCH,
    RESTORE,
    OPEN_DETAILS,
}

internal val AppDataState.homePrimaryAction: HomePrimaryAction
    get() = when (this) {
        AppDataState.Main -> HomePrimaryAction.SWITCH
        is AppDataState.Clone -> HomePrimaryAction.RESTORE
        AppDataState.Unknown -> HomePrimaryAction.OPEN_DETAILS
    }

internal enum class DetailPrimaryAction {
    SWITCH,
    RESTORE,
    DISABLED_UNKNOWN,
}

internal val AppDataState.detailPrimaryAction: DetailPrimaryAction
    get() = when (this) {
        AppDataState.Main -> DetailPrimaryAction.SWITCH
        is AppDataState.Clone -> DetailPrimaryAction.RESTORE
        AppDataState.Unknown -> DetailPrimaryAction.DISABLED_UNKNOWN
    }

internal fun UiState.failClosedAfterWorkspaceReadFailure(): UiState {
    val affectedPackages = buildSet {
        apps.mapTo(this) { it.packageName }
        selectedPackage?.let(::add)
    }
    return copy(
        rollbackIds = emptyList(),
        restoreBackups = emptyList(),
        cloneRollbackBackups = emptyList(),
        switchRollbackIds = emptyMap(),
        unknownStatePackages = affectedPackages,
    )
}
