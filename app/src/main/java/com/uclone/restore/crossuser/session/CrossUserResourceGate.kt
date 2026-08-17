package com.uclone.restore.crossuser.session

import java.util.UUID

enum class CrossUserResourceKind {
    USER_LIFECYCLE,
    PACKAGE_RUNTIME,
    PACKAGE_DATA,
    DISPLAY,
    GLOBAL_FRAMEWORK_HOOK,
    GLOBAL_CROSS_USER_SESSION,
    GLOBAL_DATA_TRANSACTION,
}

data class CrossUserResourceKey(
    val kind: CrossUserResourceKind,
    val userId: Int? = null,
    val packageName: String? = null,
    val displayId: Int? = null,
)

data class CrossUserGateLease(
    val leaseId: String,
    val owner: String,
    val keys: Set<CrossUserResourceKey>,
)

sealed class CrossUserGateResult {
    data class Acquired(val lease: CrossUserGateLease) : CrossUserGateResult()
    data class Busy(val conflicts: List<CrossUserGateLease>) : CrossUserGateResult()
}

class CrossUserResourceGate {
    private val leases = linkedMapOf<String, CrossUserGateLease>()

    @Synchronized
    fun tryAcquire(owner: String, keys: Set<CrossUserResourceKey>): CrossUserGateResult {
        require(owner.isNotBlank()) { "owner required" }
        require(keys.isNotEmpty()) { "at least one key required" }
        val conflicts = leases.values.filter { existing ->
            existing.keys.any { existingKey ->
                keys.any { requestedKey -> conflicts(existingKey, requestedKey) }
            }
        }
        if (conflicts.isNotEmpty()) return CrossUserGateResult.Busy(conflicts)
        val lease = CrossUserGateLease(
            leaseId = UUID.randomUUID().toString(),
            owner = owner,
            keys = keys,
        )
        leases[lease.leaseId] = lease
        return CrossUserGateResult.Acquired(lease)
    }

    @Synchronized
    fun release(leaseId: String): Boolean = leases.remove(leaseId) != null

    @Synchronized
    fun activeLeases(): List<CrossUserGateLease> = leases.values.toList()

    private fun conflicts(left: CrossUserResourceKey, right: CrossUserResourceKey): Boolean {
        if (left == right) return true
        if (left.kind == CrossUserResourceKind.GLOBAL_FRAMEWORK_HOOK ||
            right.kind == CrossUserResourceKind.GLOBAL_FRAMEWORK_HOOK
        ) {
            return true
        }
        val crossUserVsDataTransaction =
            left.kind == CrossUserResourceKind.GLOBAL_CROSS_USER_SESSION &&
                right.kind == CrossUserResourceKind.GLOBAL_DATA_TRANSACTION ||
                left.kind == CrossUserResourceKind.GLOBAL_DATA_TRANSACTION &&
                right.kind == CrossUserResourceKind.GLOBAL_CROSS_USER_SESSION
        if (crossUserVsDataTransaction) return true
        val runtimeVsData =
            left.kind == CrossUserResourceKind.PACKAGE_RUNTIME &&
                right.kind == CrossUserResourceKind.PACKAGE_DATA ||
                left.kind == CrossUserResourceKind.PACKAGE_DATA &&
                right.kind == CrossUserResourceKind.PACKAGE_RUNTIME
        if (runtimeVsData) {
            return left.userId == right.userId && left.packageName == right.packageName
        }
        val userLifecycleVsPackage =
            left.kind == CrossUserResourceKind.USER_LIFECYCLE &&
                right.kind in setOf(CrossUserResourceKind.PACKAGE_RUNTIME, CrossUserResourceKind.PACKAGE_DATA) ||
                right.kind == CrossUserResourceKind.USER_LIFECYCLE &&
                left.kind in setOf(CrossUserResourceKind.PACKAGE_RUNTIME, CrossUserResourceKind.PACKAGE_DATA)
        if (userLifecycleVsPackage) {
            val lifecycle = if (left.kind == CrossUserResourceKind.USER_LIFECYCLE) left else right
            val packageKey = if (left.kind == CrossUserResourceKind.USER_LIFECYCLE) right else left
            return lifecycle.userId == packageKey.userId
        }
        return false
    }
}
