package com.uclone.restore.external

internal data class ExternalServiceFinalization(
    val removeForeground: Boolean = false,
    val stopStartId: Int? = null,
) {
    companion object {
        val None = ExternalServiceFinalization()
    }
}

internal class ExternalServiceLifecyclePolicy {
    private val pendingStartIds = mutableSetOf<Int>()
    private var latestStartId: Int? = null
    private var foregroundOwnerStartId: Int? = null

    fun onStart(startId: Int) {
        require(startId > 0)
        check(latestStartId == null || startId > requireNotNull(latestStartId))
        check(pendingStartIds.add(startId))
        latestStartId = startId
    }

    fun onAccepted(startId: Int) {
        check(pendingStartIds.remove(startId))
        foregroundOwnerStartId = startId
    }

    fun onRejected(startId: Int): ExternalServiceFinalization {
        check(pendingStartIds.remove(startId))
        return when {
            foregroundOwnerStartId != null -> ExternalServiceFinalization.None
            pendingStartIds.isNotEmpty() -> ExternalServiceFinalization.None
            else -> ExternalServiceFinalization(stopStartId = latestStartId)
        }
    }

    fun onAcceptedFinished(startId: Int): ExternalServiceFinalization {
        if (foregroundOwnerStartId != startId) return ExternalServiceFinalization.None
        foregroundOwnerStartId = null
        return ExternalServiceFinalization(
            removeForeground = true,
            stopStartId = latestStartId.takeIf { pendingStartIds.isEmpty() },
        )
    }
}
