package com.uclone.restore.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.uclone.restore.AppContainer
import com.uclone.restore.launcher.FavoriteShortcutEntry
import com.uclone.restore.model.AppRule
import com.uclone.restore.model.TaskProgress
import com.uclone.restore.model.TaskStatus
import com.uclone.restore.model.UCloneSettings
import com.uclone.restore.model.User10CeState
import com.uclone.restore.service.SyncForegroundService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class UCloneViewModel(
    application: Application,
    private val container: AppContainer,
) : AndroidViewModel(application) {
    private val settingsStore = container.settingsStore
    private val packageInspector = container.packageInspector
    private val syncEngine = container.syncEngine
    private val launcherShortcutController = container.launcherShortcutController
    private val _state = MutableStateFlow(UiState(settings = settingsStore.load()))
    val state: StateFlow<UiState> = _state

    init {
        refreshAll()
    }

    fun refreshAll() {
        refreshEnvironment()
        loadApps()
        refreshHistory()
    }

    fun refreshEnvironment() {
        viewModelScope.launch {
            val settings = _state.value.settings
            runBusy("环境检测中") {
                val environment = syncEngine.checkEnvironment(settings)
                _state.update { it.copy(environment = environment, message = "环境检测完成") }
            }
        }
    }

    fun loadApps() {
        viewModelScope.launch {
            val settings = _state.value.settings
            runBusy("读取 App 列表") {
                val snapshots = syncEngine.snapshotMetadata(settings)
                val switchMarkers = syncEngine.switchMarkerIds(settings)
                val restoreBackups = syncEngine.listRestoreBackups(settings)
                val apps = packageInspector.listApps(settings).map { app ->
                    val snapshot = snapshots[app.packageName]
                    app.copy(
                        lastSnapshotAt = snapshot?.updatedAt,
                        snapshotSizeKb = snapshot?.sizeKb,
                    )
                }
                _state.update {
                    it.copy(
                        apps = apps,
                        restoreBackups = restoreBackups,
                        switchRollbackIds = switchMarkers,
                    )
                }
                syncLauncherShortcuts()
            }
        }
    }

    fun selectPackage(packageName: String) {
        viewModelScope.launch {
            val settings = _state.value.settings
            val snapshot = syncEngine.latestSnapshotMetadata(packageName, settings)
            val rollbackIds = syncEngine.listRollbackIds(packageName, settings)
            val switchRollbackId = syncEngine.switchMarkerId(packageName, settings)
            _state.update {
                val apps = it.apps.map { app ->
                    if (app.packageName == packageName) {
                        app.copy(
                            lastSnapshotAt = snapshot?.updatedAt,
                            snapshotSizeKb = snapshot?.sizeKb,
                        )
                    } else {
                        app
                    }
                }
                val switchRollbackIds = if (switchRollbackId == null) {
                    it.switchRollbackIds - packageName
                } else {
                    it.switchRollbackIds + (packageName to switchRollbackId)
                }
                it.copy(
                    selectedPackage = packageName,
                    apps = apps,
                    rollbackIds = rollbackIds,
                    switchRollbackIds = switchRollbackIds,
                )
            }
            syncLauncherShortcuts()
        }
    }

    fun updateSearch(value: String) {
        _state.update { it.copy(search = value) }
    }

    fun saveSettings(settings: UCloneSettings) {
        settingsStore.save(settings)
        _state.update { it.copy(settings = settings, message = "设置已保存") }
        syncLauncherShortcuts()
        refreshAll()
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
        val rule = _state.value.selectedRule ?: AppRule(packageName)
        runTask("正在建立分身快照") { report ->
            syncEngine.captureSnapshot(packageName, rule, _state.value.settings, report)
        }
    }

    fun restoreSelected() {
        val packageName = _state.value.selectedPackage ?: return
        restoreSnapshot(packageName)
    }

    fun restoreSnapshot(packageName: String) {
        _state.update { it.copy(selectedPackage = packageName) }
        runTask("正在恢复到主系统") { report ->
            syncEngine.restoreSnapshot(packageName, _state.value.settings, report)
        }
    }

    fun restoreLatestSelected() {
        val packageName = _state.value.selectedPackage ?: return
        val rule = _state.value.selectedRule ?: AppRule(packageName)
        runTask("正在从分身最新恢复") { report ->
            syncEngine.restoreFromCloneLatest(packageName, rule, _state.value.settings, report)
        }
    }

    fun switchToCloneState(packageName: String) {
        _state.update { it.copy(selectedPackage = packageName) }
        val rule = _state.value.selectedRule ?: AppRule(packageName)
        runTask("正在切换到分身态") { report ->
            syncEngine.switchToCloneState(packageName, rule, _state.value.settings, report)
        }
    }

    fun switchToCloneStateSelected() {
        val packageName = _state.value.selectedPackage ?: return
        switchToCloneState(packageName)
    }

    fun restoreSwitchMainState(packageName: String) {
        _state.update { it.copy(selectedPackage = packageName) }
        val rollbackId = _state.value.switchRollbackIds[packageName] ?: return
        runTask("正在还原主系统态") { report ->
            syncEngine.restoreSwitchMainState(packageName, rollbackId, _state.value.settings, report)
        }
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
        viewModelScope.launch {
            if (_state.value.currentTask.task?.status == TaskStatus.RUNNING) {
                _state.update { it.copy(message = "已有任务正在执行") }
                return@launch
            }
            if (!waitUntilIdleForLauncherShortcut()) {
                _state.update { it.copy(message = "初始化未完成，请稍后重试快捷入口") }
                return@launch
            }
            val rollbackId = syncEngine.switchMarkerId(packageName, _state.value.settings)
            _state.update {
                val switchRollbackIds = if (rollbackId == null) {
                    it.switchRollbackIds - packageName
                } else {
                    it.switchRollbackIds + (packageName to rollbackId)
                }
                it.copy(
                    selectedPackage = packageName,
                    switchRollbackIds = switchRollbackIds,
                )
            }
            syncLauncherShortcuts()
            if (rollbackId == null) {
                runTask("正在从桌面快捷入口切换") { report ->
                    syncEngine.switchToCloneState(packageName, ruleFor(packageName), _state.value.settings, report)
                }
            } else {
                runTask("正在从桌面快捷入口还原") { report ->
                    syncEngine.restoreSwitchMainState(packageName, rollbackId, _state.value.settings, report)
                }
            }
        }
    }

    fun rollbackSelected(rollbackId: String) {
        val packageName = _state.value.selectedPackage ?: return
        restoreBackup(packageName, rollbackId)
    }

    fun restoreBackup(packageName: String, rollbackId: String) {
        _state.update { it.copy(selectedPackage = packageName) }
        runTask("正在恢复被动备份") { report ->
            syncEngine.rollback(packageName, rollbackId, _state.value.settings, report)
        }
    }

    fun deleteSnapshotSelected() {
        val packageName = _state.value.selectedPackage ?: return
        deleteSnapshot(packageName)
    }

    fun deleteSnapshot(packageName: String) {
        _state.update { it.copy(selectedPackage = packageName) }
        runTask("正在删除 active 快照") { report ->
            syncEngine.deleteSnapshot(packageName, _state.value.settings, report)
        }
    }

    fun deleteRestoreBackup(packageName: String, rollbackId: String) {
        _state.update { it.copy(selectedPackage = packageName) }
        runTask("正在删除被动备份") { report ->
            syncEngine.deleteRestoreBackup(packageName, rollbackId, _state.value.settings, report)
        }
    }

    fun probeCloneCe() {
        runTask("正在探测分身 CE") { report ->
            syncEngine.probeCloneCe(_state.value.settings, report)
            val environment = syncEngine.checkEnvironment(_state.value.settings)
            _state.update { it.copy(environment = environment) }
        }
    }

    fun unlockCloneWithCredential() {
        val settings = _state.value.settings
        if (settings.cloneUnlockCredential.isBlank()) {
            _state.update { it.copy(message = "请先在设置中填写分身锁屏 PIN/密码") }
            return
        }
        runTask("正在无感启动分身") { report ->
            syncEngine.unlockCloneWithCredential(_state.value.settings, report)
            val environment = syncEngine.checkEnvironment(_state.value.settings)
            _state.update { it.copy(environment = environment) }
        }
    }

    fun debugCloneSystem() {
        runTask("正在调试分身系统") { report ->
            syncEngine.debugCloneSystem(_state.value.settings, report)
            val environment = syncEngine.checkEnvironment(_state.value.settings)
            _state.update { it.copy(environment = environment) }
        }
    }

    fun auditRestoreConsistencySelected() {
        val packageName = _state.value.selectedPackage ?: return
        _state.update { it.copy(selectedPackage = packageName) }
        runTask("正在生成恢复审计包") { report ->
            syncEngine.auditRestoreConsistency(packageName, _state.value.settings, report)
        }
    }

    fun clearLogs() {
        viewModelScope.launch {
            runBusy("清理日志") {
                val result = syncEngine.clearLogs(_state.value.settings)
                val message = if (result.isSuccess) {
                    result.stdout.trim().ifBlank { "日志已清理" }
                } else {
                    result.stderr.trim().ifBlank { "日志清理失败：${result.exitCode}" }
                }
                _state.update {
                    it.copy(
                        currentTask = TaskProgress(null),
                        history = syncEngine.history(),
                        message = message,
                    )
                }
            }
        }
    }

    fun startCloneUser() {
        viewModelScope.launch {
            runBusy("启动分身") {
                val result = syncEngine.startCloneUser(_state.value.settings)
                _state.update { it.copy(message = result.stdout.ifBlank { result.stderr }) }
                refreshEnvironment()
            }
        }
    }

    fun switchToCloneUser() {
        viewModelScope.launch {
            val result = syncEngine.switchToCloneUser(_state.value.settings)
            _state.update { it.copy(message = result.stdout.ifBlank { result.stderr }) }
        }
    }

    fun stopCloneUser() {
        viewModelScope.launch {
            runBusy("关闭分身") {
                val result = syncEngine.stopCloneUser(_state.value.settings)
                _state.update { it.copy(message = result.stdout.ifBlank { result.stderr }) }
                refreshEnvironment()
            }
        }
    }

    private fun refreshHistory() {
        _state.update { it.copy(history = syncEngine.history()) }
    }

    private fun runTask(
        serviceMessage: String,
        block: suspend (suspend (TaskProgress) -> Unit) -> Unit,
    ) {
        if (_state.value.busy) {
            _state.update { it.copy(message = "已有任务正在执行") }
            return
        }
        _state.update { it.copy(busy = true, message = serviceMessage) }
        viewModelScope.launch {
            SyncForegroundService.start(getApplication(), serviceMessage)
            try {
                block { progress ->
                    _state.update {
                        it.copy(
                            currentTask = progress,
                            history = syncEngine.history(),
                        )
                    }
                }
                val task = _state.value.currentTask.task
                val message = if (task?.status == TaskStatus.SUCCESS) "任务完成" else task?.message ?: "任务结束"
                _state.update { it.copy(message = message) }
            } catch (error: Exception) {
                _state.update { it.copy(message = error.message ?: "任务失败") }
            } finally {
                SyncForegroundService.stop(getApplication())
                val settings = _state.value.settings
                val restoreBackups = syncEngine.listRestoreBackups(settings)
                val switchMarkers = syncEngine.switchMarkerIds(settings)
                val environment = syncEngine.checkEnvironment(settings)
                val finalTask = _state.value.currentTask.task
                val finalMessage = if (
                    finalTask?.status == TaskStatus.SUCCESS &&
                    environment.user10CeState is User10CeState.NotStarted
                ) {
                    "任务完成，分身已关闭"
                } else {
                    _state.value.message
                }
                _state.update {
                    it.copy(
                        busy = false,
                        environment = environment,
                        history = syncEngine.history(),
                        restoreBackups = restoreBackups,
                        switchRollbackIds = switchMarkers,
                        message = finalMessage,
                    )
                }
                syncLauncherShortcuts()
                _state.value.selectedPackage?.let(::selectPackage)
            }
        }
    }

    private fun ruleFor(packageName: String): AppRule {
        val settings = _state.value.settings
        return AppRule(
            packageName = packageName,
            includeCe = settings.includeCe,
            includeDe = settings.includeDe,
            includeExternal = settings.includeExternal,
            includeMedia = settings.includeMedia,
            includeObb = settings.includeObb,
            includePermissions = settings.includePermissions,
            excludeCache = settings.excludeCache,
        )
    }

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

    private suspend fun waitUntilIdleForLauncherShortcut(): Boolean {
        repeat(LAUNCHER_SHORTCUT_IDLE_RETRY_COUNT) {
            if (!_state.value.busy) return true
            delay(LAUNCHER_SHORTCUT_IDLE_RETRY_DELAY_MS)
        }
        return !_state.value.busy
    }

    private suspend fun runBusy(label: String, block: suspend () -> Unit) {
        _state.update { it.copy(busy = true, message = label) }
        try {
            block()
        } catch (error: Exception) {
            _state.update { it.copy(message = error.message ?: "操作失败") }
        } finally {
            _state.update { it.copy(busy = false) }
        }
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

private const val LAUNCHER_SHORTCUT_IDLE_RETRY_COUNT = 80
private const val LAUNCHER_SHORTCUT_IDLE_RETRY_DELAY_MS = 250L
