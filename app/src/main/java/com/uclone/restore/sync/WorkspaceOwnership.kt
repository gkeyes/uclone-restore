package com.uclone.restore.sync

import com.uclone.restore.model.WorkspaceOwnershipReport
import com.uclone.restore.root.shellQuote

internal object WorkspaceOwnershipScripts {
    fun scan(rootDir: String): String = """
        set -u
        set -o pipefail || exit 73
        ${guard(rootDir)}
        workspace_owner_scan
    """.trimIndent()

    fun repair(rootDir: String): String = """
        set -u
        set -o pipefail || exit 73
        ${guard(rootDir)}
        echo "UCLONE_STAGE_BEGIN:PRECHECK"
        workspace_owner_scan
        CHANGED=${'$'}WORKSPACE_NON_ROOT
        echo "UCLONE_STAGE_BEGIN:RESTORE_METADATA"
        if [ "${'$'}CHANGED" -gt 0 ]; then
          for NAME in snapshots rollback clone_rollback tmp; do
            workspace_owner_target "${'$'}NAME" || exit 76
            [ -n "${'$'}WORKSPACE_TARGET" ] || continue
            find "${'$'}WORKSPACE_TARGET" -xdev \( ! -uid 0 -o ! -gid 0 \) -print0 2>/dev/null |
              ${'$'}WORKSPACE_LOW_PRIORITY xargs -0 -n 500 sh -c '
                [ "${'$'}#" -gt 0 ] || exit 0
                chown -h 0:0 "${'$'}@" || exit 76
                echo "WORKSPACE_OWNER_BATCH changed=${'$'}#"
              ' sh || { echo "ERR_WORKSPACE_OWNER_REPAIR_FAILED:${'$'}WORKSPACE_TARGET" >&2; exit 76; }
          done
        fi
        echo "UCLONE_STAGE_BEGIN:VERIFY"
        workspace_owner_scan
        echo "WORKSPACE_OWNER_VERIFY remaining=${'$'}WORKSPACE_NON_ROOT"
        [ "${'$'}WORKSPACE_NON_ROOT" -eq 0 ] || { echo "ERR_WORKSPACE_OWNER_REMAINING:${'$'}WORKSPACE_NON_ROOT" >&2; exit 77; }
        echo "UCLONE_STAGE_BEGIN:COMMIT"
        mkdir -p "${'$'}ROOT_REAL/config" || exit 78
        printf '%s\n' "root-owned-v1" > "${'$'}ROOT_REAL/config/workspace_owner_root_v1" || exit 78
        chmod 600 "${'$'}ROOT_REAL/config/workspace_owner_root_v1" || exit 78
        echo "WORKSPACE_OWNER_REPAIR_DONE changed=${'$'}CHANGED"
    """.trimIndent()

