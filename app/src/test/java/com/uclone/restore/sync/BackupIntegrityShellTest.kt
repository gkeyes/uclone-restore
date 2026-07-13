package com.uclone.restore.sync

import com.uclone.restore.root.shellQuote
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class BackupIntegrityShellTest {
    @Test
    fun packageDigestUsesApkContentAndStableSplitName() {
        val script = BackupIntegrityShell.functions()

        assertContains(script, "PACKAGE_APK_NAME=${'$'}(basename \"${'$'}PACKAGE_APK_PATH\")")
        assertContains(script, "\"${'$'}PACKAGE_APK_SHA\" \"${'$'}PACKAGE_APK_NAME\"")
        assertFalse("\"${'$'}PACKAGE_APK_SHA\" \"${'$'}PACKAGE_APK_PATH\"" in script)
    }

    @Test
    fun versionNameReaderHasOneDefinition() {
        val script = BackupIntegrityShell.functions()

        assertEquals(1, Regex("uclone_package_version_name\\(\\)").findAll(script).count())
        assertContains(script, "grep '[[:cntrl:]]'")
    }

    @Test
    fun signingCertificateSetIsValidatedBeforeAnyManifestUsesIt() {
        val script = BackupIntegrityShell.functions()

        assertContains(script, "UCLONE_EXPECTED_SIGNING_CERTIFICATE_SHA256")
        assertContains(script, "ERR_PACKAGE_SIGNING_CERTIFICATE_UNAVAILABLE")
        assertContains(script, "UCLONE_SIGNING_CERTIFICATE_SHA256=")
    }

    @Test
    fun backupMetadataAndPartEntrypointsMustRemainCanonical() {
        val script = BackupIntegrityShell.functions()

        assertContains(script, "uclone_require_canonical_backup_directory()")
        assertContains(script, "uclone_require_canonical_backup_file()")
        assertContains(script, "uclone_require_canonical_backup_directory \"${'$'}VERIFY_ROOT/.state\"")
        assertContains(script, "uclone_require_canonical_backup_directory \"${'$'}VERIFY_ROOT/.meta\"")
        assertContains(script, "uclone_require_canonical_backup_file \"${'$'}VERIFY_STATE_FILE\"")
        assertContains(script, "uclone_require_canonical_backup_file \"${'$'}VERIFY_META_FILE\"")
        assertContains(script, "uclone_require_canonical_backup_directory \"${'$'}VERIFY_ROOT/${'$'}VERIFY_PART\"")
        assertContains(script, "[ ! -e \"${'$'}VERIFY_ROOT/${'$'}VERIFY_PART\" ] && [ ! -L \"${'$'}VERIFY_ROOT/${'$'}VERIFY_PART\" ]")
    }

    @Test
    fun partMeasurementUsesBoundedXargsBatchesInsteadOfFindExecPlus() {
        val functions = BackupIntegrityShell.functions()
        assertContains(functions, "xargs -0 -n 500")

        val root = Files.createTempDirectory("uclone-integrity-batch-").toFile()
        val payload = root.resolve("payload").apply { mkdirs() }
        repeat(501) { index -> payload.resolve("file $index.txt").writeText("x") }
        val bin = root.resolve("bin").apply { mkdirs() }
        bin.resolve("find").apply {
            writeText(
                """
                    #!/bin/sh
                    for arg in "${'$'}@"; do
                      [ "${'$'}arg" != "-exec" ] || exit 97
                    done
                    exec /usr/bin/find "${'$'}@"
                """.trimIndent() + "\n",
            )
            check(setExecutable(true))
        }
        bin.resolve("stat").apply {
            writeText(
                """
                    #!/bin/sh
                    [ "${'$'}1" = "-c" ] || exit 2
                    format="${'$'}2"
                    shift 2
                    for path in "${'$'}@"; do
                      case "${'$'}format" in
                        %s) wc -c < "${'$'}path" | tr -d ' ' ;;
                        *) printf '%s|regular file|0|0|600\n' "${'$'}path" ;;
                      esac
                    done
                """.trimIndent() + "\n",
            )
            check(setExecutable(true))
        }
        val script = """
            set -o pipefail
            UCLONE_EXPECTED_SIGNING_CERTIFICATE_SHA256=${"a".repeat(64)}
            ${functions}
            uclone_measure_part data ${shellQuote(payload.absolutePath)}
            printf 'items=%s bytes=%s digest=%s\n' "${'$'}UCLONE_META_ITEMS" "${'$'}UCLONE_META_BYTES" "${'$'}UCLONE_META_DIGEST"
        """.trimIndent()
        val process = ProcessBuilder("/bin/bash", "-c", script)
            .redirectErrorStream(true)
            .apply { environment()["PATH"] = "${bin.absolutePath}:${System.getenv("PATH")}" }
            .start()
        val output = process.inputStream.bufferedReader().readText()

        assertEquals(0, process.waitFor(), output)
        assertContains(output, "items=501 bytes=501 digest=")
        root.deleteRecursively()
    }

    @Test
    fun partMeasurementFailsWhenToyboxXargsMasksAStatFailure() {
        val root = Files.createTempDirectory("uclone-integrity-failure-").toFile()
        val payload = root.resolve("payload").apply { mkdirs() }
        payload.resolve("good.txt").writeText("good")
        payload.resolve("fail.txt").writeText("fail")
        val bin = root.resolve("bin").apply { mkdirs() }
        bin.resolve("stat").apply {
            writeText(
                """
                    #!/bin/sh
                    [ "${'$'}1" = "-c" ] || exit 2
                    format="${'$'}2"
                    shift 2
                    for path in "${'$'}@"; do
                      case "${'$'}path" in *fail.txt) exit 9 ;; esac
                      case "${'$'}format" in
                        %s) wc -c < "${'$'}path" | tr -d ' ' ;;
                        *) printf '%s|regular file|0|0|600\n' "${'$'}path" ;;
                      esac
                    done
                """.trimIndent() + "\n",
            )
            check(setExecutable(true))
        }
        bin.resolve("xargs").apply {
            writeText(
                """
                    #!/bin/sh
                    /usr/bin/xargs "${'$'}@"
                    exit 0
                """.trimIndent() + "\n",
            )
            check(setExecutable(true))
        }
        val script = """
            set -o pipefail
            UCLONE_EXPECTED_SIGNING_CERTIFICATE_SHA256=${"a".repeat(64)}
            ${BackupIntegrityShell.functions()}
            if uclone_measure_part data ${shellQuote(payload.absolutePath)}; then
              echo UNEXPECTED_MEASUREMENT_SUCCESS
              exit 0
            fi
            exit 41
        """.trimIndent()
        val process = ProcessBuilder("/bin/bash", "-c", script)
            .redirectErrorStream(true)
            .apply { environment()["PATH"] = "${bin.absolutePath}:${System.getenv("PATH")}" }
            .start()
        val output = process.inputStream.bufferedReader().readText()

        assertEquals(41, process.waitFor(), output)
        assertFalse(output.contains("UNEXPECTED_MEASUREMENT_SUCCESS"), output)
        root.deleteRecursively()
    }
}
