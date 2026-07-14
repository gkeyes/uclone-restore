package com.uclone.restore.ui

import com.uclone.restore.model.AppEntry
import com.uclone.restore.model.AppRule
import com.uclone.restore.model.EnvironmentStatus
import com.uclone.restore.model.RestoreBackupEntry
import com.uclone.restore.model.TaskProgress
import com.uclone.restore.model.TaskRecord
import com.uclone.restore.model.UCloneSettings
import com.uclone.restore.model.WorkspaceOwnershipReport
import com.uclone.restore.sync.AppDataState

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
    val confirmedMainPackages: Set<String> = emptySet(),
    val unknownSwitchPackages: Set<String> = emptySet(),
    val workspaceOwnership: WorkspaceOwnershipReport? = null,
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
        packageName in unknownSwitchPackages -> AppDataState.Unknown
        packageName in confirmedMainPackages -> AppDataState.Main
        else -> switchRollbackIds[packageName]?.let(AppDataState::Clone) ?: AppDataState.Main
    }

    fun hasConfirmedMainState(packageName: String): Boolean = packageName in confirmedMainPackages
}
