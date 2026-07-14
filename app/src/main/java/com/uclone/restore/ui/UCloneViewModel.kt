package com.uclone.restore.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.uclone.restore.AppContainer
import com.uclone.restore.external.ExternalActionService
import com.uclone.restore.launcher.FavoriteShortcutEntry
import com.uclone.restore.model.CrossUserInstallMode
import com.uclone.restore.model.TaskRecord
import com.uclone.restore.model.TaskType
import com.uclone.restore.model.UCloneSettings
import com.uclone.restore.sync.WorkspaceIndex
import com.uclone.restore.sync.WorkspaceOwnershipReportParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class UCloneViewModel(
    application: Application,
    private val container: AppContainer,
) : AndroidViewModel(application) {
    private val settingsStore = container.settingsStore
    private val packageInspector = container.packageInspector
    private val syncEngine = container.syncEngine
    private val taskRepository = container.taskRepository
    private val taskCoordinator = container.taskCoordinator
    private val launcherShortcutController = container.launcherShortcutController
    private val _state = MutableStateFlow(UiState(settings = settingsStore.load()))
    val state: StateFlow<UiState> = _state
    private val refreshMutex = Mutex()
    private var workspaceCache: WorkspaceCache? = null
    private var workspaceRevision = 0L
    private val workspaceCacheInvalidationTracker = WorkspaceCacheInvalidationTracker()
    private val terminalRefreshTracker = TaskInstanceDeduplicator()

    init {
        observeTasks()
        refreshAll()
    }

    fun refreshAll() {
        viewModelScope.launch {
            refreshMutex.withLock {
                val settings = _state.value.settings
                val revisionAtStart = workspaceRevision
                runBusy("刷新状态与 App 列表") {
                    val environment = syncEngine.checkEnvironment(settings)
                    val workspace = syncEngine.loadWorkspaceIndex(settings)
                    val apps = packageInspector.listApps(settings).map { app ->
                        val snapshot = workspace.snapshots[app.packageName]
                        app.copy(
                            lastSnapshotAt = snapshot?.updatedAt,
                            snapshotSizeKb = snapshot?.sizeKb,
                        )
                    }
                    if (revisionAtStart != workspaceRevision) return@runBusy
                    workspaceCache = WorkspaceCache.from(settings, workspace, revisionAtStart)
                    _state.update { state ->
                        if (SettingsDiff.between(settings, state.settings).requiresFullRefresh) {
                            state
                        } else {
                            state.copy(
                                environment = environment,
                                apps = apps,
                                rollbackIds = state.selectedPackage?.let(workspace::rollbackIds).orEmpty(),
                                restoreBackups = workspace.restoreBackups,
                                cloneRollbackBackups = workspace.cloneRollbackBackups,
                                switchRollbackIds = workspace.switchMarkers,
                                confirmedMainPackages = workspace.confirmedMainPackages,
                                unknownSwitchPackages = if (workspace.readSucceeded) {
                                    workspace.unknownSwitchPackages
                                } else {
                                    apps.mapTo(linkedSetOf()) { app -> app.packageName }
                                },
                                history = taskRepository.history.value,
                                message = "状态与 App 列表已刷新",
                            )
                        }
                    }
                    syncLauncherShortcuts()
                }
            }
        }
    }

    fun refreshEnvironment() {
        viewModelScope.launch {
            refreshMutex.withLock {
                val settings = _state.value.settings
                runBusy("环境检测中") {
                    val environment = syncEngine.checkEnvironment(settings)
                    _state.update { it.copy(environment = environment, message = "环境检测完成") }
                }
            }
        }
    }

    fun selectPackage(packageName: String) {
        val settings = _state.value.settings
        val workspace = workspaceCache?.usableIndex(settings, workspaceRevision)
        _state.update {
            it.copy(
                selectedPackage = packageName,
                rollbackIds = workspace?.rollbackIds(packageName).orEmpty(),
                confirmedMainPackages = if (workspace == null) {
                    it.confirmedMainPackages - packageName
                } else {
                    it.confirmedMainPackages
                },
                unknownSwitchPackages = if (workspace == null) {
                    it.unknownSwitchPackages + packageName
                } else {
                    it.unknownSwitchPackages
                },
            )
        }
    }

    fun updateSearch(value: String) {
        _state.update { it.copy(search = value) }
    }

    fun saveSettings(settings: UCloneSettings) {
        val old = _state.value.settings
        val diff = SettingsDiff.between(old, settings)
        settingsStore.save(settings)
        if (diff.requiresFullRefresh) invalidateWorkspaceCache()
        _state.update {
            it.copy(
                settings = settings,
                workspaceOwnership = it.workspaceOwnership.takeIf {
                    old.rootDir == settings.rootDir &&
                        old.mainUserId == settings.mainUserId &&
                        old.cloneUserId == settings.cloneUserId
                },
                message = "设置已保存",
            )
        }
        when {
            diff.requiresFullRefresh -> refreshAll()
            diff.requiresShortcutSync -> syncLauncherShortcuts()
        }
    }

    fun toggleFavorite(packageName: String) {
        val current = _state.value.settings
        val favorites = if (packageName in current.favoritePackages) {
            current.favoritePackages - packageName
        } else {
            current.favoritePackages + packageName
        }
        saveSettings(current.copy(favoritePackages = favorites))
    }

    fun captureSelected() {
        val packageName = _state.value.selectedPackage ?: return
        submitTask(UiTaskAction.CAPTURE, packageName, "正在建立分身快照")
    }

    fun restoreSelected() {
        val packageName = _state.value.selectedPackage ?: return
        restoreSnapshot(packageName)
    }

    fun restoreSnapshot(packageName: String) {
        _state.update { it.copy(selectedPackage = packageName) }
        submitTask(UiTaskAction.RESTORE_ACTIVE, packageName, "正在恢复到主系统")
    }

    fun restoreLatestSelected() {
        val packageName = _state.value.selectedPackage ?: return
        submitTask(UiTaskAction.RESTORE_CLONE_LATEST, packageName, "正在从分身最新恢复")
    }

    fun switchToCloneState(packageName: String) {
        _state.update { it.copy(selectedPackage = packageName) }
        submitTask(UiTaskAction.SWITCH_TO_CLONE, packageName, "正在切换到分身态")
    }

    fun switchToCloneStateSelected() {
        val packageName = _state.value.selectedPackage ?: return
        switchToCloneState(packageName)
    }

    fun pushMainToClone(packageName: String) {
        _state.update { it.copy(selectedPackage = packageName) }
        submitTask(UiTaskAction.PUSH_MAIN, packageName, "正在推送到分身")
    }

    fun restoreCloneRollback(packageName: String) {
        _state.update { it.copy(selectedPackage = packageName) }
        submitTask(UiTaskAction.RESTORE_CLONE_ROLLBACK, packageName, "正在恢复分身回滚")
    }

    fun installSelectedToOtherUser(mode: CrossUserInstallMode) {
        val app = _state.value.selectedApp ?: return
        val settings = _state.value.settings
        if (app.isSystemApp || app.packageName == getApplication<Application>().packageName) {
            _state.update { it.copy(message = "0.3 不支持跨用户安装系统 App 或 UClone 自身") }
            return
        }
        val targetUserId = when {
            app.user0Installed && !app.user10Installed -> settings.cloneUserId
            !app.user0Installed && app.user10Installed -> settings.mainUserId
            else -> {
                _state.update { it.copy(message = "仅当 App 只安装在一侧时才提供跨用户安装") }
                return
            }
        }
        val action = when (mode) {
            CrossUserInstallMode.INSTALL_ONLY -> UiTaskAction.INSTALL_OTHER_USER
            CrossUserInstallMode.INSTALL_WITH_PERMISSIONS -> UiTaskAction.INSTALL_OTHER_USER_WITH_PERMISSIONS
            CrossUserInstallMode.INSTALL_AND_SYNC -> UiTaskAction.INSTALL_OTHER_USER_AND_SYNC
        }
        val message = when (mode) {
            CrossUserInstallMode.INSTALL_ONLY -> "正在安装到 user$targetUserId"
            CrossUserInstallMode.INSTALL_WITH_PERMISSIONS -> "正在安装并迁移权限到 user$targetUserId"
            CrossUserInstallMode.INSTALL_AND_SYNC -> "正在安装并同步数据到 user$targetUserId"
        }
        submitTask(action, app.packageName, message, targetUserId = targetUserId)
    }

    fun restoreSwitchMainState(packageName: String) {
        _state.update { it.copy(selectedPackage = packageName) }
        submitTask(UiTaskAction.RESTORE_MAIN, packageName, "正在还原主系统态")
    }

    fun restoreSwitchMainStateSelected() {
        val packageName = _state.value.selectedPackage ?: return
        restoreSwitchMainState(packageName)
    }

    fun updateMainReturnPointSelected() {
        val packageName = _state.value.selectedPackage ?: return
        submitTask(UiTaskAction.UPDATE_MAIN_RETURN_POINT, packageName, "正在更新固定 MAIN 返回点")
    }

    fun handleLauncherFavoriteShortcut(packageName: String) {
        val settings = _state.value.settings
        if (packageName !in settings.favoritePackages) {
            _state.update { it.copy(message = "快捷入口已失效，请重新收藏 App") }
            syncLauncherShortcuts()
            return
        }
        _state.update { it.copy(selectedPackage = packageName) }
        submitTask(UiTaskAction.SWITCH_OR_RESTORE, packageName, "正在从桌面快捷入口切换/还原")
    }

    fun rollbackSelected(rollbackId: String) {
        val packageName = _state.value.selectedPackage ?: return
        restoreBackup(packageName, rollbackId)
    }

    fun restoreBackup(packageName: String, rollbackId: String) {
        _state.update { it.copy(selectedPackage = packageName) }
        submitTask(UiTaskAction.RESTORE_ROLLBACK, packageName, "正在恢复被动备份", rollbackId)
    }

    fun deleteSnapshotSelected() {
        val packageName = _state.value.selectedPackage ?: return
        deleteSnapshot(packageName)
    }

    fun deleteSnapshot(packageName: String) {
        _state.update { it.copy(selectedPackage = packageName) }
        submitTask(UiTaskAction.DELETE_SNAPSHOT, packageName, "正在删除 active 快照")
    }

    fun deleteRestoreBackup(packageName: String, rollbackId: String) {
        _state.update { it.copy(selectedPackage = packageName) }
        submitTask(UiTaskAction.DELETE_ROLLBACK, packageName, "正在删除被动备份", rollbackId)
    }

    fun probeCloneCe() {
        submitTask(UiTaskAction.PROBE_CLONE, cloneUserLabel(), "正在探测分身 CE")
    }

    fun unlockCloneWithCredential() {
        val settings = _state.value.settings
        if (settings.cloneUnlockCredential.isBlank()) {
            _state.update { it.copy(message = "请先在设置中填写分身锁屏 PIN/密码") }
            return
        }
        submitTask(UiTaskAction.UNLOCK_CLONE, cloneUserLabel(), "正在无感启动分身")
    }

    fun debugCloneSystem() {
        submitTask(UiTaskAction.DEBUG_CLONE, cloneUserLabel(), "正在调试分身系统")
    }

    fun auditRestoreConsistencySelected() {
        val packageName = _state.value.selectedPackage ?: return
        _state.update { it.copy(selectedPackage = packageName) }
        submitTask(UiTaskAction.AUDIT_RESTORE, packageName, "正在生成恢复审计包")
    }

    fun clearLogs() {
        submitTask(UiTaskAction.CLEAR_LOGS, "logs", "正在清理日志")
    }

    fun resetWorkspace() {
        submitTask(UiTaskAction.RESET_WORKSPACE, "workspace", "正在重置 UClone 数据")
    }

    fun scanWorkspaceOwnership() {
        submitTask(UiTaskAction.SCAN_WORKSPACE_OWNERSHIP, "workspace", "正在扫描备份容量归属")
    }

    fun repairWorkspaceOwnership() {
        val report = _state.value.workspaceOwnership ?: return
        if (report.nonRootEntries <= 0L) {
            _state.update { it.copy(message = "备份容量归属已经正常") }
            return
        }
        val settings = _state.value.settings
        if (
            report.scannedRootDir != settings.rootDir ||
            report.scannedMainUserId != settings.mainUserId ||
            report.scannedCloneUserId != settings.cloneUserId
        ) {
            _state.update { it.copy(workspaceOwnership = null, message = "工作目录或用户设置已变化，请重新扫描") }
            return
        }
        submitTask(
            UiTaskAction.REPAIR_WORKSPACE_OWNERSHIP,
            "workspace",
            "正在修复备份容量归属",
            expectedWorkspaceRoot = report.canonicalRoot,
        )
    }

    fun startCloneUser() {
        submitTask(UiTaskAction.START_CLONE_USER, cloneUserLabel(), "正在启动分身")
    }

    fun switchToCloneUser() {
        submitTask(UiTaskAction.SWITCH_CLONE_USER, cloneUserLabel(), "正在切换到分身")
    }

    fun stopCloneUser() {
        submitTask(UiTaskAction.STOP_CLONE_USER, cloneUserLabel(), "正在关闭分身")
    }

    fun refreshTaskHistory() {
        _state.update { it.copy(history = taskRepository.all()) }
    }

    private fun observeTasks() {
        viewModelScope.launch {
            taskRepository.history.collect { history ->
                _state.update { it.copy(history = history) }
            }
        }
        viewModelScope.launch {
            taskRepository.progress.collect { progress ->
                val task = progress.task
                if (workspaceCacheInvalidationTracker.shouldInvalidate(task)) {
                    invalidateWorkspaceCache()
                }
                _state.update { TaskUiStateReducer.progress(it, progress) }
                if (task?.status?.isTerminal == true && terminalRefreshTracker.shouldHandle(task)) {
                    refreshAfterTask(task)
                }
            }
        }
    }

    private fun submitTask(
        action: UiTaskAction,
        packageName: String,
        message: String,
        rollbackId: String? = null,
        expectedWorkspaceRoot: String? = null,
        targetUserId: Int? = null,
    ) {
        if (_state.value.busy || taskCoordinator.isBusy()) {
            _state.update { it.copy(message = "已有任务正在执行") }
            return
        }
        _state.update { it.copy(message = message) }
        ExternalActionService.startInternal(
            context = getApplication(),
            operation = action.operation,
            packageName = packageName,
            rollbackId = rollbackId,
            expectedWorkspaceRoot = expectedWorkspaceRoot,
            targetUserId = targetUserId,
        )
    }

    private suspend fun refreshAfterTask(task: TaskRecord) {
        val ownershipOutput = _state.value.currentTask.liveLog
        refreshMutex.withLock {
            runCatching {
                val policy = RefreshPolicy.forTask(task.type)
                val settings = _state.value.settings
                val revisionAtStart = workspaceRevision
                val workspace = if (policy.workspace) {
                    syncEngine.loadWorkspaceIndex(settings)
                } else {
                    null
                }
                val environment = when (policy.environment) {
                    EnvironmentRefresh.NONE -> null
                    EnvironmentRefresh.CLONE_STATE_ONLY ->
                        syncEngine.refreshCloneEnvironment(settings, _state.value.environment)
                    EnvironmentRefresh.FULL -> syncEngine.checkEnvironment(settings)
                }
                val refreshedApps = if (policy.apps) packageInspector.listApps(settings) else null
                if (policy.workspace && revisionAtStart != workspaceRevision) return@runCatching
                if (workspace != null) {
                    workspaceCache = WorkspaceCache.from(settings, workspace, revisionAtStart)
                }
                val snapshot = TaskRefreshSnapshot(
                    environment = environment,
                    history = taskRepository.history.value,
                    workspaceIndex = workspace,
                    apps = refreshedApps,
                )
                _state.update { TaskUiStateReducer.refreshed(it, task, snapshot) }
                if (task.status.isSuccessful && task.type in setOf(
                        com.uclone.restore.model.TaskType.SCAN_WORKSPACE_OWNERSHIP,
                        com.uclone.restore.model.TaskType.REPAIR_WORKSPACE_OWNERSHIP,
                    )
                ) {
                    WorkspaceOwnershipReportParser.parse(ownershipOutput)?.let { report ->
                        _state.update {
                            it.copy(
                                workspaceOwnership = report.copy(
                                    scannedRootDir = settings.rootDir,
                                    scannedMainUserId = settings.mainUserId,
                                    scannedCloneUserId = settings.cloneUserId,
                                    scannedAt = task.finishedAt ?: task.startedAt,
                                ),
                                message = if (report.nonRootEntries == 0L) {
                                    "备份容量归属正常"
                                } else {
                                    "发现 ${report.nonRootEntries} 个文件或目录需要修复"
                                },
                            )
                        }
                    }
                }
                if (policy.shortcuts) syncLauncherShortcuts()
            }.onFailure { error ->
                _state.update { it.copy(busy = false, message = error.message ?: "任务结束后刷新状态失败") }
            }
        }
    }

    private fun cloneUserLabel(): String = "user${_state.value.settings.cloneUserId}"

    private fun syncLauncherShortcuts() {
        val state = _state.value
        launcherShortcutController.updateFavoriteShortcuts(
            state.favoriteApps.map { app ->
                FavoriteShortcutEntry(
                    packageName = app.packageName,
                    label = app.label,
                    dataState = state.dataStateFor(app.packageName),
                )
            },
        )
    }

    private suspend fun runBusy(label: String, block: suspend () -> Unit) {
        _state.update { it.copy(busy = true, message = label) }
        try {
            block()
        } catch (error: Exception) {
            _state.update { it.copy(message = error.message ?: "操作失败") }
        } finally {
            _state.update { it.copy(busy = taskCoordinator.isBusy()) }
        }
    }

    private fun invalidateWorkspaceCache() {
        workspaceRevision += 1
        workspaceCache = null
    }
}

internal data class WorkspaceCache(
    val rootDir: String,
    val mainUserId: Int,
    val cloneUserId: Int,
    val index: WorkspaceIndex,
    val revision: Long,
) {
    fun matches(settings: UCloneSettings): Boolean =
        rootDir == settings.rootDir &&
            mainUserId == settings.mainUserId &&
            cloneUserId == settings.cloneUserId

    fun usableIndex(settings: UCloneSettings, currentRevision: Long): WorkspaceIndex? {
        return index.takeIf {
            matches(settings) &&
                it.readSucceeded &&
                revision == currentRevision
        }
    }

    companion object {
        fun from(
            settings: UCloneSettings,
            index: WorkspaceIndex,
            revision: Long,
        ) = WorkspaceCache(
            rootDir = settings.rootDir,
            mainUserId = settings.mainUserId,
            cloneUserId = settings.cloneUserId,
            index = index,
            revision = revision,
        )
    }
}

class UCloneViewModelFactory(
    private val application: Application,
    private val container: AppContainer,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(UCloneViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return UCloneViewModel(application, container) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
