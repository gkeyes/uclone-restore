package com.uclone.restore.sync

import com.uclone.restore.model.TaskStatus
import com.uclone.restore.model.TaskProgress
import com.uclone.restore.model.TaskType
import com.uclone.restore.root.RootShellExecutor
import com.uclone.restore.root.ShellResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TaskCoordinatorTest {
    @Test
    fun duplicateRequestIsAlreadyRunningAndDifferentRequestIsBusy() {
        val repository = TaskLogStore(NoopShell)
        val coordinator = TaskCoordinator(repository)

        val accepted = coordinator.accept("request-1", TaskType.SWITCH_TO_CLONE_STATE, "com.example.app")
        val duplicate = coordinator.accept("request-1", TaskType.SWITCH_TO_CLONE_STATE, "com.example.app")
        val busy = coordinator.accept("request-2", TaskType.PUSH_MAIN_TO_CLONE, "com.other.app")

        assertIs<TaskSubmissionResult.Accepted>(accepted)
        assertIs<TaskSubmissionResult.AlreadyRunning>(duplicate)
        assertIs<TaskSubmissionResult.Busy>(busy)
        assertEquals(1, repository.all().size)
        assertEquals(TaskStatus.ACCEPTED, repository.all().single().status)
    }

    @Test
    fun completedRequestReleasesCoordinatorForNextTask() {
        val repository = TaskLogStore(NoopShell)
        val coordinator = TaskCoordinator(repository)
        coordinator.accept("request-1", TaskType.SWITCH_TO_CLONE_STATE, "com.example.app")

        coordinator.complete("request-1")

        assertIs<TaskSubmissionResult.Accepted>(
            coordinator.accept("request-2", TaskType.PUSH_MAIN_TO_CLONE, "com.other.app"),
        )
    }

    @Test
    fun runningTransitionReusesAcceptedHistoryRecord() {
        val repository = TaskLogStore(NoopShell)
        repository.accepted(TaskType.SWITCH_TO_CLONE_STATE, "com.example.app", "request-1")

        val running = repository.running(
            TaskType.SWITCH_TO_CLONE_STATE,
            "com.example.app",
            "/data/adb/uclone/logs/task.log",
            "request-1",
        )

        assertEquals(1, repository.all().size)
        assertEquals(TaskStatus.RUNNING, running.status)
        assertEquals(running.id, repository.all().single().id)
    }

    @Test
    fun failureBecomesTerminalButLockReleasesOnlyOnComplete() {
        val repository = TaskLogStore(NoopShell)
        val coordinator = TaskCoordinator(repository)
        coordinator.accept("request-1", TaskType.SWITCH_TO_CLONE_STATE, "com.example.app")

        val failed = coordinator.fail("request-1", "boom")

        assertEquals(TaskStatus.FAILED, failed?.status)
        assertIs<TaskSubmissionResult.Busy>(
            coordinator.accept("request-2", TaskType.PUSH_MAIN_TO_CLONE, "com.other.app"),
        )
        coordinator.complete("request-1")
        assertIs<TaskSubmissionResult.Accepted>(
            coordinator.accept("request-2", TaskType.PUSH_MAIN_TO_CLONE, "com.other.app"),
        )
    }

    @Test
    fun historyCanBeClearedWithoutLosingTerminalProgress() {
        val repository = TaskLogStore(NoopShell)
        val record = repository.accepted(TaskType.CLEAR_LOGS, "logs", "request-1")
        repository.publish(TaskProgress(record))

        repository.clearHistoryPreservingProgress()

        assertEquals(emptyList(), repository.all())
        assertEquals("request-1", repository.progress.value.task?.requestId)
    }

    @Test
    fun startupFailureIsPersistedAndReleasesCoordinatorAtomically() {
        val repository = TaskLogStore(NoopShell)
        val coordinator = TaskCoordinator(repository)
        coordinator.accept("request-1", TaskType.SWITCH_TO_CLONE_STATE, "com.example.app")

        val failed = coordinator.failAndComplete("request-1", "foreground denied")

        assertEquals(TaskStatus.FAILED, failed?.status)
        assertEquals("foreground denied", repository.all().single().message)
        assertIs<TaskSubmissionResult.Accepted>(
            coordinator.accept("request-2", TaskType.PUSH_MAIN_TO_CLONE, "com.other.app"),
        )
    }

    private object NoopShell : RootShellExecutor {
        override suspend fun exec(command: String, timeoutSeconds: Long): ShellResult = ShellResult(0, "", "")
    }
}
