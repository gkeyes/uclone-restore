package com.uclone.restore.sync

import com.uclone.restore.model.AppRule
import com.uclone.restore.model.CrossUserInstallMode
import com.uclone.restore.model.UCloneSettings
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CrossUserInstallScriptsTest {
    @Test
    fun installOnlyNeverReadsCeOrCopiesAppData() {
        val script = build(CrossUserInstallMode.INSTALL_ONLY, targetUserId = 10)

        assertContains(script, "cmd package install-existing --user")
        assertContains(script, "ERR_INSTALL_PACKAGE_QUERY")
        assertContains(script, "SOURCE_PACKAGE_STATUS")
        assertContains(script, "TARGET_PACKAGE_STATUS")
        assertContains(script, "INSTALL_ONLY_DONE")
        assertFalse(script.contains("pm install-existing"))
        assertFalse(script.contains("INSTALL_FALLBACK"))
        assertFalse(script.contains("INSTALL_WAIT"))
        assertFalse(script.contains("sleep 1"))
        assertFalse(script.contains("ENSURE_CLONE_CE_BEGIN"))
        assertFalse(script.contains("/data/user/"))
        assertFalse(script.contains("/data/app"))
        assertFalse(script.contains("uclone_capture_permission_state"))
    }

    @Test
    fun permissionModeIsMergeOnlyAndKeepsTheInstalledPackageOnWarnings() {
        val script = build(CrossUserInstallMode.INSTALL_WITH_PERMISSIONS, targetUserId = 10)

        assertContains(script, "uclone_capture_permission_state")
        assertContains(script, "uclone_restore_permission_state")
        assertContains(script, "INSTALL_PERMISSIONS_DONE")
        assertContains(script, "mode=MERGE")
        assertFalse(script.contains("package revoke"))
        assertFalse(script.contains("appops reset"))
        assertFalse(script.contains("uninstall"))
    }

    @Test
    fun syncDirectionIsDerivedFromTheMissingSide() {
        val mainToClone = build(CrossUserInstallMode.INSTALL_AND_SYNC, targetUserId = 10)
        val cloneToMain = build(CrossUserInstallMode.INSTALL_AND_SYNC, targetUserId = 0)

        assertContains(mainToClone, "SRC_USER=0")
        assertContains(mainToClone, "DST_USER=10")
        assertContains(mainToClone, "PUSH_MAIN_TO_CLONE_DONE")
        assertContains(cloneToMain, "SRC_USER=10")
        assertContains(cloneToMain, "DST_USER=0")
        assertContains(cloneToMain, "COMPOSITE_STEP=CAPTURE_SNAPSHOT_FROM_CLONE")
        assertContains(cloneToMain, "COMPOSITE_STEP=RESTORE_SNAPSHOT_TO_MAIN")
    }

    @Test
    fun syncFailureReturnsPartialSuccessWithoutUninstallingThePackage() {
        val script = build(CrossUserInstallMode.INSTALL_AND_SYNC, targetUserId = 10)

        assertContains(script, "WARN_INSTALL_SYNC_FAILED")
        assertContains(script, "INSTALL_PARTIAL_SUCCESS")
        assertContains(script, "INSTALL_PACKAGE_PRESERVED")
        assertFalse(script.contains("uninstall"))
        assertFalse(script.contains("delete-package"))
    }

    @Test
    fun emptySuccessfulTargetQueryTriggersInstallAndVerification() {
        val directory = Files.createTempDirectory("uclone-cross-user-install")
        val installed = directory.resolve("target-installed")
        val cmd = writeExecutable(
            directory.resolve("cmd"),
            """
                case "${'$'}*" in
                  "package list packages --user 0 com.example.app") echo "package:com.example.app" ;;
                  "package list packages --user 10 com.example.app") [ ! -f ${shellQuote(installed.toString())} ] || echo "package:com.example.app" ;;
                  "package install-existing --user 10 com.example.app") : > ${shellQuote(installed.toString())}; echo "Package com.example.app installed for user: 10" ;;
                  "package list packages -U --user 10 com.example.app") [ -f ${shellQuote(installed.toString())} ] && echo "package:com.example.app uid:1010123" ;;
                  *) echo "UNEXPECTED_CMD:${'$'}*" >&2; exit 97 ;;
                esac
            """.trimIndent(),
        )
        val script = build(CrossUserInstallMode.INSTALL_ONLY, targetUserId = 10)
            .replace("/system/bin/cmd", shellQuote(cmd.toString()))

        val result = runShell(script)

        assertEquals(0, result.exitCode, result.output)
        assertTrue(Files.exists(installed), result.output)
        assertContains(result.output, "INSTALL_VERIFIED:user=10 package:com.example.app uid:1010123")
        assertContains(result.output, "INSTALL_ONLY_DONE targetUser=10")
    }

    @Test
    fun packageQueryCommandFailureDoesNotAttemptInstallation() {
        val directory = Files.createTempDirectory("uclone-cross-user-query-failure")
        val cmd = writeExecutable(directory.resolve("cmd"), "echo query-failed >&2; exit 9")
        val script = build(CrossUserInstallMode.INSTALL_ONLY, targetUserId = 10)
            .replace("/system/bin/cmd", shellQuote(cmd.toString()))

        val result = runShell(script)

        assertEquals(95, result.exitCode, result.output)
        assertContains(result.output, "ERR_INSTALL_PACKAGE_QUERY:user=0 exit=9")
        assertFalse(result.output.contains("install-existing"), result.output)
    }

    private fun build(mode: CrossUserInstallMode, targetUserId: Int): String =
        CrossUserInstallScripts.build(
            packageName = "com.example.app",
            targetUserId = targetUserId,
            mode = mode,
            rule = AppRule("com.example.app"),
            settings = UCloneSettings(mainUserId = 0, cloneUserId = 10, rootDir = "/data/adb/uclone"),
            appPackage = "com.uclone.restore",
        )

    private fun writeExecutable(path: Path, body: String): Path {
        Files.writeString(path, "#!/bin/sh\n$body\n")
        check(path.toFile().setExecutable(true))
        return path
    }

    private fun runShell(script: String): ShellRun {
        val process = ProcessBuilder("/bin/sh", "-c", script).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        return ShellRun(process.waitFor(), output)
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"

    private data class ShellRun(val exitCode: Int, val output: String)
}
