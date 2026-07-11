package com.uclone.restore.sync

import com.uclone.restore.model.RestoreBackupEntry
import com.uclone.restore.model.PassiveBackupStateKind
import com.uclone.restore.root.shellQuote

data class SnapshotMetadata(
    val updatedAt: Long,
    val sizeKb: Long?,
)

data class WorkspaceIndex(
    val snapshots: Map<String, SnapshotMetadata> = emptyMap(),
    val switchMarkers: Map<String, String> = emptyMap(),
    val mainRollbackBackups: List<RestoreBackupEntry> = emptyList(),
    val cloneRollbackBackups: List<RestoreBackupEntry> = emptyList(),
) {
    val restoreBackups: List<RestoreBackupEntry>
        get() = mainRollbackBackups
            .groupBy { it.packageName }
            .values
            .flatMap { backups ->
                val labeled = PassiveBackupStateKind.entries.mapNotNull { kind ->
                    backups.filter { it.stateKind == kind }.maxByOrNull { it.createdAt }
                }
                labeled.ifEmpty { listOfNotNull(backups.maxByOrNull { it.createdAt }) }
            }
            .sortedByDescending { it.createdAt }

    fun rollbackIds(packageName: String): List<String> =
        mainRollbackBackups.filter { it.packageName == packageName }.map { it.rollbackId }
}

internal object WorkspaceIndexParser {
    fun parse(output: String): WorkspaceIndex {
        val snapshots = linkedMapOf<String, SnapshotMetadata>()
        val switchMarkers = linkedMapOf<String, String>()
        val mainRollbacks = mutableListOf<MainRollbackRow>()
        val cloneRollbacks = mutableListOf<RestoreBackupEntry>()

        output.lineSequence().forEach { line ->
            val parts = line.split('\t', limit = 7)
            when (parts.firstOrNull()) {
                "SNAPSHOT" -> parseSnapshot(parts)?.let { (packageName, metadata) ->
                    snapshots[packageName] = metadata
                }
                "SWITCH" -> parseSwitch(parts)?.let { (packageName, rollbackId) ->
                    switchMarkers[packageName] = rollbackId
                }
                "MAIN_ROLLBACK" -> parseMainRollback(parts)?.let(mainRollbacks::add)
                "CLONE_ROLLBACK" -> parseCloneRollback(parts)?.let(cloneRollbacks::add)
            }
        }

        return WorkspaceIndex(
            snapshots = snapshots,
            switchMarkers = switchMarkers,
            mainRollbackBackups = mainRollbacks.map { row -> row.toBackup(switchMarkers[row.packageName]) },
            cloneRollbackBackups = cloneRollbacks.sortedByDescending { it.createdAt },
        )
    }

    private fun parseSnapshot(parts: List<String>): Pair<String, SnapshotMetadata>? {
        val packageName = parts.getOrNull(1)?.takeIf(String::isNotBlank) ?: return null
        val updatedAt = parts.getOrNull(2)?.toLongOrNull()?.times(1000) ?: return null
        return packageName to SnapshotMetadata(updatedAt, parts.getOrNull(3)?.toLongOrNull())
    }

    private fun parseSwitch(parts: List<String>): Pair<String, String>? {
        val packageName = parts.getOrNull(1)?.takeIf(String::isNotBlank) ?: return null
        val rollbackId = parts.getOrNull(2)?.takeIf(String::isNotBlank) ?: return null
        return packageName to rollbackId
    }

    private fun parseMainRollback(parts: List<String>): MainRollbackRow? {
        val packageName = parts.getOrNull(1)?.takeIf(String::isNotBlank) ?: return null
        val rollbackId = parts.getOrNull(2)?.takeIf(String::isNotBlank) ?: return null
        return MainRollbackRow(
            packageName = packageName,
            rollbackId = rollbackId,
            createdAt = parts.getOrNull(3)?.toLongOrNull()?.times(1000) ?: 0L,
            sizeKb = parts.getOrNull(4)?.toLongOrNull(),
            reason = parts.getOrNull(5).orEmpty(),
            stateKind = parts.getOrNull(6).toPassiveBackupStateKind(),
        )
    }

    private fun parseCloneRollback(parts: List<String>): RestoreBackupEntry? {
        val packageName = parts.getOrNull(1)?.takeIf(String::isNotBlank) ?: return null
        return RestoreBackupEntry(
            packageName = packageName,
            rollbackId = parts.getOrNull(2)?.takeIf(String::isNotBlank) ?: "latest",
            createdAt = parts.getOrNull(3)?.toLongOrNull()?.times(1000) ?: 0L,
            sizeKb = parts.getOrNull(4)?.toLongOrNull(),
            reason = parts.getOrNull(5)?.takeIf(String::isNotBlank) ?: "推送到分身前生成",
            isActiveSwitchBackup = false,
            isCloneRollback = true,
            stateKind = PassiveBackupStateKind.CLONE,
        )
    }

