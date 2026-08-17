package com.uclone.restore.crossuser.client

import com.uclone.restore.crossuser.domain.CrossUserCapability
import com.uclone.restore.crossuser.domain.CrossUserCapabilityKey
import com.uclone.restore.crossuser.domain.CrossUserCapabilityReport
import com.uclone.restore.crossuser.domain.CrossUserErrorCode
import com.uclone.restore.crossuser.domain.CrossUserSessionConfig
import com.uclone.restore.crossuser.domain.CrossUserSessionHandle
import com.uclone.restore.crossuser.domain.CrossUserSessionSnapshot
import com.uclone.restore.crossuser.domain.CrossUserSessionState
import com.uclone.restore.crossuser.domain.CrossUserSupportStatus
import com.uclone.restore.crossuser.session.CrossUserSessionStateMachine
import java.security.SecureRandom

data class CrossUserProbeRequest(
    val schemaVersion: Int = 1,
    val mainUserId: Int,
    val targetUserId: Int,
    val probePackageName: String,
)

interface CrossUserRuntimeBackend {
    fun probe(request: CrossUserProbeRequest): CrossUserCapabilityReport
    fun createSession(config: CrossUserSessionConfig): CrossUserSessionSnapshot
    fun closeSession(sessionId: String): CrossUserSessionSnapshot?
}

class FakeCrossUserRuntime(
    private val clockMillis: () -> Long,
    private val randomBytes: (ByteArray) -> Unit = SecureRandom()::nextBytes,
) : CrossUserRuntimeBackend {
    private val sessions = linkedMapOf<String, CrossUserSessionStateMachine>()

    override fun probe(request: CrossUserProbeRequest): CrossUserCapabilityReport {
        require(request.schemaVersion == 1) { "Unsupported probe schema" }
        require(request.mainUserId >= 0 && request.targetUserId >= 0)
        return CrossUserCapabilityReport(
            frameworkAdapterId = "fake-analysis-only",
            capabilities = CrossUserCapabilityKey.entries.associateWith { key ->
                CrossUserCapability(
                    key = key,
                    status = CrossUserSupportStatus.UNKNOWN,
                    reasonCode = "NEEDS_TARGET_DEVICE_L0_L4",
                    humanMessage = "Fake backend does not call Android framework or root.",
                )
            },
        )
    }

    override fun createSession(config: CrossUserSessionConfig): CrossUserSessionSnapshot {
        val handle = CrossUserSessionHandle(newSessionId())
        val machine = CrossUserSessionStateMachine(handle, config, clockMillis)
        sessions[handle.sessionId] = machine
        machine.transitionTo(CrossUserSessionState.PRECHECKING, "fake_precheck")
        return machine.transitionTo(
            next = CrossUserSessionState.UNSUPPORTED,
            reason = "fake_backend_never_launches_real_apps",
            errorCode = CrossUserErrorCode.FAKE_BACKEND_ONLY,
        )
    }

    override fun closeSession(sessionId: String): CrossUserSessionSnapshot? {
        val machine = sessions[sessionId] ?: return null
        return machine.close("fake_close")
    }

    private fun newSessionId(): String {
        val bytes = ByteArray(16)
        randomBytes(bytes)
        return bytes.joinToString(separator = "") { "%02x".format(it) }
    }
}
