package com.uclone.crossuser.runtime

import android.os.Build
import android.os.Process
import com.uclone.crossuser.api.CrossUserCapabilityEntry
import com.uclone.crossuser.api.CrossUserCapabilityReport
import com.uclone.crossuser.api.CrossUserProbeRequest
import com.uclone.crossuser.api.CrossUserProtocol
import com.uclone.crossuser.api.CrossUserSessionConfig
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

internal data class CrossUserCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
) {
    val combined: String = listOf(stdout, stderr).filter(String::isNotBlank).joinToString("\n")
}

internal fun interface CrossUserCommandExecutor {
    fun run(arguments: List<String>, timeoutSeconds: Long): CrossUserCommandResult
}

internal class ProcessCrossUserCommandExecutor : CrossUserCommandExecutor {
    override fun run(arguments: List<String>, timeoutSeconds: Long): CrossUserCommandResult {
        require(arguments.isNotEmpty())
        val process = ProcessBuilder(arguments).redirectErrorStream(false).start()
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val stdoutThread = process.inputStream.copyAsync(stdout)
        val stderrThread = process.errorStream.copyAsync(stderr)
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        stdoutThread.join(2_000)
        stderrThread.join(2_000)
        return CrossUserCommandResult(
            exitCode = if (finished) process.exitValue() else 124,
            stdout = stdout.toString(Charsets.UTF_8.name()).takeLast(MAX_CAPTURE_CHARS),
            stderr = stderr.toString(Charsets.UTF_8.name()).takeLast(MAX_CAPTURE_CHARS),
            timedOut = !finished,
        )
    }

    private fun java.io.InputStream.copyAsync(output: ByteArrayOutputStream): Thread = Thread {
        use { input -> input.copyTo(output, bufferSize = 8 * 1024) }
    }.apply {
        isDaemon = true
        start()
    }

    private companion object {
        const val MAX_CAPTURE_CHARS = 32 * 1024
    }
}

internal data class FrameworkStartReceipt(
    val userWasRunning: Boolean,
    val userStartedBySession: Boolean,
    val message: String,
)

internal class FrameworkCallException(
    val errorCode: String,
    val supportStatus: String,
    message: String,
) : IllegalStateException(message)

internal interface CrossUserFrameworkAdapter {
    val id: String
    fun probe(request: CrossUserProbeRequest): CrossUserCapabilityReport
    fun startVisibleUserAndLaunch(config: CrossUserSessionConfig, displayId: Int): FrameworkStartReceipt
    fun closeTarget(config: CrossUserSessionConfig, userStartedBySession: Boolean): List<String>
}

