package com.uclone.restore.sync

import com.uclone.restore.model.WorkspaceOwnershipReport
import com.uclone.restore.root.shellQuote

internal object WorkspaceOwnershipScripts {
    fun scan(rootDir: String): String = """
        set -u
        set -o pipefail || exit 73
        ${guard(rootDir)}
        echo "UCLONE_STAGE_BEGIN:PRECHECK"
        workspace_owner_scan
        echo "UCLONE_STAGE_BEGIN:VERIFY"
    """.trimIndent()

    fun repair(rootDir: String, expectedCanonicalRoot: String): String = """
        set -u
        set -o pipefail || exit 73
        ${guard(rootDir)}
        EXPECTED_CANONICAL_ROOT=${shellQuote(expectedCanonicalRoot)}
        [ "${'$'}ROOT_REAL" = "${'$'}EXPECTED_CANONICAL_ROOT" ] || {
          echo "ERR_WORKSPACE_SCAN_STALE:expected=${'$'}EXPECTED_CANONICAL_ROOT:actual=${'$'}ROOT_REAL" >&2
          exit 75
        }
        echo "UCLONE_STAGE_BEGIN:PRECHECK"
        workspace_owner_scan
        CHANGED=${'$'}WORKSPACE_NON_ROOT
        echo "UCLONE_STAGE_BEGIN:RESTORE_METADATA"
        if [ "${'$'}CHANGED" -gt 0 ]; then
          for NAME in snapshots rollback clone_rollback tmp; do
            workspace_owner_target "${'$'}NAME" || exit 76
            [ -n "${'$'}WORKSPACE_TARGET" ] || continue
            find "${'$'}WORKSPACE_TARGET" -xdev \( ! -uid 0 -o ! -gid 0 \) -print0 2>/dev/null |
              xargs -0 -n 500 sh -c '
                [ "${'$'}#" -gt 0 ] || exit 0
                chown -h 0:0 "${'$'}@" || exit 76
                echo "WORKSPACE_OWNER_BATCH changed=${'$'}#"
              ' sh || { echo "ERR_WORKSPACE_OWNER_REPAIR_FAILED:${'$'}WORKSPACE_TARGET" >&2; exit 76; }
          done
        fi
        echo "UCLONE_STAGE_BEGIN:VERIFY"
        workspace_owner_scan
        echo "WORKSPACE_OWNER_VERIFY remaining=${'$'}WORKSPACE_NON_ROOT"
        [ "${'$'}WORKSPACE_NON_ROOT" -eq 0 ] || exit 77
        echo "UCLONE_STAGE_BEGIN:COMMIT"
        mkdir -p "${'$'}ROOT_REAL/config" || exit 78
        printf '%s\n' 'root-owned-v1' > "${'$'}ROOT_REAL/config/workspace_owner_root_v1" || exit 78
        chmod 600 "${'$'}ROOT_REAL/config/workspace_owner_root_v1" || exit 78
        echo "WORKSPACE_OWNER_REPAIR_DONE changed=${'$'}CHANGED"
    """.trimIndent()

