package com.uclone.crossuser.runtime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Surface
import androidx.core.content.ContextCompat
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
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Direct-Surface backend: user0 owns the Surface while the uid-0 bridge owns
 * the trusted VirtualDisplay, user assignment, launch, input, and cleanup.
 */
class BridgeOwnedCrossUserRuntime(context: Context) : CrossUserRuntime {
    private val appContext = context.applicationContext
    private val journal = CrossUserSessionJournal(appContext)
    private val operationMutex = Mutex()
    private val monitor = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var binding: BridgeBinding? = null
    private var active: ClientSession? = null
    private var pendingIdleUnbind: Runnable? = null

    override suspend fun probe(request: CrossUserProbeRequest): CrossUserCapabilityReport =
        operationMutex.withLock {
            val bridge = requireBridge()
            try {
                recoverStaleSessionIfNeeded(bridge)
                withContext(Dispatchers.IO) { bridge.probe(request) }
            } finally {
                // createSession follows a successful Host probe immediately.
                // Delay the idle unbind so both calls use one live RootService,
                // while a probe-only flow still releases root deterministically.
                scheduleBridgeReleaseIfIdle()
            }
        }

    override suspend fun createSession(
        config: CrossUserSessionConfig,
        surface: Surface,
        callback: CrossUserRuntimeCallback,
    ): CrossUserSessionState = operationMutex.withLock {
        CrossUserSessionValidator.requireValid(config)
        require(surface.isValid) { CrossUserRuntimeErrors.SURFACE_INVALID }
        check(synchronized(monitor) { active == null }) { CrossUserRuntimeErrors.SESSION_BUSY }
        val bridge = requireBridge()
        recoverStaleSessionIfNeeded(bridge)
        val latestState = AtomicReference<CrossUserSessionState?>()
        val callbackBinder = object : ICrossUserCallback.Stub() {
            override fun onStateChanged(state: CrossUserSessionState) {
                latestState.set(state)
                journal.write(state)
                synchronized(monitor) { active?.state = state }
                callback.onStateChanged(state)
            }

            override fun onFirstFrame(sessionId: String, monotonicNanos: Long) {
                callback.onFirstFrame(sessionId, monotonicNanos)
            }

            override fun onTaskChanged(sessionId: String, taskId: Int, packageName: String) {
                callback.onTaskChanged(sessionId, taskId, packageName)
            }

            override fun onRecoverableError(sessionId: String, code: String, message: String) {
                callback.onRecoverableError(sessionId, code, message)
            }

            override fun onTerminal(sessionId: String, code: String, message: String) {
                callback.onTerminal(sessionId, code, message)
            }
        }
        try {
            val handle = withContext(Dispatchers.IO) {
                bridge.createSession(
                    config,
                    surface,
                    callbackBinder,
                )
            }
            val state = withContext(Dispatchers.IO) {
                bridge.getSession(handle.sessionId, handle.sessionToken)
            }
            val session = ClientSession(
                handle = handle,
                config = config,
                callback = callback,
                callbackBinder = callbackBinder,
                state = latestState.get() ?: state,
            )
            synchronized(monitor) { active = session }
            journal.write(session.state)
            session.state
        } catch (error: Throwable) {
            releaseBridgeIfIdle()
            throw error
        }
    }

    override suspend fun replaceSurface(
        sessionId: String,
        surface: Surface,
        widthPx: Int,
        heightPx: Int,
        densityDpi: Int,
        rotation: Int,
    ): CrossUserSessionState = operationMutex.withLock {
        require(surface.isValid) { CrossUserRuntimeErrors.SURFACE_INVALID }
        val session = requireClientSession(sessionId)
        val updatedConfig = session.config.copy(
            widthPx = widthPx,
            heightPx = heightPx,
            densityDpi = densityDpi,
            rotation = rotation,
        )
        CrossUserSessionValidator.requireValid(updatedConfig)
        val bridge = requireNotNull(synchronized(monitor) { binding?.bridge }) {
            CrossUserRuntimeErrors.BRIDGE_DIED
        }
        val state = withContext(Dispatchers.IO) {
            bridge.replaceSurface(
                session.handle.sessionId,
                session.handle.sessionToken,
                surface,
                widthPx,
                heightPx,
                densityDpi,
                rotation,
            )
        }
        session.config = updatedConfig
        session.state = state
        journal.write(state)
        state
    }