internal class ShellFrameworkAdapter(
    private val commands: CrossUserCommandExecutor,
) : CrossUserFrameworkAdapter {
    override val id: String = "android-shell-visible-display-v1"

    override fun probe(request: CrossUserProbeRequest): CrossUserCapabilityReport {
        require(request.schemaVersion == CrossUserProtocol.SCHEMA_VERSION)
        require(request.mainUserId == 0)
        require(request.targetUserId == 10)
        require(request.probePackageName == CrossUserProtocol.PROBE_PACKAGE)

        val visible = commands.run(
            listOf(CMD, "user", "is-visible-background-users-supported", "-v"),
            PROBE_TIMEOUT_SECONDS,
        )
        val help = commands.run(listOf(AM, "help"), PROBE_TIMEOUT_SECONDS)
        val enforcingResult = commands.run(listOf(GETENFORCE), PROBE_TIMEOUT_SECONDS)
        val contextResult = commands.run(listOf(CAT, "/proc/self/attr/current"), PROBE_TIMEOUT_SECONDS)

        val visibleStatus = booleanStatus(visible)
        val startCommandStatus = if (
            help.exitCode == 0 &&
            help.combined.contains("start-user") &&
            help.combined.contains("--display")
        ) {
            CrossUserProtocol.STATUS_SUPPORTED
        } else {
            CrossUserProtocol.STATUS_UNKNOWN
        }
        val launchCommandStatus = if (
            help.exitCode == 0 &&
            help.combined.contains("start") &&
            help.combined.contains("--user") &&
            help.combined.contains("--display")
        ) {
            CrossUserProtocol.STATUS_SUPPORTED
        } else {
            CrossUserProtocol.STATUS_UNKNOWN
        }
        val capabilities = listOf(
            capability(
                "visible_background_users_runtime",
                visibleStatus,
                if (visibleStatus == CrossUserProtocol.STATUS_SUPPORTED) "VISIBLE_USERS_TRUE" else "VISIBLE_USERS_NOT_CONFIRMED",
                visible.combined.ifBlank { "No visible-user capability response" },
            ),
            capability(
                "start_user_visible_api_callable",
                startCommandStatus,
                "AM_START_USER_DISPLAY_HELP",
                "am start-user --display availability",
            ),
            capability(
                "launch_as_user_on_display",
                launchCommandStatus,
                "AM_START_USER_DISPLAY_OPTIONS",
                "am start --user --display availability",
            ),
            capability(
                "display_targeted_input_injection",
                CrossUserProtocol.STATUS_UNKNOWN,
                "INPUT_RUNTIME_PROBE_REQUIRED",
                "Confirmed only after a display-scoped Probe event succeeds",
            ),
        )
        return CrossUserCapabilityReport(
            sdkInt = Build.VERSION.SDK_INT,
            buildFingerprint = Build.FINGERPRINT.orEmpty(),
            rootUid = Process.myUid(),
            rootSelinuxContext = contextResult.stdout.trim(),
            selinuxEnforcing = enforcingResult.stdout.trim().equals("Enforcing", ignoreCase = true),
            adapterId = id,
            capabilities = capabilities,
        )
    }

    override fun startVisibleUserAndLaunch(
        config: CrossUserSessionConfig,
        displayId: Int,
    ): FrameworkStartReceipt {
        CrossUserSessionValidator.requireValid(config)
        require(displayId > 0) { "secondary display required" }
        val visibleSupport = commands.run(
            listOf(CMD, "user", "is-visible-background-users-supported", "-v"),
            PROBE_TIMEOUT_SECONDS,
        )
        if (booleanStatus(visibleSupport) != CrossUserProtocol.STATUS_SUPPORTED) {
            throw FrameworkCallException(
                CrossUserRuntimeErrors.FEATURE_VISIBLE_USERS_DISABLED,
                if (booleanStatus(visibleSupport) == CrossUserProtocol.STATUS_UNSUPPORTED) {
                    CrossUserProtocol.STATUS_UNSUPPORTED
                } else {
                    CrossUserProtocol.STATUS_UNKNOWN
                },
                "Visible background users are not confirmed: ${visibleSupport.combined}",
            )
        }
        val currentBefore = currentUser()
        if (currentBefore != config.mainUserId) {
            throw FrameworkCallException(
                CrossUserRuntimeErrors.DISPLAY_ASSIGNMENT_FAILED,
                CrossUserProtocol.STATUS_UNSUPPORTED,
                "Current foreground user is user$currentBefore, expected user${config.mainUserId}",
            )
        }
        val packagePath = commands.run(
            listOf(PM, "path", "--user", config.targetUserId.toString(), config.packageName),
            PROBE_TIMEOUT_SECONDS,
        )
        if (packagePath.exitCode != 0 || !packagePath.stdout.lineSequence().any { it.startsWith("package:") }) {
            throw commandFailure(
                CrossUserRuntimeErrors.TARGET_PACKAGE_NOT_INSTALLED,
                "Probe is not installed for user${config.targetUserId}",
                packagePath,
            )
        }
        val beforeState = userState(config.targetUserId)
        val userWasRunning = beforeState.contains("RUNNING")
        var startAttempted = false
        try {
            val start = commands.run(
                listOf(AM, "start-user", "-w", "--display", displayId.toString(), config.targetUserId.toString()),
                USER_TIMEOUT_SECONDS,
            )
            startAttempted = true
            val visibility = commands.run(
                listOf(CMD, "user", "is-user-visible", "--display", displayId.toString(), config.targetUserId.toString()),
                PROBE_TIMEOUT_SECONDS,
            )
            val visibleConfirmed = booleanStatus(visibility) == CrossUserProtocol.STATUS_SUPPORTED
            if (start.exitCode != 0 || !visibleConfirmed) {
                throw commandFailure(
                    CrossUserRuntimeErrors.DISPLAY_ASSIGNMENT_FAILED,
                    "Unable to start user${config.targetUserId} visible on display $displayId",
                    start,
                    visibility,
                )
            }
            val state = userState(config.targetUserId)
            if (!state.contains("RUNNING_UNLOCKED")) {
                throw FrameworkCallException(
                    CrossUserRuntimeErrors.TARGET_USER_LOCKED,
                    CrossUserProtocol.STATUS_UNSUPPORTED,
                    "user${config.targetUserId} is not RUNNING_UNLOCKED: $state",
                )
            }
            val launch = commands.run(
                listOf(
                    AM,
                    "start",
                    "--user",
                    config.targetUserId.toString(),
                    "--display",
                    displayId.toString(),
                    "-n",
                    config.componentName,
                ),
                LAUNCH_TIMEOUT_SECONDS,
            )
            if (launch.exitCode != 0 || launch.combined.lineSequence().any { it.startsWith("Error:") }) {
                throw commandFailure(
                    CrossUserRuntimeErrors.TARGET_LAUNCH_FAILED,
                    "Unable to launch Probe on display $displayId",
                    launch,
                )
            }
            val currentAfter = currentUser()
            if (currentAfter != config.mainUserId) {
                throw FrameworkCallException(
                    CrossUserRuntimeErrors.DISPLAY_ASSIGNMENT_FAILED,
                    CrossUserProtocol.STATUS_UNSUPPORTED,
                    "Foreground user changed unexpectedly to user$currentAfter",
                )
            }
            return FrameworkStartReceipt(
                userWasRunning = userWasRunning,
                userStartedBySession = !userWasRunning,
                message = launch.combined.ifBlank { "Probe launch requested" },
            )
        } catch (error: Throwable) {
            if (startAttempted) {
                runCatching { closeTarget(config, userStartedBySession = !userWasRunning) }
            }
            throw error
        }
    }

    override fun closeTarget(
        config: CrossUserSessionConfig,
        userStartedBySession: Boolean,
    ): List<String> {
        CrossUserSessionValidator.requireValid(config)
        val results = mutableListOf<String>()
        val forceStop = commands.run(
            listOf(AM, "force-stop", "--user", config.targetUserId.toString(), config.packageName),
            LAUNCH_TIMEOUT_SECONDS,
        )
        results += "force-stop exit=${forceStop.exitCode}"
        if (forceStop.exitCode != 0) {
            throw commandFailure(
                CrossUserRuntimeErrors.CLEANUP_INCOMPLETE,
                "Unable to stop Probe for user${config.targetUserId}",
                forceStop,
            )
        }
        if (userStartedBySession) {
            val current = currentUser()
            if (current == config.targetUserId) {
                throw FrameworkCallException(
                    CrossUserRuntimeErrors.CLEANUP_INCOMPLETE,
                    CrossUserProtocol.STATUS_UNSUPPORTED,
                    "Refusing to stop foreground user$current during cleanup",
                )
            }
            val stop = commands.run(
                listOf(AM, "stop-user", "-w", config.targetUserId.toString()),
                USER_TIMEOUT_SECONDS,
            )
            results += "stop-user exit=${stop.exitCode}"
            if (stop.exitCode != 0) {
                throw commandFailure(
                    CrossUserRuntimeErrors.CLEANUP_INCOMPLETE,
                    "Unable to stop session-owned user${config.targetUserId}",
                    stop,
                )
            }
        }
        return results
    }

    private fun currentUser(): Int {
        val result = commands.run(listOf(AM, "get-current-user"), PROBE_TIMEOUT_SECONDS)
        return result.stdout.trim().toIntOrNull() ?: throw commandFailure(
            CrossUserRuntimeErrors.FRAMEWORK_SIGNATURE_MISMATCH,
            "Unable to determine current user",
            result,
        )
    }

    private fun userState(userId: Int): String {
        val result = commands.run(listOf(AM, "get-started-user-state", userId.toString()), PROBE_TIMEOUT_SECONDS)
        return result.combined.trim()
    }

    private fun booleanStatus(result: CrossUserCommandResult): String {
        val normalized = result.combined.lowercase()
        return when {
            result.exitCode != 0 -> CrossUserProtocol.STATUS_UNKNOWN
            Regex("(^|\\W)true(\\W|$)").containsMatchIn(normalized) -> CrossUserProtocol.STATUS_SUPPORTED
            Regex("(^|\\W)false(\\W|$)").containsMatchIn(normalized) -> CrossUserProtocol.STATUS_UNSUPPORTED
            else -> CrossUserProtocol.STATUS_UNKNOWN
        }
    }

    private fun capability(name: String, status: String, reason: String, message: String) =
        CrossUserCapabilityEntry(
            name = name,
            status = status,
            reasonCode = reason,
            humanMessage = message.take(1_024),
        )

    private fun commandFailure(
        code: String,
        message: String,
        vararg results: CrossUserCommandResult,
    ): FrameworkCallException {
        val combined = results.joinToString("\n") { it.combined }.take(2_048)
        val denied = combined.contains("Permission Denial", ignoreCase = true) ||
            combined.contains("SecurityException", ignoreCase = true)
        val selinux = combined.contains("avc: denied", ignoreCase = true)
        return FrameworkCallException(
            errorCode = code,
            supportStatus = when {
                selinux -> CrossUserProtocol.STATUS_BLOCKED_SELINUX
                denied -> CrossUserProtocol.STATUS_BLOCKED_PERMISSION
                else -> CrossUserProtocol.STATUS_UNSUPPORTED
            },
            message = "$message: $combined",
        )
    }

    private companion object {
        const val AM = "/system/bin/am"
        const val CMD = "/system/bin/cmd"
        const val PM = "/system/bin/pm"
        const val GETENFORCE = "/system/bin/getenforce"
        const val CAT = "/system/bin/cat"
        const val PROBE_TIMEOUT_SECONDS = 15L
        const val LAUNCH_TIMEOUT_SECONDS = 30L
        const val USER_TIMEOUT_SECONDS = 90L
    }
}
