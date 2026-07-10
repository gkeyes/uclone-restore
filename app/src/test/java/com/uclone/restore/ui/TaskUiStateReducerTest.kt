package com.uclone.restore.ui

import com.uclone.restore.external.ExternalActionContract
import com.uclone.restore.model.AppEntry
import com.uclone.restore.model.CheckResult
import com.uclone.restore.model.EnvironmentStatus
import com.uclone.restore.model.RestoreBackupEntry
import com.uclone.restore.model.RiskLevel
import com.uclone.restore.model.TaskProgress
import com.uclone.restore.model.TaskRecord
import com.uclone.restore.model.TaskStatus
import com.uclone.restore.model.TaskType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskUiStateReducerTest {
    @Test
    fun progressMirrorsPersistentTaskLifecycle() {
        val accepted = task(TaskType.SWITCH_TO_CLONE_STATE, TaskStatus.ACCEPTED)
        val terminal = accepted.copy(status = TaskStatus.SUCCESS, message = "完成")

        val runningState = TaskUiStateReducer.progress(UiState(), TaskProgress(accepted))
        val finishedState = TaskUiStateReducer.progress(runningState, TaskProgress(terminal))

        assertTrue(runningState.busy)
        assertFalse(finishedState.busy)
        assertEquals("完成", finishedState.message)
        assertEquals(terminal, finishedState.currentTask.task)
    }

    @Test
    fun successfulResetClearsEveryWorkspaceDerivedUiField() {
        val app = AppEntry(
            packageName = "com.example.app",
            label = "Example",
            user0Installed = true,
            user10Installed = true,
            user0Uid = 10123,
            user10Uid = 1010123,
            isSystemApp = false,
            riskLevel = RiskLevel.NORMAL,
            lastSnapshotAt = 1234,
            snapshotSizeKb = 2048,
            lastRestoreAt = 5678,
        )
        val backup = RestoreBackupEntry(
            packageName = app.packageName,
            rollbackId = "rollback-1",
            createdAt = 1234,
            sizeKb = 100,
            reason = "test",
            isActiveSwitchBackup = true,
        )
        val initial = UiState(
            apps = listOf(app),
            busy = true,
            history = listOf(task(TaskType.RESTORE_SNAPSHOT_TO_MAIN, TaskStatus.SUCCESS)),
            rollbackIds = listOf("rollback-1"),
            restoreBackups = listOf(backup),
            cloneRollbackBackups = listOf(backup.copy(isCloneRollback = true)),
            switchRollbackIds = mapOf(app.packageName to "rollback-1"),
        )
        val reset = task(TaskType.RESET_WORKSPACE, TaskStatus.SUCCESS)
        val refreshed = TaskUiStateReducer.refreshed(
            initial,
            reset,
            TaskRefreshSnapshot(
                environment = environment(),
                history = emptyList(),
                restoreBackups = emptyList(),
                cloneRollbackBackups = emptyList(),
                switchRollbackIds = emptyMap(),
            ),
        )

        assertFalse(refreshed.busy)
        assertEquals(emptyList(), refreshed.history)
        assertEquals(emptyList(), refreshed.rollbackIds)
        assertEquals(emptyList(), refreshed.restoreBackups)
        assertEquals(emptyList(), refreshed.cloneRollbackBackups)
        assertEquals(emptyMap(), refreshed.switchRollbackIds)
        assertNull(refreshed.apps.single().lastSnapshotAt)
        assertNull(refreshed.apps.single().snapshotSizeKb)
        assertNull(refreshed.apps.single().lastRestoreAt)
    }

    @Test
    fun everyUiActionHasAUniqueExternalOperation() {
        assertEquals(UiTaskAction.entries.size, UiTaskAction.entries.map { it.operation }.toSet().size)
        assertTrue(UiTaskAction.entries.any { it.operation == ExternalActionContract.OPERATION_RESET_WORKSPACE })
        assertTrue(UiTaskAction.entries.any { it.operation == ExternalActionContract.OPERATION_RESTORE_FROM_CLONE_LATEST })
    }

    private fun task(type: TaskType, status: TaskStatus) = TaskRecord(
        id = 1,
        requestId = "request-1",
        packageName = "com.example.app",
        type = type,
        startedAt = 100,
        finishedAt = if (status.isTerminal) 200 else null,
        status = status,
        logPath = "/tmp/task.log",
        message = status.name,
    )

    private fun environment() = EnvironmentStatus(
        root = CheckResult(true, "root"),
        currentUser = "0",
        user0Present = true,
        user10Present = true,
        user10State = "User is not started: 10",
        dataAdbWritable = CheckResult(true, "ok"),
        snapshotDirReady = CheckResult(true, "ok"),
    )
}
