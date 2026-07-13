package com.uclone.restore.external

import com.uclone.restore.AppContainer
import com.uclone.restore.model.AppRule
import com.uclone.restore.model.CrossUserInstallMode
import com.uclone.restore.model.TaskProgress
import com.uclone.restore.model.TaskRecord
import com.uclone.restore.model.UCloneSettings
import com.uclone.restore.sync.AppDataState
import com.uclone.restore.sync.RestoreCompatibilityOptions

internal sealed interface SwitchOrRestoreDecision {
    data object SwitchToClone : SwitchOrRestoreDecision

    data class RestoreMain(val rollbackId: String) : SwitchOrRestoreDecision
}

internal fun switchOrRestoreDecision(state: AppDataState): SwitchOrRestoreDecision = when (state) {
    AppDataState.Main -> SwitchOrRestoreDecision.SwitchToClone
    is AppDataState.Clone -> SwitchOrRestoreDecision.RestoreMain(state.mainReturnPointId)
    AppDataState.Unknown -> error("数据状态未知，请先进入 App 详情选择已标识的备份恢复状态")
}

internal class ExternalActionDispatcher(private val container: AppContainer) {
    suspend fun execute(
        request: ExternalActionRequest,
        settings: UCloneSettings,
        report: (TaskProgress) -> Unit,
    ): TaskRecord = when (request.operation) {
        ExternalActionContract.OPERATION_BACKUP_DEFAULT ->
            container.syncEngine.captureSnapshot(request.packageName, ruleFor(request.packageName, settings), settings, report, request.requestId)
        ExternalActionContract.OPERATION_RESTORE_LATEST_BACKUP ->
            container.syncEngine.restoreSnapshot(
                request.packageName,
                settings,
                report,
                request.requestId,
                request.restoreCompatibility(),
            )
        ExternalActionContract.OPERATION_RESTORE_FROM_CLONE_LATEST ->
            container.syncEngine.restoreFromCloneLatest(request.packageName, ruleFor(request.packageName, settings), settings, report, request.requestId)
        ExternalActionContract.OPERATION_PUSH_MAIN_TO_CLONE ->
            container.syncEngine.pushMainToClone(request.packageName, ruleFor(request.packageName, settings), settings, report, request.requestId)
        ExternalActionContract.OPERATION_RESTORE_LATEST_CLONE_ROLLBACK ->
            container.syncEngine.restoreCloneRollback(
                request.packageName,
                settings,
                report,
                request.requestId,
                request.restoreCompatibility(),
            )
        ExternalActionContract.OPERATION_SWITCH_TO_CLONE -> switchToClone(request, settings, report)
        ExternalActionContract.OPERATION_RESTORE_MAIN -> restoreMain(request, settings, report)
        ExternalActionContract.OPERATION_SWITCH_OR_RESTORE -> switchOrRestore(request, settings, report)
        ExternalActionContract.OPERATION_RESTORE_ROLLBACK ->
            container.syncEngine.rollback(
                request.packageName,
                requireRollbackId(request),
                settings,
                report,
                request.requestId,
                request.restoreCompatibility(),
            )
        ExternalActionContract.OPERATION_RESET_SWITCH_STATE ->
            container.syncEngine.resetSwitchState(request.packageName, settings, report, request.requestId)
        ExternalActionContract.OPERATION_DELETE_SNAPSHOT ->
            container.syncEngine.deleteSnapshot(request.packageName, settings, report, request.requestId)
        ExternalActionContract.OPERATION_DELETE_RESTORE_BACKUP ->
            container.syncEngine.deleteRestoreBackup(request.packageName, requireRollbackId(request), settings, report, request.requestId)
        ExternalActionContract.OPERATION_DELETE_CLONE_ROLLBACK ->
            container.syncEngine.deleteCloneRollback(request.packageName, requireRollbackId(request), settings, report, request.requestId)
        ExternalActionContract.OPERATION_PROBE_CLONE_CE ->
            container.syncEngine.probeCloneCe(settings, report, request.requestId)
        ExternalActionContract.OPERATION_UNLOCK_CLONE ->
            container.syncEngine.unlockCloneWithCredential(settings, report, request.requestId)
        ExternalActionContract.OPERATION_DEBUG_CLONE_SYSTEM ->
            container.syncEngine.debugCloneSystem(settings, report, request.requestId)
        ExternalActionContract.OPERATION_AUDIT_RESTORE ->
            container.syncEngine.auditRestoreConsistency(request.packageName, settings, report, request.requestId)
        ExternalActionContract.OPERATION_CLEAR_LOGS ->
            container.syncEngine.clearLogs(settings, report, request.requestId)
        ExternalActionContract.OPERATION_RESET_WORKSPACE ->
            container.syncEngine.resetWorkspace(settings, report, request.requestId)
        ExternalActionContract.OPERATION_START_CLONE_USER ->
            container.syncEngine.startCloneUser(settings, report, request.requestId)
        ExternalActionContract.OPERATION_SWITCH_TO_CLONE_USER ->
            container.syncEngine.switchToCloneUser(settings, report, request.requestId)
        ExternalActionContract.OPERATION_STOP_CLONE_USER ->
            container.syncEngine.stopCloneUserByExplicitUserRequest(settings, report, request.requestId)
        ExternalActionContract.OPERATION_SCAN_WORKSPACE_OWNERSHIP ->
            container.syncEngine.scanWorkspaceOwnership(settings, report, request.requestId)
        ExternalActionContract.OPERATION_REPAIR_WORKSPACE_OWNERSHIP ->
            container.syncEngine.repairWorkspaceOwnership(
                settings = settings,
                expectedCanonicalRoot = requireNotNull(request.expectedWorkspaceRoot) {
                    "缺少扫描时确认的工作目录"
                },
                report = report,
                requestId = request.requestId,
            )
        ExternalActionContract.OPERATION_INSTALL_TO_OTHER_USER ->
            installAcrossUsers(request, settings, CrossUserInstallMode.INSTALL_ONLY, report)
        ExternalActionContract.OPERATION_INSTALL_WITH_PERMISSIONS_TO_OTHER_USER ->
            installAcrossUsers(request, settings, CrossUserInstallMode.INSTALL_WITH_PERMISSIONS, report)
        ExternalActionContract.OPERATION_INSTALL_AND_SYNC_TO_OTHER_USER ->
            installAcrossUsers(request, settings, CrossUserInstallMode.INSTALL_AND_SYNC, report)
        ExternalActionContract.OPERATION_RECOVER_INTERRUPTED_TRANSACTION ->
            container.syncEngine.recoverInterruptedTransaction(
                transactionRequestId = requireRollbackId(request),
                packageName = request.packageName,
                settings = settings,
                report = report,
                requestId = request.requestId,
            )
        else -> error("不支持的任务操作：${request.operation}")
    }

