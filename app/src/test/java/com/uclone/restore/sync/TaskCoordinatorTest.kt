package com.uclone.restore.sync

import com.uclone.restore.model.TaskStatus
import com.uclone.restore.model.TaskProgress
import com.uclone.restore.model.TaskType
import com.uclone.restore.root.RootShellExecutor
import com.uclone.restore.root.ShellResult
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

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

    @Test
    fun shortStateMutationCannotRaceAnAcceptedTask() {
        val repository = TaskLogStore(NoopShell)
        val coordinator = TaskCoordinator(repository)
        var mutations = 0

        assertTrue(coordinator.tryRunWhileIdle { mutations += 1 })
        coordinator.accept("request-1", TaskType.SWITCH_TO_CLONE_STATE, "com.example.app")
        assertFalse(coordinator.tryRunWhileIdle { mutations += 1 })
        coordinator.complete("request-1")
        assertTrue(coordinator.tryRunWhileIdle { mutations += 1 })

        assertEquals(2, mutations)
    }

    @Test
    fun persistedAcceptedRequestCannotBeReacceptedAfterStoreRecreation() {
        val historyFile = Files.createTempDirectory("uclone-coordinator-restart")
            .resolve("task_history_v2.jsonl")
            .toFile()
        TaskLogStore(NoopShell, historyFile).accepted(
            type = TaskType.SWITCH_TO_CLONE_STATE,
            packageName = "com.example.app",
            requestId = "persisted-request",
        )
        val restartedRepository = TaskLogStore(NoopShell, historyFile)
        val restartedCoordinator = TaskCoordinator(restartedRepository)

        val duplicate = restartedCoordinator.accept(
            requestId = "persisted-request",
            type = TaskType.SWITCH_TO_CLONE_STATE,
            packageName = "com.example.app",
        )

        assertIs<TaskSubmissionResult.AlreadyCompleted>(duplicate)
        assertEquals(TaskStatus.INTERRUPTED, duplicate.record.status)
        assertEquals(1, restartedRepository.all().size)
    }

    private object NoopShell : RootShellExecutor {
        override suspend fun exec(command: String, timeoutSeconds: Long): ShellResult = ShellResult(0, "", "")
    }
}
