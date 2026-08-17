package com.uclone.restore.crossuser.session

import android.view.Surface
import com.uclone.crossuser.api.CrossUserKeyPacket
import com.uclone.crossuser.api.CrossUserCapabilityReport
import com.uclone.crossuser.api.CrossUserProbeRequest
import com.uclone.crossuser.api.CrossUserProtocol
import com.uclone.crossuser.api.CrossUserSessionConfig
import com.uclone.crossuser.api.CrossUserSessionState
import com.uclone.crossuser.api.CrossUserTouchPacket
import com.uclone.crossuser.runtime.CrossUserRuntime
import com.uclone.crossuser.runtime.CrossUserRuntimeCallback
import com.uclone.crossuser.runtime.CrossUserRuntimeErrors
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Keeps interactive sessions separate from finite TaskCoordinator records. */
class CrossUserSessionCoordinator(
    private val runtime: CrossUserRuntime,
    private val resourceGate: CrossUserResourceGate,
) {
    private val leases = ConcurrentHashMap<String, CrossUserGateLease>()

    suspend fun probe(request: CrossUserProbeRequest): CrossUserCapabilityReport = runtime.probe(request)

    suspend fun open(
        config: CrossUserSessionConfig,
        surface: Surface,
        callback: CrossUserRuntimeCallback,
    ): CrossUserSessionState {
        val gate = resourceGate.tryAcquire(
            owner = "cross-user:${UUID.randomUUID()}",
            keys = setOf(
                CrossUserResourceKey(CrossUserResourceKind.GLOBAL_CROSS_USER_SESSION),
                CrossUserResourceKey(
                    CrossUserResourceKind.USER_LIFECYCLE,
                    userId = config.targetUserId,
                ),
                CrossUserResourceKey(
                    CrossUserResourceKind.PACKAGE_RUNTIME,
                    userId = config.targetUserId,
                    packageName = config.packageName,
                ),
            ),
        )
        val lease = when (gate) {
            is CrossUserGateResult.Acquired -> gate.lease
            is CrossUserGateResult.Busy -> error(CrossUserRuntimeErrors.RESOURCE_CONFLICT)
        }
        return try {
            val wrapped = releasingCallback(callback, lease)
            runtime.createSession(config, surface, wrapped).also { state ->
                leases[state.sessionId] = lease
                if (state.state == CrossUserProtocol.SESSION_CLOSED) release(state.sessionId, lease)
            }
        } catch (error: Throwable) {
            resourceGate.release(lease.leaseId)
            throw error
        }
    }

    suspend fun replaceSurface(
        sessionId: String,
        surface: Surface,
        widthPx: Int,
        heightPx: Int,
        densityDpi: Int,
        rotation: Int,
    ): CrossUserSessionState = runtime.replaceSurface(
        sessionId,
        surface,
        widthPx,
        heightPx,
        densityDpi,
        rotation,
    )

    suspend fun injectTouch(sessionId: String, packet: CrossUserTouchPacket): Boolean =
        runtime.injectTouch(sessionId, packet)

    suspend fun injectKey(sessionId: String, packet: CrossUserKeyPacket): Boolean =
        runtime.injectKey(sessionId, packet)

    suspend fun close(sessionId: String, reason: Int): CrossUserSessionState? {
        val state = runtime.closeSession(sessionId, reason)
        if (state?.state == CrossUserProtocol.SESSION_CLOSED) {
            leases.remove(sessionId)?.let { resourceGate.release(it.leaseId) }
        }
        return state
    }

    private fun releasingCallback(
        downstream: CrossUserRuntimeCallback,
        lease: CrossUserGateLease,
    ) = object : CrossUserRuntimeCallback {
        override fun onStateChanged(state: CrossUserSessionState) {
            downstream.onStateChanged(state)
            if (state.state == CrossUserProtocol.SESSION_CLOSED) release(state.sessionId, lease)
        }

        override fun onFirstFrame(sessionId: String, monotonicNanos: Long) =
            downstream.onFirstFrame(sessionId, monotonicNanos)

        override fun onTaskChanged(sessionId: String, taskId: Int, packageName: String) =
            downstream.onTaskChanged(sessionId, taskId, packageName)

        override fun onRecoverableError(sessionId: String, code: String, message: String) =
            downstream.onRecoverableError(sessionId, code, message)

        override fun onTerminal(sessionId: String, code: String, message: String) =
            downstream.onTerminal(sessionId, code, message)
    }

    private fun release(sessionId: String, fallback: CrossUserGateLease) {
        val lease = leases.remove(sessionId) ?: fallback
        resourceGate.release(lease.leaseId)
    }
}
