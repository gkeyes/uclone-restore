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
    private val displayFactory: CrossUserDisplayFactory by lazy {
        AndroidCrossUserDisplayFactory(this)
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
            callback: ICrossUserCallback,
        ): CrossUserSessionHandle {
            enforceCaller()
            CrossUserSessionValidator.requireValid(config)
            require(surface.isValid) { "invalid Surface" }
            synchronized(monitor) {
                check(!creating && active == null) { CrossUserRuntimeErrors.SESSION_BUSY }
                creating = true
            }
            var display: CrossUserDisplayHandle? = null
            var registeredSession: SessionRecord? = null
            try {
                val createdDisplay = displayFactory.create(config, surface)
                display = createdDisplay
                val handle = CrossUserSessionHandle(
                    sessionId = randomToken(),
                    sessionToken = randomToken(),
                )
                val state = CrossUserSessionState(
                    sessionId = handle.sessionId,
                    state = CrossUserProtocol.SESSION_DISPLAY_READY,
                    displayId = createdDisplay.displayId,
                    taskId = -1,
                    mainUserId = config.mainUserId,
                    targetUserId = config.targetUserId,
                    packageName = config.packageName,
                    userWasRunning = false,
                    userStartedBySession = false,
                    message = "Trusted display ${createdDisplay.displayId} ready",
                )
                val deathRecipient = IBinder.DeathRecipient {
                    closeInternal(handle.sessionId, CALLBACK_DIED_REASON)
                }
                val record = SessionRecord(handle, config, state, callback, deathRecipient, createdDisplay)
                synchronized(monitor) {
                    active = record
                    registeredSession = record
                    creating = false
                }
                callback.asBinder().linkToDeath(deathRecipient, 0)
                check(callback.asBinder().isBinderAlive && synchronized(monitor) { active === record }) {
                    CrossUserRuntimeErrors.BRIDGE_DIED
                }
                callback.safeState(state)
                return handle
            } catch (error: Throwable) {
                synchronized(monitor) {
                    if (active === registeredSession) active = null
                    creating = false
                }
                runCatching { display?.release() }
                callback.safeTerminal(
                    sessionId = "pending",
                    code = (error as? FrameworkCallException)?.errorCode ?: CrossUserRuntimeErrors.DISPLAY_ASSIGNMENT_FAILED,
                    message = error.message ?: "Cross-user session failed",
                )
                throw error
            }
        }

        override fun startVisibleUser(sessionId: String, sessionToken: String): CrossUserSessionState {
            enforceCaller()
            val session = requireSession(sessionId, sessionToken)
            check(session.state.state == CrossUserProtocol.SESSION_DISPLAY_READY) {
                CrossUserRuntimeErrors.SESSION_BUSY
            }
            updateState(
                session,
                session.state.copy(
                    state = CrossUserProtocol.SESSION_USER_STARTING,
                    errorCode = null,
                    message = "Starting user${session.config.targetUserId} on display ${session.state.displayId}",
                ),
            )
            return try {
                val receipt = framework.startVisibleUser(session.config, session.state.displayId)
                if (synchronized(monitor) { active !== session }) {
                    runCatching {
                        framework.closeSession(
                            session.config,
                            receipt.userStartedBySession,
                            targetLaunchRequested = false,
                        )
                    }
                    error(CrossUserRuntimeErrors.SESSION_CLOSED)
                }
                updateState(
                    session,
                    session.state.copy(
                        state = if (receipt.userUnlocked) {
                            CrossUserProtocol.SESSION_USER_VISIBLE_UNLOCKED
                        } else {
                            CrossUserProtocol.SESSION_USER_VISIBLE_LOCKED
                        },
                        userWasRunning = receipt.userWasRunning,
                        userStartedBySession = receipt.userStartedBySession,
                        errorCode = null,
                        message = receipt.message,
                    ),
                )
            } catch (error: Throwable) {
                failAndClose(session, error, CrossUserRuntimeErrors.DISPLAY_ASSIGNMENT_FAILED)
                throw error
            }
        }

        override fun launchTarget(sessionId: String, sessionToken: String): CrossUserSessionState {
            enforceCaller()
            val session = requireSession(sessionId, sessionToken)
            check(
                session.state.state == CrossUserProtocol.SESSION_USER_VISIBLE_LOCKED ||
                    session.state.state == CrossUserProtocol.SESSION_USER_VISIBLE_UNLOCKED,
            ) { CrossUserRuntimeErrors.SESSION_BUSY }
            synchronized(monitor) { session.targetLaunchRequested = true }
            updateState(
                session,
                session.state.copy(
                    state = CrossUserProtocol.SESSION_TARGET_LAUNCHING,
                    errorCode = null,
                    message = "Launching ${session.config.packageName} for user${session.config.targetUserId}",
                ),
            )
            return try {
                val message = framework.launchTarget(session.config, session.state.displayId)
                if (synchronized(monitor) { active !== session }) {
                    runCatching {
                        framework.closeSession(
                            session.config,
                            session.state.userStartedBySession,
                            targetLaunchRequested = true,
                        )
                    }
                    error(CrossUserRuntimeErrors.SESSION_CLOSED)
                }
                updateState(
                    session,
                    session.state.copy(
                        state = CrossUserProtocol.SESSION_WAITING_FIRST_FRAME,
                        errorCode = null,
                        message = message,
                    ),
                )
            } catch (error: Throwable) {
                failAndClose(session, error, CrossUserRuntimeErrors.TARGET_LAUNCH_FAILED)
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
            session.display.replaceSurface(surface, widthPx, heightPx, densityDpi)
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
            val componentName = requireNotNull(CrossUserProtocol.componentForPackage(state.packageName)) {
                CrossUserRuntimeErrors.TARGET_PACKAGE_DENIED
            }
            synchronized(monitor) {
                check(!creating && active == null) { CrossUserRuntimeErrors.SESSION_BUSY }
            }
            val config = CrossUserSessionConfig(
                packageName = state.packageName,
                componentName = componentName,
                mainUserId = 0,
                targetUserId = 10,
                widthPx = 1080,
                heightPx = 2400,
                densityDpi = 440,
                rotation = 0,
            )
            return runCatching {
                framework.closeSession(
                    config,
                    state.userStartedBySession,
                    targetLaunchRequested = state.mayHaveLaunchedTarget(),
                )
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

    private fun updateState(session: SessionRecord, state: CrossUserSessionState): CrossUserSessionState {
        synchronized(monitor) {
            check(active === session) { CrossUserRuntimeErrors.SESSION_CLOSED }
            session.state = state
        }
        session.callback.safeState(state)
        return state
    }

    private fun failAndClose(session: SessionRecord, error: Throwable, fallbackCode: String) {
        if (synchronized(monitor) { active !== session }) return
        val code = (error as? FrameworkCallException)?.errorCode ?: fallbackCode
        runCatching {
            updateState(
                session,
                session.state.copy(
                    state = CrossUserProtocol.SESSION_FAILED,
                    errorCode = code,
                    message = error.message ?: code,
                ),
            )
        }
        closeInternal(session.handle.sessionId, OPERATION_FAILED_REASON)
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
        session.state = closing
        session.callback.safeState(closing)
        val cleanup = runCatching {
            framework.closeSession(
                session.config,
                session.state.userStartedBySession,
                session.targetLaunchRequested,
            )
        }
        val displayCleanup = runCatching { session.display.release() }
        val cleanupError = cleanup.exceptionOrNull() ?: displayCleanup.exceptionOrNull()
        val cleanupMessage = when {
            cleanup.exceptionOrNull() != null -> cleanup.exceptionOrNull()?.message ?: "Target cleanup failed"
            displayCleanup.exceptionOrNull() != null ->
                displayCleanup.exceptionOrNull()?.message ?: "Display cleanup failed"
            else -> cleanup.getOrNull()?.joinToString().orEmpty().ifBlank { "Session closed" }
        }
        val closed = closing.copy(
            state = if (cleanupError == null) {
                CrossUserProtocol.SESSION_CLOSED
            } else {
                CrossUserProtocol.SESSION_RECOVERY_REQUIRED
            },
            errorCode = cleanupError?.let { CrossUserRuntimeErrors.CLEANUP_INCOMPLETE } ?: closing.errorCode,
            message = cleanupMessage,
        )
        session.state = closed
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
        @Volatile var state: CrossUserSessionState,
        val callback: ICrossUserCallback,
        val deathRecipient: IBinder.DeathRecipient,
        val display: CrossUserDisplayHandle,
        var targetLaunchRequested: Boolean = false,
        val createdAtNanos: Long = SystemClock.elapsedRealtimeNanos(),
    )

    private companion object {
        const val CALLBACK_DIED_REASON = 2
        const val SERVICE_DESTROYED_REASON = 3
        const val OPERATION_FAILED_REASON = 4
    }
}

private fun CrossUserSessionState.mayHaveLaunchedTarget(): Boolean = state in setOf(
    CrossUserProtocol.SESSION_TARGET_LAUNCHING,
    CrossUserProtocol.SESSION_WAITING_FIRST_FRAME,
    CrossUserProtocol.SESSION_ACTIVE,
)

private fun ICrossUserCallback.safeState(state: CrossUserSessionState) {
    runCatching { onStateChanged(state) }
}

private fun ICrossUserCallback.safeRecoverable(sessionId: String, code: String, message: String) {
    runCatching { onRecoverableError(sessionId, code, message) }
}

private fun ICrossUserCallback.safeTerminal(sessionId: String, code: String, message: String) {
    runCatching { onTerminal(sessionId, code, message) }
}
