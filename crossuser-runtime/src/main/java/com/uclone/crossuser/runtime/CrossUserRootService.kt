package com.uclone.crossuser.runtime

import android.os.Binder
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.view.Surface
import com.topjohnwu.superuser.ipc.RootService
import com.uclone.crossuser.api.CrossUserCapabilityReport
import com.uclone.crossuser.api.CrossUserKeyPacket
import com.uclone.crossuser.api.CrossUserProbeRequest
import com.uclone.crossuser.api.CrossUserProtocol
import com.uclone.crossuser.api.CrossUserSessionConfig
import com.uclone.crossuser.api.CrossUserSessionHandle
import com.uclone.crossuser.api.CrossUserSessionState
import com.uclone.crossuser.api.CrossUserTouchPacket
import com.uclone.crossuser.api.ICrossUserBridge
import com.uclone.crossuser.api.ICrossUserCallback
import java.security.SecureRandom

class CrossUserRootService : RootService() {
    private val monitor = Any()
    private val random = SecureRandom()
    private val framework: CrossUserFrameworkAdapter by lazy {
        ShellFrameworkAdapter(ProcessCrossUserCommandExecutor())
    }
    private val input: CrossUserInputInjector by lazy { AndroidInputInjector(this) }
    private var creating = false
    private var active: SessionRecord? = null

    private val binder = object : ICrossUserBridge.Stub() {
        override fun probe(request: CrossUserProbeRequest): CrossUserCapabilityReport {
            enforceCaller()
            return framework.probe(request)
        }

        override fun createSession(
            config: CrossUserSessionConfig,
            surface: Surface,
            displayId: Int,
            callback: ICrossUserCallback,
        ): CrossUserSessionHandle {
            enforceCaller()
            CrossUserSessionValidator.requireValid(config)
            require(surface.isValid) { "invalid Surface" }
            require(displayId > 0) { "secondary display required" }
            synchronized(monitor) {
                check(!creating && active == null) { CrossUserRuntimeErrors.SESSION_BUSY }
                creating = true
            }
            var receipt: FrameworkStartReceipt? = null
            try {
                val startReceipt = framework.startVisibleUserAndLaunch(config, displayId)
                receipt = startReceipt
                val handle = CrossUserSessionHandle(
                    sessionId = randomToken(),
                    sessionToken = randomToken(),
                )
                val state = CrossUserSessionState(
                    sessionId = handle.sessionId,
                    state = CrossUserProtocol.SESSION_WAITING_FIRST_FRAME,
                    displayId = displayId,
                    taskId = -1,
                    mainUserId = config.mainUserId,
                    targetUserId = config.targetUserId,
                    packageName = config.packageName,
                    userWasRunning = startReceipt.userWasRunning,
                    userStartedBySession = startReceipt.userStartedBySession,
                    message = startReceipt.message,
                )
                val deathRecipient = IBinder.DeathRecipient {
                    closeInternal(handle.sessionId, CALLBACK_DIED_REASON)
                }
                callback.asBinder().linkToDeath(deathRecipient, 0)
                synchronized(monitor) {
                    active = SessionRecord(handle, config, state, callback, deathRecipient)
                    creating = false
                }
                callback.safeState(state)
                return handle
            } catch (error: Throwable) {
                synchronized(monitor) { creating = false }
                receipt?.let {
                    runCatching { framework.closeTarget(config, it.userStartedBySession) }
                }
                callback.safeTerminal(
                    sessionId = "pending",
                    code = (error as? FrameworkCallException)?.errorCode ?: CrossUserRuntimeErrors.DISPLAY_ASSIGNMENT_FAILED,
                    message = error.message ?: "Cross-user session failed",
                )
                throw error
            }
        }

        override fun getSession(sessionId: String, sessionToken: String): CrossUserSessionState {
            enforceCaller()
            return requireSession(sessionId, sessionToken).state
        }

        override fun replaceSurface(
            sessionId: String,
            sessionToken: String,
            surface: Surface,
            widthPx: Int,
            heightPx: Int,
            densityDpi: Int,
            rotation: Int,
        ): CrossUserSessionState {
            enforceCaller()
            require(surface.isValid) { CrossUserRuntimeErrors.SURFACE_INVALID }
            val session = requireSession(sessionId, sessionToken)
            val updated = session.config.copy(
                widthPx = widthPx,
                heightPx = heightPx,
                densityDpi = densityDpi,
                rotation = rotation,
            )
            CrossUserSessionValidator.requireValid(updated)
            synchronized(monitor) { session.config = updated }
            return session.state
        }

        override fun injectTouch(
            sessionId: String,
            sessionToken: String,
            packet: CrossUserTouchPacket,
        ): Boolean {
            enforceCaller()
            val session = requireSession(sessionId, sessionToken)
            val result = runCatching {
                input.injectTouch(
                    packet = packet,
                    displayId = session.state.displayId,
                    widthPx = session.config.widthPx,
                    heightPx = session.config.heightPx,
                )
            }.getOrDefault(false)
            if (!result) {
                session.callback.safeRecoverable(
                    sessionId,
                    CrossUserRuntimeErrors.INPUT_PERMISSION_DENIED,
                    "Display-targeted touch injection was rejected",
                )
            }
            return result
        }

        override fun injectKey(
            sessionId: String,
            sessionToken: String,
            packet: CrossUserKeyPacket,
        ): Boolean {
            enforceCaller()
            val session = requireSession(sessionId, sessionToken)
            val result = runCatching {
                input.injectKey(packet, session.state.displayId)
            }.getOrDefault(false)
            if (!result) {
                session.callback.safeRecoverable(
                    sessionId,
                    CrossUserRuntimeErrors.INPUT_PERMISSION_DENIED,
                    "Display-targeted Back injection was rejected",
                )
            }
            return result
        }

        override fun closeSession(sessionId: String, sessionToken: String, reason: Int) {
            enforceCaller()
            val session = synchronized(monitor) { active }
            if (session == null) return
            require(session.handle.sessionId == sessionId && session.handle.sessionToken == sessionToken) {
                CrossUserRuntimeErrors.UNAUTHORIZED_CALLER
            }
            closeInternal(sessionId, reason)
        }

        override fun recoverStaleSession(state: CrossUserSessionState): Boolean {
            enforceCaller()
            require(state.schemaVersion == CrossUserProtocol.SCHEMA_VERSION)
            require(state.mainUserId == 0 && state.targetUserId == 10)
            require(state.packageName == CrossUserProtocol.PROBE_PACKAGE)
            synchronized(monitor) {
                check(!creating && active == null) { CrossUserRuntimeErrors.SESSION_BUSY }
            }
            val config = CrossUserSessionConfig(
                packageName = CrossUserProtocol.PROBE_PACKAGE,
                componentName = CrossUserProtocol.PROBE_COMPONENT,
                mainUserId = 0,
                targetUserId = 10,
                widthPx = 1080,
                heightPx = 2400,
                densityDpi = 440,
                rotation = 0,
            )
            return runCatching {
                framework.closeTarget(config, state.userStartedBySession)
            }.isSuccess
        }
    }

