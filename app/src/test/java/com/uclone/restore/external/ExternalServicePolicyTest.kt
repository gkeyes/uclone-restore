package com.uclone.restore.external

import com.uclone.restore.sync.TaskCoordinator
import com.uclone.restore.sync.TaskLogStore
import com.uclone.restore.root.RootShellExecutor
import com.uclone.restore.root.ShellResult
import com.uclone.restore.model.TaskStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking

class ExternalServicePolicyTest {
    @Test
    fun onlyValidInProcessTokenCanClaimInternalSources() {
        val validApp = request(ExternalActionContract.SOURCE_APP, "secret")
        val invalidApp = request(ExternalActionContract.SOURCE_APP, "spoofed")
        val validShortcut = request(ExternalActionContract.SOURCE_LAUNCHER_SHORTCUT, "secret")
        val invalidShortcut = request(ExternalActionContract.SOURCE_LAUNCHER_SHORTCUT, null)
        val module = request(ExternalActionContract.SOURCE_MODULE, null)

        assertEquals(null, validApp.sourceAccessRejection(allowModuleControl = false, "secret"))
        assertEquals("无效的内部任务请求", invalidApp.sourceAccessRejection(allowModuleControl = true, "secret"))
        assertEquals(null, validShortcut.sourceAccessRejection(allowModuleControl = false, "secret"))
        assertEquals("无效的桌面快捷请求", invalidShortcut.sourceAccessRejection(allowModuleControl = true, "secret"))
        assertEquals("模块控制未开启", module.sourceAccessRejection(allowModuleControl = false, "secret"))
        assertEquals(null, module.sourceAccessRejection(allowModuleControl = true, "secret"))
    }

    @Test
    fun moduleSourcesAreRestrictedToPublishedDataOperations() {
        val module = request(ExternalActionContract.SOURCE_MODULE, null)
        val shortcut = request(ExternalActionContract.SOURCE_LAUNCHER_SHORTCUT, "secret")
        val publishedOperations = setOf(
            ExternalActionContract.OPERATION_SWITCH_OR_RESTORE,
            ExternalActionContract.OPERATION_SWITCH_TO_CLONE,
            ExternalActionContract.OPERATION_RESTORE_MAIN,
            ExternalActionContract.OPERATION_BACKUP_DEFAULT,
            ExternalActionContract.OPERATION_RESTORE_LATEST_BACKUP,
            ExternalActionContract.OPERATION_PUSH_MAIN_TO_CLONE,
            ExternalActionContract.OPERATION_RESTORE_LATEST_CLONE_ROLLBACK,
        )

        publishedOperations.forEach { operation ->
            assertEquals(null, module.copy(operation = operation).sourceOperationRejection(), operation)
        }
        listOf(
            ExternalActionContract.OPERATION_RESTORE_FROM_CLONE_LATEST,
            ExternalActionContract.OPERATION_RESTORE_ROLLBACK,
            ExternalActionContract.OPERATION_RESET_SWITCH_STATE,
            ExternalActionContract.OPERATION_DELETE_SNAPSHOT,
            ExternalActionContract.OPERATION_DELETE_RESTORE_BACKUP,
            ExternalActionContract.OPERATION_DELETE_CLONE_ROLLBACK,
            ExternalActionContract.OPERATION_PROBE_CLONE_CE,
            ExternalActionContract.OPERATION_UNLOCK_CLONE,
            ExternalActionContract.OPERATION_DEBUG_CLONE_SYSTEM,
            ExternalActionContract.OPERATION_AUDIT_RESTORE,
            ExternalActionContract.OPERATION_CLEAR_LOGS,
            ExternalActionContract.OPERATION_RESET_WORKSPACE,
            ExternalActionContract.OPERATION_START_CLONE_USER,
            ExternalActionContract.OPERATION_SWITCH_TO_CLONE_USER,
            ExternalActionContract.OPERATION_STOP_CLONE_USER,
            ExternalActionContract.OPERATION_SCAN_WORKSPACE_OWNERSHIP,
            ExternalActionContract.OPERATION_REPAIR_WORKSPACE_OWNERSHIP,
            ExternalActionContract.OPERATION_INSTALL_TO_OTHER_USER,
            ExternalActionContract.OPERATION_INSTALL_WITH_PERMISSIONS_TO_OTHER_USER,
            ExternalActionContract.OPERATION_INSTALL_AND_SYNC_TO_OTHER_USER,
        ).forEach { operation ->
            assertEquals("模块不允许执行此操作", module.copy(operation = operation).sourceOperationRejection(), operation)
        }
        assertEquals(
            "模块不允许执行此操作",
            module.copy(
                source = ExternalActionContract.SOURCE_LAUNCHER_MODULE,
                operation = ExternalActionContract.OPERATION_RESET_SWITCH_STATE,
            ).sourceOperationRejection(),
        )
        assertEquals(null, shortcut.sourceOperationRejection())
        assertEquals(
            "桌面快捷入口不允许执行此操作",
            shortcut.copy(operation = ExternalActionContract.OPERATION_BACKUP_DEFAULT).sourceOperationRejection(),
        )
    }

