package com.uclone.restore.sync

internal object PermissionStateShell {
    fun functions(): String = """
        uclone_permission_value() {
          PERMISSION_STATE_FILE="${'$'}1"
          PERMISSION_STATE_KEY="${'$'}2"
          awk -F= -v key="${'$'}PERMISSION_STATE_KEY" '${'$'}1 == key { sub(/^[^=]*=/, ""); print; exit }' "${'$'}PERMISSION_STATE_FILE" 2>/dev/null
        }
        uclone_write_permission_capture_state() {
          PERMISSION_DIR="${'$'}1"
          PERMISSION_USER="${'$'}2"
          RUNTIME_STATUS="${'$'}3"
          APPOPS_STATUS="${'$'}4"
          APPOPS_SCOPE="${'$'}5"
          APPOPS_UID_SKIPPED="${'$'}6"
          PERMISSION_STATE="${'$'}PERMISSION_DIR/capture.state"
          PERMISSION_STATE_TMP="${'$'}PERMISSION_STATE.tmp.${'$'}${'$'}"
          {
            echo "schema=1"
            echo "sourceUserId=${'$'}PERMISSION_USER"
            echo "runtimeStatus=${'$'}RUNTIME_STATUS"
            echo "appOpsStatus=${'$'}APPOPS_STATUS"
            echo "appOpsScope=${'$'}APPOPS_SCOPE"
            echo "appOpsUidEntriesSkipped=${'$'}APPOPS_UID_SKIPPED"
          } > "${'$'}PERMISSION_STATE_TMP" || return 1
          chmod 600 "${'$'}PERMISSION_STATE_TMP" || return 1
          mv -f "${'$'}PERMISSION_STATE_TMP" "${'$'}PERMISSION_STATE" || return 1
        }
        uclone_normalize_appops_dump() {
          awk '
            {
              line=${'$'}0
              sub(/^[[:space:]]*/, "", line)
              sub(/^Uid mode:[[:space:]]*/, "", line)
              if (line ~ /^[A-Z0-9_()]+:[[:space:]]*(allow|ignore|deny|default|foreground|ask)(;|[[:space:]]|${'$'})/) {
                op=line
                sub(/:.*/, "", op)
                mode=line
                sub(/^[^:]*:[[:space:]]*/, "", mode)
                sub(/[;[:space:]].*/, "", mode)
                print op " " mode
              }
            }
          ' "${'$'}1"
        }
        uclone_appops_unique() {
          awk '
            NF != 2 { invalid=1; next }
            seen[${'$'}1]++ { invalid=1 }
            END { exit invalid ? 1 : 0 }
          ' "${'$'}1"
        }
        uclone_appops_uid_prefix_matches() {
          awk '
            FILENAME == ARGV[1] { expected[++expected_count]=${'$'}0; next }
            {
              actual_count++
              if (actual_count <= expected_count && ${'$'}0 != expected[actual_count]) mismatch=1
            }
            END {
              if (actual_count < expected_count) mismatch=1
              exit mismatch ? 1 : 0
            }
          ' "${'$'}1" "${'$'}2"
        }
        uclone_capture_permission_state() {
          PERMISSION_DIR="${'$'}1"
          PERMISSION_USER="${'$'}2"
          mkdir -p "${'$'}PERMISSION_DIR" || return 1
          PACKAGE_DUMP="${'$'}PERMISSION_DIR/package.dump.tmp"
          APPOPS_DUMP="${'$'}PERMISSION_DIR/appops.dump.tmp"
          APPOPS_UID_DUMP="${'$'}PERMISSION_DIR/appops.uid.dump.tmp"
          RUNTIME_TMP="${'$'}PERMISSION_DIR/runtime_grants.tmp"
          RUNTIME_SORTED="${'$'}PERMISSION_DIR/runtime_grants.sorted.tmp"
          APPOPS_ALL="${'$'}PERMISSION_DIR/appops.all.tmp"
          APPOPS_UID="${'$'}PERMISSION_DIR/appops.uid.tmp"
          APPOPS_PACKAGE="${'$'}PERMISSION_DIR/appops.package.tmp"
          rm -f "${'$'}PACKAGE_DUMP" "${'$'}APPOPS_DUMP" "${'$'}APPOPS_UID_DUMP" \
            "${'$'}RUNTIME_TMP" "${'$'}RUNTIME_SORTED" "${'$'}APPOPS_ALL" \
            "${'$'}APPOPS_UID" "${'$'}APPOPS_PACKAGE" \
            "${'$'}PERMISSION_DIR/runtime_grants.txt" "${'$'}PERMISSION_DIR/appops.txt"
          RUNTIME_STATUS=FAILED
          APPOPS_STATUS=FAILED
          APPOPS_SCOPE=NONE
          APPOPS_UID_SKIPPED=0
          if dumpsys package "${'$'}PKG" > "${'$'}PACKAGE_DUMP" 2>/dev/null; then
            if awk -v user="${'$'}PERMISSION_USER" '
              ${'$'}0 ~ "^[[:space:]]*User " user ":" { in_user=1; found_user=1; next }
              ${'$'}0 ~ "^[[:space:]]*User [0-9]+:" { in_user=0 }
              in_user && ${'$'}0 ~ "^[[:space:]]*runtime permissions:" { found_runtime=1 }
              END { exit(found_user && found_runtime ? 0 : 1) }
            ' "${'$'}PACKAGE_DUMP"; then
              if awk -v user="${'$'}PERMISSION_USER" '
                ${'$'}0 ~ "^[[:space:]]*User " user ":" { in_user=1; in_runtime=0; next }
                ${'$'}0 ~ "^[[:space:]]*User [0-9]+:" { in_user=0; in_runtime=0 }
                in_user && ${'$'}0 ~ "^[[:space:]]*runtime permissions:" { in_runtime=1; next }
                in_runtime && substr(${'$'}0, 1, 8) != "        " { in_runtime=0 }
                in_runtime && ${'$'}0 ~ "^[[:space:]]*[A-Za-z][A-Za-z0-9_.]*:" {
                  name=${'$'}1; sub(":", "", name); if (${'$'}0 ~ "granted=true") print name
                }
              ' "${'$'}PACKAGE_DUMP" > "${'$'}RUNTIME_TMP" &&
                 sort -u "${'$'}RUNTIME_TMP" > "${'$'}RUNTIME_SORTED"; then
                mv -f "${'$'}RUNTIME_SORTED" "${'$'}PERMISSION_DIR/runtime_grants.txt" || return 1
                chmod 600 "${'$'}PERMISSION_DIR/runtime_grants.txt" || return 1
                RUNTIME_STATUS=VALID
              else
                echo "WARN_PERMISSION_CAPTURE_RUNTIME_PARSE:user=${'$'}PERMISSION_USER"
              fi
            else
              echo "WARN_PERMISSION_CAPTURE_RUNTIME_BLOCK:user=${'$'}PERMISSION_USER"
            fi
          else
            echo "WARN_PERMISSION_CAPTURE_RUNTIME_COMMAND:user=${'$'}PERMISSION_USER"
          fi
          PACKAGE_UID_OUTPUT=${'$'}(cmd package list packages -U --user "${'$'}PERMISSION_USER" "${'$'}PKG" 2>&1)
          PACKAGE_UID_EXIT=${'$'}?
          PACKAGE_UID=""
          if [ "${'$'}PACKAGE_UID_EXIT" -eq 0 ]; then
            PACKAGE_UID=${'$'}(printf '%s\n' "${'$'}PACKAGE_UID_OUTPUT" | awk -v expected="package:${'$'}PKG" '
              ${'$'}1 == expected && ${'$'}2 ~ /^uid:[0-9]+${'$'}/ {
                sub(/^uid:/, "", ${'$'}2)
                print ${'$'}2
                exit
              }
            ')
          fi
          if [ -z "${'$'}PACKAGE_UID" ]; then
            echo "WARN_PERMISSION_CAPTURE_APPOPS_UID_QUERY:user=${'$'}PERMISSION_USER exit=${'$'}PACKAGE_UID_EXIT"
          elif cmd appops get --user "${'$'}PERMISSION_USER" "${'$'}PKG" > "${'$'}APPOPS_DUMP" 2>/dev/null &&
               cmd appops get --user "${'$'}PERMISSION_USER" "${'$'}PACKAGE_UID" > "${'$'}APPOPS_UID_DUMP" 2>/dev/null; then
            if uclone_normalize_appops_dump "${'$'}APPOPS_DUMP" > "${'$'}APPOPS_ALL" &&
               uclone_normalize_appops_dump "${'$'}APPOPS_UID_DUMP" > "${'$'}APPOPS_UID"; then
              APPOPS_MIXED_UID_MARKER=0
              APPOPS_UID_MARKER=0
              awk '/^[[:space:]]*Uid mode:/ { found=1 } END { exit found ? 0 : 1 }' "${'$'}APPOPS_DUMP" && APPOPS_MIXED_UID_MARKER=1
              awk '/^[[:space:]]*Uid mode:/ { found=1 } END { exit found ? 0 : 1 }' "${'$'}APPOPS_UID_DUMP" && APPOPS_UID_MARKER=1
              APPOPS_UID_SKIPPED=${'$'}(awk 'END { print NR + 0 }' "${'$'}APPOPS_UID")
              if [ "${'$'}APPOPS_MIXED_UID_MARKER" = "${'$'}APPOPS_UID_MARKER" ] &&
                 uclone_appops_unique "${'$'}APPOPS_UID" &&
                 uclone_appops_uid_prefix_matches "${'$'}APPOPS_UID" "${'$'}APPOPS_ALL" &&
                 awk -v skip="${'$'}APPOPS_UID_SKIPPED" 'NR > skip { print }' "${'$'}APPOPS_ALL" > "${'$'}APPOPS_PACKAGE" &&
                 uclone_appops_unique "${'$'}APPOPS_PACKAGE"; then
                mv -f "${'$'}APPOPS_PACKAGE" "${'$'}PERMISSION_DIR/appops.txt" || return 1
                chmod 600 "${'$'}PERMISSION_DIR/appops.txt" || return 1
                APPOPS_STATUS=PACKAGE_VALID
                APPOPS_SCOPE=PACKAGE
                if [ "${'$'}APPOPS_UID_SKIPPED" -gt 0 ]; then
                  echo "WARN_APPOPS_UID_SCOPE_SKIPPED:user=${'$'}PERMISSION_USER count=${'$'}APPOPS_UID_SKIPPED"
                fi
              else
                echo "WARN_PERMISSION_CAPTURE_APPOPS_SCOPE:user=${'$'}PERMISSION_USER"
              fi
            else
              echo "WARN_PERMISSION_CAPTURE_APPOPS_PARSE:user=${'$'}PERMISSION_USER"
            fi
          else
            echo "WARN_PERMISSION_CAPTURE_APPOPS_COMMAND:user=${'$'}PERMISSION_USER"
          fi
          uclone_write_permission_capture_state "${'$'}PERMISSION_DIR" "${'$'}PERMISSION_USER" \
            "${'$'}RUNTIME_STATUS" "${'$'}APPOPS_STATUS" "${'$'}APPOPS_SCOPE" "${'$'}APPOPS_UID_SKIPPED" || return 1
          rm -f "${'$'}PACKAGE_DUMP" "${'$'}APPOPS_DUMP" "${'$'}APPOPS_UID_DUMP" \
            "${'$'}RUNTIME_TMP" "${'$'}RUNTIME_SORTED" "${'$'}APPOPS_ALL" \
            "${'$'}APPOPS_UID" "${'$'}APPOPS_PACKAGE"
          PERMISSION_COUNT=0
          APPOPS_COUNT=0
          [ "${'$'}RUNTIME_STATUS" != "VALID" ] || PERMISSION_COUNT=${'$'}(wc -l < "${'$'}PERMISSION_DIR/runtime_grants.txt" | tr -d ' ')
          [ "${'$'}APPOPS_STATUS" != "PACKAGE_VALID" ] || APPOPS_COUNT=${'$'}(wc -l < "${'$'}PERMISSION_DIR/appops.txt" | tr -d ' ')
          echo "PERMISSIONS_CAPTURED:user=${'$'}PERMISSION_USER runtime=${'$'}RUNTIME_STATUS grants=${'$'}PERMISSION_COUNT appops=${'$'}APPOPS_STATUS appopsCount=${'$'}APPOPS_COUNT uidAppOpsSkipped=${'$'}APPOPS_UID_SKIPPED"
          return 0
        }
        uclone_restore_permission_state() {
          PERMISSION_DIR="${'$'}1"
          PERMISSION_USER="${'$'}2"
          PERMISSION_STATE="${'$'}PERMISSION_DIR/capture.state"
          [ -f "${'$'}PERMISSION_STATE" ] && [ ! -L "${'$'}PERMISSION_STATE" ] || {
            echo "WARN_PERMISSION_CAPTURE_STATE_MISSING:${'$'}PERMISSION_DIR"
            return 0
          }
          [ "${'$'}(uclone_permission_value "${'$'}PERMISSION_STATE" schema)" = "1" ] || {
            echo "WARN_PERMISSION_CAPTURE_STATE_INVALID:${'$'}PERMISSION_DIR"
            return 0
          }
          GRANT_COUNT=0
          APPOPS_COUNT=0
          FAILURE_COUNT=0
          RUNTIME_STATUS=${'$'}(uclone_permission_value "${'$'}PERMISSION_STATE" runtimeStatus)
          APPOPS_STATUS=${'$'}(uclone_permission_value "${'$'}PERMISSION_STATE" appOpsStatus)
          if [ "${'$'}RUNTIME_STATUS" = "VALID" ] && [ -f "${'$'}PERMISSION_DIR/runtime_grants.txt" ] && [ ! -L "${'$'}PERMISSION_DIR/runtime_grants.txt" ]; then
            while IFS= read -r PERMISSION; do
              [ -n "${'$'}PERMISSION" ] || continue
              case "${'$'}PERMISSION" in *[!A-Za-z0-9_.]*) continue ;; esac
              if cmd package grant --user "${'$'}PERMISSION_USER" "${'$'}PKG" "${'$'}PERMISSION" >/dev/null 2>&1 ||
                 pm grant --user "${'$'}PERMISSION_USER" "${'$'}PKG" "${'$'}PERMISSION" >/dev/null 2>&1; then
                GRANT_COUNT=${'$'}((GRANT_COUNT + 1))
              else
                echo "WARN_GRANT_FAILED:${'$'}PERMISSION"
                FAILURE_COUNT=${'$'}((FAILURE_COUNT + 1))
              fi
            done < "${'$'}PERMISSION_DIR/runtime_grants.txt"
          else
            echo "WARN_PERMISSION_RUNTIME_SKIPPED:status=${'$'}RUNTIME_STATUS"
          fi
          if [ "${'$'}APPOPS_STATUS" = "PACKAGE_VALID" ] && [ -f "${'$'}PERMISSION_DIR/appops.txt" ] && [ ! -L "${'$'}PERMISSION_DIR/appops.txt" ]; then
            while read -r OP MODE EXTRA; do
              [ -n "${'$'}OP" ] && [ -z "${'$'}EXTRA" ] || continue
              case "${'$'}OP" in *[!A-Z0-9_\(\)]*|'') continue ;; esac
              case "${'$'}MODE" in allow|ignore|deny|default|foreground|ask) ;; *) continue ;; esac
              if cmd appops set --user "${'$'}PERMISSION_USER" "${'$'}PKG" "${'$'}OP" "${'$'}MODE" >/dev/null 2>&1; then
                APPOPS_COUNT=${'$'}((APPOPS_COUNT + 1))
              else
                echo "WARN_APPOPS_FAILED:${'$'}OP:${'$'}MODE"
                FAILURE_COUNT=${'$'}((FAILURE_COUNT + 1))
              fi
            done < "${'$'}PERMISSION_DIR/appops.txt"
            cmd appops write-settings >/dev/null 2>&1 || echo "WARN_APPOPS_WRITE_SETTINGS_FAILED"
          else
            echo "WARN_PERMISSION_APPOPS_SKIPPED:status=${'$'}APPOPS_STATUS"
          fi
          echo "RESTORED_PERMISSIONS:mode=MERGE grants=${'$'}GRANT_COUNT appops=${'$'}APPOPS_COUNT failures=${'$'}FAILURE_COUNT"
          return 0
        }
    """.trimIndent()
}