    private fun guard(rootDir: String): String = """
        ROOT=${shellQuote(rootDir)}
        ROOT_REAL=${'$'}(readlink -f "${'$'}ROOT" 2>/dev/null || true)
        [ -n "${'$'}ROOT_REAL" ] && [ -d "${'$'}ROOT_REAL" ] || { echo "ERR_WORKSPACE_MISSING:${'$'}ROOT" >&2; exit 74; }
        case "${'$'}ROOT_REAL" in
          /data/adb/uclone) ;;
          *)
            IDENTITY="${'$'}ROOT_REAL/config/workspace.identity"
            [ -f "${'$'}IDENTITY" ] && [ "${'$'}(sed -n '1p' "${'$'}IDENTITY" 2>/dev/null)" = "com.uclone.restore.workspace.v1" ] || {
              echo "ERR_UNTRUSTED_WORKSPACE:${'$'}ROOT_REAL" >&2
              exit 75
            }
            ;;
        esac
        command -v nice >/dev/null 2>&1 && WORKSPACE_LOW_PRIORITY="nice -n 10" || WORKSPACE_LOW_PRIORITY=""
        workspace_owner_target() {
          NAME="${'$'}1"
          case "${'$'}NAME" in snapshots|rollback|clone_rollback|tmp) ;; *) return 1 ;; esac
          WORKSPACE_TARGET="${'$'}ROOT_REAL/${'$'}NAME"
          [ -e "${'$'}WORKSPACE_TARGET" ] || { WORKSPACE_TARGET=""; return 0; }
          [ ! -L "${'$'}WORKSPACE_TARGET" ] || { echo "ERR_WORKSPACE_SYMLINK:${'$'}WORKSPACE_TARGET" >&2; return 1; }
          TARGET_REAL=${'$'}(readlink -f "${'$'}WORKSPACE_TARGET" 2>/dev/null || true)
          [ "${'$'}TARGET_REAL" = "${'$'}ROOT_REAL/${'$'}NAME" ] || { echo "ERR_UNSAFE_WORKSPACE_TARGET:${'$'}WORKSPACE_TARGET" >&2; return 1; }
        }
        workspace_owner_scan() {
          WORKSPACE_TOTAL=0
          WORKSPACE_NON_ROOT=0
          WORKSPACE_SIZE_KB=0
          for NAME in snapshots rollback clone_rollback tmp; do
            workspace_owner_target "${'$'}NAME" || exit 76
            [ -n "${'$'}WORKSPACE_TARGET" ] || continue
            TARGET_TOTAL=${'$'}(find "${'$'}WORKSPACE_TARGET" -xdev 2>/dev/null | wc -l | tr -d ' ') || {
              echo "ERR_WORKSPACE_SCAN:${'$'}WORKSPACE_TARGET:total" >&2
              exit 76
            }
            TARGET_NON_ROOT=${'$'}(find "${'$'}WORKSPACE_TARGET" -xdev \( ! -uid 0 -o ! -gid 0 \) 2>/dev/null | wc -l | tr -d ' ') || {
              echo "ERR_WORKSPACE_SCAN:${'$'}WORKSPACE_TARGET:owner" >&2
              exit 76
            }
            TARGET_SIZE_KB=${'$'}(du -sk "${'$'}WORKSPACE_TARGET" 2>/dev/null | awk '{print ${'$'}1}') || {
              echo "ERR_WORKSPACE_SCAN:${'$'}WORKSPACE_TARGET:size" >&2
              exit 76
            }
            [ -n "${'$'}TARGET_TOTAL" ] && [ -n "${'$'}TARGET_NON_ROOT" ] && [ -n "${'$'}TARGET_SIZE_KB" ] || {
              echo "ERR_WORKSPACE_SCAN:${'$'}WORKSPACE_TARGET:empty" >&2
              exit 76
            }
            case "${'$'}TARGET_TOTAL:${'$'}TARGET_NON_ROOT:${'$'}TARGET_SIZE_KB" in *[!0-9:]*) echo "ERR_WORKSPACE_SCAN:${'$'}WORKSPACE_TARGET" >&2; exit 76 ;; esac
            WORKSPACE_TOTAL=${'$'}((WORKSPACE_TOTAL + TARGET_TOTAL))
            WORKSPACE_NON_ROOT=${'$'}((WORKSPACE_NON_ROOT + TARGET_NON_ROOT))
            WORKSPACE_SIZE_KB=${'$'}((WORKSPACE_SIZE_KB + TARGET_SIZE_KB))
          done
          echo "WORKSPACE_OWNER_SCAN root=${'$'}ROOT_REAL total=${'$'}WORKSPACE_TOTAL nonRoot=${'$'}WORKSPACE_NON_ROOT sizeKb=${'$'}WORKSPACE_SIZE_KB"
        }
    """.trimIndent()
}

internal object WorkspaceOwnershipReportParser {
    private val scanPattern = Regex(
        "^WORKSPACE_OWNER_SCAN root=(.+) total=([0-9]+) nonRoot=([0-9]+) sizeKb=([0-9]+)$",
    )

    fun parse(output: String): WorkspaceOwnershipReport? = output.lineSequence()
        .map(String::trim)
        .mapNotNull(scanPattern::matchEntire)
        .lastOrNull()
        ?.let { match ->
            WorkspaceOwnershipReport(
                rootPath = match.groupValues[1],
                totalEntries = match.groupValues[2].toLong(),
                nonRootEntries = match.groupValues[3].toLong(),
                totalSizeKb = match.groupValues[4].toLong(),
            )
        }
}
