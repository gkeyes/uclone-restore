package com.uclone.restore.sync

internal object StateBackupShell {
    fun functions(): String = """
        UCLONE_UNKNOWN_STATE_MARKER=${shellSingleQuote(UNKNOWN_SWITCH_MARKER)}
        UCLONE_MAIN_STATE_MARKER=${shellSingleQuote(MAIN_SWITCH_MARKER)}
        uclone_manifest_state_kind() {
          UCLONE_MSK_MANIFEST="${'$'}1/manifest.json"
          [ -f "${'$'}UCLONE_MSK_MANIFEST" ] && [ ! -L "${'$'}UCLONE_MSK_MANIFEST" ] || return 1
          sed -n 's/.*"stateKind":"\([^"]*\)".*/\1/p' "${'$'}UCLONE_MSK_MANIFEST" | head -1
        }
        uclone_valid_state_backup() {
          UCLONE_VSB_DIR="${'$'}1"
          UCLONE_VSB_EXPECTED="${'$'}2"
          [ -d "${'$'}UCLONE_VSB_DIR" ] && [ ! -L "${'$'}UCLONE_VSB_DIR" ] || return 1
          UCLONE_VSB_ACTUAL=${'$'}(uclone_manifest_state_kind "${'$'}UCLONE_VSB_DIR" 2>/dev/null || true)
          if [ -z "${'$'}UCLONE_VSB_ACTUAL" ] && [ "${'$'}UCLONE_VSB_EXPECTED" = "MAIN" ]; then
            UCLONE_VSB_ACTUAL=MAIN
          fi
          [ "${'$'}UCLONE_VSB_ACTUAL" = "${'$'}UCLONE_VSB_EXPECTED" ] || return 1
          for UCLONE_VSB_PART in ce de external media obb; do
            UCLONE_VSB_STATE_FILE="${'$'}UCLONE_VSB_DIR/.state/${'$'}UCLONE_VSB_PART"
            [ -f "${'$'}UCLONE_VSB_STATE_FILE" ] && [ ! -L "${'$'}UCLONE_VSB_STATE_FILE" ] || return 1
            UCLONE_VSB_PART_STATE=${'$'}(sed -n '1p' "${'$'}UCLONE_VSB_STATE_FILE" | tr -d '\r')
            case "${'$'}UCLONE_VSB_PART_STATE" in
              data)
                [ -d "${'$'}UCLONE_VSB_DIR/${'$'}UCLONE_VSB_PART" ] &&
                  [ ! -L "${'$'}UCLONE_VSB_DIR/${'$'}UCLONE_VSB_PART" ] &&
                  [ "${'$'}(find "${'$'}UCLONE_VSB_DIR/${'$'}UCLONE_VSB_PART" -mindepth 1 -print -quit 2>/dev/null)" != "" ] || return 1
                ;;
              absent|empty)
                if [ -d "${'$'}UCLONE_VSB_DIR/${'$'}UCLONE_VSB_PART" ] &&
                   [ "${'$'}(find "${'$'}UCLONE_VSB_DIR/${'$'}UCLONE_VSB_PART" -mindepth 1 -print -quit 2>/dev/null)" != "" ]; then
                  return 1
                fi
                ;;
              *) return 1 ;;
            esac
          done
          return 0
        }
        uclone_read_main_return_id() {
          UCLONE_RMR_MARKER="${'$'}ROOT/switches/${'$'}PKG/active"
          [ -f "${'$'}UCLONE_RMR_MARKER" ] && [ ! -L "${'$'}UCLONE_RMR_MARKER" ] || return 1
          UCLONE_RMR_ID=${'$'}(sed -n '1p' "${'$'}UCLONE_RMR_MARKER" | tr -d '\r')
          case "${'$'}UCLONE_RMR_ID" in ''|.|..|*[!A-Za-z0-9_.-]*) return 1 ;; esac
          case "${'$'}UCLONE_RMR_ID" in "${'$'}UCLONE_MAIN_STATE_MARKER"|persistent_clone|persistent_clone.previous) return 1 ;; esac
          UCLONE_RMR_DIR="${'$'}ROOT/rollback/${'$'}PKG/${'$'}UCLONE_RMR_ID"
          uclone_valid_state_backup "${'$'}UCLONE_RMR_DIR" MAIN || return 1
          printf '%s\n' "${'$'}UCLONE_RMR_ID"
        }
        uclone_current_main_state() {
          UCLONE_CMS_MARKER="${'$'}ROOT/switches/${'$'}PKG/active"
          if [ ! -e "${'$'}UCLONE_CMS_MARKER" ] && [ ! -L "${'$'}UCLONE_CMS_MARKER" ]; then
            echo MAIN
          elif [ ! -f "${'$'}UCLONE_CMS_MARKER" ] || [ -L "${'$'}UCLONE_CMS_MARKER" ]; then
            echo UNKNOWN
          else
            UCLONE_CMS_VALUE=${'$'}(sed -n '1p' "${'$'}UCLONE_CMS_MARKER" 2>/dev/null | tr -d '\r')
            if [ "${'$'}UCLONE_CMS_VALUE" = "${'$'}UCLONE_MAIN_STATE_MARKER" ]; then
              echo MAIN
            elif uclone_read_main_return_id >/dev/null 2>&1; then
              echo CLONE
            else
              echo UNKNOWN
            fi
          fi
        }
        uclone_confirmed_main_state() {
          UCLONE_CFM_MARKER="${'$'}ROOT/switches/${'$'}PKG/active"
          [ -f "${'$'}UCLONE_CFM_MARKER" ] && [ ! -L "${'$'}UCLONE_CFM_MARKER" ] || return 1
          UCLONE_CFM_VALUE=${'$'}(sed -n '1p' "${'$'}UCLONE_CFM_MARKER" 2>/dev/null | tr -d '\r')
          [ "${'$'}UCLONE_CFM_VALUE" = "${'$'}UCLONE_MAIN_STATE_MARKER" ]
        }
        uclone_select_transaction_state_backup() {
          UCLONE_SSB_TRANSACTION_DIR="${'$'}1"
          UCLONE_SSB_TRANSACTION_ID="${'$'}2"
          UCLONE_SSB_STATE_KIND="${'$'}3"
          [ "${'$'}UCLONE_SSB_STATE_KIND" = "MAIN" ] || return 1
          UCLONE_SSB_PERSISTENT_ID="persistent_main"
          UCLONE_SSB_PERSISTENT_DIR="${'$'}ROOT/rollback/${'$'}PKG/${'$'}UCLONE_SSB_PERSISTENT_ID"
          if uclone_valid_state_backup "${'$'}UCLONE_SSB_PERSISTENT_DIR" MAIN; then
            UCLONE_STATE_BACKUP_ID="${'$'}UCLONE_SSB_PERSISTENT_ID"
            UCLONE_STATE_BACKUP_PATH="${'$'}UCLONE_SSB_PERSISTENT_DIR"
            UCLONE_STATE_BACKUP_REUSED=1
            echo "MAIN_RETURN_SELECTED:path=${'$'}UCLONE_SSB_PERSISTENT_DIR mode=fixed"
          else
            uclone_valid_state_backup "${'$'}UCLONE_SSB_TRANSACTION_DIR" MAIN || return 1
            UCLONE_STATE_BACKUP_ID="${'$'}UCLONE_SSB_TRANSACTION_ID"
            UCLONE_STATE_BACKUP_PATH="${'$'}UCLONE_SSB_TRANSACTION_DIR"
            UCLONE_STATE_BACKUP_REUSED=0
            echo "MAIN_RETURN_SELECTED:path=${'$'}UCLONE_SSB_TRANSACTION_DIR mode=initialize"
          fi
        }
        uclone_promote_transaction_state_backup() {
          UCLONE_PSB_TRANSACTION_DIR="${'$'}1"
          UCLONE_PSB_TRANSACTION_ID="${'$'}2"
          UCLONE_PSB_STATE_KIND="${'$'}3"
          UCLONE_PSB_TARGET_USER="${'$'}4"
          [ "${'$'}UCLONE_PSB_STATE_KIND" = "MAIN" ] || return 1
          case "${'$'}UCLONE_PSB_TRANSACTION_DIR" in
            "${'$'}ROOT"/rollback/"${'$'}PKG"/"${'$'}UCLONE_PSB_TRANSACTION_ID") ;;
            *) echo "WARN_STATE_BACKUP_BAD_TRANSACTION_PATH:${'$'}UCLONE_PSB_TRANSACTION_DIR"; return 1 ;;
          esac
          [ -d "${'$'}UCLONE_PSB_TRANSACTION_DIR" ] && [ ! -L "${'$'}UCLONE_PSB_TRANSACTION_DIR" ] || return 1
          [ "${'$'}{UCLONE_READY_TO_COMMIT:-0}" = "1" ] || { echo "WARN_STATE_BACKUP_BEFORE_COMMIT"; return 1; }
          uclone_valid_state_backup "${'$'}UCLONE_PSB_TRANSACTION_DIR" "${'$'}UCLONE_PSB_STATE_KIND" || return 1
          UCLONE_PSB_PERSISTENT_ID="persistent_main"
          UCLONE_PSB_PERSISTENT_DIR="${'$'}ROOT/rollback/${'$'}PKG/${'$'}UCLONE_PSB_PERSISTENT_ID"
          UCLONE_PSB_PREVIOUS="${'$'}UCLONE_PSB_PERSISTENT_DIR.previous"
          [ ! -L "${'$'}UCLONE_PSB_PERSISTENT_DIR" ] || { echo "WARN_STATE_BACKUP_SYMLINK:${'$'}UCLONE_PSB_PERSISTENT_DIR"; return 1; }
          [ ! -L "${'$'}UCLONE_PSB_PREVIOUS" ] || { echo "WARN_STATE_BACKUP_SYMLINK:${'$'}UCLONE_PSB_PREVIOUS"; return 1; }
          [ ! -e "${'$'}UCLONE_PSB_PERSISTENT_DIR" ] || [ -d "${'$'}UCLONE_PSB_PERSISTENT_DIR" ] || return 1
          [ ! -e "${'$'}UCLONE_PSB_PREVIOUS" ] || [ -d "${'$'}UCLONE_PSB_PREVIOUS" ] || return 1
          UCLONE_PSB_SIZE_KB=${'$'}(du -sk "${'$'}UCLONE_PSB_TRANSACTION_DIR" 2>/dev/null | awk '{print ${'$'}1}')
          UCLONE_PSB_MANIFEST_TMP="${'$'}ROOT/rollback/${'$'}PKG/.manifest.state_${'$'}TS.tmp"
          UCLONE_PROMOTION_ORIGINAL_MANIFEST="${'$'}ROOT/rollback/${'$'}PKG/.manifest.transaction_${'$'}{RUN_ID:-${'$'}{TS}_${'$'}${'$'}}.tmp"
          rm -f "${'$'}UCLONE_PROMOTION_ORIGINAL_MANIFEST" >/dev/null 2>&1 || return 1
          cp "${'$'}UCLONE_PSB_TRANSACTION_DIR/manifest.json" "${'$'}UCLONE_PROMOTION_ORIGINAL_MANIFEST" || return 1
          printf '%s\n' "{\"packageName\":\"${'$'}PKG\",\"rollbackId\":\"${'$'}UCLONE_PSB_PERSISTENT_ID\",\"createdAt\":\"${'$'}TS\",\"reason\":\"固定 MAIN 返回点\",\"targetUser\":\"${'$'}UCLONE_PSB_TARGET_USER\",\"stateKind\":\"MAIN\",\"backupKind\":\"persistent_state\",\"sizeKb\":\"${'$'}UCLONE_PSB_SIZE_KB\"}" > "${'$'}UCLONE_PSB_MANIFEST_TMP" || { rm -f "${'$'}UCLONE_PROMOTION_ORIGINAL_MANIFEST"; return 1; }
          chmod 600 "${'$'}UCLONE_PSB_MANIFEST_TMP" >/dev/null 2>&1 || { rm -f "${'$'}UCLONE_PSB_MANIFEST_TMP" "${'$'}UCLONE_PROMOTION_ORIGINAL_MANIFEST"; return 1; }
          rm -rf "${'$'}UCLONE_PSB_PREVIOUS" || { rm -f "${'$'}UCLONE_PSB_MANIFEST_TMP" "${'$'}UCLONE_PROMOTION_ORIGINAL_MANIFEST"; return 1; }
          UCLONE_PSB_HAD_PREVIOUS=0
          if [ -d "${'$'}UCLONE_PSB_PERSISTENT_DIR" ]; then
            mv "${'$'}UCLONE_PSB_PERSISTENT_DIR" "${'$'}UCLONE_PSB_PREVIOUS" || { rm -f "${'$'}UCLONE_PSB_MANIFEST_TMP" "${'$'}UCLONE_PROMOTION_ORIGINAL_MANIFEST"; return 1; }
            UCLONE_PSB_HAD_PREVIOUS=1
          fi
          if ! mv "${'$'}UCLONE_PSB_TRANSACTION_DIR" "${'$'}UCLONE_PSB_PERSISTENT_DIR"; then
            rm -f "${'$'}UCLONE_PSB_MANIFEST_TMP"
            [ "${'$'}UCLONE_PSB_HAD_PREVIOUS" = "0" ] || mv "${'$'}UCLONE_PSB_PREVIOUS" "${'$'}UCLONE_PSB_PERSISTENT_DIR" >/dev/null 2>&1 || true
            rm -f "${'$'}UCLONE_PROMOTION_ORIGINAL_MANIFEST"
            return 1
          fi
          if ! mv -f "${'$'}UCLONE_PSB_MANIFEST_TMP" "${'$'}UCLONE_PSB_PERSISTENT_DIR/manifest.json"; then
            rm -f "${'$'}UCLONE_PSB_MANIFEST_TMP"
            mv "${'$'}UCLONE_PSB_PERSISTENT_DIR" "${'$'}UCLONE_PSB_TRANSACTION_DIR" >/dev/null 2>&1 || true
            [ "${'$'}UCLONE_PSB_HAD_PREVIOUS" = "0" ] || mv "${'$'}UCLONE_PSB_PREVIOUS" "${'$'}UCLONE_PSB_PERSISTENT_DIR" >/dev/null 2>&1 || true
            rm -f "${'$'}UCLONE_PROMOTION_ORIGINAL_MANIFEST"
            return 1
          fi
          sync
          UCLONE_STATE_BACKUP_ID="${'$'}UCLONE_PSB_PERSISTENT_ID"
          UCLONE_STATE_BACKUP_PATH="${'$'}UCLONE_PSB_PERSISTENT_DIR"
          UCLONE_STATE_BACKUP_REUSED=0
          echo "MAIN_RETURN_CREATED:path=${'$'}UCLONE_PSB_PERSISTENT_DIR"
          return 0
        }
        uclone_revert_promoted_state_backup() {
          UCLONE_RPS_TRANSACTION_DIR="${'$'}1"
          UCLONE_RPS_PERSISTENT_DIR="${'$'}ROOT/rollback/${'$'}PKG/persistent_main"
          UCLONE_RPS_PREVIOUS="${'$'}UCLONE_RPS_PERSISTENT_DIR.previous"
          [ -d "${'$'}UCLONE_RPS_PERSISTENT_DIR" ] && [ ! -L "${'$'}UCLONE_RPS_PERSISTENT_DIR" ] || return 1
          [ ! -e "${'$'}UCLONE_RPS_TRANSACTION_DIR" ] && [ ! -L "${'$'}UCLONE_RPS_TRANSACTION_DIR" ] || return 1
          mv "${'$'}UCLONE_RPS_PERSISTENT_DIR" "${'$'}UCLONE_RPS_TRANSACTION_DIR" || return 1
          if [ "${'$'}{UCLONE_PSB_HAD_PREVIOUS:-0}" = "1" ]; then
            if ! mv "${'$'}UCLONE_RPS_PREVIOUS" "${'$'}UCLONE_RPS_PERSISTENT_DIR"; then
              mv "${'$'}UCLONE_RPS_TRANSACTION_DIR" "${'$'}UCLONE_RPS_PERSISTENT_DIR" >/dev/null 2>&1 || true
              return 1
            fi
          fi
          if ! mv -f "${'$'}UCLONE_PROMOTION_ORIGINAL_MANIFEST" "${'$'}UCLONE_RPS_TRANSACTION_DIR/manifest.json"; then
            if [ "${'$'}{UCLONE_PSB_HAD_PREVIOUS:-0}" = "1" ]; then
              mv "${'$'}UCLONE_RPS_PERSISTENT_DIR" "${'$'}UCLONE_RPS_PREVIOUS" >/dev/null 2>&1 || true
            fi
            mv "${'$'}UCLONE_RPS_TRANSACTION_DIR" "${'$'}UCLONE_RPS_PERSISTENT_DIR" >/dev/null 2>&1 || true
            return 1
          fi
          sync
          return 0
        }
    """.trimIndent()

    private fun shellSingleQuote(value: String): String = "'${value.replace("'", "'\\''")}'"
}