    override suspend fun injectTouch(sessionId: String, packet: CrossUserTouchPacket): Boolean =
        operationMutex.withLock {
            val session = requireClientSession(sessionId)
            val bridge = requireNotNull(synchronized(monitor) { binding?.bridge }) {
                CrossUserRuntimeErrors.BRIDGE_DIED
            }
            withContext(Dispatchers.IO) {
                bridge.injectTouch(session.handle.sessionId, session.handle.sessionToken, packet)
            }
        }

    override suspend fun injectKey(sessionId: String, packet: CrossUserKeyPacket): Boolean =
        operationMutex.withLock {
            val session = requireClientSession(sessionId)
            val bridge = requireNotNull(synchronized(monitor) { binding?.bridge }) {
                CrossUserRuntimeErrors.BRIDGE_DIED
            }
            withContext(Dispatchers.IO) {
                bridge.injectKey(session.handle.sessionId, session.handle.sessionToken, packet)
            }
        }

    override suspend fun getSession(sessionId: String): CrossUserSessionState? = operationMutex.withLock {
        val session = synchronized(monitor) { active } ?: return@withLock null
        if (session.handle.sessionId != sessionId) return@withLock null
        val bridge = synchronized(monitor) { binding?.bridge } ?: return@withLock session.state
        runCatching {
            withContext(Dispatchers.IO) {
                bridge.getSession(session.handle.sessionId, session.handle.sessionToken)
            }
        }.getOrNull()?.also {
            session.state = it
            journal.write(it)
        } ?: session.state
    }

    override suspend fun closeSession(sessionId: String, reason: Int): CrossUserSessionState? =
        operationMutex.withLock {
            val session = synchronized(monitor) { active } ?: return@withLock null
            if (session.handle.sessionId != sessionId) return@withLock null
            val bridge = synchronized(monitor) { binding?.bridge }
            val cleanup = runCatching {
                requireNotNull(bridge) { CrossUserRuntimeErrors.BRIDGE_DIED }
                withContext(Dispatchers.IO) {
                    bridge.closeSession(session.handle.sessionId, session.handle.sessionToken, reason)
                }
            }
            val finalState = when {
                cleanup.isFailure -> session.state.copy(
                    state = CrossUserProtocol.SESSION_RECOVERY_REQUIRED,
                    errorCode = CrossUserRuntimeErrors.CLEANUP_INCOMPLETE,
                    message = cleanup.exceptionOrNull()?.message ?: "Session cleanup failed",
                )
                session.state.state == CrossUserProtocol.SESSION_CLOSED ||
                    session.state.state == CrossUserProtocol.SESSION_RECOVERY_REQUIRED -> session.state
                else -> session.state.copy(
                    state = CrossUserProtocol.SESSION_CLOSED,
                    errorCode = null,
                    message = "Session closed",
                )
            }
            synchronized(monitor) { active = null }
            if (finalState.state == CrossUserProtocol.SESSION_CLOSED) journal.clear() else journal.write(finalState)
            releaseBridgeIfIdle()
            finalState
        }

    private suspend fun requireBridge(): ICrossUserBridge {
        cancelScheduledBridgeRelease()
        synchronized(monitor) { binding?.bridge }?.let { return it }
        val connection = BridgeServiceConnection()
        val intent = Intent(appContext, CrossUserRootService::class.java)
        withContext(Dispatchers.Main.immediate) {
            RootService.bind(intent, ContextCompat.getMainExecutor(appContext), connection)
        }
        val bridge = try {
            withTimeout(BIND_TIMEOUT_MILLIS) { connection.connected.await() }
        } catch (error: Throwable) {
            unbind(connection)
            throw IllegalStateException(CrossUserRuntimeErrors.BRIDGE_DIED, error)
        }
        synchronized(monitor) { binding = BridgeBinding(connection, bridge) }
        return bridge
    }

