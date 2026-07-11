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
    private var foregroundClaimed = false

    fun onStart(startId: Int): Boolean {
        require(startId > 0)
        check(latestStartId == null || startId > requireNotNull(latestStartId))
        check(pendingStartIds.add(startId))
        latestStartId = startId
        if (foregroundClaimed) return false
        foregroundClaimed = true
        return true
    }

    fun onAccepted(startId: Int) {
        check(pendingStartIds.remove(startId))
        foregroundOwnerStartId = startId
    }

    fun onBootstrapFailed(startId: Int): ExternalServiceFinalization {
        pendingStartIds.remove(startId)
        return finalizeIfIdle()
    }

    fun onRejected(startId: Int): ExternalServiceFinalization {
        pendingStartIds.remove(startId)
        return finalizeIfIdle()
    }

    fun onAcceptedFinished(startId: Int): ExternalServiceFinalization {
        if (foregroundOwnerStartId != startId) return ExternalServiceFinalization.None
        foregroundOwnerStartId = null
        return finalizeIfIdle()
    }

    private fun finalizeIfIdle(): ExternalServiceFinalization {
        if (!foregroundClaimed || foregroundOwnerStartId != null || pendingStartIds.isNotEmpty()) {
            return ExternalServiceFinalization.None
        }
        foregroundClaimed = false
        return ExternalServiceFinalization(
            removeForeground = true,
            stopStartId = latestStartId,
        )
    }
}