    override fun onBind(intent: android.content.Intent): IBinder = binder

    override fun onDestroy() {
        synchronized(monitor) { active?.handle?.sessionId }?.let {
            closeInternal(it, SERVICE_DESTROYED_REASON)
        }
        super.onDestroy()
    }

    private fun requireSession(sessionId: String, token: String): SessionRecord = synchronized(monitor) {
        active?.takeIf {
            it.handle.sessionId == sessionId && it.handle.sessionToken == token
        } ?: throw SecurityException(CrossUserRuntimeErrors.UNAUTHORIZED_CALLER)
    }

    private fun closeInternal(sessionId: String, reason: Int) {
        val session = synchronized(monitor) {
            active?.takeIf { it.handle.sessionId == sessionId }?.also { active = null }
        } ?: return
        runCatching { session.callback.asBinder().unlinkToDeath(session.deathRecipient, 0) }
        val closing = session.state.copy(
            state = CrossUserProtocol.SESSION_CLOSING,
            message = "Closing reason=$reason",
        )
        session.callback.safeState(closing)
        val cleanup = runCatching {
            framework.closeTarget(session.config, session.state.userStartedBySession)
        }
        val closed = closing.copy(
            state = if (cleanup.isSuccess) CrossUserProtocol.SESSION_CLOSED else CrossUserProtocol.SESSION_RECOVERY_REQUIRED,
            errorCode = cleanup.exceptionOrNull()?.let { CrossUserRuntimeErrors.CLEANUP_INCOMPLETE },
            message = cleanup.fold(
                onSuccess = { it.joinToString() },
                onFailure = { it.message ?: "Cleanup incomplete" },
            ),
        )
        session.callback.safeState(closed)
        session.callback.safeTerminal(
            sessionId,
            closed.errorCode ?: "CLOSED",
            closed.message,
        )
    }

    private fun enforceCaller() {
        val callingUid = Binder.getCallingUid()
        val expectedUid = runCatching {
            packageManager.getApplicationInfo(CrossUserProtocol.MAIN_APP_PACKAGE, 0).uid
        }.getOrDefault(-1)
        if (callingUid != expectedUid || expectedUid <= 0 || Process.myUid() != 0) {
            throw SecurityException(CrossUserRuntimeErrors.UNAUTHORIZED_CALLER)
        }
    }

    private fun randomToken(): String = ByteArray(16).also(random::nextBytes).joinToString("") {
        "%02x".format(it)
    }

    private data class SessionRecord(
        val handle: CrossUserSessionHandle,
        var config: CrossUserSessionConfig,
        val state: CrossUserSessionState,
        val callback: ICrossUserCallback,
        val deathRecipient: IBinder.DeathRecipient,
        val createdAtNanos: Long = SystemClock.elapsedRealtimeNanos(),
    )

    private companion object {
        const val CALLBACK_DIED_REASON = 2
        const val SERVICE_DESTROYED_REASON = 3
    }
}

private fun ICrossUserCallback.safeState(state: CrossUserSessionState) {
    runCatching { onStateChanged(state) }
}

private fun ICrossUserCallback.safeRecoverable(sessionId: String, code: String, message: String) {
    runCatching { onRecoverableError(sessionId, code, message) }
}

private fun ICrossUserCallback.safeTerminal(sessionId: String, code: String, message: String) {
    runCatching { onTerminal(sessionId, code, message) }
}
