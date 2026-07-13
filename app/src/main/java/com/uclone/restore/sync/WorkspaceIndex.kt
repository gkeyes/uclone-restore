package com.uclone.restore.sync

import com.uclone.restore.model.RestoreBackupEntry
import com.uclone.restore.model.PassiveBackupStateKind
import com.uclone.restore.root.shellQuote

data class SnapshotMetadata(
    val updatedAt: Long,
    val sizeKb: Long?,
)

internal const val UNKNOWN_SWITCH_MARKER = "UCLONE_STATE_UNKNOWN_V1"

sealed interface AppDataState {
    data object Main : AppDataState

    data class Clone(val mainReturnPointId: String) : AppDataState

    data object Unknown : AppDataState
}

data class WorkspaceIndex(
    val snapshots: Map<String, SnapshotMetadata> = emptyMap(),
    val switchMarkers: Map<String, String> = emptyMap(),
    val switchMarkerSigningCertificates: Map<String, String> = emptyMap(),
    val unknownSwitchPackages: Set<String> = emptySet(),
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

    fun dataState(packageName: String): AppDataState = when {
        packageName in unknownSwitchPackages -> AppDataState.Unknown
        else -> switchMarkers[packageName]?.let(AppDataState::Clone) ?: AppDataState.Main
    }

    fun verifySwitchMarkerSignatures(expectedCertificate: (String) -> String?): WorkspaceIndex {
        val invalidPackages = switchMarkers.keys.filterTo(linkedSetOf()) { packageName ->
            switchMarkerSigningCertificates[packageName] != expectedCertificate(packageName)
        }
        if (invalidPackages.isEmpty()) return this
        val verifiedMarkers = switchMarkers.filterKeys { it !in invalidPackages }
        val verifiedCertificates = switchMarkerSigningCertificates.filterKeys { it !in invalidPackages }
        return copy(
            switchMarkers = verifiedMarkers,
            switchMarkerSigningCertificates = verifiedCertificates,
            unknownSwitchPackages = unknownSwitchPackages + invalidPackages,
            mainRollbackBackups = mainRollbackBackups.map { backup ->
                val isActive = verifiedMarkers[backup.packageName] == backup.rollbackId
                backup.copy(
                    isActiveSwitchBackup = isActive,
                    stateKind = backup.stateKind ?: PassiveBackupStateKind.MAIN.takeIf { isActive },
                )
            },
        )
    }
}

internal object WorkspaceIndexParser {
    fun parse(output: String): WorkspaceIndex {
        val snapshots = linkedMapOf<String, SnapshotMetadata>()
        val switchMarkers = linkedMapOf<String, String>()
        val switchMarkerSigningCertificates = linkedMapOf<String, String>()
        val unknownSwitchPackages = linkedSetOf<String>()
        val mainRollbacks = mutableListOf<MainRollbackRow>()
        val cloneRollbacks = mutableListOf<RestoreBackupEntry>()

        output.lineSequence().forEach { line ->
            val parts = line.split('\t', limit = 7)
            when (parts.firstOrNull()) {
                "SNAPSHOT" -> parseSnapshot(parts)?.let { (packageName, metadata) ->
                    snapshots[packageName] = metadata
                }
                "SWITCH" -> {
                    val packageName = parts.getOrNull(1)?.takeIf(String::isNotBlank)
                    parseSwitch(parts)?.let { row ->
                        switchMarkers[row.packageName] = row.rollbackId
                        switchMarkerSigningCertificates[row.packageName] = row.signingCertificateSha256
                    } ?: packageName?.let(unknownSwitchPackages::add)
                }
                "UNKNOWN" -> parts.getOrNull(1)?.takeIf(String::isNotBlank)?.let(unknownSwitchPackages::add)
                "MAIN_ROLLBACK" -> parseMainRollback(parts)?.let(mainRollbacks::add)
                "CLONE_ROLLBACK" -> parseCloneRollback(parts)?.let(cloneRollbacks::add)
            }
        }

        return WorkspaceIndex(
            snapshots = snapshots,
            switchMarkers = switchMarkers,
            switchMarkerSigningCertificates = switchMarkerSigningCertificates,
            unknownSwitchPackages = unknownSwitchPackages,
            mainRollbackBackups = mainRollbacks.map { row -> row.toBackup(switchMarkers[row.packageName]) },
            cloneRollbackBackups = cloneRollbacks.sortedByDescending { it.createdAt },
        )
    }

    private fun parseSnapshot(parts: List<String>): Pair<String, SnapshotMetadata>? {
        val packageName = parts.getOrNull(1)?.takeIf(String::isNotBlank) ?: return null
        val updatedAt = parts.getOrNull(2)?.toLongOrNull()?.times(1000) ?: return null
        return packageName to SnapshotMetadata(updatedAt, parts.getOrNull(3)?.toLongOrNull())
    }

