package com.uclone.restore.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.uclone.restore.AppContainer
import com.uclone.restore.external.ExternalActionService
import com.uclone.restore.launcher.FavoriteShortcutEntry
import com.uclone.restore.model.TaskRecord
import com.uclone.restore.model.UCloneSettings
import com.uclone.restore.sync.WorkspaceIndex
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
    private val externalRequestStore = container.externalRequestStore
    private val _state = MutableStateFlow(UiState(settings = settingsStore.load()))
    val state: StateFlow<UiState> = _state
    private var refreshedTerminalRequestId: String? = null
    private val refreshMutex = Mutex()
    private var workspaceCache: WorkspaceCache? = null

    init {
        observeTasks()
        refreshAll()
    }

    fun refreshAll() {
        launchRefresh {
            val settings = _state.value.settings
            runBusy("刷新中") {
                val environment = syncEngine.checkEnvironment(settings)
                val workspace = syncEngine.loadWorkspaceIndex(settings)
                val apps = packageInspector.listApps(settings).map { app ->
                    val snapshot = workspace.snapshots[app.packageName]
                    app.copy(
                        lastSnapshotAt = snapshot?.updatedAt,
                        snapshotSizeKb = snapshot?.sizeKb,
                    )
                }
                workspaceCache = WorkspaceCache(settings.rootDir, workspace)
                _state.update { state ->
                    if (SettingsDiff.between(settings, state.settings).requiresFullRefresh) {
                        state
                    } else {
                        state.copy(
                            environment = environment,
                            apps = apps,
                            history = taskRepository.all(),
                            rollbackIds = state.selectedPackage?.let(workspace::rollbackIds).orEmpty(),
                            restoreBackups = workspace.restoreBackups,
                            cloneRollbackBackups = workspace.cloneRollbackBackups,
                            switchRollbackIds = workspace.switchMarkers,
                            message = "刷新完成",
                        )
                    }
                }
                syncLauncherShortcuts()
            }
        }
    }

    fun refreshEnvironment() {
        launchRefresh {
            val settings = _state.value.settings
            runBusy("环境检测中") {
                val environment = syncEngine.checkEnvironment(settings)
                _state.update { it.copy(environment = environment, message = "环境检测完成") }
            }
        }
    }

    fun loadApps() {
        launchRefresh {
            val settings = _state.value.settings
            runBusy("读取 App 列表") {
                val workspace = syncEngine.loadWorkspaceIndex(settings)
                val apps = packageInspector.listApps(settings).map { app ->
                    val snapshot = workspace.snapshots[app.packageName]
                    app.copy(
                        lastSnapshotAt = snapshot?.updatedAt,
                        snapshotSizeKb = snapshot?.sizeKb,
                    )
                }
                workspaceCache = WorkspaceCache(settings.rootDir, workspace)
                _state.update {
                    it.copy(
                        apps = apps,
                        rollbackIds = it.selectedPackage?.let(workspace::rollbackIds).orEmpty(),
                        restoreBackups = workspace.restoreBackups,
                        cloneRollbackBackups = workspace.cloneRollbackBackups,
                        switchRollbackIds = workspace.switchMarkers,
                    )
                }
                syncLauncherShortcuts()
            }
        }
    }

    fun selectPackage(packageName: String) {
        _state.update { it.copy(selectedPackage = packageName) }
        launchRefresh {
            val settings = _state.value.settings
            val workspace = workspaceIndex(settings)
            _state.update {
                val apps = it.apps.map { app ->
                    val snapshot = workspace.snapshots[app.packageName]
                    app.copy(
                        lastSnapshotAt = snapshot?.updatedAt,
                        snapshotSizeKb = snapshot?.sizeKb,
                    )
                }
                it.copy(
                    apps = apps,
                    rollbackIds = workspace.rollbackIds(packageName),
                    restoreBackups = workspace.restoreBackups,
                    cloneRollbackBackups = workspace.cloneRollbackBackups,
                    switchRollbackIds = workspace.switchMarkers,
                )
            }
        }
    }

    fun updateSearch(value: String) {
        _state.update { it.copy(search = value) }
    }

    fun saveSettings(settings: UCloneSettings) {
        val diff = SettingsDiff.between(_state.value.settings, settings)
        settingsStore.save(settings)
        _state.update { it.copy(settings = settings, message = "设置已保存") }
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

    fun restoreSwitchMainState(packageName: String) {
        _state.update { it.copy(selectedPackage = packageName) }
        submitTask(UiTaskAction.RESTORE_MAIN, packageName, "正在还原主系统态")
    }

    fun restoreSwitchMainStateSelected() {
        val packageName = _state.value.selectedPackage ?: return
        restoreSwitchMainState(packageName)
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

    fun resetSwitchStateSelected() {
        val packageName = _state.value.selectedPackage ?: return
        submitTask(UiTaskAction.RESET_SWITCH_STATE, packageName, "正在重置切换状态")
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

    fun deleteCloneRollback(packageName: String, rollbackId: String) {
        submitTask(UiTaskAction.DELETE_CLONE_ROLLBACK, packageName, "正在删除分身回滚", rollbackId)
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
        if (_state.value.busy || taskCoordinator.isBusy()) {
            _state.update { it.copy(message = "已有任务正在执行") }
            return
        }
        launchRefresh {
            val settings = _state.value.settings
            runBusy("正在扫描备份容量归属") {
                val report = syncEngine.scanWorkspaceOwnership(settings)
                _state.update {
                    it.copy(
                        workspaceOwnership = report,
                        message = if (report.nonRootEntries == 0L) {
                            "备份容量归属正常"
                        } else {
                            "发现 ${report.nonRootEntries} 个文件或目录需要修复"
                        },
                    )
                }
            }
        }
    }

    fun repairWorkspaceOwnership() {
        val report = _state.value.workspaceOwnership ?: return
        if (report.nonRootEntries <= 0L) {
            _state.update { it.copy(message = "备份容量归属已经正常") }
            return
        }
        submitTask(UiTaskAction.REPAIR_WORKSPACE_OWNERSHIP, "workspace", "正在修复备份容量归属")
    }

    internal fun installSelectedToOtherUser(action: UiTaskAction) {
        val app = _state.value.selectedApp ?: return
        val settings = _state.value.settings
        val targetUserId = when {
            app.user0Installed && !app.user10Installed -> settings.cloneUserId
            app.user10Installed && !app.user0Installed -> settings.mainUserId
            else -> {
                _state.update { it.copy(message = "只有单侧已安装的 App 才能执行跨用户安装") }
                return
            }
        }
        val message = when (action) {
            UiTaskAction.INSTALL_OTHER_USER -> "正在安装到 user$targetUserId"
            UiTaskAction.INSTALL_OTHER_USER_WITH_PERMISSIONS -> "正在安装并迁移权限到 user$targetUserId"
            UiTaskAction.INSTALL_OTHER_USER_AND_SYNC -> "正在安装并同步数据到 user$targetUserId"
            else -> return
        }
        submitTask(action, app.packageName, message, targetUserId = targetUserId)
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
            externalRequestStore.events.collect { events ->
                _state.update { it.copy(externalRequests = events) }
            }
        }
        viewModelScope.launch {
            taskRepository.history.collect { history ->
                _state.update { it.copy(history = history) }
            }
        }
        viewModelScope.launch {
            taskRepository.progress.collect { progress ->
                val task = progress.task
                _state.update { TaskUiStateReducer.progress(it, progress) }
                if (task?.status?.isTerminal == true && refreshedTerminalRequestId != task.requestId) {
                    refreshedTerminalRequestId = task.requestId
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
        targetUserId: Int? = null,
    ) {
        if (_state.value.busy || taskCoordinator.isBusy()) {
            _state.update { it.copy(message = "已有任务正在执行") }
            return
        }
        _state.update { it.copy(message = message) }
        ExternalActionService.startInternal(
            getApplication(),
            action.operation,
            packageName,
            rollbackId,
            targetUserId,
        )
    }

    private suspend fun refreshAfterTask(task: TaskRecord) {
        refreshMutex.withLock {
            runCatching {
                val policy = RefreshPolicy.forTask(task.type)
                val settings = _state.value.settings
                val workspace = if (policy.workspace) {
                    syncEngine.loadWorkspaceIndex(settings).also {
                        workspaceCache = WorkspaceCache(settings.rootDir, it)
                    }
                } else {
                    null
                }
                val environment = if (policy.environment) syncEngine.checkEnvironment(settings) else null
                val apps = if (policy.apps) {
                    val previousApps = _state.value.apps.associateBy { it.packageName }
                    packageInspector.listApps(settings).map { app ->
                        val snapshot = workspace?.snapshots?.get(app.packageName)
                        val previous = previousApps[app.packageName]
                        app.copy(
                            lastSnapshotAt = snapshot?.updatedAt ?: previous?.lastSnapshotAt,
                            snapshotSizeKb = snapshot?.sizeKb ?: previous?.snapshotSizeKb,
                            lastRestoreAt = previous?.lastRestoreAt,
                        )
                    }
                } else {
                    null
                }
                val snapshot = TaskRefreshSnapshot(
                    history = taskRepository.all(),
                    environment = environment,
                    workspaceIndex = workspace,
                    apps = apps,
                )
                _state.update { TaskUiStateReducer.refreshed(it, task, snapshot) }
                if (task.type == com.uclone.restore.model.TaskType.REPAIR_WORKSPACE_OWNERSHIP) {
                    val ownership = syncEngine.scanWorkspaceOwnership(settings)
                    _state.update { it.copy(workspaceOwnership = ownership) }
                }
                if (policy.shortcuts) syncLauncherShortcuts()
            }.onFailure { error ->
                _state.update { it.copy(busy = false, message = error.message ?: "任务结束后刷新状态失败") }
            }
        }
    }

    private fun launchRefresh(block: suspend () -> Unit) =
        viewModelScope.launch {
            refreshMutex.withLock { block() }
        }

    private suspend fun workspaceIndex(settings: UCloneSettings): WorkspaceIndex {
        val cached = workspaceCache
        if (cached?.rootDir == settings.rootDir) return cached.index
        return syncEngine.loadWorkspaceIndex(settings).also {
            workspaceCache = WorkspaceCache(settings.rootDir, it)
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
                    switched = app.packageName in state.switchRollbackIds,
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
}

private data class WorkspaceCache(
    val rootDir: String,
    val index: WorkspaceIndex,
)

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
