package com.uclone.restore.sync

import com.uclone.restore.model.BackupEntry
import com.uclone.restore.model.BackupKind
import com.uclone.restore.model.BackupRef
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
    val backups: List<BackupEntry> = emptyList(),
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

    fun backupsFor(packageName: String): List<BackupEntry> =
        backups.filter { it.packageName == packageName }.sortedByDescending { it.createdAt }

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
        val backups = mutableListOf<BackupEntry>()
        var completed = false
        var malformedStateRecord = false

        output.lineSequence().forEach { line ->
            val parts = line.split('\t', limit = 14)
            when (parts.firstOrNull()) {
                "SNAPSHOT" -> parseSnapshot(parts)?.let { (packageName, metadata) -> snapshots[packageName] = metadata }
                "BACKUP" -> parseBackup(parts)?.let(backups::add)
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
            backups = backups.sortedByDescending { it.createdAt },
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

    private fun parseBackup(parts: List<String>): BackupEntry? {
        val kind = parts.getOrNull(1)?.let(BackupKind::fromWireName) ?: return null
        val packageName = parts.getOrNull(2)?.takeIf(String::isNotBlank) ?: return null
        val id = parts.getOrNull(3)?.takeIf(String::isNotBlank) ?: return null
        return BackupEntry(
            packageName = packageName,
            ref = BackupRef(kind, id),
            createdAt = parts.getOrNull(4)?.toLongOrNull()?.times(1000) ?: 0L,
            sizeKb = parts.getOrNull(5)?.toLongOrNull(),
            sourceUserId = parts.getOrNull(6)?.toIntOrNull(),
            capturedFromUserId = parts.getOrNull(7)?.toIntOrNull(),
            stateKind = parts.getOrNull(8).toPassiveBackupStateKind(),
            typeLabel = parts.getOrNull(9)?.takeIf(String::isNotBlank) ?: kind.wireName,
            reason = parts.getOrNull(10)?.takeIf(String::isNotBlank).orEmpty(),
            name = parts.getOrNull(11)?.takeIf(String::isNotBlank)
                ?: parts.getOrNull(10)?.takeIf(String::isNotBlank)
                ?: id,
            path = parts.getOrNull(12)?.takeIf(String::isNotBlank).orEmpty(),
            deleteBlockedReason = parts.getOrNull(13)?.takeIf(String::isNotBlank),
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

    manifest_size_kb() {
      MSK_BYTES=${'$'}(wc -c < "${'$'}1" 2>/dev/null | awk '{print ${'$'}1}') || return 1
      case "${'$'}MSK_BYTES" in ''|*[!0-9]*) return 1 ;; esac
      [ "${'$'}MSK_BYTES" -gt 0 ] && [ "${'$'}MSK_BYTES" -le 16384 ] || return 1
      MSK_NULS=${'$'}(LC_ALL=C tr -cd '\000' < "${'$'}1" | wc -c | awk '{print ${'$'}1}') || return 1
      [ "${'$'}MSK_NULS" -eq 0 ] || return 1
      MSK_NEWLINES=${'$'}(LC_ALL=C tr -cd '\n' < "${'$'}1" | wc -c | awk '{print ${'$'}1}') || return 1
      [ "${'$'}MSK_NEWLINES" -le 1 ] || return 1
      MSK_JSON=${'$'}(cat "${'$'}1" 2>/dev/null) || return 1
      MSK_LINES=${'$'}(printf '%s\n' "${'$'}MSK_JSON" | awk 'END { print NR }')
      [ "${'$'}MSK_LINES" -eq 1 ] || return 1
      if printf '%s\n' "${'$'}MSK_JSON" | grep -q '[[:cntrl:]]'; then return 1; fi
      case "${'$'}MSK_JSON" in *'\'*) return 1 ;; esac
      printf '%s\n' "${'$'}MSK_JSON" | grep -Eq '^[[:space:]]*\{[[:space:]]*"[^"]+"[[:space:]]*:[[:space:]]*("[^"]*"|0|[1-9][0-9]*|true|false|null)([[:space:]]*,[[:space:]]*"[^"]+"[[:space:]]*:[[:space:]]*("[^"]*"|0|[1-9][0-9]*|true|false|null))*[[:space:]]*\}[[:space:]]*${'$'}' || return 1
      case "${'$'}MSK_JSON" in
        *'{'*'{'*|*'}'*'}'*|*'['*|*']'*) return 1 ;;
      esac
      MSK_AFTER_FIRST=${'$'}{MSK_JSON#*'"sizeKb"'}
      [ "${'$'}MSK_AFTER_FIRST" != "${'$'}MSK_JSON" ] || return 1
      case "${'$'}MSK_AFTER_FIRST" in *'"sizeKb"'*) return 1 ;; esac
      MSK_SIZE=${'$'}(printf '%s\n' "${'$'}MSK_JSON" | sed -n 's/.*"sizeKb"[[:space:]]*:[[:space:]]*"\([0-9][0-9]*\)"[[:space:]]*[,}].*/\1/p' | head -1)
      [ -n "${'$'}MSK_SIZE" ] || MSK_SIZE=${'$'}(printf '%s\n' "${'$'}MSK_JSON" | sed -n 's/.*"sizeKb"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\)[[:space:]]*[,}].*/\1/p' | head -1)
      case "${'$'}MSK_SIZE" in ''|*[!0-9]*) return 1 ;; esac
      [ "${'$'}{#MSK_SIZE}" -le 18 ] || return 1
      printf '%s\n' "${'$'}MSK_SIZE"
    }

    manifest_field() {
      MF_FILE="${'$'}1"
      MF_KEY="${'$'}2"
      [ -f "${'$'}MF_FILE" ] && [ ! -L "${'$'}MF_FILE" ] || return 1
      sed -n "s/.*\"${'$'}MF_KEY\":\"\([^\"]*\)\".*/\1/p" "${'$'}MF_FILE" | head -1
    }

    metadata_line() {
      ML_FILE="${'$'}1"
      [ -f "${'$'}ML_FILE" ] && [ ! -L "${'$'}ML_FILE" ] || return 1
      ML_LINES=${'$'}(awk 'END { print NR + 0 }' "${'$'}ML_FILE" 2>/dev/null) || return 1
      [ "${'$'}ML_LINES" -eq 1 ] || return 1
      sed -n '1p' "${'$'}ML_FILE" | tr '\t\r\n' '   '
    }

    numeric_or_empty() {
      NOE_VALUE="${'$'}1"
      case "${'$'}NOE_VALUE" in ''|*[!0-9]*) echo "" ;; *) echo "${'$'}NOE_VALUE" ;; esac
    }

    emit_backup() {
      EB_KIND="${'$'}1"
      EB_PKG="${'$'}2"
      EB_ID="${'$'}3"
      EB_DIR="${'$'}4"
      EB_TYPE="${'$'}5"
      EB_DEFAULT_NAME="${'$'}6"
      EB_DEFAULT_REASON="${'$'}7"
      EB_DEFAULT_SOURCE_USER="${'$'}8"
      EB_DEFAULT_CAPTURE_USER="${'$'}9"
      EB_DEFAULT_STATE="${'$'}{10}"
      EB_BLOCKED="${'$'}{11}"
      [ -d "${'$'}EB_DIR" ] && [ ! -L "${'$'}EB_DIR" ] || return 0
      EB_MANIFEST="${'$'}EB_DIR/manifest.json"
      [ -f "${'$'}EB_MANIFEST" ] && [ ! -L "${'$'}EB_MANIFEST" ] || return 0
      EB_TS=${'$'}(stat -c %Y "${'$'}EB_DIR" 2>/dev/null || echo 0)
      EB_SIZE=${'$'}(manifest_size_kb "${'$'}EB_MANIFEST") || EB_SIZE=${'$'}(du -sk "${'$'}EB_DIR" 2>/dev/null | awk '{print ${'$'}1}')
      EB_META="${'$'}EB_DIR/.uclone-meta"
      EB_NAME=${'$'}(metadata_line "${'$'}EB_META/name" 2>/dev/null || true)
      [ -n "${'$'}EB_NAME" ] || EB_NAME=${'$'}(manifest_field "${'$'}EB_MANIFEST" name 2>/dev/null || true)
      [ -n "${'$'}EB_NAME" ] || EB_NAME="${'$'}EB_DEFAULT_NAME"
      EB_REASON=${'$'}(manifest_field "${'$'}EB_MANIFEST" reason 2>/dev/null || true)
      [ -n "${'$'}EB_REASON" ] || EB_REASON="${'$'}EB_DEFAULT_REASON"
      EB_SOURCE=${'$'}(metadata_line "${'$'}EB_META/source_user_id" 2>/dev/null || true)
      [ -n "${'$'}EB_SOURCE" ] || EB_SOURCE=${'$'}(manifest_field "${'$'}EB_MANIFEST" sourceUser 2>/dev/null || true)
      [ -n "${'$'}EB_SOURCE" ] || EB_SOURCE="${'$'}EB_DEFAULT_SOURCE_USER"
      EB_CAPTURED=${'$'}(metadata_line "${'$'}EB_META/captured_from_user_id" 2>/dev/null || true)
      [ -n "${'$'}EB_CAPTURED" ] || EB_CAPTURED=${'$'}(manifest_field "${'$'}EB_MANIFEST" capturedFromUserId 2>/dev/null || true)
      [ -n "${'$'}EB_CAPTURED" ] || EB_CAPTURED="${'$'}EB_DEFAULT_CAPTURE_USER"
      EB_STATE=${'$'}(metadata_line "${'$'}EB_META/source_state_kind" 2>/dev/null || true)
      [ -n "${'$'}EB_STATE" ] || EB_STATE=${'$'}(manifest_field "${'$'}EB_MANIFEST" stateKind 2>/dev/null || true)
      [ -n "${'$'}EB_STATE" ] || EB_STATE="${'$'}EB_DEFAULT_STATE"
      EB_SOURCE=${'$'}(numeric_or_empty "${'$'}EB_SOURCE")
      EB_CAPTURED=${'$'}(numeric_or_empty "${'$'}EB_CAPTURED")
      EB_NAME=${'$'}(printf '%s' "${'$'}EB_NAME" | tr '\t\r\n' '   ')
      EB_REASON=${'$'}(printf '%s' "${'$'}EB_REASON" | tr '\t\r\n' '   ')
      printf 'BACKUP\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
        "${'$'}EB_KIND" "${'$'}EB_PKG" "${'$'}EB_ID" "${'$'}EB_TS" "${'$'}EB_SIZE" \
        "${'$'}EB_SOURCE" "${'$'}EB_CAPTURED" "${'$'}EB_STATE" "${'$'}EB_TYPE" \
        "${'$'}EB_REASON" "${'$'}EB_NAME" "${'$'}EB_DIR" "${'$'}EB_BLOCKED"
    }

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
        emit_backup active "${'$'}pkg" active "${'$'}(dirname "${'$'}f")" "active 主动快照" "active 主动快照" "主动备份" "${settings.cloneUserId}" "${settings.cloneUserId}" CLONE ""
      done
      for d in "${'$'}SNAPSHOT_ROOT"/*/history/*; do
        [ -d "${'$'}d" ] && [ ! -L "${'$'}d" ] || continue
        [ -f "${'$'}d/manifest.json" ] || continue
        pkg=${'$'}(basename "${'$'}(dirname "${'$'}(dirname "${'$'}d")")")
        id=${'$'}(basename "${'$'}d")
        case "${'$'}id" in ''|.|..|*[!A-Za-z0-9_.-]*) continue ;; esac
        emit_backup history "${'$'}pkg" "${'$'}id" "${'$'}d" "历史主动快照" "history ${'$'}id" "历史主动备份" "${settings.cloneUserId}" "${settings.cloneUserId}" CLONE ""
      done
      for d in "${'$'}SNAPSHOT_ROOT"/*/manual/*; do
        [ -d "${'$'}d" ] && [ ! -L "${'$'}d" ] || continue
        [ -f "${'$'}d/manifest.json" ] || continue
        pkg=${'$'}(basename "${'$'}(dirname "${'$'}(dirname "${'$'}d")")")
        id=${'$'}(basename "${'$'}d")
        case "${'$'}id" in ''|.|..|*[!A-Za-z0-9_.-]*) continue ;; esac
        emit_backup manual "${'$'}pkg" "${'$'}id" "${'$'}d" "命名备份" "命名备份 ${'$'}id" "手动命名备份" "" "" "" ""
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
        size=${'$'}(manifest_size_kb "${'$'}d/manifest.json") || size=${'$'}(du -sk "${'$'}d" 2>/dev/null | awk '{print ${'$'}1}')
        reason=${'$'}(sed -n 's/.*"reason":"\([^"]*\)".*/\1/p' "${'$'}d/manifest.json" | head -1)
        state_kind=${'$'}(sed -n 's/.*"stateKind":"\([^"]*\)".*/\1/p' "${'$'}d/manifest.json" | head -1)
        backup_kind=${'$'}(sed -n 's/.*"backupKind":"\([^"]*\)".*/\1/p' "${'$'}d/manifest.json" | head -1)
        printf 'MAIN_ROLLBACK\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "${'$'}pkg" "${'$'}id" "${'$'}ts" "${'$'}size" "${'$'}reason" "${'$'}state_kind" "${'$'}backup_kind"
        blocked=""
        if [ -f "${'$'}SWITCH_ROOT/${'$'}pkg/active" ] &&
           [ "${'$'}(sed -n '1p' "${'$'}SWITCH_ROOT/${'$'}pkg/active" | tr -d '\r')" = "${'$'}id" ]; then
          blocked="当前 CLONE 状态依赖的 MAIN 返回点"
        fi
        emit_backup main_rollback "${'$'}pkg" "${'$'}id" "${'$'}d" "主系统侧备份" "${'$'}reason" "${'$'}reason" "${settings.mainUserId}" "${settings.mainUserId}" "${'$'}state_kind" "${'$'}blocked"
      done
    fi

    if [ -d "${'$'}CLONE_ROLLBACK_ROOT" ]; then
      for d in "${'$'}CLONE_ROLLBACK_ROOT"/*/latest; do
        [ -d "${'$'}d" ] || continue
        [ -f "${'$'}d/manifest.json" ] || continue
        pkg=${'$'}(basename "${'$'}(dirname "${'$'}d")")
        ts=${'$'}(stat -c %Y "${'$'}d" 2>/dev/null || echo 0)
        size=${'$'}(manifest_size_kb "${'$'}d/manifest.json") || size=${'$'}(du -sk "${'$'}d" 2>/dev/null | awk '{print ${'$'}1}')
        reason=${'$'}(sed -n 's/.*"reason":"\([^"]*\)".*/\1/p' "${'$'}d/manifest.json" | head -1)
        backup_kind=${'$'}(sed -n 's/.*"backupKind":"\([^"]*\)".*/\1/p' "${'$'}d/manifest.json" | head -1)
        printf 'CLONE_ROLLBACK\t%s\t%s\t%s\t%s\t%s\t%s\n' "${'$'}pkg" latest "${'$'}ts" "${'$'}size" "${'$'}reason" "${'$'}backup_kind"
      done
      for d in "${'$'}CLONE_ROLLBACK_ROOT"/*/*; do
        [ -d "${'$'}d" ] && [ ! -L "${'$'}d" ] || continue
        [ -f "${'$'}d/manifest.json" ] || continue
        pkg=${'$'}(basename "${'$'}(dirname "${'$'}d")")
        id=${'$'}(basename "${'$'}d")
        case "${'$'}id" in ''|.|..|*[!A-Za-z0-9_.-]*) continue ;; esac
        emit_backup clone_rollback "${'$'}pkg" "${'$'}id" "${'$'}d" "分身侧回滚" "分身回滚 ${'$'}id" "分身回滚" "${settings.cloneUserId}" "${settings.cloneUserId}" CLONE ""
      done
    fi

    echo WORKSPACE_INDEX_OK
""".trimIndent()

private fun String?.toPassiveBackupStateKind(): PassiveBackupStateKind? = when (this?.uppercase()) {
    "MAIN" -> PassiveBackupStateKind.MAIN
    "CLONE" -> PassiveBackupStateKind.CLONE
    else -> null
}