    private suspend fun recoverStaleSessionIfNeeded(bridge: ICrossUserBridge) {
        val stale = journal.read() ?: return
        if (stale.state == CrossUserProtocol.SESSION_CLOSED) {
            journal.clear()
            return
        }
        val recovered = withContext(Dispatchers.IO) { bridge.recoverStaleSession(stale) }
        check(recovered) { CrossUserRuntimeErrors.CLEANUP_INCOMPLETE }
        journal.clear()
    }

    private fun requireClientSession(sessionId: String): ClientSession = synchronized(monitor) {
        active?.takeIf { it.handle.sessionId == sessionId }
            ?: error(CrossUserRuntimeErrors.INPUT_TARGET_MISMATCH)
    }

    private suspend fun releaseBridgeIfIdle() {
        cancelScheduledBridgeRelease()
        val current = synchronized(monitor) {
            if (active != null) null else binding.also { binding = null }
        } ?: return
        current.connection.intentionalDisconnect = true
        unbind(current.connection)
    }

    private suspend fun unbind(connection: BridgeServiceConnection) {
        withContext(Dispatchers.Main.immediate) {
            runCatching { RootService.unbind(connection) }
        }
    }

    private fun scheduleBridgeReleaseIfIdle() {
        val task = Runnable {
            val current = synchronized(monitor) {
                pendingIdleUnbind = null
                if (active != null) null else binding.also { binding = null }
            } ?: return@Runnable
            current.connection.intentionalDisconnect = true
            runCatching { RootService.unbind(current.connection) }
        }
        val previous = synchronized(monitor) {
            pendingIdleUnbind.also { pendingIdleUnbind = task }
        }
        previous?.let(mainHandler::removeCallbacks)
        mainHandler.postDelayed(task, IDLE_UNBIND_DELAY_MILLIS)
    }

    private fun cancelScheduledBridgeRelease() {
        val pending = synchronized(monitor) {
            pendingIdleUnbind.also { pendingIdleUnbind = null }
        }
        pending?.let(mainHandler::removeCallbacks)
    }

    private fun onBridgeDied(connection: BridgeServiceConnection) {
        if (connection.intentionalDisconnect) return
        val session = synchronized(monitor) {
            if (binding?.connection === connection) binding = null
            active?.also { active = null }
        } ?: return
        val failed = session.state.copy(
            state = CrossUserProtocol.SESSION_RECOVERY_REQUIRED,
            errorCode = CrossUserRuntimeErrors.BRIDGE_DIED,
            message = "Root bridge disconnected; cleanup must be reconciled",
        )
        journal.write(failed)
        session.callback.onStateChanged(failed)
        session.callback.onTerminal(session.handle.sessionId, CrossUserRuntimeErrors.BRIDGE_DIED, failed.message)
    }

    private inner class BridgeServiceConnection : ServiceConnection {
        val connected = CompletableDeferred<ICrossUserBridge>()
        @Volatile var intentionalDisconnect = false

        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            connected.complete(ICrossUserBridge.Stub.asInterface(service))
        }

        override fun onServiceDisconnected(name: ComponentName) {
            if (!connected.isCompleted) {
                connected.completeExceptionally(IllegalStateException(CrossUserRuntimeErrors.BRIDGE_DIED))
            }
            onBridgeDied(this)
        }

        override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)

        override fun onNullBinding(name: ComponentName) {
            connected.completeExceptionally(IllegalStateException(CrossUserRuntimeErrors.BRIDGE_DIED))
            onBridgeDied(this)
        }
    }

    private data class BridgeBinding(
        val connection: BridgeServiceConnection,
        val bridge: ICrossUserBridge,
    )

    private data class ClientSession(
        val handle: CrossUserSessionHandle,
        var config: CrossUserSessionConfig,
        val callback: CrossUserRuntimeCallback,
        val callbackBinder: ICrossUserCallback,
        var state: CrossUserSessionState,
    )

    private companion object {
        const val BIND_TIMEOUT_MILLIS = 30_000L
        const val IDLE_UNBIND_DELAY_MILLIS = 10_000L
    }
}