    private suspend fun installAcrossUsers(
        request: ExternalActionRequest,
        settings: UCloneSettings,
        mode: CrossUserInstallMode,
        report: (TaskProgress) -> Unit,
    ): TaskRecord = container.syncEngine.installAcrossUsers(
        packageName = request.packageName,
        targetUserId = requireNotNull(request.targetUserId) { "缺少目标用户 ID" },
        mode = mode,
        rule = ruleFor(request.packageName, settings),
        settings = settings,
        report = report,
        requestId = request.requestId,
    )

    private suspend fun switchOrRestore(
        request: ExternalActionRequest,
        settings: UCloneSettings,
        report: (TaskProgress) -> Unit,
    ): TaskRecord {
        val state = container.syncEngine.appDataState(request.packageName, settings)
        return when (val decision = switchOrRestoreDecision(state)) {
            SwitchOrRestoreDecision.SwitchToClone -> container.syncEngine.switchToCloneState(
                request.packageName,
                ruleFor(request.packageName, settings),
                settings,
                report,
                request.requestId,
            )
            is SwitchOrRestoreDecision.RestoreMain -> container.syncEngine.restoreSwitchMainState(
                request.packageName,
                decision.rollbackId,
                ruleFor(request.packageName, settings),
                settings,
                report,
                request.requestId,
                request.restoreCompatibility(),
            )
        }
    }

    private suspend fun switchToClone(
        request: ExternalActionRequest,
        settings: UCloneSettings,
        report: (TaskProgress) -> Unit,
    ): TaskRecord {
        when (container.syncEngine.appDataState(request.packageName, settings)) {
            AppDataState.Main -> Unit
            is AppDataState.Clone -> error("已处于分身态，请先还原主系统")
            AppDataState.Unknown -> error("数据状态未知，请先进入 App 详情选择已标识的备份恢复状态")
        }
        return container.syncEngine.switchToCloneState(
            request.packageName,
            ruleFor(request.packageName, settings),
            settings,
            report,
            request.requestId,
        )
    }

    private suspend fun restoreMain(
        request: ExternalActionRequest,
        settings: UCloneSettings,
        report: (TaskProgress) -> Unit,
    ): TaskRecord {
        val rollbackId = when (val state = container.syncEngine.appDataState(request.packageName, settings)) {
            AppDataState.Main -> error("没有可用的切换回滚点")
            is AppDataState.Clone -> state.mainReturnPointId
            AppDataState.Unknown -> error("数据状态未知，请先进入 App 详情选择已标识的备份恢复状态")
        }
        return container.syncEngine.restoreSwitchMainState(
            request.packageName,
            rollbackId,
            ruleFor(request.packageName, settings),
            settings,
            report,
            request.requestId,
            request.restoreCompatibility(),
        )
    }

    private fun ruleFor(packageName: String, settings: UCloneSettings): AppRule = AppRule(
        packageName = packageName,
        includeCe = settings.includeCe,
        includeDe = settings.includeDe,
        includeExternal = settings.includeExternal,
        includeMedia = settings.includeMedia,
        includeObb = settings.includeObb,
        includePermissions = settings.includePermissions,
        excludeCache = settings.excludeCache,
    )

    private fun requireRollbackId(request: ExternalActionRequest): String =
        requireNotNull(request.rollbackId) { "缺少回滚 ID" }

    private fun ExternalActionRequest.restoreCompatibility() = RestoreCompatibilityOptions(
        allowVersionMismatch = allowVersionMismatch,
        allowLegacyIdentity = allowLegacyIdentity,
    )

}