    private data class MainRollbackRow(
        val packageName: String,
        val rollbackId: String,
        val createdAt: Long,
        val sizeKb: Long?,
        val reason: String,
        val stateKind: PassiveBackupStateKind?,
    ) {
        fun toBackup(activeRollbackId: String?): RestoreBackupEntry {
            val active = rollbackId == activeRollbackId
            val fallbackReason = when {
                rollbackId.startsWith("rollback_") -> "恢复主系统备份前生成"
                active -> "切换到分身态前生成"
                else -> "恢复或切换前生成"
            }
            return RestoreBackupEntry(
                packageName = packageName,
                rollbackId = rollbackId,
                createdAt = createdAt,
                sizeKb = sizeKb,
                reason = reason.ifBlank { fallbackReason },
                isActiveSwitchBackup = active,
                stateKind = stateKind ?: if (active) PassiveBackupStateKind.MAIN else null,
            )
        }
    }
}

internal fun workspaceIndexScript(rootDir: String): String = """
    ROOT=${shellQuote(rootDir)}
    SNAPSHOT_ROOT="${'$'}ROOT/snapshots"
    SWITCH_ROOT="${'$'}ROOT/switches"
    ROLLBACK_ROOT="${'$'}ROOT/rollback"
    CLONE_ROLLBACK_ROOT="${'$'}ROOT/clone_rollback"

    if [ -d "${'$'}SNAPSHOT_ROOT" ]; then
      for f in "${'$'}SNAPSHOT_ROOT"/*/active/manifest.json; do
        [ -f "${'$'}f" ] || continue
        pkg=${'$'}(basename "${'$'}(dirname "${'$'}(dirname "${'$'}f")")")
        ts=${'$'}(stat -c %Y "${'$'}f" 2>/dev/null || echo 0)
        size=${'$'}(sed -n 's/.*"snapshotSizeKb":"\([0-9][0-9]*\)".*/\1/p' "${'$'}f" | head -1)
        [ -n "${'$'}size" ] || size=${'$'}(du -sk "${'$'}(dirname "${'$'}f")" 2>/dev/null | awk '{print ${'$'}1}')
        printf 'SNAPSHOT\t%s\t%s\t%s\n' "${'$'}pkg" "${'$'}ts" "${'$'}size"
      done
    fi

    if [ -d "${'$'}ROLLBACK_ROOT" ]; then
      for d in "${'$'}ROLLBACK_ROOT"/*/*; do
        [ -d "${'$'}d" ] || continue
        [ -f "${'$'}d/manifest.json" ] || continue
        pkg=${'$'}(basename "${'$'}(dirname "${'$'}d")")
        id=${'$'}(basename "${'$'}d")
        ts=${'$'}(stat -c %Y "${'$'}d" 2>/dev/null || echo 0)
        size=${'$'}(du -sk "${'$'}d" 2>/dev/null | awk '{print ${'$'}1}')
        reason=${'$'}(sed -n 's/.*"reason":"\([^"]*\)".*/\1/p' "${'$'}d/manifest.json" | head -1)
        state_kind=${'$'}(sed -n 's/.*"stateKind":"\([^"]*\)".*/\1/p' "${'$'}d/manifest.json" | head -1)
        printf 'MAIN_ROLLBACK\t%s\t%s\t%s\t%s\t%s\t%s\n' "${'$'}pkg" "${'$'}id" "${'$'}ts" "${'$'}size" "${'$'}reason" "${'$'}state_kind"
      done
    fi

    if [ -d "${'$'}SWITCH_ROOT" ]; then
      for f in "${'$'}SWITCH_ROOT"/*/active; do
        [ -f "${'$'}f" ] || continue
        pkg=${'$'}(basename "${'$'}(dirname "${'$'}f")")
        id=${'$'}(sed -n '1p' "${'$'}f" | tr -d '\r')
        [ -n "${'$'}pkg" ] && [ -n "${'$'}id" ] || continue
        [ -f "${'$'}ROLLBACK_ROOT/${'$'}pkg/${'$'}id/manifest.json" ] || continue
        printf 'SWITCH\t%s\t%s\n' "${'$'}pkg" "${'$'}id"
      done
    fi

    if [ -d "${'$'}CLONE_ROLLBACK_ROOT" ]; then
      for d in "${'$'}CLONE_ROLLBACK_ROOT"/*/latest; do
        [ -d "${'$'}d" ] || continue
        [ -f "${'$'}d/manifest.json" ] || continue
        pkg=${'$'}(basename "${'$'}(dirname "${'$'}d")")
        ts=${'$'}(stat -c %Y "${'$'}d" 2>/dev/null || echo 0)
        size=${'$'}(du -sk "${'$'}d" 2>/dev/null | awk '{print ${'$'}1}')
        reason=${'$'}(sed -n 's/.*"reason":"\([^"]*\)".*/\1/p' "${'$'}d/manifest.json" | head -1)
        printf 'CLONE_ROLLBACK\t%s\t%s\t%s\t%s\t%s\n' "${'$'}pkg" "latest" "${'$'}ts" "${'$'}size" "${'$'}reason"
      done
    fi
""".trimIndent()

private fun String?.toPassiveBackupStateKind(): PassiveBackupStateKind? = when (this?.lowercase()) {
    "main" -> PassiveBackupStateKind.MAIN
    "clone" -> PassiveBackupStateKind.CLONE
    else -> null
}
