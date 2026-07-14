package com.uclone.restore.sync

import com.uclone.restore.model.PassiveBackupStateKind
import com.uclone.restore.model.RestoreBackupEntry
import com.uclone.restore.root.shellQuote

data class SnapshotMetadata(
    val updatedAt: Long,
    val sizeKb: Long?,
)

internal const val UNKNOWN_SWITCH_MARKER = "UCLONE_STATE_UNKNOWN_V1"
internal const val MAIN_SWITCH_MARKER = "UCLONE_STATE_MAIN_V1"

sealed interface AppDataState {
    data object Main : AppDataState

    data class Clone(val mainReturnPointId: String) : AppDataState

    data object Unknown : AppDataState
}

data class WorkspaceIndex(
    val readSucceeded: Boolean = true,
    val snapshots: Map<String, SnapshotMetadata> = emptyMap(),
    val switchMarkers: Map<String, String> = emptyMap(),
    val confirmedMainPackages: Set<String> = emptySet(),
    val unknownSwitchPackages: Set<String> = emptySet(),
    val mainRollbackBackups: List<RestoreBackupEntry> = emptyList(),
    val cloneRollbackBackups: List<RestoreBackupEntry> = emptyList(),
) {
    val restoreBackups: List<RestoreBackupEntry>
        get() = mainRollbackBackups
            .groupBy { it.packageName }
            .values
            .flatMap { backups ->
                val fixedMainReturnPoint = backups
                    .filter {
                        it.rollbackId == "persistent_main" &&
                            it.stateKind == PassiveBackupStateKind.MAIN
                    }
                    .maxByOrNull { it.createdAt }
                val labeled = PassiveBackupStateKind.entries.mapNotNull { kind ->
                    backups.filter { it.stateKind == kind }.maxByOrNull { it.createdAt }
                }
                (listOfNotNull(fixedMainReturnPoint) + labeled)
                    .distinctBy { it.rollbackId }
                    .ifEmpty { listOfNotNull(backups.maxByOrNull { it.createdAt }) }
            }
            .sortedByDescending { it.createdAt }

    fun rollbackIds(packageName: String): List<String> =
        mainRollbackBackups.filter { it.packageName == packageName }.map { it.rollbackId }

    fun hasConfirmedMainState(packageName: String): Boolean = packageName in confirmedMainPackages

    fun dataState(packageName: String): AppDataState = when {
        !readSucceeded -> AppDataState.Unknown
        packageName in unknownSwitchPackages -> AppDataState.Unknown
        packageName in confirmedMainPackages -> AppDataState.Main
        else -> switchMarkers[packageName]?.let(AppDataState::Clone) ?: AppDataState.Main
    }
}

