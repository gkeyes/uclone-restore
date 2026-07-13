package com.uclone.restore.sync

internal object PermissionStateShell {
    fun functions(): String = """
        uclone_permission_require_directory() {
          PERMISSION_SAFE_DIRECTORY="${'$'}1"
          [ -d "${'$'}PERMISSION_SAFE_DIRECTORY" ] && [ ! -L "${'$'}PERMISSION_SAFE_DIRECTORY" ] || return 1
          PERMISSION_SAFE_DIRECTORY_REAL=${'$'}(readlink -f "${'$'}PERMISSION_SAFE_DIRECTORY" 2>/dev/null || true)
          [ "${'$'}PERMISSION_SAFE_DIRECTORY_REAL" = "${'$'}PERMISSION_SAFE_DIRECTORY" ]
        }
        uclone_permission_require_file() {
          PERMISSION_SAFE_FILE="${'$'}1"
          [ -f "${'$'}PERMISSION_SAFE_FILE" ] && [ ! -L "${'$'}PERMISSION_SAFE_FILE" ] || return 1
          PERMISSION_SAFE_FILE_REAL=${'$'}(readlink -f "${'$'}PERMISSION_SAFE_FILE" 2>/dev/null || true)
          [ "${'$'}PERMISSION_SAFE_FILE_REAL" = "${'$'}PERMISSION_SAFE_FILE" ]
        }
        uclone_permission_value() {
          PERMISSION_STATE_FILE="${'$'}1"
          PERMISSION_STATE_KEY="${'$'}2"
          awk -F= -v key="${'$'}PERMISSION_STATE_KEY" '${'$'}1 == key { sub(/^[^=]*=/, ""); print; exit }' "${'$'}PERMISSION_STATE_FILE" 2>/dev/null
        }
        uclone_write_permission_capture_state() {
          PERMISSION_DIR="${'$'}1"
          PERMISSION_USER="${'$'}2"
          RUNTIME_STATUS="${'$'}3"
          USER_BLOCK_FOUND="${'$'}4"
          RUNTIME_BLOCK_FOUND="${'$'}5"
          APPOPS_STATUS="${'$'}6"
          uclone_permission_require_directory "${'$'}PERMISSION_DIR" || return 1
          PERMISSION_STATE="${'$'}PERMISSION_DIR/capture.state"
          PERMISSION_STATE_TMP="${'$'}PERMISSION_STATE.tmp.${'$'}${'$'}"
          {
            echo "schema=1"
            echo "sourceUserId=${'$'}PERMISSION_USER"
            echo "runtimeStatus=${'$'}RUNTIME_STATUS"
            echo "runtimeUserBlockFound=${'$'}USER_BLOCK_FOUND"
            echo "runtimePermissionBlockFound=${'$'}RUNTIME_BLOCK_FOUND"
            echo "appOpsStatus=${'$'}APPOPS_STATUS"
          } > "${'$'}PERMISSION_STATE_TMP" || return 1
          chmod 600 "${'$'}PERMISSION_STATE_TMP" || return 1
          mv -f "${'$'}PERMISSION_STATE_TMP" "${'$'}PERMISSION_STATE" || return 1
        }
        uclone_capture_permission_state() {
          PERMISSION_DIR="${'$'}1"
          PERMISSION_USER="${'$'}2"
          CAPTURE_MODE="${'$'}{3:-MERGE}"
          case "${'$'}CAPTURE_MODE" in MERGE|EXACT) ;; *) return 1 ;; esac
          uclone_remove_tree "${'$'}PERMISSION_DIR" || return 1
          mkdir -p "${'$'}PERMISSION_DIR" || return 1
          uclone_permission_require_directory "${'$'}PERMISSION_DIR" || return 1
          PACKAGE_DUMP="${'$'}PERMISSION_DIR/package.dump.tmp"
          APPOPS_DUMP="${'$'}PERMISSION_DIR/appops.dump.tmp"
          RUNTIME_TMP="${'$'}PERMISSION_DIR/runtime_grants.tmp"
          APPOPS_TMP="${'$'}PERMISSION_DIR/appops.tmp"
          if ! /system/bin/dumpsys package "${'$'}PKG" > "${'$'}PACKAGE_DUMP" 2>/dev/null; then
            uclone_write_permission_capture_state "${'$'}PERMISSION_DIR" "${'$'}PERMISSION_USER" FAILED 0 0 NOT_CAPTURED || true
            rm -f "${'$'}PACKAGE_DUMP" "${'$'}APPOPS_DUMP" "${'$'}RUNTIME_TMP" "${'$'}APPOPS_TMP"
            echo "ERR_PERMISSION_CAPTURE_RUNTIME_COMMAND:user=${'$'}PERMISSION_USER" >&2
            return 1
          fi
          USER_BLOCK_FOUND=${'$'}(awk -v user="${'$'}PERMISSION_USER" '${'$'}0 ~ "^[[:space:]]*User " user ":" { print 1; exit }' "${'$'}PACKAGE_DUMP")
          [ "${'$'}USER_BLOCK_FOUND" = "1" ] || {
            uclone_write_permission_capture_state "${'$'}PERMISSION_DIR" "${'$'}PERMISSION_USER" FAILED 0 0 NOT_CAPTURED || true
            rm -f "${'$'}PACKAGE_DUMP" "${'$'}APPOPS_DUMP" "${'$'}RUNTIME_TMP" "${'$'}APPOPS_TMP"
            echo "ERR_PERMISSION_CAPTURE_USER_BLOCK:user=${'$'}PERMISSION_USER" >&2
            return 1
          }
          RUNTIME_BLOCK_FOUND=${'$'}(awk -v user="${'$'}PERMISSION_USER" '
            ${'$'}0 ~ "^[[:space:]]*User " user ":" { in_user=1; next }
            ${'$'}0 ~ "^[[:space:]]*User [0-9]+:" { in_user=0 }
            in_user && ${'$'}0 ~ "^[[:space:]]*runtime permissions:" { print 1; exit }
          ' "${'$'}PACKAGE_DUMP")
          [ -n "${'$'}RUNTIME_BLOCK_FOUND" ] || RUNTIME_BLOCK_FOUND=0
          if [ "${'$'}CAPTURE_MODE" = "EXACT" ] && [ "${'$'}RUNTIME_BLOCK_FOUND" != "1" ]; then
            uclone_write_permission_capture_state "${'$'}PERMISSION_DIR" "${'$'}PERMISSION_USER" FAILED 1 0 NOT_CAPTURED || true
            rm -f "${'$'}PACKAGE_DUMP" "${'$'}APPOPS_DUMP" "${'$'}RUNTIME_TMP" "${'$'}APPOPS_TMP"
            echo "ERR_PERMISSION_EXACT_UNVERIFIED_RUNTIME_BLOCK:user=${'$'}PERMISSION_USER" >&2
            return 1
          fi
          awk -v user="${'$'}PERMISSION_USER" '
            ${'$'}0 ~ "^[[:space:]]*User " user ":" { in_user=1; in_runtime=0; next }
            ${'$'}0 ~ "^[[:space:]]*User [0-9]+:" { in_user=0; in_runtime=0 }
            in_user && ${'$'}0 ~ "^[[:space:]]*runtime permissions:" { in_runtime=1; next }
            in_runtime && substr(${'$'}0, 1, 8) != "        " { in_runtime=0 }
            in_runtime && ${'$'}0 ~ "^[[:space:]]*[A-Za-z][A-Za-z0-9_.]*:" {
              name=${'$'}1; sub(":", "", name); if (${'$'}0 ~ "granted=true") print name; next
            }
          ' "${'$'}PACKAGE_DUMP" | sort -u > "${'$'}RUNTIME_TMP" || {
            uclone_write_permission_capture_state "${'$'}PERMISSION_DIR" "${'$'}PERMISSION_USER" FAILED 1 "${'$'}RUNTIME_BLOCK_FOUND" NOT_CAPTURED || true
            rm -f "${'$'}PACKAGE_DUMP" "${'$'}APPOPS_DUMP" "${'$'}RUNTIME_TMP" "${'$'}APPOPS_TMP"
            return 1
          }
          if ! /system/bin/cmd appops get --user "${'$'}PERMISSION_USER" "${'$'}PKG" > "${'$'}APPOPS_DUMP" 2>/dev/null; then
            uclone_write_permission_capture_state "${'$'}PERMISSION_DIR" "${'$'}PERMISSION_USER" VALID 1 "${'$'}RUNTIME_BLOCK_FOUND" FAILED || true
            rm -f "${'$'}PACKAGE_DUMP" "${'$'}APPOPS_DUMP" "${'$'}RUNTIME_TMP" "${'$'}APPOPS_TMP"
            echo "ERR_PERMISSION_CAPTURE_APPOPS_COMMAND:user=${'$'}PERMISSION_USER" >&2
            return 1
          fi
          awk '
            /^[A-Z0-9_()]+: (allow|ignore|deny|default|foreground|ask)/ {
              op=${'$'}1; sub(":", "", op); mode=${'$'}2; sub(";", "", mode); print op " " mode
            }
          ' "${'$'}APPOPS_DUMP" | sort -u > "${'$'}APPOPS_TMP" || return 1
          mv -f "${'$'}RUNTIME_TMP" "${'$'}PERMISSION_DIR/runtime_grants.txt" || return 1
          mv -f "${'$'}APPOPS_TMP" "${'$'}PERMISSION_DIR/appops.txt" || return 1
          chmod 600 "${'$'}PERMISSION_DIR/runtime_grants.txt" "${'$'}PERMISSION_DIR/appops.txt" || return 1
          uclone_write_permission_capture_state "${'$'}PERMISSION_DIR" "${'$'}PERMISSION_USER" VALID 1 "${'$'}RUNTIME_BLOCK_FOUND" VALID || return 1
          rm -f "${'$'}PACKAGE_DUMP" "${'$'}APPOPS_DUMP"
          PERMISSION_COUNT=${'$'}(wc -l < "${'$'}PERMISSION_DIR/runtime_grants.txt" | tr -d ' ')
          APPOPS_COUNT=${'$'}(wc -l < "${'$'}PERMISSION_DIR/appops.txt" | tr -d ' ')
          echo "PERMISSIONS_CAPTURED:user=${'$'}PERMISSION_USER grants=${'$'}PERMISSION_COUNT appops=${'$'}APPOPS_COUNT runtimeBlock=${'$'}RUNTIME_BLOCK_FOUND"
        }
        uclone_permission_capture_valid() {
          PERMISSION_DIR="${'$'}1"
          PERMISSION_STATE="${'$'}PERMISSION_DIR/capture.state"
          uclone_permission_require_directory "${'$'}PERMISSION_DIR" || return 1
          uclone_permission_require_file "${'$'}PERMISSION_STATE" || return 1
          [ "${'$'}(uclone_permission_value "${'$'}PERMISSION_STATE" schema)" = "1" ] || return 1
          [ "${'$'}(uclone_permission_value "${'$'}PERMISSION_STATE" runtimeStatus)" = "VALID" ] || return 1
          [ "${'$'}(uclone_permission_value "${'$'}PERMISSION_STATE" appOpsStatus)" = "VALID" ] || return 1
          uclone_permission_require_file "${'$'}PERMISSION_DIR/runtime_grants.txt" || return 1
          uclone_permission_require_file "${'$'}PERMISSION_DIR/appops.txt" || return 1
          return 0
        }
        uclone_permission_capture_exact_valid() {
          PERMISSION_DIR="${'$'}1"
          PERMISSION_STATE="${'$'}PERMISSION_DIR/capture.state"
          uclone_permission_capture_valid "${'$'}PERMISSION_DIR" || return 1
          [ "${'$'}(uclone_permission_value "${'$'}PERMISSION_STATE" runtimeUserBlockFound)" = "1" ] || return 1
          [ "${'$'}(uclone_permission_value "${'$'}PERMISSION_STATE" runtimePermissionBlockFound)" = "1" ] || return 1
          return 0
        }
        uclone_permission_capture_valid_for_user() {
          PERMISSION_DIR="${'$'}1"
          PERMISSION_EXPECTED_SOURCE_USER="${'$'}2"
          case "${'$'}PERMISSION_EXPECTED_SOURCE_USER" in ''|*[!0-9]*) return 1 ;; esac
          uclone_permission_capture_valid "${'$'}PERMISSION_DIR" || return 1
          [ "${'$'}(uclone_permission_value "${'$'}PERMISSION_DIR/capture.state" sourceUserId)" = "${'$'}PERMISSION_EXPECTED_SOURCE_USER" ]
        }
        uclone_restore_permission_state() {
          PERMISSION_DIR="${'$'}1"
          PERMISSION_USER="${'$'}2"
          RESTORE_MODE="${'$'}3"
          PERMISSION_EXPECTED_SOURCE_USER="${'$'}{4:-}"
          PERMISSION_STATE="${'$'}PERMISSION_DIR/capture.state"
          uclone_permission_capture_valid "${'$'}PERMISSION_DIR" || {
            echo "ERR_PERMISSION_CAPTURE_STATE_INVALID:${'$'}PERMISSION_DIR" >&2
            return 1
          }
          if [ -n "${'$'}PERMISSION_EXPECTED_SOURCE_USER" ]; then
            uclone_permission_capture_valid_for_user "${'$'}PERMISSION_DIR" "${'$'}PERMISSION_EXPECTED_SOURCE_USER" || {
              echo "ERR_PERMISSION_CAPTURE_SOURCE_USER_MISMATCH:${'$'}PERMISSION_DIR" >&2
              return 1
            }
          fi
          GRANTS_FILE="${'$'}PERMISSION_DIR/runtime_grants.txt"
          APPOPS_FILE="${'$'}PERMISSION_DIR/appops.txt"
          GRANT_COUNT=0
          REVOKE_COUNT=0
          APPOPS_COUNT=0
          PERMISSION_RESTORE_FAILURES=0
          CURRENT_PERMISSION_DIR=""
          if [ "${'$'}RESTORE_MODE" = "EXACT" ]; then
            uclone_permission_capture_exact_valid "${'$'}PERMISSION_DIR" || {
              echo "ERR_PERMISSION_EXACT_UNVERIFIED_RUNTIME_BLOCK" >&2
              return 1
            }
            CURRENT_PERMISSION_DIR="${'$'}ROOT/tmp/current_permissions_${'$'}{PKG}_${'$'}{UCLONE_REQUEST_ID}_${'$'}PERMISSION_USER"
            uclone_capture_permission_state "${'$'}CURRENT_PERMISSION_DIR" "${'$'}PERMISSION_USER" EXACT || return 1
            while IFS= read -r CURRENT_PERMISSION; do
              [ -n "${'$'}CURRENT_PERMISSION" ] || continue
              grep -Fxq "${'$'}CURRENT_PERMISSION" "${'$'}GRANTS_FILE" 2>/dev/null && continue
              if /system/bin/cmd package revoke --user "${'$'}PERMISSION_USER" "${'$'}PKG" "${'$'}CURRENT_PERMISSION" >/dev/null 2>&1 ||
                 /system/bin/pm revoke --user "${'$'}PERMISSION_USER" "${'$'}PKG" "${'$'}CURRENT_PERMISSION" >/dev/null 2>&1; then
                REVOKE_COUNT=${'$'}((REVOKE_COUNT + 1))
              else
                echo "WARN_REVOKE_FAILED:${'$'}CURRENT_PERMISSION"
                PERMISSION_RESTORE_FAILURES=${'$'}((PERMISSION_RESTORE_FAILURES + 1))
              fi
            done < "${'$'}CURRENT_PERMISSION_DIR/runtime_grants.txt"
          fi
          while IFS= read -r PERMISSION; do
            [ -n "${'$'}PERMISSION" ] || continue
            case "${'$'}PERMISSION" in *[!A-Za-z0-9_.]*|"") continue ;; esac
            if /system/bin/cmd package grant --user "${'$'}PERMISSION_USER" "${'$'}PKG" "${'$'}PERMISSION" >/dev/null 2>&1 ||
               /system/bin/pm grant --user "${'$'}PERMISSION_USER" "${'$'}PKG" "${'$'}PERMISSION" >/dev/null 2>&1; then
              GRANT_COUNT=${'$'}((GRANT_COUNT + 1))
            else
              echo "WARN_GRANT_FAILED:${'$'}PERMISSION"
              PERMISSION_RESTORE_FAILURES=${'$'}((PERMISSION_RESTORE_FAILURES + 1))
            fi
          done < "${'$'}GRANTS_FILE"
          if [ "${'$'}RESTORE_MODE" = "EXACT" ]; then
            if ! /system/bin/cmd appops reset --user "${'$'}PERMISSION_USER" "${'$'}PKG" >/dev/null 2>&1; then
              echo "WARN_APPOPS_RESET_FAILED"
              uclone_remove_tree "${'$'}CURRENT_PERMISSION_DIR" || true
              return 1
            fi
          elif [ "${'$'}RESTORE_MODE" != "MERGE" ]; then
            return 1
          fi
          while read -r OP MODE EXTRA; do
            [ -n "${'$'}OP" ] && [ -z "${'$'}EXTRA" ] || continue
            case "${'$'}MODE" in allow|ignore|deny|default|foreground|ask) ;; *) continue ;; esac
            if /system/bin/cmd appops set --user "${'$'}PERMISSION_USER" "${'$'}PKG" "${'$'}OP" "${'$'}MODE" >/dev/null 2>&1; then
              APPOPS_COUNT=${'$'}((APPOPS_COUNT + 1))
            else
              echo "WARN_APPOPS_FAILED:${'$'}OP:${'$'}MODE"
              PERMISSION_RESTORE_FAILURES=${'$'}((PERMISSION_RESTORE_FAILURES + 1))
            fi
          done < "${'$'}APPOPS_FILE"
          if ! /system/bin/cmd appops write-settings >/dev/null 2>&1; then
            echo "WARN_APPOPS_WRITE_SETTINGS_FAILED"
            PERMISSION_RESTORE_FAILURES=${'$'}((PERMISSION_RESTORE_FAILURES + 1))
          fi
          [ -z "${'$'}CURRENT_PERMISSION_DIR" ] || uclone_remove_tree "${'$'}CURRENT_PERMISSION_DIR"
          echo "RESTORED_PERMISSIONS:mode=${'$'}RESTORE_MODE grants=${'$'}GRANT_COUNT revokes=${'$'}REVOKE_COUNT appops=${'$'}APPOPS_COUNT failures=${'$'}PERMISSION_RESTORE_FAILURES"
          if [ "${'$'}RESTORE_MODE" = "EXACT" ] && [ "${'$'}PERMISSION_RESTORE_FAILURES" -gt 0 ]; then
            echo "ERR_PERMISSION_EXACT_PARTIAL:${'$'}PERMISSION_RESTORE_FAILURES" >&2
            return 1
          fi
          return 0
        }
    """.trimIndent()
}
