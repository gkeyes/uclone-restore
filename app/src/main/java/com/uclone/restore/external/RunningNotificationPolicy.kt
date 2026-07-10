package com.uclone.restore.external

import com.uclone.restore.model.StepStatus
import com.uclone.restore.model.TaskProgress

internal data class RunningNotificationUpdate(
    val message: String,
    val stageKey: String?,
    val isTerminal: Boolean,
)

internal class RunningNotificationPolicy(
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private var lastDisplayed: RunningNotificationUpdate? = null
    private var lastDisplayedAt: Long? = null

    init {
        require(intervalMs >= 0)
    }

    @Synchronized
    fun recordDisplayed(update: RunningNotificationUpdate) {
        lastDisplayed = update
        lastDisplayedAt = nowMs()
    }

    @Synchronized
    fun shouldNotify(update: RunningNotificationUpdate): Boolean {
        val previous = lastDisplayed
        if (update == previous) return false

        val now = nowMs()
        val stageChanged = previous != null && update.stageKey != previous.stageKey
        val elapsed = lastDisplayedAt?.let { now - it }
        val shouldNotify = previous == null || update.isTerminal || stageChanged || elapsed == null || elapsed >= intervalMs
        if (shouldNotify) {
            lastDisplayed = update
            lastDisplayedAt = now
        }
        return shouldNotify
    }

    private companion object {
        const val DEFAULT_INTERVAL_MS = 350L
    }
}

internal fun TaskProgress.toRunningNotificationUpdate(): RunningNotificationUpdate {
    val currentStage = task?.currentStage
    val runningStepIndex = steps.indexOfFirst { it.status == StepStatus.RUNNING }
    val runningStep = steps.getOrNull(runningStepIndex)
    val isTerminal = task?.status?.isTerminal == true
    val message = when {
        isTerminal -> requireNotNull(task).message
        currentStage != null -> currentStage.displayLabel
        runningStep != null -> runningStep.label
        else -> liveLog.lineSequence().map(String::trim).firstOrNull(String::isNotBlank) ?: "执行中"
    }
    return RunningNotificationUpdate(
        message = message,
        stageKey = currentStage?.let { "stage:${it.name}" }
            ?: runningStepIndex.takeIf { it >= 0 }?.let { "step:$it" },
        isTerminal = isTerminal,
    )
}
