package com.uclone.restore.sync

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PermissionStateShellTest {
    @Test
    fun failedCaptureIsRecordedAndRestoreNeverUsesDestructiveExactSemantics() {
        val script = PermissionStateShell.functions()

        assertContains(script, "runtimeStatus=${'$'}RUNTIME_STATUS")
        assertContains(script, "appOpsStatus=${'$'}APPOPS_STATUS")
        assertContains(script, "WARN_PERMISSION_CAPTURE_RUNTIME_BLOCK")
        assertContains(script, "WARN_PERMISSION_CAPTURE_APPOPS_COMMAND")
        assertContains(script, "WARN_PERMISSION_CAPTURE_APPOPS_SCOPE")
        assertContains(script, "WARN_PERMISSION_APPOPS_SKIPPED")
        assertContains(script, "RESTORED_PERMISSIONS:mode=MERGE")
        assertContains(script, "APPOPS_STATUS=PACKAGE_VALID")
        assertFalse(script.contains("package revoke"))
        assertFalse(script.contains("appops reset"))
        assertFalse(script.contains("appops set --uid"))
    }

    @Test
    fun validEmptyCaptureRemainsDifferentFromCaptureFailure() {
        val script = PermissionStateShell.functions()

        assertContains(script, "RUNTIME_STATUS=FAILED")
        assertContains(script, "RUNTIME_STATUS=VALID")
        assertContains(script, "APPOPS_STATUS=FAILED")
        assertContains(script, "APPOPS_STATUS=PACKAGE_VALID")
        assertContains(script, "runtime=${'$'}RUNTIME_STATUS grants=${'$'}PERMISSION_COUNT")
        assertFalse(script.contains("| sort -u"))
    }

    @Test
    fun validEmptyPackageStateIsAcceptedWithoutCopyingUidScope() {
        val result = runCapture(
            dumpsysOutput = """
                Package [com.example.app]
                    User 0:
                        runtime permissions:
            """.trimIndent() + "\n",
            cmdBody = """
                case "${'$'}*" in
                  "package list packages -U --user 0 com.example.app") echo "package:com.example.app uid:10123" ;;
                  "appops get --user 0 com.example.app") echo "Uid mode: CAMERA: ignore" ;;
                  "appops get --user 0 10123") echo "Uid mode: CAMERA: ignore" ;;
                  *) echo "UNEXPECTED_CMD:${'$'}*" >&2; exit 97 ;;
                esac
            """.trimIndent(),
        )

        assertEquals(0, result.exitCode, result.output)
        assertContains(result.output, "runtimeStatus=VALID")
        assertContains(result.output, "appOpsStatus=PACKAGE_VALID")
        assertContains(result.output, "appOpsScope=PACKAGE")
        assertContains(result.output, "grants=0")
        assertContains(result.output, "appopsCount=0")
        assertContains(result.output, "WARN_APPOPS_UID_SCOPE_SKIPPED:user=0 count=1")
        assertFalse(result.output.lineSequence().any { it == "CAMERA ignore" }, result.output)
    }

    @Test
    fun appOpsCommandFailureIsRecordedWithoutFailingDataCapture() {
        val result = runCapture(
            dumpsysOutput = "Package [com.example.app]\n",
            cmdBody = """
                case "${'$'}*" in
                  "package list packages -U --user 0 com.example.app") echo "package:com.example.app uid:10123" ;;
                  "appops get --user 0 com.example.app") exit 9 ;;
                  *) echo "UNEXPECTED_CMD:${'$'}*" >&2; exit 97 ;;
                esac
            """.trimIndent(),
        )

        assertEquals(0, result.exitCode, result.output)
        assertContains(result.output, "WARN_PERMISSION_CAPTURE_RUNTIME_BLOCK:user=0")
        assertContains(result.output, "WARN_PERMISSION_CAPTURE_APPOPS_COMMAND:user=0")
        assertContains(result.output, "CAPTURE_EXIT=0")
        assertContains(result.output, "runtimeStatus=FAILED")
        assertContains(result.output, "appOpsStatus=FAILED")
    }

    @Test
    fun realShapeAppOpsAndRuntimeOutputAreParsedByExecutableShell() {
        val result = runCapture(
            dumpsysOutput = """
                Package [com.example.app]
                    User 0:
                        runtime permissions:
                        android.permission.CAMERA: granted=true, flags=[ USER_SET ]
                        android.permission.RECORD_AUDIO: granted=false, flags=[ USER_SET ]
            """.trimIndent() + "\n",
            cmdBody = """
                case "${'$'}*" in
                  "package list packages -U --user 0 com.example.app")
                    echo "package:com.example.app uid:10123"
                    ;;
                  "appops get --user 0 com.example.app")
                    cat <<'EOF'
                Uid mode: COARSE_LOCATION: ignore
                CAMERA: foreground
                CAMERA: allow; time=+1m ago
                RUN_ANY_IN_BACKGROUND: allow
                MIUIOP(10001): allow
                EOF
                    ;;
                  "appops get --user 0 10123")
                    cat <<'EOF'
                Uid mode: COARSE_LOCATION: ignore
                CAMERA: foreground
                EOF
                    ;;
                  *) echo "UNEXPECTED_CMD:${'$'}*" >&2; exit 97 ;;
                esac
            """.trimIndent(),
        )

        assertEquals(0, result.exitCode, result.output)
        assertContains(result.output, "runtimeStatus=VALID")
        assertContains(result.output, "appOpsStatus=PACKAGE_VALID")
        assertContains(result.output, "appOpsScope=PACKAGE")
        assertContains(result.output, "uidAppOpsSkipped=2")
        assertContains(result.output, "WARN_APPOPS_UID_SCOPE_SKIPPED:user=0 count=2")
        assertContains(result.output, "android.permission.CAMERA")
        assertContains(result.output, "CAMERA allow")
        assertContains(result.output, "RUN_ANY_IN_BACKGROUND allow")
        assertContains(result.output, "MIUIOP(10001) allow")
        assertFalse(result.output.contains("COARSE_LOCATION ignore"), result.output)
        assertFalse(result.output.contains("CAMERA foreground"), result.output)
    }

    @Test
    fun mismatchedUidPrefixFailsClosedWithoutFailingDataCapture() {
        val result = runCapture(
            dumpsysOutput = "Package [com.example.app]\n",
            cmdBody = """
                case "${'$'}*" in
                  "package list packages -U --user 0 com.example.app") echo "package:com.example.app uid:10123" ;;
                  "appops get --user 0 com.example.app") printf '%s\n' "Uid mode: CAMERA: allow" "CAMERA: foreground" ;;
                  "appops get --user 0 10123") echo "Uid mode: CAMERA: ignore" ;;
                  *) echo "UNEXPECTED_CMD:${'$'}*" >&2; exit 97 ;;
                esac
            """.trimIndent(),
        )

        assertEquals(0, result.exitCode, result.output)
        assertContains(result.output, "WARN_PERMISSION_CAPTURE_APPOPS_SCOPE:user=0")
        assertContains(result.output, "CAPTURE_EXIT=0")
        assertContains(result.output, "appOpsStatus=FAILED")
        assertContains(result.output, "appOpsScope=NONE")
        assertFalse(result.output.contains("CAMERA foreground"), result.output)
    }

    private fun runCapture(
        dumpsysOutput: String,
        cmdBody: String,
    ): ShellRun {
        val directory = Files.createTempDirectory("uclone-permission-capture")
        val bin = Files.createDirectories(directory.resolve("bin"))
        val dumpsysFixture = directory.resolve("dumpsys.fixture")
        Files.writeString(dumpsysFixture, dumpsysOutput)
        writeExecutable(bin.resolve("dumpsys"), "cat ${shellQuote(dumpsysFixture.toString())}")
        writeExecutable(bin.resolve("cmd"), cmdBody)
        val outputDirectory = directory.resolve("capture")
        val script = """
            set -u
            PKG=com.example.app
            OUT=${shellQuote(outputDirectory.toString())}
            ${PermissionStateShell.functions()}
            uclone_capture_permission_state "${'$'}OUT" 0
            echo "CAPTURE_EXIT=${'$'}?"
            cat "${'$'}OUT/capture.state"
            [ ! -f "${'$'}OUT/runtime_grants.txt" ] || cat "${'$'}OUT/runtime_grants.txt"
            [ ! -f "${'$'}OUT/appops.txt" ] || cat "${'$'}OUT/appops.txt"
        """.trimIndent()
        val process = ProcessBuilder("/bin/sh", "-c", script)
            .redirectErrorStream(true)
            .apply { environment()["PATH"] = "${bin.toAbsolutePath()}:${System.getenv("PATH")}" }
            .start()
        val output = process.inputStream.bufferedReader().readText()
        return ShellRun(process.waitFor(), output)
    }

    private fun writeExecutable(path: Path, body: String) {
        Files.writeString(path, "#!/bin/sh\n$body\n")
        check(path.toFile().setExecutable(true))
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"

    private data class ShellRun(val exitCode: Int, val output: String)
}