    @Test
    fun moduleSourcesMustBindUser0IconToConfiguredMainUser() {
        val module = request(
            source = ExternalActionContract.SOURCE_MODULE,
            token = null,
            targetUserId = 0,
        )
        val launcherModule = module.copy(source = ExternalActionContract.SOURCE_LAUNCHER_MODULE)

        assertEquals(null, module.moduleTargetUserRejection(mainUserId = 0))
        assertEquals(null, launcherModule.moduleTargetUserRejection(mainUserId = 0))
        assertEquals(
            "模块桌面目标是 user0，但当前主系统配置为 user10",
            module.moduleTargetUserRejection(mainUserId = 10),
        )
        assertEquals(
            "模块桌面目标是 user0，但当前主系统配置为 user10",
            launcherModule.moduleTargetUserRejection(mainUserId = 10),
        )
        assertEquals(null, module.copy(targetUserId = null).moduleTargetUserRejection(mainUserId = 0))
        assertEquals(
            "模块桌面目标是 user0，但当前主系统配置为 user10",
            launcherModule.copy(targetUserId = null).moduleTargetUserRejection(mainUserId = 10),
        )
    }

    @Test
    fun internalAppAndLauncherShortcutDoNotUseModuleTargetUserBinding() {
        val app = request(ExternalActionContract.SOURCE_APP, "secret", targetUserId = 10)
        val shortcut = request(ExternalActionContract.SOURCE_LAUNCHER_SHORTCUT, "secret", targetUserId = null)

        assertEquals(null, app.moduleTargetUserRejection(mainUserId = 0))
        assertEquals(null, shortcut.moduleTargetUserRejection(mainUserId = 10))
    }

    @Test
    fun compatibilityOverridesAreInternalAppOnly() {
        val app = request(ExternalActionContract.SOURCE_APP, "secret")
            .copy(allowVersionMismatch = true, allowLegacyIdentity = true)
        val module = request(ExternalActionContract.SOURCE_MODULE, null)
            .copy(allowVersionMismatch = true)

        assertEquals(null, app.compatibilityOverrideRejection())
        assertEquals("兼容性恢复豁免只能由主 App 二次确认后使用", module.compatibilityOverrideRejection())
    }

    @Test
    fun trustedRecoveryBypassesOrdinaryTargetPolicyAfterTokenAndOperationChecks() {
        val app = request(ExternalActionContract.SOURCE_APP, "secret")
        val recovery = app.copy(
            source = ExternalActionContract.SOURCE_RECOVERY,
            operation = ExternalActionContract.OPERATION_RECOVER_INTERRUPTED_TRANSACTION,
            rollbackId = "interrupted-request",
        )
        val install = app.copy(operation = ExternalActionContract.OPERATION_INSTALL_TO_OTHER_USER)
        val maintenance = app.copy(operation = ExternalActionContract.OPERATION_CLEAR_LOGS)
        val module = request(ExternalActionContract.SOURCE_MODULE, null)

        assertEquals(true, app.requiresTargetPackagePolicy())
        assertEquals(false, recovery.requiresTargetPackagePolicy())
        assertEquals(true, install.requiresTargetPackagePolicy())
        assertEquals(false, maintenance.requiresTargetPackagePolicy())
        assertEquals(true, module.requiresTargetPackagePolicy())
        assertEquals(null, recovery.sourceOperationRejection())
    }

