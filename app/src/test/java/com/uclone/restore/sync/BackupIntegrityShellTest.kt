package com.uclone.restore.sync

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
}
