package com.uclone.restore.sync

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class RestoreTransactionShellTest {
    @Test
    fun rollbackHelperExitBecomesFatalMarkerAndStillRunsCleanup() {
        val result = runHarness("exit 59")

        assertEquals(91, result.exitCode)
        assertContains(result.stderr, "AUTO_ROLLBACK_FAILED originalExit=58")
        assertContains(result.stderr, "TRANSACTION_ROLLBACK_PRESERVED=")
        assertContains(result.stdout, "ROLLBACK_PRESERVED")
        assertContains(result.stdout, "CLEANUP_CALLED")
    }

    @Test
    fun successfulRollbackAttemptsEveryDataPartAndReportsRolledBack() {
        val result = runHarness("echo APPLIED:${'$'}1")

        assertEquals(90, result.exitCode)
        assertContains(result.stdout, "AUTO_ROLLBACK_SUCCESS originalExit=58")
        assertEquals(5, result.stdout.lineSequence().count { it.startsWith("APPLIED:") })
        assertContains(result.stdout, "APPLIED:/data/user/10/com.example.app")
        assertContains(result.stdout, "APPLIED:/data/user_de/10/com.example.app")
        assertContains(result.stdout, "APPLIED:/data/media/10/Android/obb/com.example.app")
        assertContains(result.stdout, "DOWNTIME=1000")
        assertContains(result.stdout, "CLEANUP_CALLED")
    }

    @Test
    fun missingPartStateFailsBeforeClearingThatTarget() {
        val result = runHarness("echo APPLIED:${'$'}1", missingState = "ce")

        assertEquals(91, result.exitCode)
        assertContains(result.stderr, "ERR_ROLLBACK_STATE_MISSING:ce")
        assertFalse(result.stdout.contains("CLEARED:/data/user/10/com.example.app"))
    }

    @Test
    fun stagedUnknownMarkerIsRemovedWhenFailureHappensBeforeTargetMutation() {
        val root = Files.createTempDirectory("uclone-marker-stage-")
        val rollback = Files.createTempDirectory("uclone-marker-rollback-")
        val marker = root.resolve("switches/com.example.app/active")
        val script = """
            set -u
            ROOT='${root.toAbsolutePath()}'
            PKG=com.example.app
            DST_USER=0
            ROLLBACK='${rollback.toAbsolutePath()}'
            UID_VALUE=10123
            TS=20260713-120003
            UCLONE_UNKNOWN_STATE_MARKER=UCLONE_STATE_UNKNOWN_V1
            UCLONE_TARGET_STOPPED_AT=1000
            validate_rollback_id() { :; }
            uclone_stage_begin() { :; }
            uclone_stage_end() { :; }
            uclone_now_ms() { echo 2000; }
            uclone_emit_metrics() { :; }
            uclone_perf_emit() { :; }
            force_stop_package_users() { :; }
            sync() { :; }
            ${RestoreTransactionShell.guard("UID_VALUE", includePermissions = false, manageSwitchMarker = true)}
            stage_switch_marker_unknown || exit 70
            [ -f '${marker.toAbsolutePath()}' ] && echo MARKER_STAGED
            exit 58
        """.trimIndent()
        val process = ProcessBuilder("/bin/sh").start()
        process.outputStream.bufferedWriter().use { it.write(script) }
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        assertEquals(58, exitCode, stderr)
        assertContains(stdout, "MARKER_STAGED")
        assertContains(stdout, "SWITCH_MARKER_STAGE_ROLLED_BACK")
        assertFalse(Files.exists(marker))
    }

    private fun runHarness(applyTargetSecurityBody: String, missingState: String? = null): HarnessResult {
        val rollback = Files.createTempDirectory("uclone-transaction-rollback-").toFile().apply { deleteOnExit() }
        val states = rollback.resolve(".state").apply { mkdirs(); deleteOnExit() }
        listOf("ce", "de", "external", "media", "obb").filterNot { it == missingState }.forEach { name ->
            states.resolve(name).apply {
                writeText("empty\n")
                deleteOnExit()
            }
        }
        val script = """
            set -u
            ROOT=/tmp/uclone-transaction-test
            PKG=com.example.app
            DST_USER=10
            ROLLBACK='${rollback.absolutePath}'
            UID_VALUE=10123
            UCLONE_TARGET_STOPPED_AT=1000
            uclone_stage_begin() { :; }
            uclone_stage_end() { :; }
            uclone_now_ms() { echo 2000; }
            uclone_emit_metrics() { echo "DOWNTIME=${'$'}UCLONE_TARGET_DOWNTIME_MS"; }
            uclone_perf_emit() { :; }
            count_items() { echo 0; }
            validate_target_path() { :; }
            read_target_context() { echo ''; }
            clear_target_contents() { echo "CLEARED:${'$'}1"; }
            mkdir() { :; }
            rm() { :; }
            sync() { :; }
            force_stop_package_users() { :; }
            restore_permission_state() { :; }
            apply_target_security() { $applyTargetSecurityBody; }
            cleanup_switch_temp() {
              if [ "${'$'}{TRANSACTION_ROLLBACK_PRESERVE:-0}" = "1" ]; then
                echo ROLLBACK_PRESERVED
              else
                echo ROLLBACK_CLEANED
              fi
              echo CLEANUP_CALLED
            }
            ${RestoreTransactionShell.guard("UID_VALUE", includePermissions = false, manageSwitchMarker = false)}
            TARGET_MUTATED=1
            exit 58
        """.trimIndent()
        val process = ProcessBuilder("/bin/sh").start()
        process.outputStream.bufferedWriter().use { it.write(script) }
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        return HarnessResult(process.waitFor(), stdout, stderr)
    }

    private data class HarnessResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )
}