    private fun parseSwitch(parts: List<String>): SwitchMarkerRow? {
        val packageName = parts.getOrNull(1)?.takeIf(String::isNotBlank) ?: return null
        val rollbackId = parts.getOrNull(2)?.takeIf(String::isNotBlank) ?: return null
        val signingCertificateSha256 = parts.getOrNull(3)?.takeIf(String::isNotBlank) ?: return null
        return SwitchMarkerRow(packageName, rollbackId, signingCertificateSha256)
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

    private data class SwitchMarkerRow(
        val packageName: String,
        val rollbackId: String,
        val signingCertificateSha256: String,
    )
}

internal fun workspaceIndexScript(
    rootDir: String,
    mainUserId: Int,
): String = """
    ${WorkspacePathGuard.inspect(rootDir)}
    SNAPSHOT_ROOT="${'$'}ROOT/snapshots"
    SWITCH_ROOT="${'$'}ROOT/switches"
    ROLLBACK_ROOT="${'$'}ROOT/rollback"
    CLONE_ROLLBACK_ROOT="${'$'}ROOT/clone_rollback"
    UNKNOWN_SWITCH_MARKER=${shellQuote(UNKNOWN_SWITCH_MARKER)}
    MAIN_USER=$mainUserId

    if [ -d "${'$'}SNAPSHOT_ROOT" ]; then
      for f in "${'$'}SNAPSHOT_ROOT"/*/active/manifest.json; do
        [ -f "${'$'}f" ] || continue
        pkg=${'$'}(basename "${'$'}(dirname "${'$'}(dirname "${'$'}f")")")
        ts=${'$'}(stat -c %Y "${'$'}f" 2>/dev/null || echo 0)
        size=${'$'}(sed -n 's/.*"snapshotSizeKb":"\([0-9][0-9]*\)".*/\1/p' "${'$'}f" | head -1)
        [ -n "${'$'}size" ] || size=${'$'}(du -skx "${'$'}(dirname "${'$'}f")" 2>/dev/null | awk '{print ${'$'}1}')
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
        size=${'$'}(du -skx "${'$'}d" 2>/dev/null | awk '{print ${'$'}1}')
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
        [ -n "${'$'}pkg" ] || continue
        if [ "${'$'}id" = "${'$'}UNKNOWN_SWITCH_MARKER" ]; then
          printf 'UNKNOWN\t%s\n' "${'$'}pkg"
        else
          case "${'$'}id" in
            ''|.|..|*[!A-Za-z0-9_.-]*)
              printf 'UNKNOWN\t%s\n' "${'$'}pkg"
              continue
              ;;
          esac
          marker_manifest="${'$'}ROLLBACK_ROOT/${'$'}pkg/${'$'}id/manifest.json"
          if [ -f "${'$'}marker_manifest" ] && [ ! -L "${'$'}marker_manifest" ]; then
            marker_manifest_real=${'$'}(readlink -f "${'$'}marker_manifest" 2>/dev/null || true)
            marker_schema=${'$'}(sed -n 's/.*"schemaVersion":\([0-9][0-9]*\).*/\1/p' "${'$'}marker_manifest" | head -1)
            marker_package=${'$'}(sed -n 's/.*"packageName":"\([^"]*\)".*/\1/p' "${'$'}marker_manifest" | head -1)
            marker_state=${'$'}(sed -n 's/.*"stateKind":"\([^"]*\)".*/\1/p' "${'$'}marker_manifest" | head -1)
            marker_kind=${'$'}(sed -n 's/.*"backupKind":"\([^"]*\)".*/\1/p' "${'$'}marker_manifest" | head -1)
            marker_signing_certificate=${'$'}(sed -n 's/.*"sourceSigningCertificateSha256":"\([0-9a-f,]*\)".*/\1/p' "${'$'}marker_manifest" | head -1)
            marker_source_user=${'$'}(${manifestUserIdReadCommand("sourceUser", "\"${'$'}marker_manifest\"")})
            marker_target_user=${'$'}(${manifestUserIdReadCommand("targetUser", "\"${'$'}marker_manifest\"")})
            if [ "${'$'}marker_manifest_real" = "${'$'}marker_manifest" ] &&
              [ "${'$'}marker_schema" = "$CURRENT_MANIFEST_SCHEMA" ] &&
              [ "${'$'}marker_package" = "${'$'}pkg" ] &&
              [ "${'$'}marker_state" = "main" ] &&
              [ "${'$'}marker_kind" = "rollback" ] &&
              [ -n "${'$'}marker_signing_certificate" ] &&
              [ "${'$'}marker_source_user" = "${'$'}MAIN_USER" ] &&
              [ "${'$'}marker_target_user" = "${'$'}MAIN_USER" ]; then
              printf 'SWITCH\t%s\t%s\t%s\n' "${'$'}pkg" "${'$'}id" "${'$'}marker_signing_certificate"
            else
              printf 'UNKNOWN\t%s\n' "${'$'}pkg"
            fi
          else
            printf 'UNKNOWN\t%s\n' "${'$'}pkg"
          fi
        fi
      done
    fi

    if [ -d "${'$'}CLONE_ROLLBACK_ROOT" ]; then
      for d in "${'$'}CLONE_ROLLBACK_ROOT"/*/latest; do
        [ -d "${'$'}d" ] || continue
        [ -f "${'$'}d/manifest.json" ] || continue
        pkg=${'$'}(basename "${'$'}(dirname "${'$'}d")")
        ts=${'$'}(stat -c %Y "${'$'}d" 2>/dev/null || echo 0)
        size=${'$'}(du -skx "${'$'}d" 2>/dev/null | awk '{print ${'$'}1}')
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
