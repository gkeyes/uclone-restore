package com.uclone.crossuser.runtime

import com.uclone.crossuser.api.CrossUserProbeRequest
import com.uclone.crossuser.api.CrossUserProtocol
import com.uclone.crossuser.api.CrossUserSessionConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ShellFrameworkAdapterTest {
    @Test
    fun deviceEligibleDisplayListIsParsedExactly() {
        val adapter = ShellFrameworkAdapter(FakeCommands { result("") })

        assertEquals(setOf(1, 4), adapter.parseEligibleDisplayIds("[1, 4]"))
        assertEquals(emptySet(), adapter.parseEligibleDisplayIds("[]"))
    }

    @Test
    fun probeNeverInfersVisibleUsersFromExitCodeAlone() {
        val executor = FakeCommands { args ->
            when {
                args.contains("is-visible-background-users-supported") -> result("not reported")
                args.last() == "help" -> result("start-user [-w] [--display DISPLAY_ID] --user --display")
                args.last() == "/proc/self/attr/current" -> result("u:r:su:s0")
                else -> result("Enforcing")
            }
        }
        val report = ShellFrameworkAdapter(
            commands = executor,
            rootUidProvider = { 0 },
        ).probe(CrossUserProbeRequest(mainUserId = 0, targetUserId = 10))

        assertEquals(0, report.rootUid)
        assertEquals(
            CrossUserProtocol.STATUS_UNKNOWN,
            report.statusOf("visible_background_users_runtime"),
        )
    }

    @Test
    fun startRequiresCurrentUserToRemainUser0() {
        var currentCalls = 0
        val executor = FakeCommands { args ->
            when {
                args.contains("is-visible-background-users-supported") -> result("true")
                args.contains("list-displays-for-starting-users") -> result("[1, 42]")
                args.contains("get-current-user") -> {
                    currentCalls += 1
                    result(if (currentCalls == 1) "0" else "10")
                }
                args.contains("get-started-user-state") -> result("RUNNING_UNLOCKED")
                args.firstOrNull()?.endsWith("/pm") == true -> result("package:/data/app/probe/base.apk")
                args.contains("is-user-visible") -> result("true")
                args.contains("start-user") -> result("Success: user started on display 42")
                args.contains("start") -> result("Starting: Intent")
                else -> result("")
            }
        }

        val failure = assertFailsWith<FrameworkCallException> {
            ShellFrameworkAdapter(executor).startVisibleUserAndLaunch(validConfig(), 42)
        }

        assertEquals(CrossUserRuntimeErrors.DISPLAY_ASSIGNMENT_FAILED, failure.errorCode)
    }

    @Test
    fun cleanupStopsOnlyUserOwnedBySession() {
        val executor = FakeCommands { args ->
            if (args.contains("get-current-user")) result("0") else result("")
        }
        val adapter = ShellFrameworkAdapter(executor)

        adapter.closeTarget(validConfig(), userStartedBySession = false)
        assertTrue(executor.calls.none { it.contains("stop-user") })

        adapter.closeTarget(validConfig(), userStartedBySession = true)
        assertTrue(executor.calls.any { it.contains("stop-user") })
    }

    @Test
    fun disabledVisibleUsersFailsBeforeAnyUserMutation() {
        val executor = FakeCommands { args ->
            if (args.contains("is-visible-background-users-supported")) result("false") else result("")
        }

        val failure = assertFailsWith<FrameworkCallException> {
            ShellFrameworkAdapter(executor).startVisibleUserAndLaunch(validConfig(), 42)
        }

        assertEquals(CrossUserRuntimeErrors.FEATURE_VISIBLE_USERS_DISABLED, failure.errorCode)
        assertTrue(executor.calls.none { it.contains("start-user") })
    }

    @Test
    fun ineligibleDisplayFailsBeforeAnyUserMutation() {
        val executor = FakeCommands { args ->
            when {
                args.contains("is-visible-background-users-supported") -> result("true")
                args.contains("list-displays-for-starting-users") -> result("[1, 4]")
                else -> result("")
            }
        }

        val failure = assertFailsWith<FrameworkCallException> {
            ShellFrameworkAdapter(executor).startVisibleUserAndLaunch(validConfig(), 42)
        }

        assertEquals(CrossUserRuntimeErrors.DISPLAY_NOT_ELIGIBLE, failure.errorCode)
        assertTrue(executor.calls.none { it.contains("start-user") })
    }

    @Test
    fun launchFailureStopsAUserStartedByTheSession() {
        var stateCalls = 0
        val executor = FakeCommands { args ->
            when {
                args.contains("is-visible-background-users-supported") -> result("true")
                args.contains("list-displays-for-starting-users") -> result("[1, 4]")
                args.contains("get-current-user") -> result("0")
                args.firstOrNull()?.endsWith("/pm") == true -> result("package:/data/app/probe/base.apk")
                args.contains("get-started-user-state") -> {
                    stateCalls += 1
                    result(if (stateCalls == 1) "STOPPED" else "RUNNING_UNLOCKED")
                }
                args.contains("start-user") -> result("Success: user started on display 4")
                args.contains("is-user-visible") -> result("true")
                args.contains("force-stop") -> result("")
                args.contains("stop-user") -> result("")
                args.contains("start") -> result("Error: launch failed", exit = 1)
                else -> result("")
            }
        }

        val failure = assertFailsWith<FrameworkCallException> {
            ShellFrameworkAdapter(executor).startVisibleUserAndLaunch(validConfig(), 4)
        }

        assertEquals(CrossUserRuntimeErrors.TARGET_LAUNCH_FAILED, failure.errorCode)
        assertTrue(executor.calls.any { it.contains("force-stop") })
        assertTrue(executor.calls.any { it.contains("stop-user") })
    }

    private fun validConfig() = CrossUserSessionConfig(
        packageName = CrossUserProtocol.PROBE_PACKAGE,
        componentName = CrossUserProtocol.PROBE_COMPONENT,
        mainUserId = 0,
        targetUserId = 10,
        widthPx = 1080,
        heightPx = 2400,
        densityDpi = 440,
        rotation = 0,
    )

    private fun result(stdout: String, exit: Int = 0) = CrossUserCommandResult(exit, stdout, "")

    private class FakeCommands(
        private val responder: (List<String>) -> CrossUserCommandResult,
    ) : CrossUserCommandExecutor {
        val calls = mutableListOf<List<String>>()
        override fun run(arguments: List<String>, timeoutSeconds: Long): CrossUserCommandResult {
            calls += arguments
            return responder(arguments)
        }
    }
}