    private fun guard(rootDir: String): String = """
        ROOT_INPUT=${shellQuote(rootDir)}
        case "${'$'}ROOT_INPUT" in /*) ;; *) echo "ERR_UNSAFE_WORKSPACE_ROOT:${'$'}ROOT_INPUT" >&2; exit 74 ;; esac
        case "${'$'}ROOT_INPUT" in /|/data|/data/adb) echo "ERR_UNSAFE_WORKSPACE_ROOT:${'$'}ROOT_INPUT" >&2; exit 74 ;; esac
        [ ! -L "${'$'}ROOT_INPUT" ] || { echo "ERR_WORKSPACE_SYMLINK:${'$'}ROOT_INPUT" >&2; exit 74; }
        ROOT_REAL=${'$'}(readlink -f "${'$'}ROOT_INPUT" 2>/dev/null || true)
        [ -n "${'$'}ROOT_REAL" ] && [ "${'$'}ROOT_REAL" = "${'$'}ROOT_INPUT" ] || {
          echo "ERR_UNSAFE_WORKSPACE_ROOT:${'$'}ROOT_INPUT" >&2
          exit 74
        }
        if [ "${'$'}ROOT_REAL" != "/data/adb/uclone" ]; then
          IDENTITY="${'$'}ROOT_REAL/config/workspace.identity"
          [ -f "${'$'}IDENTITY" ] && [ ! -L "${'$'}IDENTITY" ] &&
            [ "${'$'}(sed -n '1p' "${'$'}IDENTITY" 2>/dev/null | tr -d '\r')" = "com.uclone.restore.workspace.v1" ] || {
              echo "ERR_UNTRUSTED_WORKSPACE:${'$'}ROOT_REAL" >&2
              exit 74
            }
        fi
        workspace_owner_target() {
          NAME="${'$'}1"
          case "${'$'}NAME" in snapshots|rollback|clone_rollback|tmp) ;; *) return 1 ;; esac
          WORKSPACE_TARGET="${'$'}ROOT_REAL/${'$'}NAME"
          [ -e "${'$'}WORKSPACE_TARGET" ] || { WORKSPACE_TARGET=""; return 0; }
          [ ! -L "${'$'}WORKSPACE_TARGET" ] || { echo "ERR_WORKSPACE_SYMLINK:${'$'}WORKSPACE_TARGET" >&2; return 1; }
          TARGET_REAL=${'$'}(readlink -f "${'$'}WORKSPACE_TARGET" 2>/dev/null || true)
          [ "${'$'}TARGET_REAL" = "${'$'}ROOT_REAL/${'$'}NAME" ] || return 1
        }
        workspace_owner_scan() {
          WORKSPACE_TOTAL=0
          WORKSPACE_NON_ROOT=0
          WORKSPACE_SIZE_KB=0
          for NAME in snapshots rollback clone_rollback tmp; do
            workspace_owner_target "${'$'}NAME" || exit 76
            [ -n "${'$'}WORKSPACE_TARGET" ] || continue
            TARGET_TOTAL=${'$'}(find "${'$'}WORKSPACE_TARGET" -xdev 2>/dev/null | wc -l | tr -d ' ') || exit 76
            TARGET_NON_ROOT=${'$'}(find "${'$'}WORKSPACE_TARGET" -xdev \( ! -uid 0 -o ! -gid 0 \) 2>/dev/null | wc -l | tr -d ' ') || exit 76
            TARGET_SIZE_KB=${'$'}(du -skx "${'$'}WORKSPACE_TARGET" 2>/dev/null | awk '{print ${'$'}1}') || exit 76
            case "${'$'}TARGET_TOTAL:${'$'}TARGET_NON_ROOT:${'$'}TARGET_SIZE_KB" in *[!0-9:]*) exit 76 ;; esac
            WORKSPACE_TOTAL=${'$'}((WORKSPACE_TOTAL + TARGET_TOTAL))
            WORKSPACE_NON_ROOT=${'$'}((WORKSPACE_NON_ROOT + TARGET_NON_ROOT))
            WORKSPACE_SIZE_KB=${'$'}((WORKSPACE_SIZE_KB + TARGET_SIZE_KB))
          done
          echo "WORKSPACE_OWNER_SCAN root=${'$'}ROOT_REAL total=${'$'}WORKSPACE_TOTAL nonRoot=${'$'}WORKSPACE_NON_ROOT sizeKb=${'$'}WORKSPACE_SIZE_KB"
        }
    """.trimIndent()
}

internal object WorkspaceOwnershipReportParser {
    private val pattern = Regex("^WORKSPACE_OWNER_SCAN root=(.+) total=([0-9]+) nonRoot=([0-9]+) sizeKb=([0-9]+)$")

    fun parse(output: String): WorkspaceOwnershipReport? = output.lineSequence()
        .map(String::trim)
        .mapNotNull(pattern::matchEntire)
        .lastOrNull()
        ?.let { match ->
            WorkspaceOwnershipReport(
                canonicalRoot = match.groupValues[1],
                totalEntries = match.groupValues[2].toLong(),
                nonRootEntries = match.groupValues[3].toLong(),
                totalSizeKb = match.groupValues[4].toLong(),
            )
        }
}
