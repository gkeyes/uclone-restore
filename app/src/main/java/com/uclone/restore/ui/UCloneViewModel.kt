package com.uclone.restore.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.uclone.restore.AppContainer
import com.uclone.restore.external.ExternalActionService
import com.uclone.restore.external.taskTypeForOperation
import com.uclone.restore.data.SettingsValidation
import com.uclone.restore.launcher.FavoriteShortcutEntry
import com.uclone.restore.model.TaskRecord
import com.uclone.restore.model.UCloneSettings
import com.uclone.restore.model.WorkspaceOwnershipReport
import com.uclone.restore.sync.WorkspaceIndex
import com.uclone.restore.sync.TransactionRecoveryState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
    private val transactionRecovery = container.transactionRecovery
    private val _state = MutableStateFlow(
        UiState(
            settings = settingsStore.load(),
            transactionRecovery = transactionRecovery.state.value,
        ),
    )
    val state: StateFlow<UiState> = _state
    private val _settingsMessages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val settingsMessages: SharedFlow<String> = _settingsMessages.asSharedFlow()
    private var refreshedTerminalRequestId: String? = null
    private val refreshMutex = Mutex()
    private var workspaceCache: WorkspaceCache? = null
    private var settingsSaveGeneration = 0L

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
        val generation = ++settingsSaveGeneration
        val previous = _state.value.settings
        val normalized = runCatching { SettingsValidation.requireValid(settings) }
            .onFailure { error -> publishSettingsMessage(error.message ?: "设置无效") }
            .getOrNull() ?: return
        val diff = SettingsDiff.between(previous, normalized)
        diff.runtimeTargetChangeBlockingMessage(
            taskBusy = taskCoordinator.isBusy(),
            recoveryState = transactionRecovery.state.value,
        )?.let { reason ->
            publishSettingsMessage(reason)
            return
        }
        if (diff.requiresRuntimeValidation) {
            launchRefresh {
                runBusy("验证用户和工作目录") {
                    val validation = runCatching { syncEngine.validateSettingsTarget(normalized) }
                        .onFailure { error -> publishSettingsMessage(error.message ?: "设置验证失败") }
                        .getOrNull() ?: return@runBusy
                    if (!validation.ok) {
                        publishSettingsMessage(validation.detail)
                        return@runBusy
                    }
                    runCatching {
                        persistRuntimeTargetSettings(previous, normalized, diff, generation)
                    }.onFailure { error ->
                        publishSettingsMessage(error.message ?: "设置保存失败")
                    }
                }
            }
            return
        }
        runCatching { persistSettings(previous, normalized, diff, generation) }
            .onFailure { error -> publishSettingsMessage(error.message ?: "设置保存失败") }
    }

    private fun persistRuntimeTargetSettings(
        previous: UCloneSettings,
        normalized: UCloneSettings,
        diff: SettingsDiff,
        generation: Long,
    ) {
        var rejection: String? = null
        val admitted = taskCoordinator.tryRunWhileIdle {
            rejection = diff.runtimeTargetChangeBlockingMessage(
                taskBusy = false,
                recoveryState = transactionRecovery.state.value,
            )
            if (rejection == null) {
                persistSettings(previous, normalized, diff, generation)
            }
        }
        if (!admitted) {
            rejection = diff.runtimeTargetChangeBlockingMessage(
                taskBusy = true,
                recoveryState = transactionRecovery.state.value,
            )
        }
        rejection?.let(::publishSettingsMessage)
    }

    private fun persistSettings(
        previous: UCloneSettings,
        normalized: UCloneSettings,
        diff: SettingsDiff,
        generation: Long,
    ) {
        if (generation != settingsSaveGeneration) return
        settingsStore.save(normalized)
        _state.update {
            it.copy(
                settings = normalized,
                workspaceOwnership = WorkspaceOwnershipReportPolicy.retainAfterSettingsChange(
                    it.workspaceOwnership,
                    previous,
                    normalized,
                ),
                message = "设置已保存",
            )
        }
        _settingsMessages.tryEmit("设置已保存")
        when {
            diff.requiresFullRefresh -> refreshAll()
            diff.requiresShortcutSync -> syncLauncherShortcuts()
        }
    }

    private fun publishSettingsMessage(message: String) {
        _state.update { it.copy(message = message) }
        _settingsMessages.tryEmit(message)
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

    fun restoreSelected(allowVersionMismatch: Boolean = false, allowLegacyIdentity: Boolean = false) {
        val packageName = _state.value.selectedPackage ?: return
        restoreSnapshot(packageName, allowVersionMismatch, allowLegacyIdentity)
    }

    fun restoreSnapshot(
        packageName: String,
        allowVersionMismatch: Boolean = false,
        allowLegacyIdentity: Boolean = false,
    ) {
        _state.update { it.copy(selectedPackage = packageName) }
        submitTask(
            UiTaskAction.RESTORE_ACTIVE,
            packageName,
            "正在恢复到主系统",
            allowVersionMismatch = allowVersionMismatch,
            allowLegacyIdentity = allowLegacyIdentity,
        )
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

    fun restoreCloneRollback(
        packageName: String,
        allowVersionMismatch: Boolean = false,
        allowLegacyIdentity: Boolean = false,
    ) {
        _state.update { it.copy(selectedPackage = packageName) }
        submitTask(
            UiTaskAction.RESTORE_CLONE_ROLLBACK,
            packageName,
            "正在恢复分身回滚",
            allowVersionMismatch = allowVersionMismatch,
            allowLegacyIdentity = allowLegacyIdentity,
        )
    }

    fun restoreSwitchMainState(
        packageName: String,
        allowVersionMismatch: Boolean = false,
        allowLegacyIdentity: Boolean = false,
    ) {
        _state.update { it.copy(selectedPackage = packageName) }
        submitTask(
            UiTaskAction.RESTORE_MAIN,
            packageName,
            "正在还原主系统态",
            allowVersionMismatch = allowVersionMismatch,
            allowLegacyIdentity = allowLegacyIdentity,
        )
    }

    fun restoreSwitchMainStateSelected(
        allowVersionMismatch: Boolean = false,
        allowLegacyIdentity: Boolean = false,
    ) {
        val packageName = _state.value.selectedPackage ?: return
        restoreSwitchMainState(packageName, allowVersionMismatch, allowLegacyIdentity)
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

    fun rollbackSelected(
        rollbackId: String,
        allowVersionMismatch: Boolean = false,
        allowLegacyIdentity: Boolean = false,
    ) {
        val packageName = _state.value.selectedPackage ?: return
        restoreBackup(packageName, rollbackId, allowVersionMismatch, allowLegacyIdentity)
    }

    fun restoreBackup(
        packageName: String,
        rollbackId: String,
        allowVersionMismatch: Boolean = false,
        allowLegacyIdentity: Boolean = false,
    ) {
        _state.update { it.copy(selectedPackage = packageName) }
        submitTask(
            UiTaskAction.RESTORE_ROLLBACK,
            packageName,
            "正在恢复被动备份",
            rollbackId,
            allowVersionMismatch = allowVersionMismatch,
            allowLegacyIdentity = allowLegacyIdentity,
        )
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
        submitTask(UiTaskAction.SCAN_WORKSPACE_OWNERSHIP, "workspace", "正在扫描备份容量归属")
    }

    fun repairWorkspaceOwnership() {
        val report = _state.value.workspaceOwnership ?: return
        if (report.nonRootEntries <= 0L) {
            _state.update { it.copy(message = "备份容量归属已经正常") }
            return
        }
        if (!WorkspaceOwnershipReportPolicy.canRepair(report, _state.value.settings)) {
            _state.update { it.copy(workspaceOwnership = null, message = "工作目录或用户设置已变化，请重新扫描后再修复") }
            return
        }
        submitTask(
            action = UiTaskAction.REPAIR_WORKSPACE_OWNERSHIP,
            packageName = "workspace",
            message = "正在修复备份容量归属",
            expectedWorkspaceRoot = report.canonicalRoot,
        )
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
            transactionRecovery.state.collect { recovery ->
                _state.update { state ->
                    state.copy(
                        transactionRecovery = recovery,
                        message = when (recovery) {
                            is TransactionRecoveryState.Required -> "检测到未完成事务，必须先执行安全恢复"
                            is TransactionRecoveryState.Recovering -> "正在恢复未完成事务"
                            is TransactionRecoveryState.RootTaskStillRunning -> "上次 Root 数据任务仍在运行"
                            is TransactionRecoveryState.Failed -> "事务状态检查失败：${recovery.message}"
                            else -> state.message
                        },
                    )
                }
            }
        }
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
        expectedWorkspaceRoot: String? = null,
        allowVersionMismatch: Boolean = false,
        allowLegacyIdentity: Boolean = false,
    ) {
        transactionRecovery.blockingMessage(taskTypeForOperation(action.operation))?.let { reason ->
            _state.update { it.copy(message = reason) }
            return
        }
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
            expectedWorkspaceRoot,
            allowVersionMismatch,
            allowLegacyIdentity,
        )
    }

    fun retryInterruptedTransactionRecovery() {
        viewModelScope.launch {
            val settings = _state.value.settings
            val activeRootTaskResult = runCatching { container.activeRootTaskProbe.probe(settings.rootDir) }
                .onFailure { error -> transactionRecovery.markFailed(error.message ?: "Root 任务锁检查失败") }
            if (activeRootTaskResult.isFailure) return@launch
            val activeRootTask = activeRootTaskResult.getOrNull()
            val pending = runCatching { container.transactionRecoveryProbe.scan(settings.rootDir) }
                .onFailure { error -> transactionRecovery.markFailed(error.message ?: "事务扫描失败") }
                .getOrNull() ?: return@launch
            transactionRecovery.updateScan(
                pending,
                liveRequestId = activeRootTask?.takeIf { it.isLive }
                    ?.let { it.recoveryTransactionId ?: it.requestId },
            )
            val required = transactionRecovery.state.value as? TransactionRecoveryState.Required ?: return@launch
            val transaction = required.transactions.firstOrNull() ?: return@launch
            transactionRecovery.markRecovering(transaction, required.transactions.drop(1))
            runCatching { ExternalActionService.startRecovery(getApplication(), transaction) }
                .onFailure { error -> transactionRecovery.markFailed(error.message ?: "无法启动事务恢复") }
        }
    }

    fun cancelCurrentTaskSafely() {
        val task = _state.value.currentTask.task?.takeIf { !it.status.isTerminal } ?: return
        viewModelScope.launch {
            val requested = syncEngine.requestTransactionCancel(task.requestId, _state.value.settings)
            _state.update {
                it.copy(
                    message = if (requested) {
                        "已请求安全停止；写入阶段会先自动回滚，提交和回滚阶段不会被强制中断"
                    } else {
                        "当前任务尚未进入可安全停止的事务阶段"
                    },
                )
            }
        }
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
                if (
                    task.status.isSuccessful &&
                    task.type in setOf(
                        com.uclone.restore.model.TaskType.SCAN_WORKSPACE_OWNERSHIP,
                        com.uclone.restore.model.TaskType.REPAIR_WORKSPACE_OWNERSHIP,
                    )
                ) {
                    workspaceOwnershipFrom(task, settings)?.let { ownership ->
                        _state.update {
                            it.copy(
                                workspaceOwnership = ownership,
                                message = if (ownership.nonRootEntries == 0L) {
                                    "备份容量归属正常"
                                } else {
                                    "发现 ${ownership.nonRootEntries} 个文件或目录需要修复"
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

    private fun workspaceOwnershipFrom(task: TaskRecord, settings: UCloneSettings): WorkspaceOwnershipReport? {
        val audit = task.audit
        val canonicalRoot = audit.path ?: return null
        val totalEntries = audit.totalEntries ?: return null
        val nonRootEntries = audit.nonRootEntries ?: return null
        val totalSizeKb = audit.sizeKb ?: return null
        return WorkspaceOwnershipReportPolicy.bind(
            report = WorkspaceOwnershipReport(
                canonicalRoot = canonicalRoot,
                totalEntries = totalEntries,
                nonRootEntries = nonRootEntries,
                totalSizeKb = totalSizeKb,
            ),
            settings = settings,
            scannedAt = task.finishedAt ?: task.startedAt,
        )
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
