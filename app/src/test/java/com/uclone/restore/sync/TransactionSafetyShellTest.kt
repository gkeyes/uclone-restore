package com.uclone.restore.sync

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class TransactionSafetyShellTest {
    @Test
    fun journalIsPersistedBeforeMutationAndCarriesRecoveryState() {
        val script = TransactionSafetyShell.functions()

        assertContains(script, "transactions/${'$'}UCLONE_REQUEST_ID")
        assertContains(script, "transaction.json")
        assertContains(script, "\"schemaVersion\":2")
        assertContains(script, "\"selectedParts\":\"${'$'}TXN_PARTS_ESC\"")
        assertContains(script, "\"modifiedParts\":\"${'$'}TXN_MODIFIED_PARTS_ESC\"")
        assertContains(script, "\"originBootId\":\"${'$'}TXN_BOOT_ESC\"")
        assertContains(script, "\"originRootPid\":${'$'}UCLONE_TXN_ORIGIN_ROOT_PID")
        assertContains(script, "\"originRootPidStartTicks\":\"${'$'}TXN_PID_TICKS_ESC\"")
        assertContains(script, "ERR_TRANSACTION_SELECTED_PART:")
        assertContains(script, "uclone_transaction_target_mutating()")
        assertContains(script, "UCLONE_TXN_MODIFIED_PARTS=\"${'$'}UCLONE_TXN_MUTATING_PART\"")
        assertContains(script, "mv -f \"${'$'}TXN_TMP\" \"${'$'}TXN_JSON\"")
        assertContains(script, "uclone_transaction_target_mutating")
        assertContains(script, "uclone_transaction_cleanup_complete")
        assertContains(script, "UCLONE_TXN_STAGE=RECOVERY_REQUIRED")
        assertContains(script, "RECOVERY_REQUIRED:request=")
    }

    @Test
    fun executionGatePreservesAndRestoresExactEnabledState() {
        val script = TransactionSafetyShell.functions()

        assertContains(script, "previousEnabled=${'$'}GATE_ENABLED")
        assertContains(script, "previousSuspended=${'$'}GATE_SUSPENDED")
        assertContains(script, "previousStopped=${'$'}GATE_STOPPED")
        assertContains(script, "cmd package disable-user --user")
        assertContains(script, "cmd package default-state --user")
        assertContains(script, "cmd package disable-until-used --user")
        assertContains(script, "cmd package suspend --user")
        assertContains(script, "cmd package unsuspend --user")
        assertContains(script, "cmd package unstop --user")
        assertContains(script, "uclone_gate_can_restore_stopped_state")
        assertContains(script, "ERR_GATE_UNSTOP_UNSUPPORTED:user=${'$'}GATE_USER")
        val gateAcquire = script.substringAfter("uclone_gate_acquire() {")
        val compatibilityCheck = gateAcquire.indexOf("ERR_GATE_UNSTOP_UNSUPPORTED")
        val disableApp = gateAcquire.indexOf("cmd package disable-user --user")
        kotlin.test.assertTrue(compatibilityCheck in 0 until disableApp)
        assertContains(script, "am force-stop --user")
        assertContains(script, "RELEASE_CURRENT_STOPPED")
        assertContains(script, "RELEASE_CURRENT_SUSPENDED")
        assertContains(script, "ERR_GATE_SHARED_UID:")
        assertContains(script, "uclone_gate_is_critical_package")
        assertContains(script, "cmd package list packages -s --user")
        assertContains(script, "grep -Fqx \"package:${'$'}PKG\"")
        assertContains(script, "settings --user \"${'$'}CRITICAL_USER\" get secure default_input_method")
        assertContains(script, "resolve-activity --brief --user \"${'$'}CRITICAL_USER\"")
        assertContains(script, "android.intent.category.HOME")
        assertContains(script, "ERR_GATE_CRITICAL_PACKAGE:${'$'}PKG:user=${'$'}GATE_USER")
        assertContains(script, "uclone_app_process_exists")
        assertContains(script, "/proc/[0-9]*/cmdline")
        assertContains(script, "${'$'}PKG\":*) return 0")
        assertContains(script, "APP_GATE_HELD:")
        assertContains(script, "APP_GATE_RELEASED:")
        assertContains(script, "uclone_gate_abort_acquire")
        assertContains(script, "ERR_GATE_ACQUIRE_ROLLBACK:")
        assertContains(script, "UCLONE_GATE_DIR=\"${'$'}GATE_DIR\"")
        assertFalse(script.contains("pm enable --user"))
        assertFalse(script.contains("set-stopped-state"))
    }
}
