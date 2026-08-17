package com.uclone.restore.crossuser.domain

private val PackageNamePattern = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

enum class CrossUserSupportStatus {
    SUPPORTED,
    UNSUPPORTED,
    BLOCKED_BY_PERMISSION,
    BLOCKED_BY_SELINUX,
    UNKNOWN,
}

enum class CrossUserCapabilityKey(val wireName: String) {
    ACTIVITIES_ON_SECONDARY_DISPLAYS("activities_on_secondary_displays"),
    VISIBLE_BACKGROUND_USERS_RESOURCE("visible_background_users_resource"),
    VISIBLE_BACKGROUND_USERS_RUNTIME("visible_background_users_runtime"),
    START_USER_VISIBLE_API_PRESENT("start_user_visible_api_present"),
    START_USER_VISIBLE_API_CALLABLE("start_user_visible_api_callable"),
    HOST_PUBLIC_DISPLAY_CREATABLE("host_public_display_creatable"),
    HOST_PUBLIC_DISPLAY_ACCEPTS_OTHER_UID("host_public_display_accepts_other_uid"),
    TRUSTED_DISPLAY_CREATABLE("trusted_display_creatable"),
    LAUNCH_AS_USER_ON_DISPLAY("launch_as_user_on_display"),
    TARGET_TASK_OBSERVED_ON_DISPLAY("target_task_observed_on_display"),
    VIRTUAL_TOUCH_DEVICE("virtual_touch_device"),
    DISPLAY_TARGETED_INPUT_INJECTION("display_targeted_input_injection"),
    BACK_INJECTION("back_injection"),
    MULTI_TOUCH_INJECTION("multi_touch_injection"),
    USER_RUNNING("user_running"),
    USER_UNLOCKED("user_unlocked"),
    DIRECT_SURFACE_SUPPORTED("direct_surface_supported"),
    ENCODED_FALLBACK_SUPPORTED("encoded_fallback_supported"),
    SECURE_CONTENT_POLICY("secure_content_policy"),
    REQUIRES_FRAMEWORK_HOOK("requires_framework_hook"),
}

data class CrossUserCapability(
    val key: CrossUserCapabilityKey,
    val status: CrossUserSupportStatus,
    val evidenceId: String? = null,
    val reasonCode: String,
    val humanMessage: String,
)

data class CrossUserCapabilityReport(
    val schemaVersion: Int = 1,
    val sdkInt: Int? = null,
    val buildFingerprint: String? = null,
    val rootProvider: String? = null,
    val rootUid: Int? = null,
    val rootSelinuxContext: String? = null,
    val selinuxEnforcing: Boolean? = null,
    val frameworkAdapterId: String? = null,
    val capabilities: Map<CrossUserCapabilityKey, CrossUserCapability> = emptyMap(),
) {
    fun statusOf(key: CrossUserCapabilityKey): CrossUserSupportStatus =
        capabilities[key]?.status ?: CrossUserSupportStatus.UNKNOWN
}

enum class CrossUserSessionState {
    IDLE,
    PRECHECKING,
    SURFACE_READY,
    DISPLAY_CREATING,
    DISPLAY_READY,
    USER_STARTING,
    USER_VISIBLE_LOCKED,
    USER_UNLOCKING,
    USER_VISIBLE_UNLOCKED,
    TARGET_LAUNCHING,
    WAITING_FIRST_FRAME,
    ACTIVE,
    HOST_BACKGROUND,
    CLOSING,
    CLOSED,
    UNSUPPORTED,
    FAILED,
    STALE_SESSION,
    RECOVERY_REQUIRED,
    BRIDGE_DIED,
    SURFACE_LOST,
    DISPLAY_REMOVED,
    USER_STOPPED,
    TARGET_DIED,
    SECURE_CONTENT_BLOCKED,
}

fun CrossUserSessionState.isTerminal(): Boolean = when (this) {
    CrossUserSessionState.CLOSED,
    CrossUserSessionState.UNSUPPORTED,
    CrossUserSessionState.FAILED,
    CrossUserSessionState.STALE_SESSION,
    CrossUserSessionState.RECOVERY_REQUIRED,
    CrossUserSessionState.BRIDGE_DIED,
    CrossUserSessionState.SURFACE_LOST,
    CrossUserSessionState.DISPLAY_REMOVED,
    CrossUserSessionState.USER_STOPPED,
    CrossUserSessionState.TARGET_DIED,
    CrossUserSessionState.SECURE_CONTENT_BLOCKED,
    -> true
    else -> false
}

enum class CrossUserErrorCode {
    FEATURE_VISIBLE_USERS_DISABLED,
    VISIBLE_USER_API_MISSING,
    VISIBLE_USER_CALL_DENIED,
    DISPLAY_CREATE_DENIED,
    DISPLAY_NOT_PUBLIC,
    DISPLAY_NOT_TRUSTED,
    DISPLAY_ASSIGNMENT_FAILED,
    ACTIVITY_SECONDARY_DISPLAY_UNSUPPORTED,
    TARGET_NOT_INSTALLED,
    TARGET_PACKAGE_DENIED,
    TARGET_USER_LOCKED,
    CLONE_CREDENTIAL_MISSING,
    CLONE_CREDENTIAL_REJECTED,
    CLONE_UNLOCK_FAILED,
    TARGET_LAUNCH_FAILED,
    TARGET_TASK_ESCAPED_DISPLAY,
    INPUT_PERMISSION_DENIED,
    INPUT_TARGET_MISMATCH,
    IME_LOCAL_UNSUPPORTED,
    SURFACE_INVALID,
    BRIDGE_DIED,
    SESSION_BUSY,
    SESSION_CLOSED,
    RESOURCE_CONFLICT,
    CLEANUP_INCOMPLETE,
    FRAMEWORK_SIGNATURE_MISMATCH,
    HOOK_DISABLED,
    SECURE_CONTENT_EXPECTED_BLANK,
    FAKE_BACKEND_ONLY,
}

data class CrossUserSessionConfig(
    val schemaVersion: Int = 1,
    val packageName: String,
    val mainUserId: Int,
    val targetUserId: Int,
    val widthPx: Int,
    val heightPx: Int,
    val densityDpi: Int,
    val rotation: Int,
    val backendId: String,
) {
    init {
        require(PackageNamePattern.matches(packageName)) { "Invalid package name" }
        require(mainUserId >= 0) { "mainUserId must be explicit and non-negative" }
        require(targetUserId >= 0) { "targetUserId must be explicit and non-negative" }
        require(mainUserId != targetUserId) { "mainUserId and targetUserId must differ" }
        require(widthPx > 0 && heightPx > 0) { "display size must be positive" }
        require(densityDpi in 120..1000) { "densityDpi out of supported range" }
        require(rotation in 0..3) { "rotation must be one of 0, 1, 2, 3" }
        require(backendId.isNotBlank()) { "backendId required" }
    }
}

data class CrossUserSessionHandle(
    val sessionId: String,
    val schemaVersion: Int = 1,
)

data class CrossUserStateTransition(
    val from: CrossUserSessionState,
    val to: CrossUserSessionState,
    val monotonicMillis: Long,
    val reason: String,
)

data class CrossUserSessionSnapshot(
    val handle: CrossUserSessionHandle,
    val config: CrossUserSessionConfig,
    val state: CrossUserSessionState,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val displayId: Int? = null,
    val taskId: Int? = null,
    val lastErrorCode: CrossUserErrorCode? = null,
    val transitions: List<CrossUserStateTransition> = emptyList(),
)
