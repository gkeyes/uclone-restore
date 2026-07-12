package com.uclone.restore.sync

import com.uclone.restore.model.AppRule
import com.uclone.restore.model.CrossUserInstallMode
import com.uclone.restore.model.UCloneSettings
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CrossUserInstallScriptsTest {
    private val settings = UCloneSettings(
        mainUserId = 0,
        cloneUserId = 10,
        autoUnlockClone = true,
        cloneUnlockCredential = "123456",
    )
    private val rule = AppRule(packageName = "com.example.app")

    @Test
    fun installOnlyNeverStartsOrUnlocksClone() {
        val script = script(targetUser = 10, mode = CrossUserInstallMode.INSTALL_ONLY)

        assertContains(script, "cmd package install-existing --user")
        assertContains(script, "install_stage INSTALL_PACKAGE")
        assertContains(script, "awk -v expected=\"package:${'$'}PKG\"")
        assertContains(script, "INSTALL_ONLY_DONE targetUser=${'$'}DST_USER")
        assertFalse(script.contains("ENSURE_CLONE_CE_BEGIN"))
        assertFalse(script.contains("/data/user/"))
        assertFalse(script.contains("uninstall"))
    }

    @Test
    fun permissionModeMigratesStateWithoutUnlockingClone() {
        val script = script(targetUser = 10, mode = CrossUserInstallMode.INSTALL_WITH_PERMISSIONS)

        assertContains(script, "cmd package grant --user")
        assertContains(script, "cmd appops set --user")
        assertContains(script, "INSTALL_PERMISSIONS_DONE")
        assertContains(script, "uclone_restore_permission_state \"${'$'}PERM_DIR\" \"${'$'}DST_USER\" MERGE")
        assertFalse(script.contains("ENSURE_CLONE_CE_BEGIN"))
    }

    @Test
    fun dataModeReusesDirectionalSyncAndKeepsInstallOnFailure() {
        val push = script(targetUser = 10, mode = CrossUserInstallMode.INSTALL_AND_SYNC)
        val restore = script(targetUser = 0, mode = CrossUserInstallMode.INSTALL_AND_SYNC)

        assertContains(push, "PUSH_USERS source=${'$'}SRC_USER target=${'$'}DST_USER")
        assertContains(restore, "COMPOSITE_STEP=CAPTURE_SNAPSHOT_FROM_CLONE")
        listOf(push, restore).forEach { installScript ->
            assertContains(installScript, "WARN_INSTALL_SYNC_FAILED:")
            assertContains(installScript, "INSTALL_PARTIAL_FATAL")
            assertContains(installScript, "exit 91")
            assertContains(installScript, "INSTALL_PARTIAL_SUCCESS")
            assertFalse(installScript.contains("pm uninstall"))
            assertFalse(installScript.contains("cmd package uninstall"))
        }
    }

    @Test
    fun failedAppOpsCaptureDoesNotResetTargetState() {
        val result = runPermissionHarness(
            PermissionHarnessConfig(appOpsCaptureExit = 1),
        )

        assertEquals(0, result.exitCode, result.stderr)
        assertContains(result.stdout, "WARN_INSTALL_PERMISSIONS_CAPTURE_FAILED:user=0")
        assertContains(result.stdout, "INSTALL_PERMISSIONS_DONE targetUser=10 mode=MERGE status=partial")
        assertFalse(result.commands.contains("cmd:appops reset"))
        assertFalse(result.commands.contains("cmd:package grant"))
    }

    @Test
    fun failedRuntimeCaptureDoesNotAttemptGrants() {
        val result = runPermissionHarness(
            PermissionHarnessConfig(runtimeCaptureExit = 1),
        )

        assertEquals(0, result.exitCode, result.stderr)
        assertContains(result.stdout, "WARN_INSTALL_PERMISSIONS_CAPTURE_FAILED:user=0")
        assertContains(result.stdout, "INSTALL_PERMISSIONS_DONE targetUser=10 mode=MERGE status=partial")
        assertFalse(result.commands.contains("cmd:package grant"))
        assertFalse(result.commands.contains("pm:grant"))
        assertFalse(result.commands.contains("cmd:appops set"))
    }

    @Test
    fun successfulEmptyAppOpsCaptureDoesNotResetTargetStateInMergeMode() {
        val result = runPermissionHarness(
            PermissionHarnessConfig(appOps = emptyList()),
        )

        assertEquals(0, result.exitCode, result.stderr)
        assertFalse(result.stdout.contains("WARN_INSTALL_PERMISSIONS_CAPTURE_FAILED"))
        assertFalse(result.commands.contains("cmd:appops reset"))
        assertFalse(result.commands.contains("cmd:appops set"))
        assertContains(result.stdout, "RESTORED_PERMISSIONS:mode=MERGE grants=1 revokes=0 appops=0")
        assertContains(result.stdout, "INSTALL_PERMISSIONS_DONE targetUser=10 mode=MERGE")
    }

    @Test
    fun permissionCountersIncludeOnlySuccessfulChanges() {
        val result = runPermissionHarness(
            PermissionHarnessConfig(
                runtimePermissions = listOf(
                    "android.permission.CAMERA",
                    "android.permission.RECORD_AUDIO",
                ),
                appOps = listOf("CAMERA: allow", "RECORD_AUDIO: deny"),
                failedGrant = "android.permission.RECORD_AUDIO",
                failedAppOp = "RECORD_AUDIO",
            ),
        )

        assertEquals(0, result.exitCode, result.stderr)
        assertContains(result.stdout, "WARN_GRANT_FAILED:android.permission.RECORD_AUDIO")
        assertContains(result.stdout, "WARN_APPOPS_FAILED:RECORD_AUDIO:deny")
        assertContains(result.stdout, "RESTORED_PERMISSIONS:mode=MERGE grants=1 revokes=0 appops=1")
        assertContains(result.stdout, "INSTALL_PERMISSIONS_DONE targetUser=10 mode=MERGE")
    }

    private fun script(
        targetUser: Int,
        mode: CrossUserInstallMode,
        scriptSettings: UCloneSettings = settings,
    ): String =
        CrossUserInstallScripts.build(
            packageName = "com.example.app",
            targetUserId = targetUser,
            mode = mode,
            rule = rule,
            settings = scriptSettings,
            appPackage = "com.uclone.restore",
            requestId = "request-1",
        )

    private fun runPermissionHarness(config: PermissionHarnessConfig): HarnessResult {
        val directory = Files.createTempDirectory("uclone-install-permissions-").toFile().apply { deleteOnExit() }
        val bin = directory.resolve("bin").apply {
            check(mkdirs())
            deleteOnExit()
        }
        val commandLog = directory.resolve("commands.log").apply { deleteOnExit() }
        val runtimeCapture = directory.resolve("runtime.txt").apply {
            writeText(
                buildString {
                    appendLine("    User 0:")
                    appendLine("      runtime permissions:")
                    config.runtimePermissions.forEach { permission ->
                        appendLine("        $permission: granted=true")
                    }
                    appendLine("    User 10:")
                },
            )
            deleteOnExit()
        }
        val appOpsCapture = directory.resolve("appops.txt").apply {
            writeText(config.appOps.joinToString(separator = "\n", postfix = if (config.appOps.isEmpty()) "" else "\n"))
            deleteOnExit()
        }
        val cmd = bin.resolve("cmd").apply {
            writeExecutable(
                """
                    #!/bin/sh
                    printf 'cmd:%s\n' "${'$'}*" >> "${'$'}UCLONE_COMMAND_LOG"
                    case "${'$'}1:${'$'}2" in
                      package:list)
                        case " ${'$'}* " in
                          *" -U "*) printf '%s\n' 'package:com.example.app uid:1010001' ;;
                          *) printf '%s\n' 'package:com.example.app' ;;
                        esac
                        ;;
                      package:grant)
                        [ "${'$'}{6-}" != "${'$'}{FAIL_GRANT-}" ]
                        ;;
                      appops:get)
                        cat "${'$'}APPOPS_CAPTURE"
                        exit "${'$'}APPOPS_CAPTURE_EXIT"
                        ;;
                      appops:reset|appops:write-settings)
                        exit 0
                        ;;
                      appops:set)
                        [ "${'$'}{6-}" != "${'$'}{FAIL_APPOP-}" ]
                        ;;
                      *)
                        exit 2
                        ;;
                    esac
                """,
            )
        }
        val pm = bin.resolve("pm").apply {
            writeExecutable(
                """
                    #!/bin/sh
                    printf 'pm:%s\n' "${'$'}*" >> "${'$'}UCLONE_COMMAND_LOG"
                    case "${'$'}1:${'$'}2" in
                      grant:--user) [ "${'$'}{5-}" != "${'$'}{FAIL_GRANT-}" ] ;;
                      *) exit 2 ;;
                    esac
                """,
            )
        }
        bin.resolve("dumpsys").apply {
            writeExecutable(
                """
                    #!/bin/sh
                    printf 'dumpsys:%s\n' "${'$'}*" >> "${'$'}UCLONE_COMMAND_LOG"
                    cat "${'$'}RUNTIME_CAPTURE"
                    exit "${'$'}RUNTIME_CAPTURE_EXIT"
                """,
            )
        }
        val portableScript = script(
            targetUser = 10,
            mode = CrossUserInstallMode.INSTALL_WITH_PERMISSIONS,
            scriptSettings = settings.copy(rootDir = directory.resolve("root").absolutePath),
        )
            .replace("/system/bin/cmd", cmd.absolutePath)
            .replace("/system/bin/pm", pm.absolutePath)
            .replace("/system/bin/dumpsys", bin.resolve("dumpsys").absolutePath)
        val process = ProcessBuilder(
            "/bin/bash",
            "-c",
            listOf("set -o pipefail", portableScript).joinToString("\n"),
        ).apply {
            environment()["PATH"] = "${bin.absolutePath}:${System.getenv("PATH").orEmpty()}"
            environment()["UCLONE_COMMAND_LOG"] = commandLog.absolutePath
            environment()["RUNTIME_CAPTURE"] = runtimeCapture.absolutePath
            environment()["APPOPS_CAPTURE"] = appOpsCapture.absolutePath
            environment()["APPOPS_CAPTURE_EXIT"] = config.appOpsCaptureExit.toString()
            environment()["RUNTIME_CAPTURE_EXIT"] = config.runtimeCaptureExit.toString()
            environment()["FAIL_GRANT"] = config.failedGrant
            environment()["FAIL_APPOP"] = config.failedAppOp
        }.start()
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        assertTrue(commandLog.isFile)
        return HarnessResult(exitCode, stdout, stderr, commandLog.readText())
    }

    private fun File.writeExecutable(contents: String) {
        writeText(contents.trimIndent() + "\n")
        check(setExecutable(true))
        deleteOnExit()
    }

    private data class PermissionHarnessConfig(
        val runtimePermissions: List<String> = listOf("android.permission.CAMERA"),
        val appOps: List<String> = listOf("CAMERA: allow"),
        val failedGrant: String = "",
        val failedAppOp: String = "",
        val runtimeCaptureExit: Int = 0,
        val appOpsCaptureExit: Int = 0,
    )

    private data class HarnessResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val commands: String,
    )
}
