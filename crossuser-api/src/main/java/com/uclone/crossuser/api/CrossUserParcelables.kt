package com.uclone.crossuser.api

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class CrossUserProbeRequest(
    val schemaVersion: Int = CrossUserProtocol.SCHEMA_VERSION,
    val mainUserId: Int,
    val targetUserId: Int,
    val probePackageName: String = CrossUserProtocol.PROBE_PACKAGE,
) : Parcelable

@Parcelize
data class CrossUserCapabilityEntry(
    val schemaVersion: Int = CrossUserProtocol.SCHEMA_VERSION,
    val name: String,
    val status: String,
    val reasonCode: String,
    val humanMessage: String,
) : Parcelable

@Parcelize
data class CrossUserCapabilityReport(
    val schemaVersion: Int = CrossUserProtocol.SCHEMA_VERSION,
    val sdkInt: Int,
    val buildFingerprint: String,
    val rootUid: Int,
    val rootSelinuxContext: String,
    val selinuxEnforcing: Boolean,
    val adapterId: String,
    val capabilities: List<CrossUserCapabilityEntry>,
) : Parcelable {
    fun statusOf(name: String): String =
        capabilities.firstOrNull { it.name == name }?.status ?: CrossUserProtocol.STATUS_UNKNOWN
}

@Parcelize
data class CrossUserSessionConfig(
    val schemaVersion: Int = CrossUserProtocol.SCHEMA_VERSION,
    val packageName: String,
    val componentName: String,
    val mainUserId: Int,
    val targetUserId: Int,
    val widthPx: Int,
    val heightPx: Int,
    val densityDpi: Int,
    val rotation: Int,
    val backendId: String = "host_public_display",
) : Parcelable

@Parcelize
data class CrossUserSessionHandle(
    val schemaVersion: Int = CrossUserProtocol.SCHEMA_VERSION,
    val sessionId: String,
    val sessionToken: String,
) : Parcelable

@Parcelize
data class CrossUserSessionState(
    val schemaVersion: Int = CrossUserProtocol.SCHEMA_VERSION,
    val sessionId: String,
    val state: String,
    val displayId: Int,
    val taskId: Int,
    val mainUserId: Int,
    val targetUserId: Int,
    val packageName: String,
    val userWasRunning: Boolean,
    val userStartedBySession: Boolean,
    val errorCode: String? = null,
    val message: String = "",
) : Parcelable

@Parcelize
data class CrossUserTouchPacket(
    val schemaVersion: Int = CrossUserProtocol.SCHEMA_VERSION,
    val action: Int,
    val x: Float,
    val y: Float,
    val downTimeMillis: Long,
    val eventTimeMillis: Long,
) : Parcelable

@Parcelize
data class CrossUserKeyPacket(
    val schemaVersion: Int = CrossUserProtocol.SCHEMA_VERSION,
    val action: Int,
    val keyCode: Int,
    val downTimeMillis: Long,
    val eventTimeMillis: Long,
) : Parcelable
