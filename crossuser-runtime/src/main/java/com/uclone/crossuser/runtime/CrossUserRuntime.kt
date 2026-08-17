package com.uclone.crossuser.runtime

import android.view.Surface
import com.uclone.crossuser.api.CrossUserCapabilityReport
import com.uclone.crossuser.api.CrossUserKeyPacket
import com.uclone.crossuser.api.CrossUserProbeRequest
import com.uclone.crossuser.api.CrossUserSessionConfig
import com.uclone.crossuser.api.CrossUserSessionState
import com.uclone.crossuser.api.CrossUserTouchPacket

interface CrossUserRuntime {
    suspend fun probe(request: CrossUserProbeRequest): CrossUserCapabilityReport

    suspend fun createSession(
        config: CrossUserSessionConfig,
        surface: Surface,
        callback: CrossUserRuntimeCallback,
    ): CrossUserSessionState

    suspend fun replaceSurface(
        sessionId: String,
        surface: Surface,
        widthPx: Int,
        heightPx: Int,
        densityDpi: Int,
        rotation: Int,
    ): CrossUserSessionState

    suspend fun injectTouch(sessionId: String, packet: CrossUserTouchPacket): Boolean

    suspend fun injectKey(sessionId: String, packet: CrossUserKeyPacket): Boolean

    suspend fun getSession(sessionId: String): CrossUserSessionState?

    suspend fun closeSession(sessionId: String, reason: Int): CrossUserSessionState?
}

interface CrossUserRuntimeCallback {
    fun onStateChanged(state: CrossUserSessionState)
    fun onFirstFrame(sessionId: String, monotonicNanos: Long) = Unit
    fun onTaskChanged(sessionId: String, taskId: Int, packageName: String) = Unit
    fun onRecoverableError(sessionId: String, code: String, message: String) = Unit
    fun onTerminal(sessionId: String, code: String, message: String) = Unit
}

object CrossUserRuntimeErrors {
    const val FEATURE_VISIBLE_USERS_DISABLED = "FEATURE_VISIBLE_USERS_DISABLED"
    const val VISIBLE_USER_CALL_DENIED = "VISIBLE_USER_CALL_DENIED"
    const val DISPLAY_CREATE_DENIED = "DISPLAY_CREATE_DENIED"
    const val DISPLAY_ASSIGNMENT_FAILED = "DISPLAY_ASSIGNMENT_FAILED"
    const val TARGET_LAUNCH_FAILED = "TARGET_LAUNCH_FAILED"
    const val TARGET_PACKAGE_NOT_INSTALLED = "TARGET_PACKAGE_NOT_INSTALLED"
    const val TARGET_PACKAGE_DENIED = "TARGET_PACKAGE_DENIED"
    const val TARGET_USER_LOCKED = "TARGET_USER_LOCKED"
    const val INPUT_PERMISSION_DENIED = "INPUT_PERMISSION_DENIED"
    const val INPUT_TARGET_MISMATCH = "INPUT_TARGET_MISMATCH"
    const val SESSION_BUSY = "SESSION_BUSY"
    const val RESOURCE_CONFLICT = "RESOURCE_CONFLICT"
    const val SURFACE_INVALID = "SURFACE_INVALID"
    const val BRIDGE_DIED = "BRIDGE_DIED"
    const val CLEANUP_INCOMPLETE = "CLEANUP_INCOMPLETE"
    const val FRAMEWORK_SIGNATURE_MISMATCH = "FRAMEWORK_SIGNATURE_MISMATCH"
    const val UNAUTHORIZED_CALLER = "UNAUTHORIZED_CALLER"
}
