package com.uclone.restore.crossuser.session

import com.uclone.restore.crossuser.domain.CrossUserErrorCode
import com.uclone.restore.crossuser.domain.CrossUserSessionConfig
import com.uclone.restore.crossuser.domain.CrossUserSessionHandle
import com.uclone.restore.crossuser.domain.CrossUserSessionSnapshot
import com.uclone.restore.crossuser.domain.CrossUserSessionState
import com.uclone.restore.crossuser.domain.CrossUserStateTransition

class CrossUserSessionStateMachine(
    private val handle: CrossUserSessionHandle,
    private val config: CrossUserSessionConfig,
    private val clockMillis: () -> Long,
) {
    private val createdAtMillis = clockMillis()
    private val transitions = mutableListOf<CrossUserStateTransition>()
    private var state: CrossUserSessionState = CrossUserSessionState.IDLE
    private var updatedAtMillis: Long = createdAtMillis
    private var displayId: Int? = null
    private var taskId: Int? = null
    private var lastErrorCode: CrossUserErrorCode? = null

    fun snapshot(): CrossUserSessionSnapshot = CrossUserSessionSnapshot(
        handle = handle,
        config = config,
        state = state,
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis,
        displayId = displayId,
        taskId = taskId,
        lastErrorCode = lastErrorCode,
        transitions = transitions.toList(),
    )

    fun transitionTo(
        next: CrossUserSessionState,
        reason: String,
        displayId: Int? = this.displayId,
        taskId: Int? = this.taskId,
        errorCode: CrossUserErrorCode? = null,
    ): CrossUserSessionSnapshot {
        require(reason.isNotBlank()) { "transition reason required" }
        if (state == CrossUserSessionState.CLOSED && next == CrossUserSessionState.CLOSED) {
            return snapshot()
        }
        require(canTransition(state, next)) { "Illegal cross-user transition: $state -> $next" }
        val now = clockMillis()
        transitions += CrossUserStateTransition(
            from = state,
            to = next,
            monotonicMillis = now,
            reason = reason,
        )
        state = next
        updatedAtMillis = now
        this.displayId = displayId
        this.taskId = taskId
        if (errorCode != null) lastErrorCode = errorCode
        return snapshot()
    }

    fun close(reason: String): CrossUserSessionSnapshot {
        require(reason.isNotBlank()) { "close reason required" }
        if (state == CrossUserSessionState.CLOSED) return snapshot()
        if (state != CrossUserSessionState.CLOSING) {
            transitionTo(CrossUserSessionState.CLOSING, reason)
        }
        return transitionTo(CrossUserSessionState.CLOSED, "cleanup_complete")
    }

    companion object {
        private val Allowed = mapOf(
            CrossUserSessionState.IDLE to setOf(CrossUserSessionState.PRECHECKING),
            CrossUserSessionState.PRECHECKING to setOf(
                CrossUserSessionState.SURFACE_READY,
                CrossUserSessionState.UNSUPPORTED,
                CrossUserSessionState.FAILED,
            ),
            CrossUserSessionState.SURFACE_READY to setOf(
                CrossUserSessionState.DISPLAY_CREATING,
                CrossUserSessionState.SURFACE_LOST,
                CrossUserSessionState.FAILED,
            ),
            CrossUserSessionState.DISPLAY_CREATING to setOf(
                CrossUserSessionState.DISPLAY_READY,
                CrossUserSessionState.DISPLAY_REMOVED,
                CrossUserSessionState.FAILED,
            ),
            CrossUserSessionState.DISPLAY_READY to setOf(
                CrossUserSessionState.USER_STARTING,
                CrossUserSessionState.DISPLAY_REMOVED,
                CrossUserSessionState.FAILED,
            ),
            CrossUserSessionState.USER_STARTING to setOf(
                CrossUserSessionState.USER_VISIBLE_LOCKED,
                CrossUserSessionState.USER_VISIBLE_UNLOCKED,
                CrossUserSessionState.USER_STOPPED,
                CrossUserSessionState.FAILED,
            ),
            CrossUserSessionState.USER_VISIBLE_LOCKED to setOf(
                CrossUserSessionState.USER_UNLOCKING,
                CrossUserSessionState.USER_STOPPED,
                CrossUserSessionState.FAILED,
            ),
            CrossUserSessionState.USER_UNLOCKING to setOf(
                CrossUserSessionState.USER_VISIBLE_UNLOCKED,
                CrossUserSessionState.USER_STOPPED,
                CrossUserSessionState.FAILED,
            ),
            CrossUserSessionState.USER_VISIBLE_UNLOCKED to setOf(
                CrossUserSessionState.TARGET_LAUNCHING,
                CrossUserSessionState.USER_STOPPED,
                CrossUserSessionState.FAILED,
            ),
            CrossUserSessionState.TARGET_LAUNCHING to setOf(
                CrossUserSessionState.WAITING_FIRST_FRAME,
                CrossUserSessionState.TARGET_DIED,
                CrossUserSessionState.FAILED,
            ),
            CrossUserSessionState.WAITING_FIRST_FRAME to setOf(
                CrossUserSessionState.ACTIVE,
                CrossUserSessionState.SECURE_CONTENT_BLOCKED,
                CrossUserSessionState.TARGET_DIED,
                CrossUserSessionState.FAILED,
            ),
            CrossUserSessionState.ACTIVE to setOf(
                CrossUserSessionState.HOST_BACKGROUND,
                CrossUserSessionState.BRIDGE_DIED,
                CrossUserSessionState.SURFACE_LOST,
                CrossUserSessionState.DISPLAY_REMOVED,
                CrossUserSessionState.USER_STOPPED,
                CrossUserSessionState.TARGET_DIED,
                CrossUserSessionState.RECOVERY_REQUIRED,
                CrossUserSessionState.FAILED,
            ),
            CrossUserSessionState.HOST_BACKGROUND to setOf(
                CrossUserSessionState.ACTIVE,
                CrossUserSessionState.STALE_SESSION,
                CrossUserSessionState.SURFACE_LOST,
                CrossUserSessionState.FAILED,
            ),
            CrossUserSessionState.CLOSING to setOf(CrossUserSessionState.CLOSED),
        )

        private val CleanupStates = CrossUserSessionState.entries
            .filterNot { it == CrossUserSessionState.CLOSED || it == CrossUserSessionState.CLOSING }
            .toSet()

        fun canTransition(from: CrossUserSessionState, to: CrossUserSessionState): Boolean {
            if (to == CrossUserSessionState.CLOSING && from in CleanupStates) return true
            return Allowed[from]?.contains(to) == true
        }

    }
}
