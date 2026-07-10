package com.uclone.restore.external

import com.uclone.restore.sync.TaskCoordinator
import com.uclone.restore.sync.TaskLogStore
import com.uclone.restore.root.RootShellExecutor
import com.uclone.restore.root.ShellResult
import com.uclone.restore.model.TaskStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

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
            ExternalActionContract.OPERATION_DELETE_SNAPSHOT,
            ExternalActionContract.OPERATION_DELETE_RESTORE_BACKUP,
            ExternalActionContract.OPERATION_PROBE_CLONE_CE,
            ExternalActionContract.OPERATION_UNLOCK_CLONE,
            ExternalActionContract.OPERATION_DEBUG_CLONE_SYSTEM,
            ExternalActionContract.OPERATION_AUDIT_RESTORE,
            ExternalActionContract.OPERATION_CLEAR_LOGS,
            ExternalActionContract.OPERATION_RESET_WORKSPACE,
            ExternalActionContract.OPERATION_START_CLONE_USER,
            ExternalActionContract.OPERATION_SWITCH_TO_CLONE_USER,
            ExternalActionContract.OPERATION_STOP_CLONE_USER,
        ).forEach { operation ->
            assertEquals("模块不允许执行此操作", module.copy(operation = operation).sourceOperationRejection(), operation)
        }
        assertEquals(null, shortcut.sourceOperationRejection())
        assertEquals(
            "桌面快捷入口不允许执行此操作",
            shortcut.copy(operation = ExternalActionContract.OPERATION_BACKUP_DEFAULT).sourceOperationRejection(),
        )
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

    private fun request(source: String, token: String?, requestId: String = "request-1") = ExternalActionRequest(
        operation = ExternalActionContract.OPERATION_SWITCH_OR_RESTORE,
        packageName = "com.example.app",
        requestId = requestId,
        source = source,
        rollbackId = null,
        internalToken = token,
    )

    private object NoopShell : RootShellExecutor {
        override suspend fun exec(command: String, timeoutSeconds: Long): ShellResult = ShellResult(0, "", "")
    }
}
