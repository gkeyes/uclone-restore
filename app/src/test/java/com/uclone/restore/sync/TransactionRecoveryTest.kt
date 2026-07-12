package com.uclone.restore.sync

import com.uclone.restore.model.TaskType
import com.uclone.restore.model.UCloneSettings
import com.uclone.restore.root.RootShellExecutor
import com.uclone.restore.root.ShellResult
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class TransactionRecoveryTest {
    @Test
    fun probeParsesCompleteSafeRecords() {
        val parsed = TransactionRecoveryProbe.parse(
            """
                ignored
                TXN|request-1|com.example.app|TARGET_MUTATING|true|true|false|TARGET_HELD|0|ce,de,permissions|ce
            """.trimIndent(),
        )

        assertEquals(1, parsed.size)
        assertEquals("request-1", parsed.single().requestId)
        assertTrue(parsed.single().targetMutated)
        assertEquals(setOf("ce", "de", "permissions"), parsed.single().selectedParts)
        assertEquals(setOf("ce"), parsed.single().modifiedParts)
    }

    @Test
    fun malformedJournalFailsClosedInsteadOfDisappearing() {
        assertFailsWith<IllegalStateException> {
            TransactionRecoveryProbe.parse("TXN_INVALID|request-2|INVALID_BOOLEAN")
        }
        assertFailsWith<IllegalArgumentException> {
            TransactionRecoveryProbe.parse(
                "TXN|../unsafe|com.example.app|TARGET_MUTATING|true|true|false|target_HELD|0",
            )
        }
    }

    @Test
    fun admissionRemainsClosedUntilRecoveryScanCompletes() {
        val repository = TransactionRecoveryRepository()

        assertContains(requireNotNull(repository.blockingMessage(TaskType.SWITCH_TO_CLONE_STATE)), "正在检查")
        assertNotNull(repository.blockingMessage(TaskType.RECOVER_INTERRUPTED_TRANSACTION))

        repository.updateScan(emptyList(), liveRequestId = null)
        assertNull(repository.blockingMessage(TaskType.SWITCH_TO_CLONE_STATE))
        assertNotNull(repository.blockingMessage(TaskType.RECOVER_INTERRUPTED_TRANSACTION))
    }

    @Test
    fun liveRootTaskBlocksAdmissionWithoutADataTransaction() {
        val repository = TransactionRecoveryRepository()

        repository.updateScan(emptyList(), liveRequestId = "live-request")

        val state = assertIs<TransactionRecoveryState.RootTaskStillRunning>(repository.state.value)
        assertEquals("live-request", state.liveRequestId)
        assertEquals(emptyList(), state.transactions)
        assertContains(requireNotNull(repository.blockingMessage(TaskType.CAPTURE_SNAPSHOT_FROM_CLONE)), "仍在运行")
    }

    @Test
    fun unfinishedTransactionBlocksBusinessAdmissionButAllowsRecovery() {
        val transaction = InterruptedTransaction(
            requestId = "request-1",
            packageName = "com.example.app",
            stage = "TARGET_MUTATING",
            rollbackReady = true,
            targetMutated = true,
            committed = false,
            gateState = "TARGET_HELD",
            targetUserId = 0,
        )
        val recovery = TransactionRecoveryRepository.ready().apply {
            updateScan(listOf(transaction), liveRequestId = null)
        }
        val coordinator = TaskCoordinator(TaskLogStore(NoopShell), recovery)

        assertIs<TaskSubmissionResult.RecoveryRequired>(
            coordinator.accept("business", TaskType.SWITCH_TO_CLONE_STATE, "com.example.app"),
        )
        assertIs<TaskSubmissionResult.RecoveryRequired>(
            coordinator.accept("premature-recovery", TaskType.RECOVER_INTERRUPTED_TRANSACTION, "com.example.app"),
        )
        recovery.markRecovering(transaction, emptyList())
        assertIs<TaskSubmissionResult.Accepted>(
            coordinator.accept("recovery", TaskType.RECOVER_INTERRUPTED_TRANSACTION, "com.example.app"),
        )
    }

    @Test
    fun recoveryScriptKeepsGateUntilRollbackAndUsesFreshPermissionSnapshotStrictly() {
        val script = TransactionRecoveryShell.build("request-1", UCloneSettings())

        assertContains(script, "uclone_recovery_gate_ensure_held")
        assertContains(script, "uclone_transaction_part_requires_recovery")
        assertContains(script, "if [ -n \"${'$'}UCLONE_TXN_MODIFIED_PARTS\" ]; then")
        assertContains(script, "case \",${'$'}UCLONE_TXN_MODIFIED_PARTS,\" in")
        assertContains(script, "case \",${'$'}UCLONE_TXN_SELECTED_PARTS,\" in")
        assertContains(script, "if uclone_transaction_part_requires_recovery ce; then")
        assertContains(script, "if uclone_transaction_part_requires_recovery permissions; then")
        assertContains(script, "ERR_TRANSACTION_PERMISSION_ROLLBACK_MISSING")
        assertContains(script, "ERR_TRANSACTION_SIGNATURE_MISMATCH")
        assertContains(script, "case \"${'$'}ROLLBACK_SCHEMA\" in 3|4)")
        assertContains(script, "UCLONE_TXN_SELECTED_PARTS=")
        assertContains(script, "UCLONE_TXN_ORIGIN_BOOT_ID=")
        assertContains(script, "UCLONE_TXN_ORIGIN_ROOT_PID=")
        assertContains(script, "UCLONE_TXN_ORIGIN_PID_START_TICKS=")
        assertContains(script, "uclone_app_process_exists")
        assertContains(script, "uclone_verify_part_metadata")
        assertContains(script, "uclone_require_canonical_backup_file \"${'$'}ROLLBACK_MANIFEST\"")
        assertContains(script, "uclone_restore_permission_state \"${'$'}ROLLBACK/permissions\" \"${'$'}UCLONE_TXN_TARGET_USER\" EXACT")
        assertContains(script, "uclone_transaction_recovery_required")
        assertTrue(script.indexOf("uclone_transaction_rolled_back") < script.lastIndexOf("uclone_recovery_release_all_gates"))
    }

    private object NoopShell : RootShellExecutor {
        override suspend fun exec(command: String, timeoutSeconds: Long): ShellResult = ShellResult(0, "", "")
    }
}