internal object WorkspaceIndexParser {
    fun parse(output: String): WorkspaceIndex {
        val snapshots = linkedMapOf<String, SnapshotMetadata>()
        val switchMarkers = linkedMapOf<String, String>()
        val confirmedMainPackages = linkedSetOf<String>()
        val unknownSwitchPackages = linkedSetOf<String>()
        val mainRollbacks = mutableListOf<RestoreBackupEntry>()
        val cloneRollbacks = mutableListOf<RestoreBackupEntry>()
        var completed = false
        var malformedStateRecord = false

        output.lineSequence().forEach { line ->
            val parts = line.split('\t', limit = 8)
            when (parts.firstOrNull()) {
                "SNAPSHOT" -> parseSnapshot(parts)?.let { (packageName, metadata) -> snapshots[packageName] = metadata }
                "SWITCH" -> {
                    val parsed = parseSwitch(parts)
                    if (parsed == null) {
                        malformedStateRecord = true
                        parts.getOrNull(1)?.takeIf(String::isNotBlank)?.let(unknownSwitchPackages::add)
                    } else {
                        switchMarkers[parsed.first] = parsed.second
                    }
                }
                "UNKNOWN" -> {
                    val packageName = parts.getOrNull(1)?.takeIf(String::isNotBlank)
                    if (packageName == null) malformedStateRecord = true else unknownSwitchPackages += packageName
                }
                "MAIN" -> {
                    val packageName = parts.getOrNull(1)?.takeIf(String::isNotBlank)
                    if (packageName == null) malformedStateRecord = true else confirmedMainPackages += packageName
                }
                "MAIN_ROLLBACK" -> parseMainRollback(parts, switchMarkers)?.let(mainRollbacks::add)
                "CLONE_ROLLBACK" -> parseCloneRollback(parts)?.let(cloneRollbacks::add)
                "WORKSPACE_INDEX_OK" -> completed = true
            }
        }

        val activeMarkers = switchMarkers.toMap()
        return WorkspaceIndex(
            readSucceeded = completed && !malformedStateRecord,
            snapshots = snapshots,
            switchMarkers = activeMarkers,
            confirmedMainPackages = confirmedMainPackages,
            unknownSwitchPackages = unknownSwitchPackages,
            mainRollbackBackups = mainRollbacks.map { backup ->
                val active = activeMarkers[backup.packageName] == backup.rollbackId
                backup.copy(
                    isActiveSwitchBackup = active,
                    stateKind = backup.stateKind ?: PassiveBackupStateKind.MAIN.takeIf { active },
                )
            },
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

    private fun parseMainRollback(
        parts: List<String>,
        switchMarkers: Map<String, String>,
    ): RestoreBackupEntry? {
        val packageName = parts.getOrNull(1)?.takeIf(String::isNotBlank) ?: return null
        val rollbackId = parts.getOrNull(2)?.takeIf(String::isNotBlank) ?: return null
        val active = switchMarkers[packageName] == rollbackId
        return RestoreBackupEntry(
            packageName = packageName,
            rollbackId = rollbackId,
            createdAt = parts.getOrNull(3)?.toLongOrNull()?.times(1000) ?: 0L,
            sizeKb = parts.getOrNull(4)?.toLongOrNull(),
            reason = parts.getOrNull(5)?.takeIf(String::isNotBlank) ?: "恢复或切换前生成",
            isActiveSwitchBackup = active,
            stateKind = parts.getOrNull(6).toPassiveBackupStateKind()
                ?: PassiveBackupStateKind.MAIN.takeIf { active },
            isPersistentStateBackup = parts.getOrNull(7) == "persistent_state",
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
            isPersistentStateBackup = parts.getOrNull(6) == "persistent_state",
        )
    }
}

internal fun workspaceIndexScript(rootDir: String): String = """
    ROOT=${shellQuote(rootDir)}
    SNAPSHOT_ROOT="${'$'}ROOT/snapshots"
    SWITCH_ROOT="${'$'}ROOT/switches"
    ROLLBACK_ROOT="${'$'}ROOT/rollback"
    CLONE_ROLLBACK_ROOT="${'$'}ROOT/clone_rollback"
    UNKNOWN_SWITCH_MARKER=${shellQuote(UNKNOWN_SWITCH_MARKER)}
    MAIN_SWITCH_MARKER=${shellQuote(MAIN_SWITCH_MARKER)}
    [ -d "${'$'}ROOT" ] && [ ! -L "${'$'}ROOT" ] || { echo "ERR_WORKSPACE_INDEX_ROOT:${'$'}ROOT" >&2; exit 2; }

    valid_main_return() {
      VMR_DIR="${'$'}1"
      [ -d "${'$'}VMR_DIR" ] && [ ! -L "${'$'}VMR_DIR" ] || return 1
      [ -f "${'$'}VMR_DIR/manifest.json" ] && [ ! -L "${'$'}VMR_DIR/manifest.json" ] || return 1
      VMR_STATE=${'$'}(sed -n 's/.*"stateKind":"\([^"]*\)".*/\1/p' "${'$'}VMR_DIR/manifest.json" | head -1)
      case "${'$'}VMR_STATE" in ''|MAIN) ;; *) return 1 ;; esac
      for VMR_PART in ce de external media obb; do
        VMR_STATE_FILE="${'$'}VMR_DIR/.state/${'$'}VMR_PART"
        [ -f "${'$'}VMR_STATE_FILE" ] && [ ! -L "${'$'}VMR_STATE_FILE" ] || return 1
        VMR_PART_STATE=${'$'}(sed -n '1p' "${'$'}VMR_STATE_FILE" | tr -d '\r')
        case "${'$'}VMR_PART_STATE" in
          data)
            [ -d "${'$'}VMR_DIR/${'$'}VMR_PART" ] && [ ! -L "${'$'}VMR_DIR/${'$'}VMR_PART" ] || return 1
            [ -n "${'$'}(find "${'$'}VMR_DIR/${'$'}VMR_PART" -mindepth 1 -print -quit 2>/dev/null)" ] || return 1
            ;;
          absent|empty)
            if [ -d "${'$'}VMR_DIR/${'$'}VMR_PART" ] &&
               [ -n "${'$'}(find "${'$'}VMR_DIR/${'$'}VMR_PART" -mindepth 1 -print -quit 2>/dev/null)" ]; then
              return 1
            fi
            ;;
          *) return 1 ;;
        esac
      done
      return 0
    }

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

    if [ -d "${'$'}SWITCH_ROOT" ]; then
      for f in "${'$'}SWITCH_ROOT"/*/active; do
        [ -e "${'$'}f" ] || [ -L "${'$'}f" ] || continue
        pkg=${'$'}(basename "${'$'}(dirname "${'$'}f")")
        [ -n "${'$'}pkg" ] || continue
        if [ -L "${'$'}f" ] || [ ! -f "${'$'}f" ]; then
          printf 'UNKNOWN\t%s\n' "${'$'}pkg"
          continue
        fi
        id=${'$'}(sed -n '1p' "${'$'}f" | tr -d '\r')
        if [ "${'$'}id" = "${'$'}UNKNOWN_SWITCH_MARKER" ]; then
          printf 'UNKNOWN\t%s\n' "${'$'}pkg"
          continue
        fi
        if [ "${'$'}id" = "${'$'}MAIN_SWITCH_MARKER" ]; then
          printf 'MAIN\t%s\n' "${'$'}pkg"
          continue
        fi
        case "${'$'}id" in ''|.|..|*[!A-Za-z0-9_.-]*) printf 'UNKNOWN\t%s\n' "${'$'}pkg"; continue ;; esac
        case "${'$'}id" in persistent_clone|persistent_clone.previous) printf 'UNKNOWN\t%s\n' "${'$'}pkg"; continue ;; esac
        if valid_main_return "${'$'}ROLLBACK_ROOT/${'$'}pkg/${'$'}id"; then
          printf 'SWITCH\t%s\t%s\n' "${'$'}pkg" "${'$'}id"
        else
          printf 'UNKNOWN\t%s\n' "${'$'}pkg"
        fi
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
        backup_kind=${'$'}(sed -n 's/.*"backupKind":"\([^"]*\)".*/\1/p' "${'$'}d/manifest.json" | head -1)
        printf 'MAIN_ROLLBACK\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "${'$'}pkg" "${'$'}id" "${'$'}ts" "${'$'}size" "${'$'}reason" "${'$'}state_kind" "${'$'}backup_kind"
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
        backup_kind=${'$'}(sed -n 's/.*"backupKind":"\([^"]*\)".*/\1/p' "${'$'}d/manifest.json" | head -1)
        printf 'CLONE_ROLLBACK\t%s\t%s\t%s\t%s\t%s\t%s\n' "${'$'}pkg" latest "${'$'}ts" "${'$'}size" "${'$'}reason" "${'$'}backup_kind"
      done
    fi

    echo WORKSPACE_INDEX_OK
""".trimIndent()

private fun String?.toPassiveBackupStateKind(): PassiveBackupStateKind? = when (this?.uppercase()) {
    "MAIN" -> PassiveBackupStateKind.MAIN
    "CLONE" -> PassiveBackupStateKind.CLONE
    else -> null
}