    @Test
    fun serviceAdmissionMapsDuplicateAndBusyWithoutQueueing() {
        val repository = TaskLogStore(NoopShell)
        val coordinator = TaskCoordinator(repository)
        val first = request(ExternalActionContract.SOURCE_MODULE, null, requestId = "request-1")
        val duplicate = first.copy()
        val other = first.copy(requestId = "request-2", packageName = "com.other.app")

        assertIs<ExternalTaskAdmission.Accepted>(admitExternalTask(coordinator, first))
        val duplicateResult = assertIs<ExternalTaskAdmission.Rejected>(admitExternalTask(coordinator, duplicate))
        val busyResult = assertIs<ExternalTaskAdmission.Rejected>(admitExternalTask(coordinator, other))

        assertEquals(ExternalActionContract.STATUS_ALREADY_RUNNING, duplicateResult.status)
        assertEquals(ExternalActionContract.STATUS_BUSY, busyResult.status)
        assertEquals(1, repository.all().size)
    }

    @Test
    fun cancellationHasDedicatedInterruptedTaskState() {
        val repository = TaskLogStore(NoopShell)
        val coordinator = TaskCoordinator(repository)
        val request = request(ExternalActionContract.SOURCE_MODULE, null, requestId = "cancelled-request")
        assertIs<ExternalTaskAdmission.Accepted>(admitExternalTask(coordinator, request))

        val interrupted = coordinator.interrupt(request.requestId)

        assertEquals(TaskStatus.INTERRUPTED, interrupted?.status)
        assertEquals(TaskStatus.INTERRUPTED, repository.find(request.requestId)?.status)
    }

    @Test
    fun terminalRequestIdCannotReplayDestructiveOperationAfterCoordinatorRestart() {
        val repository = TaskLogStore(NoopShell)
        val firstCoordinator = TaskCoordinator(repository)
        val request = request(ExternalActionContract.SOURCE_MODULE, null, requestId = "durable-request")
        val accepted = assertIs<ExternalTaskAdmission.Accepted>(admitExternalTask(firstCoordinator, request))
        repository.finish(accepted.record, TaskStatus.SUCCESS, "完成")
        firstCoordinator.complete(request.requestId)

        val replay = assertIs<ExternalTaskAdmission.Rejected>(admitExternalTask(TaskCoordinator(repository), request))

        assertEquals(ExternalActionContract.STATUS_SUCCESS, replay.status)
        assertEquals("该 requestId 已有终态记录，未重复执行", replay.message)
        assertEquals(1, repository.all().size)
    }

    @Test
    fun successfulStateChangeRefreshesShortcutsWithoutViewModel() = runBlocking {
        var refreshCount = 0
        val refresh = suspend { refreshCount += 1 }

        refreshFavoriteShortcutsAfterSuccessfulStateChange(
            ExternalActionContract.OPERATION_SWITCH_OR_RESTORE,
            TaskStatus.SUCCESS,
            refresh,
        )
        refreshFavoriteShortcutsAfterSuccessfulStateChange(
            ExternalActionContract.OPERATION_RESET_SWITCH_STATE,
            TaskStatus.SUCCESS_WITH_WARNINGS,
            refresh,
        )
        refreshFavoriteShortcutsAfterSuccessfulStateChange(
            ExternalActionContract.OPERATION_SWITCH_OR_RESTORE,
            TaskStatus.FAILED,
            refresh,
        )
        refreshFavoriteShortcutsAfterSuccessfulStateChange(
            ExternalActionContract.OPERATION_PUSH_MAIN_TO_CLONE,
            TaskStatus.SUCCESS,
            refresh,
        )

        assertEquals(2, refreshCount)
        assertEquals(true, isShortcutStateChangingOperation(ExternalActionContract.OPERATION_RESTORE_MAIN))
        assertEquals(false, isShortcutStateChangingOperation(ExternalActionContract.OPERATION_PUSH_MAIN_TO_CLONE))
    }

    private fun request(
        source: String,
        token: String?,
        requestId: String = "request-1",
        targetUserId: Int? = null,
    ) = ExternalActionRequest(
        operation = ExternalActionContract.OPERATION_SWITCH_OR_RESTORE,
        packageName = "com.example.app",
        requestId = requestId,
        source = source,
        rollbackId = null,
        internalToken = token,
        targetUserId = targetUserId,
    )

    private object NoopShell : RootShellExecutor {
        override suspend fun exec(command: String, timeoutSeconds: Long): ShellResult = ShellResult(0, "", "")
    }
}
