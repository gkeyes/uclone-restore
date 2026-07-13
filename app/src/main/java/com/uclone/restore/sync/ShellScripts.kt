package com.uclone.restore.sync

import com.uclone.restore.model.AppRule
import com.uclone.restore.model.PermissionRestoreMode
import com.uclone.restore.model.UCloneSettings
import com.uclone.restore.root.shellQuote

object ShellScripts {
    private val androidPackageNamePattern = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")
    private val rollbackIdPattern = Regex("[A-Za-z0-9_.-]+")
    private enum class RestoreSourceKind {
        ACTIVE,
        ROLLBACK,
        SWITCH_TEMP,
        CLONE_ROLLBACK,
    }

    private fun selectedParts(rule: AppRule): String = listOfNotNull(
        "ce".takeIf { rule.includeCe },
        "de".takeIf { rule.includeDe },
        "external".takeIf { rule.includeExternal },
        "media".takeIf { rule.includeMedia },
        "obb".takeIf { rule.includeObb },
        "permissions".takeIf { rule.includePermissions },
    ).joinToString(",")

    private fun selectedParts(settings: UCloneSettings): String = listOfNotNull(
        "ce".takeIf { settings.includeCe },
        "de".takeIf { settings.includeDe },
        "external".takeIf { settings.includeExternal },
        "media".takeIf { settings.includeMedia },
        "obb".takeIf { settings.includeObb },
        "permissions".takeIf { settings.includePermissions },
    ).joinToString(",")

    private fun sourcePermissionCaptureScript(
        directory: String,
        user: String,
        mode: PermissionRestoreMode,
    ): String {
        val capture = "uclone_capture_permission_state \"$directory\" \"$user\" \"${mode.name}\""
        return if (mode == PermissionRestoreMode.EXACT) {
            "$capture || { echo \"ERR_SOURCE_PERMISSION_CAPTURE:$user\" >&2; exit 54; }\nCAPTURED_PERMISSIONS=1\nPERMISSION_CAPTURE_STATE=valid"
        } else {
            """
                if $capture; then
                  CAPTURED_PERMISSIONS=1
                  PERMISSION_CAPTURE_STATE=valid
                else
                  uclone_remove_tree "$directory" || exit 54
                  PERMISSION_CAPTURE_STATE=skipped
                  echo "WARN_SOURCE_PERMISSION_CAPTURE_SKIPPED:$user"
                fi
            """.trimIndent()
        }
    }

    internal fun backupReuseValidationScript(): String = """
        uclone_backup_is_valid() {
          VALIDATE_BACKUP="${'$'}1"
          [ -f "${'$'}VALIDATE_BACKUP/manifest.json" ] && [ ! -L "${'$'}VALIDATE_BACKUP/manifest.json" ] || return 1
          VALIDATE_SCHEMA=${'$'}(sed -n 's/.*"schemaVersion":\([0-9][0-9]*\).*/\1/p' "${'$'}VALIDATE_BACKUP/manifest.json" | head -1)
          case "${'$'}VALIDATE_SCHEMA" in 2|3|4|5) ;; *) return 1 ;; esac
          for VALIDATE_PART in ce de external media obb; do
            [ -f "${'$'}VALIDATE_BACKUP/.state/${'$'}VALIDATE_PART" ] || return 1
            VALIDATE_STATE=${'$'}(sed -n '1p' "${'$'}VALIDATE_BACKUP/.state/${'$'}VALIDATE_PART" | tr -d '\r')
            case "${'$'}VALIDATE_STATE" in
              data)
                [ -d "${'$'}VALIDATE_BACKUP/${'$'}VALIDATE_PART" ] || return 1
                find "${'$'}VALIDATE_BACKUP/${'$'}VALIDATE_PART" -xdev -mindepth 1 -print -quit 2>/dev/null | grep -q . || return 1
                ;;
              absent|empty)
                [ ! -e "${'$'}VALIDATE_BACKUP/${'$'}VALIDATE_PART" ] || return 1
                ;;
              *) return 1 ;;
            esac
            uclone_verify_part_metadata "${'$'}VALIDATE_BACKUP" "${'$'}VALIDATE_PART" >/dev/null 2>&1 || return 1
          done
          VALIDATE_PERMISSION_CAPTURE_STATE=${'$'}(sed -n 's/.*"permissionCaptureState":"\([a-z]*\)".*/\1/p' "${'$'}VALIDATE_BACKUP/manifest.json" | head -1)
          case "${'$'}VALIDATE_PERMISSION_CAPTURE_STATE" in
            valid)
              uclone_permission_capture_valid "${'$'}VALIDATE_BACKUP/permissions" || return 1
              ;;
            excluded|skipped)
              [ ! -e "${'$'}VALIDATE_BACKUP/permissions" ] || return 1
              ;;
            '')
              [ "${'$'}VALIDATE_SCHEMA" -lt 5 ] || return 1
              ;;
            *) return 1 ;;
          esac
          return 0
        }
        uclone_backup_matches_identity() {
          IDENTITY_BACKUP="${'$'}1"
          IDENTITY_EXPECTED_PACKAGE="${'$'}2"
          IDENTITY_EXPECTED_SOURCE_USER="${'$'}3"
          IDENTITY_EXPECTED_TARGET_USER="${'$'}4"
          IDENTITY_EXPECTED_STATE="${'$'}5"
          IDENTITY_EXPECTED_KIND="${'$'}6"
          uclone_backup_is_valid "${'$'}IDENTITY_BACKUP" || return 1
          IDENTITY_SCHEMA=${'$'}(sed -n 's/.*"schemaVersion":\([0-9][0-9]*\).*/\1/p' "${'$'}IDENTITY_BACKUP/manifest.json" | head -1)
          [ "${'$'}IDENTITY_SCHEMA" = "$CURRENT_MANIFEST_SCHEMA" ] || return 1
          IDENTITY_PACKAGE=${'$'}(sed -n 's/.*"packageName":"\([^"]*\)".*/\1/p' "${'$'}IDENTITY_BACKUP/manifest.json" | head -1)
          IDENTITY_STATE=${'$'}(sed -n 's/.*"stateKind":"\([^"]*\)".*/\1/p' "${'$'}IDENTITY_BACKUP/manifest.json" | head -1)
          IDENTITY_KIND=${'$'}(sed -n 's/.*"backupKind":"\([^"]*\)".*/\1/p' "${'$'}IDENTITY_BACKUP/manifest.json" | head -1)
          IDENTITY_SIGNING_CERTIFICATE=${'$'}(sed -n 's/.*"sourceSigningCertificateSha256":"\([0-9a-f,]*\)".*/\1/p' "${'$'}IDENTITY_BACKUP/manifest.json" | head -1)
          IDENTITY_SOURCE_USER=${'$'}(${manifestUserIdReadCommand("sourceUser", "\"${'$'}IDENTITY_BACKUP/manifest.json\"")})
          IDENTITY_TARGET_USER=${'$'}(${manifestUserIdReadCommand("targetUser", "\"${'$'}IDENTITY_BACKUP/manifest.json\"")})
          [ "${'$'}IDENTITY_PACKAGE" = "${'$'}IDENTITY_EXPECTED_PACKAGE" ] || return 1
          [ "${'$'}IDENTITY_SOURCE_USER" = "${'$'}IDENTITY_EXPECTED_SOURCE_USER" ] || return 1
          [ "${'$'}IDENTITY_TARGET_USER" = "${'$'}IDENTITY_EXPECTED_TARGET_USER" ] || return 1
          [ "${'$'}IDENTITY_STATE" = "${'$'}IDENTITY_EXPECTED_STATE" ] || return 1
          [ "${'$'}IDENTITY_KIND" = "${'$'}IDENTITY_EXPECTED_KIND" ] || return 1
          [ "${'$'}IDENTITY_SIGNING_CERTIFICATE" = "${'$'}UCLONE_SIGNING_CERTIFICATE_SHA256" ] || return 1
          if [ -d "${'$'}IDENTITY_BACKUP/permissions" ]; then
            uclone_permission_capture_valid_for_user "${'$'}IDENTITY_BACKUP/permissions" "${'$'}IDENTITY_EXPECTED_SOURCE_USER" || return 1
          fi
          return 0
        }
    """.trimIndent()

    private fun snapshotPartCaptureFunctions(excludeCache: Boolean): String = """
        count_items() {
          COUNT_PATH="${'$'}1"
          uclone_count_tree "${'$'}COUNT_PATH"
        }
        write_part_state() {
          STATE_ROOT="${'$'}1"
          STATE_NAME="${'$'}2"
          STATE_VALUE="${'$'}3"
          STATE_SECURITY_SOURCE="${'$'}{4:-}"
          mkdir -p "${'$'}STATE_ROOT/.state" || return 1
          printf '%s\n' "${'$'}STATE_VALUE" > "${'$'}STATE_ROOT/.state/${'$'}STATE_NAME" || return 1
          uclone_write_part_metadata "${'$'}STATE_ROOT" "${'$'}STATE_NAME" "${'$'}STATE_VALUE" "${'$'}STATE_SECURITY_SOURCE" || return 1
          echo "PART_STATE:${'$'}STATE_NAME=${'$'}STATE_VALUE"
        }
        capture_excluded_part() {
          write_part_state "${'$'}1" "${'$'}2" excluded || exit 18
        }
        copy_dir_stream() {
          SRC="${'$'}1"
          DST="${'$'}2"
          SRC_ITEMS="${'$'}3"
          UCLONE_SCANNED_FILES=${'$'}((UCLONE_SCANNED_FILES + SRC_ITEMS))
          uclone_remove_tree "${'$'}DST.tmp" || exit 12
          mkdir -p "${'$'}DST.tmp" || exit 12
          uclone_extract_workspace_tree "${'$'}SRC" "${'$'}DST.tmp" || exit 13
          uclone_remove_tree "${'$'}DST" || exit 14
          mv "${'$'}DST.tmp" "${'$'}DST" || exit 14
          ${if (excludeCache) "uclone_remove_tree \"${'$'}DST/cache\" 2>/dev/null || true\nuclone_remove_tree \"${'$'}DST/code_cache\" 2>/dev/null || true" else ":"}
          DST_ITEMS=${'$'}(count_items "${'$'}DST") || { echo "ERR_COPY_SCAN:${'$'}DST" >&2; exit 17; }
          if [ "${'$'}DST_ITEMS" -le 0 ]; then
            uclone_remove_tree "${'$'}DST" || exit 17
            return 2
          fi
          PART_SIZE_KB=${'$'}(uclone_tree_kb "${'$'}DST")
          case "${'$'}PART_SIZE_KB" in ''|*[!0-9]*) echo "ERR_COPY_SIZE:${'$'}DST" >&2; exit 17 ;; esac
          COPIED_PARTS=${'$'}((COPIED_PARTS + 1))
          COPIED_ITEMS=${'$'}((COPIED_ITEMS + DST_ITEMS))
          UCLONE_COPIED_FILES=${'$'}((UCLONE_COPIED_FILES + DST_ITEMS))
          uclone_add_written_kb "${'$'}PART_SIZE_KB"
          uclone_record_temp_path "${'$'}DST"
          echo "COPIED:${'$'}SRC ITEMS=${'$'}DST_ITEMS SIZE_KB=${'$'}PART_SIZE_KB"
          return 0
        }
        capture_part() {
          if command -v uclone_cancel_checkpoint >/dev/null 2>&1; then
            uclone_cancel_checkpoint "SOURCE_PART_${'$'}2" || exit 93
          fi
          STATE_ROOT="${'$'}1"
          PART_NAME="${'$'}2"
          DST="${'$'}3"
          shift 3
          FOUND_EMPTY=0
          FOUND_EMPTY_SOURCE=""
          for SRC in "${'$'}@"; do
            [ -n "${'$'}SRC" ] || continue
            if [ -e "${'$'}SRC" ] || [ -L "${'$'}SRC" ]; then
              [ -d "${'$'}SRC" ] || {
                write_part_state "${'$'}STATE_ROOT" "${'$'}PART_NAME" unreadable || true
                echo "ERR_SOURCE_NOT_DIRECTORY:${'$'}PART_NAME:${'$'}SRC" >&2
                return 1
              }
              SRC_ITEMS=${'$'}(count_items "${'$'}SRC") || {
                write_part_state "${'$'}STATE_ROOT" "${'$'}PART_NAME" unreadable || true
                echo "ERR_SOURCE_UNREADABLE:${'$'}PART_NAME:${'$'}SRC" >&2
                return 1
              }
              echo "PROBE_PATH:${'$'}SRC ITEMS=${'$'}SRC_ITEMS"
              if [ "${'$'}SRC_ITEMS" -gt 0 ]; then
                COPY_EXIT=0
                copy_dir_stream "${'$'}SRC" "${'$'}DST" "${'$'}SRC_ITEMS" || COPY_EXIT=${'$'}?
                case "${'$'}COPY_EXIT" in
                  0)
                    write_part_state "${'$'}STATE_ROOT" "${'$'}PART_NAME" data "${'$'}SRC" || return 1
                    CAPTURED_PARTS=${'$'}((CAPTURED_PARTS + 1))
                    return 0
                    ;;
                  2) FOUND_EMPTY=1 ;;
                  *)
                    write_part_state "${'$'}STATE_ROOT" "${'$'}PART_NAME" unreadable || true
                    return "${'$'}COPY_EXIT"
                    ;;
                esac
              else
                FOUND_EMPTY=1
                FOUND_EMPTY_SOURCE="${'$'}SRC"
              fi
            else
              echo "SKIP_MISSING:${'$'}SRC"
            fi
          done
          if [ "${'$'}FOUND_EMPTY" = "1" ]; then
            write_part_state "${'$'}STATE_ROOT" "${'$'}PART_NAME" empty "${'$'}FOUND_EMPTY_SOURCE" || return 1
          else
            write_part_state "${'$'}STATE_ROOT" "${'$'}PART_NAME" absent || return 1
          fi
          CAPTURED_PARTS=${'$'}((CAPTURED_PARTS + 1))
          return 0
        }
    """.trimIndent()

    private fun uniqueStampScript(): String = """
        uclone_unique_stamp() {
          UCLONE_STAMP_TIME=${'$'}(/system/bin/date +%Y%m%d-%H%M%S 2>/dev/null || /system/bin/date +%s) || return 1
          UCLONE_STAMP_SUFFIX=${'$'}(printf '%s' "${'$'}UCLONE_REQUEST_ID" | sed 's/[^A-Za-z0-9]//g; s/^\(............\).*/\1/') || return 1
          [ -n "${'$'}UCLONE_STAMP_SUFFIX" ] || UCLONE_STAMP_SUFFIX=${'$'}${'$'}
          printf '%s_%s\n' "${'$'}UCLONE_STAMP_TIME" "${'$'}UCLONE_STAMP_SUFFIX"
        }
    """.trimIndent()

    private fun metricsScript(): String = """
        ${uniqueStampScript()}
        UCLONE_SCANNED_FILES=0
        UCLONE_COPIED_FILES=0
        UCLONE_COPIED_BYTES=0
        UCLONE_PEAK_TEMPORARY_BYTES=0
        UCLONE_TARGET_DOWNTIME_MS=0
        uclone_now_ms() {
          UCLONE_NOW=${'$'}(/system/bin/date +%s%3N 2>/dev/null || true)
          case "${'$'}UCLONE_NOW" in
            ''|*[!0-9]*) /system/bin/date +%s | awk '{ printf "%.0f\n", ${'$'}1 * 1000 }' ;;
            *) echo "${'$'}UCLONE_NOW" ;;
          esac
        }
        uclone_stage_begin() {
          UCLONE_STAGE_NAME="${'$'}1"
          case "${'$'}UCLONE_STAGE_NAME" in
            COMMIT|AUTO_ROLLBACK) ;;
            *)
              if command -v uclone_cancel_checkpoint >/dev/null 2>&1; then
                uclone_cancel_checkpoint "${'$'}UCLONE_STAGE_NAME" || exit 93
              fi
              ;;
          esac
          UCLONE_STAGE_STARTED_AT=${'$'}(uclone_now_ms)
          command -v uclone_active_stage >/dev/null 2>&1 && uclone_active_stage "${'$'}UCLONE_STAGE_NAME"
          echo "UCLONE_STAGE_BEGIN:${'$'}UCLONE_STAGE_NAME"
        }
        uclone_stage_end() {
          UCLONE_STAGE_FINISHED_AT=${'$'}(uclone_now_ms)
          echo "UCLONE_METRIC:stage=${'$'}UCLONE_STAGE_NAME started_at=${'$'}UCLONE_STAGE_STARTED_AT finished_at=${'$'}UCLONE_STAGE_FINISHED_AT"
        }
        uclone_add_written_kb() {
          UCLONE_WRITE_KB="${'$'}1"
          case "${'$'}UCLONE_WRITE_KB" in ''|*[!0-9]*) return 0 ;; esac
          UCLONE_NEXT_COPIED_BYTES=${'$'}(awk -v TOTAL="${'$'}UCLONE_COPIED_BYTES" -v KB="${'$'}UCLONE_WRITE_KB" 'BEGIN { printf "%.0f\n", TOTAL + KB * 1024 }')
          case "${'$'}UCLONE_NEXT_COPIED_BYTES" in ''|*[!0-9]*) return 0 ;; esac
          UCLONE_COPIED_BYTES="${'$'}UCLONE_NEXT_COPIED_BYTES"
        }
        uclone_record_temp_path() {
          [ -d "${'$'}1" ] || return 0
          UCLONE_TEMP_KB=${'$'}(uclone_tree_kb "${'$'}1" 2>/dev/null || true)
          case "${'$'}UCLONE_TEMP_KB" in ''|*[!0-9]*) return 0 ;; esac
          UCLONE_TEMP_BYTES=${'$'}(awk -v KB="${'$'}UCLONE_TEMP_KB" 'BEGIN { printf "%.0f\n", KB * 1024 }')
          case "${'$'}UCLONE_TEMP_BYTES" in ''|*[!0-9]*) return 0 ;; esac
          awk -v CURRENT="${'$'}UCLONE_TEMP_BYTES" -v PEAK="${'$'}UCLONE_PEAK_TEMPORARY_BYTES" 'BEGIN { exit !(CURRENT > PEAK) }' && UCLONE_PEAK_TEMPORARY_BYTES="${'$'}UCLONE_TEMP_BYTES"
        }
        uclone_elapsed_ms() {
          awk -v FINISHED_AT="${'$'}1" -v STARTED_AT="${'$'}2" 'BEGIN { VALUE = FINISHED_AT - STARTED_AT; if (VALUE < 0) VALUE = 0; printf "%.0f\n", VALUE }'
        }
        uclone_emit_metrics() {
          echo "UCLONE_METRIC:scanned_files=${'$'}UCLONE_SCANNED_FILES copied_files=${'$'}UCLONE_COPIED_FILES copied_bytes=${'$'}UCLONE_COPIED_BYTES peak_temporary_bytes=${'$'}UCLONE_PEAK_TEMPORARY_BYTES target_downtime_ms=${'$'}UCLONE_TARGET_DOWNTIME_MS"
        }
    """.trimIndent()

    internal fun storagePreflightScript(): String = """
        UCLONE_ESTIMATED_KB=0
        uclone_decimal_add() {
          LEFT="${'$'}1"
          RIGHT="${'$'}2"
          case "${'$'}LEFT:${'$'}RIGHT" in *[!0-9:]*) return 1 ;; esac
          [ "${'$'}{#LEFT}" -le 15 ] && [ "${'$'}{#RIGHT}" -le 15 ] || return 1
          awk -v LEFT="${'$'}LEFT" -v RIGHT="${'$'}RIGHT" 'BEGIN { printf "%.0f\n", LEFT + RIGHT }'
        }
        uclone_dir_kb() {
          [ -d "${'$'}1" ] || { echo 0; return 0; }
          VALUE_KB=${'$'}(uclone_tree_kb "${'$'}1" 2>/dev/null || true)
          case "${'$'}VALUE_KB" in ''|*[!0-9]*) echo 0 ;; *) echo "${'$'}VALUE_KB" ;; esac
        }
        uclone_add_dir_kb() {
          VALUE_KB=${'$'}(uclone_dir_kb "${'$'}1")
          NEXT_ESTIMATED_KB=${'$'}(uclone_decimal_add "${'$'}UCLONE_ESTIMATED_KB" "${'$'}VALUE_KB") || NEXT_ESTIMATED_KB=""
          case "${'$'}NEXT_ESTIMATED_KB" in ''|*[!0-9]*) UCLONE_ESTIMATED_KB=""; return 1 ;; esac
          UCLONE_ESTIMATED_KB="${'$'}NEXT_ESTIMATED_KB"
        }
        uclone_add_first_dir_kb() {
          for VALUE_PATH in "${'$'}@"; do
            [ -d "${'$'}VALUE_PATH" ] || continue
            [ -n "${'$'}(ls -A "${'$'}VALUE_PATH" 2>/dev/null | sed -n '1p')" ] || continue
            uclone_add_dir_kb "${'$'}VALUE_PATH"
            return 0
          done
          return 0
        }
        uclone_require_space() {
          REQUIRED_KB="${'$'}1"
          SPACE_LABEL="${'$'}2"
          case "${'$'}REQUIRED_KB" in ''|*[!0-9]*) echo "ERR_SPACE_ESTIMATE:${'$'}SPACE_LABEL:${'$'}REQUIRED_KB" >&2; exit 75 ;; esac
          [ "${'$'}{#REQUIRED_KB}" -le 15 ] || { echo "ERR_SPACE_ESTIMATE:${'$'}SPACE_LABEL:${'$'}REQUIRED_KB" >&2; exit 75; }
          AVAILABLE_KB=${'$'}(df -k "${'$'}ROOT" 2>/dev/null | awk 'NR > 1 && ${'$'}4 ~ /^[0-9]+${'$'}/ { print ${'$'}4; exit }')
          case "${'$'}AVAILABLE_KB" in ''|*[!0-9]*) echo "ERR_SPACE_UNKNOWN:${'$'}SPACE_LABEL" >&2; exit 75 ;; esac
          [ "${'$'}{#AVAILABLE_KB}" -le 15 ] || { echo "ERR_SPACE_UNKNOWN:${'$'}SPACE_LABEL" >&2; exit 75; }
          RESERVE_KB=${'$'}(awk -v REQUIRED_KB="${'$'}REQUIRED_KB" 'BEGIN { printf "%.0f\n", REQUIRED_KB / 10 }')
          case "${'$'}RESERVE_KB" in ''|*[!0-9]*) echo "ERR_SPACE_ESTIMATE:${'$'}SPACE_LABEL:${'$'}REQUIRED_KB" >&2; exit 75 ;; esac
          awk -v RESERVE_KB="${'$'}RESERVE_KB" 'BEGIN { exit !(RESERVE_KB >= 65536) }' || RESERVE_KB=65536
          NEEDED_KB=${'$'}(uclone_decimal_add "${'$'}REQUIRED_KB" "${'$'}RESERVE_KB") || NEEDED_KB=""
          case "${'$'}NEEDED_KB" in ''|*[!0-9]*) echo "ERR_SPACE_ESTIMATE:${'$'}SPACE_LABEL:${'$'}REQUIRED_KB" >&2; exit 75 ;; esac
          echo "SPACE_PREFLIGHT:${'$'}SPACE_LABEL requiredKb=${'$'}REQUIRED_KB reserveKb=${'$'}RESERVE_KB neededKb=${'$'}NEEDED_KB availableKb=${'$'}AVAILABLE_KB"
          awk -v AVAILABLE_KB="${'$'}AVAILABLE_KB" -v NEEDED_KB="${'$'}NEEDED_KB" 'BEGIN { exit !(AVAILABLE_KB >= NEEDED_KB) }' || {
            echo "ERR_INSUFFICIENT_SPACE:${'$'}SPACE_LABEL:neededKb=${'$'}NEEDED_KB:availableKb=${'$'}AVAILABLE_KB" >&2
            exit 75
          }
        }
    """.trimIndent()

    private fun cloneStateFunction(amCommand: String): String = """
        CLONE_STATE_AM_COMMAND=${shellQuote(amCommand)}
        clone_state() {
          CLONE_STATE_QUERY_USER="${'$'}{1:-${'$'}{CLONE_USER:-}}"
          case "${'$'}CLONE_STATE_QUERY_USER" in ''|*[!0-9]*)
            echo "CLONE_STATE_QUERY_UNAVAILABLE:invalid_user"
            return 0
            ;;
          esac
          CLONE_STATE_EXIT=0
          CLONE_STATE_OUTPUT=${'$'}("${'$'}CLONE_STATE_AM_COMMAND" get-started-user-state "${'$'}CLONE_STATE_QUERY_USER" 2>&1) || CLONE_STATE_EXIT=${'$'}?
          case "${'$'}CLONE_STATE_OUTPUT" in
            *RUNNING*|*"User is not started"*|*"not started"*|*SHUTDOWN*|*STOPPING*)
              printf '%s\n' "${'$'}CLONE_STATE_OUTPUT"
              ;;
            "")
              echo "CLONE_STATE_QUERY_EMPTY:exit=${'$'}CLONE_STATE_EXIT"
              ;;
            *)
              CLONE_STATE_OUTPUT_BYTES=${'$'}(printf '%s' "${'$'}CLONE_STATE_OUTPUT" | wc -c | tr -d ' ')
              echo "CLONE_STATE_QUERY_UNAVAILABLE:unexpected_output:exit=${'$'}CLONE_STATE_EXIT:bytes=${'$'}CLONE_STATE_OUTPUT_BYTES"
              ;;
          esac
        }
    """.trimIndent()

    private fun ensureCloneCeReadyScript(
        settings: UCloneSettings,
        required: Boolean,
        autoUnlockAllowed: Boolean,
        stopAfterTask: Boolean,
    ): String {
        if (!required) return """
            echo "ENSURE_CE_SKIPPED=not_required"
        """.trimIndent()
        val credential = settings.cloneUnlockCredential.trim()
        return """
            CLONE_USER=${settings.cloneUserId}
            CLONE_UNLOCK_CREDENTIAL=""
            CLONE_CREDENTIAL_CONFIGURED=${if (credential.isBlank()) "0" else "1"}
            CLONE_AUTO_UNLOCK=${if (autoUnlockAllowed) "1" else "0"}
            STOP_CLONE_AFTER_TASK=${if (stopAfterTask) "1" else "0"}
            CLONE_STARTED_BY_TASK=0
            CLONE_STOPPED_AFTER_TASK=0
            ${cloneStateFunction("/system/bin/am")}
            wait_for_clone_state() {
              WAIT_LABEL="${'$'}1"
              WAIT_LIMIT="${'$'}2"
              WAIT_INDEX=0
              while [ "${'$'}WAIT_INDEX" -lt "${'$'}WAIT_LIMIT" ]; do
                WAIT_STATE=${'$'}(clone_state)
                echo "${'$'}{WAIT_LABEL}_${'$'}{WAIT_INDEX}=${'$'}WAIT_STATE"
                case "${'$'}WAIT_STATE" in
                  *CLONE_STATE_QUERY_UNAVAILABLE*|*CLONE_STATE_QUERY_EMPTY*) return 2 ;;
                  *RUNNING_UNLOCKED*) return 0 ;;
                esac
                sleep 0.25
                WAIT_INDEX=${'$'}((WAIT_INDEX + 1))
              done
              return 1
            }
            ${cloneCleanupFunction(stopAfterTask)}
            cleanup_on_exit() {
              if command -v uclone_release_pre_mutation_gates >/dev/null 2>&1; then
                uclone_release_pre_mutation_gates || true
              fi
              if command -v cleanup_switch_temp >/dev/null 2>&1; then
                cleanup_switch_temp
              fi
              cleanup_clone_user
            }
            trap cleanup_on_exit EXIT
            echo "ENSURE_CLONE_CE_BEGIN"
            echo "ENSURE_CLONE_USER=${'$'}CLONE_USER"
            echo "ENSURE_CLONE_AUTO_UNLOCK=${'$'}CLONE_AUTO_UNLOCK"
            echo "CREDENTIAL_CONFIGURED=${'$'}CLONE_CREDENTIAL_CONFIGURED"
            echo "CREDENTIAL_LENGTH=${credential.length}"
            STATE_BEFORE_UNLOCK=${'$'}(clone_state)
            echo "STATE_BEFORE_UNLOCK=${'$'}STATE_BEFORE_UNLOCK"
            case "${'$'}STATE_BEFORE_UNLOCK" in
              *CLONE_STATE_QUERY_UNAVAILABLE*|*CLONE_STATE_QUERY_EMPTY*)
                echo "ERR_CLONE_STATE_QUERY:${'$'}STATE_BEFORE_UNLOCK" >&2
                exit 86
                ;;
              *RUNNING_UNLOCKED*)
                echo "ENSURE_CLONE_UNLOCK_RESULT=READY_ALREADY"
                ;;
              *)
                if [ "${'$'}CLONE_AUTO_UNLOCK" != "1" ]; then
                  echo "ERR_CLONE_AUTO_UNLOCK_DISABLED:${'$'}STATE_BEFORE_UNLOCK" >&2
                  exit 82
                fi
                case "${'$'}STATE_BEFORE_UNLOCK" in
                  *"User is not started"*|*"not started"*|*SHUTDOWN*|*STOPPING*)
                    ${startCloneUserRequestScript(
                        amCommand = "/system/bin/am",
                        sleepCommand = "sleep",
                        startPollLimit = 40,
                        startPollIntervalSeconds = 0.25,
                        markStartedByTask = true,
                        failureExitCode = 80,
                    )}
                    STATE_AFTER_START=${'$'}START_WAIT_STATE
                    echo "STATE_AFTER_START=${'$'}STATE_AFTER_START"
                    ;;
                esac
                STATE_BEFORE_VERIFY=${'$'}(clone_state)
                echo "STATE_BEFORE_VERIFY=${'$'}STATE_BEFORE_VERIFY"
                case "${'$'}STATE_BEFORE_VERIFY" in
                  *CLONE_STATE_QUERY_UNAVAILABLE*|*CLONE_STATE_QUERY_EMPTY*)
                    echo "ERR_CLONE_STATE_QUERY:${'$'}STATE_BEFORE_VERIFY" >&2
                    exit 86
                    ;;
                  *RUNNING_UNLOCKED*)
                    echo "ENSURE_CLONE_UNLOCK_RESULT=READY_AFTER_START"
                    ;;
                  *)
                    if [ "${'$'}CLONE_CREDENTIAL_CONFIGURED" = "1" ]; then
                      IFS= read -r CLONE_UNLOCK_CREDENTIAL || CLONE_UNLOCK_CREDENTIAL=""
                    fi
                    if [ -z "${'$'}CLONE_UNLOCK_CREDENTIAL" ]; then
                      echo "ERR_CLONE_PIN_MISSING" >&2
                      exit 83
                    fi
                    echo "VERIFY_BEGIN"
                    VERIFY_EXIT=0
                    VERIFY_OUTPUT=${'$'}(/system/bin/cmd lock_settings verify --old "${'$'}CLONE_UNLOCK_CREDENTIAL" --user "${'$'}CLONE_USER" 2>&1) || VERIFY_EXIT=${'$'}?
                    echo "VERIFY_EXIT=${'$'}VERIFY_EXIT"
                    case "${'$'}VERIFY_OUTPUT" in
                      *"Lock credential verified successfully"*) VERIFY_RESULT="SUCCESS" ;;
                      *"didn't match"*) VERIFY_RESULT="BAD_CREDENTIAL" ;;
                      *"Profile uses unified challenge"*) VERIFY_RESULT="UNIFIED_CHALLENGE_UNSUPPORTED" ;;
                      *"Request throttled"*) VERIFY_RESULT="THROTTLED" ;;
                      *"Unknown command"*|*"Unknown option"*|*"Can't find service"*) VERIFY_RESULT="UNSUPPORTED" ;;
                      "") VERIFY_RESULT="EMPTY_OUTPUT" ;;
                      *) VERIFY_RESULT="OTHER_OUTPUT_LEN_${'$'}(printf '%s' "${'$'}VERIFY_OUTPUT" | wc -c | tr -d ' ')" ;;
                    esac
                    echo "VERIFY_RESULT=${'$'}VERIFY_RESULT"
                    [ "${'$'}VERIFY_RESULT" = "SUCCESS" ] || { echo "ERR_CLONE_PIN_VERIFY_FAILED:${'$'}VERIFY_RESULT" >&2; exit 84; }
                    WAIT_AFTER_VERIFY_RESULT=0
                    wait_for_clone_state "WAIT_AFTER_VERIFY" 120 || WAIT_AFTER_VERIFY_RESULT=${'$'}?
                    case "${'$'}WAIT_AFTER_VERIFY_RESULT" in
                      0) ;;
                      2)
                        echo "STATE_AFTER_VERIFY_WAIT=${'$'}WAIT_STATE"
                        echo "ERR_CLONE_STATE_QUERY:${'$'}WAIT_STATE" >&2
                        exit 86
                        ;;
                      *)
                        echo "STATE_AFTER_VERIFY_WAIT=${'$'}WAIT_STATE"
                        echo "ERR_CLONE_UNLOCK_TIMEOUT:${'$'}WAIT_STATE" >&2
                        exit 85
                        ;;
                    esac
                    echo "STATE_AFTER_VERIFY=${'$'}WAIT_STATE"
                    echo "ENSURE_CLONE_UNLOCK_RESULT=READY_AFTER_VERIFY"
                    ;;
                esac
                ;;
            esac
        """.trimIndent()
    }

    private fun startCloneUserRequestScript(
        amCommand: String,
        sleepCommand: String,
        startPollLimit: Int,
        startPollIntervalSeconds: Double,
        markStartedByTask: Boolean,
        failureExitCode: Int,
    ): String {
        require(startPollLimit in 1..200)
        require(startPollIntervalSeconds > 0.0 && startPollIntervalSeconds <= 5.0)
        require(failureExitCode in 1..255)
        return """
            START_AM_COMMAND=${shellQuote(amCommand)}
            START_SLEEP_COMMAND=${shellQuote(sleepCommand)}
            START_POLL_INTERVAL=${shellQuote(startPollIntervalSeconds.toString())}
            START_CLONE_READY=0
            START_STATE_BEFORE_REQUEST=${'$'}(clone_state)
            echo "START_STATE_BEFORE_REQUEST=${'$'}START_STATE_BEFORE_REQUEST"
            case "${'$'}START_STATE_BEFORE_REQUEST" in
              *CLONE_STATE_QUERY_UNAVAILABLE*|*CLONE_STATE_QUERY_EMPTY*)
                echo "ERR_CLONE_STATE_QUERY:${'$'}START_STATE_BEFORE_REQUEST" >&2
                exit $failureExitCode
                ;;
              *RUNNING*)
                START_CLONE_READY=1
                echo "START_CLONE_CONFIRMED=${'$'}START_STATE_BEFORE_REQUEST"
                echo "START_CLONE_OWNERSHIP=preexisting"
                ;;
              *)
                echo "START_USER_BEGIN"
                START_OUTPUT_ROOT="${'$'}{ROOT:-${'$'}{TMPDIR:-/data/local/tmp}}"
                mkdir -p "${'$'}START_OUTPUT_ROOT/tmp" || exit $failureExitCode
                START_REQUEST_TOKEN="${'$'}{UCLONE_REQUEST_ID:-manual}"
                case "${'$'}START_REQUEST_TOKEN" in *[!A-Za-z0-9_.-]*) START_REQUEST_TOKEN=manual ;; esac
                START_USER_LOG="${'$'}START_OUTPUT_ROOT/tmp/start_user_${'$'}{START_REQUEST_TOKEN}_${'$'}${'$'}.log"
                rm -f "${'$'}START_USER_LOG"
                START_USER_EXIT=0
                "${'$'}START_AM_COMMAND" start-user "${'$'}CLONE_USER" > "${'$'}START_USER_LOG" 2>&1 &
                START_USER_PID=${'$'}!
                start_user_client_running() {
                  kill -0 "${'$'}START_USER_PID" 2>/dev/null || return 1
                  START_CLIENT_STATE=${'$'}(awk '{ sub(/^[^)]*[)] /, ""); print ${'$'}1 }' "/proc/${'$'}START_USER_PID/stat" 2>/dev/null || true)
                  [ "${'$'}START_CLIENT_STATE" != "Z" ]
                }
                START_WAIT_INDEX=0
                while [ "${'$'}START_WAIT_INDEX" -lt $startPollLimit ]; do
                  START_WAIT_STATE=${'$'}(clone_state)
                  echo "WAIT_AFTER_START_${'$'}START_WAIT_INDEX=${'$'}START_WAIT_STATE"
                  case "${'$'}START_WAIT_STATE" in
                    *CLONE_STATE_QUERY_UNAVAILABLE*|*CLONE_STATE_QUERY_EMPTY*)
                      echo "ERR_CLONE_STATE_QUERY:${'$'}START_WAIT_STATE" >&2
                      exit $failureExitCode
                      ;;
                    *RUNNING*)
                      ${if (markStartedByTask) "CLONE_STARTED_BY_TASK=1" else ":"}
                      START_CLONE_READY=1
                      echo "START_CLONE_CONFIRMED=${'$'}START_WAIT_STATE"
                      echo "START_CLONE_OWNERSHIP=requested_by_task"
                      break
                      ;;
                  esac
                  "${'$'}START_SLEEP_COMMAND" "${'$'}START_POLL_INTERVAL"
                  START_WAIT_INDEX=${'$'}((START_WAIT_INDEX + 1))
                done
                START_CLIENT_TERMINATED=0
                START_CLIENT_DETACHED=0
                if start_user_client_running; then
                  kill "${'$'}START_USER_PID" 2>/dev/null || true
                  START_KILL_WAIT=0
                  while start_user_client_running && [ "${'$'}START_KILL_WAIT" -lt 8 ]; do
                    "${'$'}START_SLEEP_COMMAND" 0.05
                    START_KILL_WAIT=${'$'}((START_KILL_WAIT + 1))
                  done
                  if start_user_client_running; then
                    kill -9 "${'$'}START_USER_PID" 2>/dev/null || true
                  fi
                  if ! start_user_client_running; then
                    START_CLIENT_TERMINATED=1
                  fi
                fi
                if start_user_client_running; then
                  START_CLIENT_DETACHED=1
                else
                  wait "${'$'}START_USER_PID" 2>/dev/null || START_USER_EXIT=${'$'}?
                fi
                START_USER_OUTPUT=${'$'}(cat "${'$'}START_USER_LOG" 2>/dev/null || true)
                rm -f "${'$'}START_USER_LOG"
                echo "START_USER_EXIT=${'$'}START_USER_EXIT"
                echo "START_USER_CLIENT_TERMINATED=${'$'}START_CLIENT_TERMINATED"
                echo "START_USER_CLIENT_DETACHED=${'$'}START_CLIENT_DETACHED"
                echo "START_USER_OUTPUT=${'$'}START_USER_OUTPUT"
                ;;
            esac
            if [ "${'$'}START_CLONE_READY" != "1" ]; then
              STATE_AFTER_START_TIMEOUT=${'$'}(clone_state)
              echo "STATE_AFTER_START_TIMEOUT=${'$'}STATE_AFTER_START_TIMEOUT"
              echo "ERR_START_CLONE_FAILED:requestExit=${'$'}START_USER_EXIT:state=${'$'}STATE_AFTER_START_TIMEOUT" >&2
              exit $failureExitCode
            fi
        """.trimIndent()
    }

    private fun cloneCleanupFunction(stopAfterTask: Boolean): String {
        if (!stopAfterTask) return """
            cleanup_clone_user() {
              echo "STOP_CLONE_AFTER_TASK=0 reason=persistent_lifecycle_action startedByTask=${'$'}CLONE_STARTED_BY_TASK"
            }
        """.trimIndent()
        return """
            cleanup_clone_user() {
              if [ "${'$'}CLONE_STOPPED_AFTER_TASK" = "1" ]; then
                echo "STOP_CLONE_AFTER_TASK=already_requested"
                return 0
              fi
              if [ "${'$'}CLONE_STARTED_BY_TASK" != "1" ]; then
                echo "STOP_CLONE_AFTER_TASK=0 startedByTask=${'$'}CLONE_STARTED_BY_TASK"
                return 0
              fi
              echo "STOP_CLONE_AFTER_TASK=1 startedByTask=1"
              ${stopCloneUserRequestScript(
                  amCommand = "/system/bin/am",
                  sleepCommand = "sleep",
                  stopPollLimit = 20,
                  stopPollIntervalSeconds = 0.25,
              )}
              if [ "${'$'}STOP_CLONE_CONFIRMED" = "1" ]; then
                CLONE_STOPPED_AFTER_TASK=1
                return 0
              fi
              if [ "${'$'}STOP_USER_EXIT" -ne 0 ] && [ "${'$'}STOP_USER_CLIENT_TERMINATED" != "1" ]; then
                echo "WARN_STOP_CLONE_REQUEST_FAILED:${'$'}STOP_USER_EXIT:${'$'}(clone_state)"
                return 0
              fi
              echo "WARN_STOP_CLONE_PENDING:${'$'}(clone_state)"
            }
        """.trimIndent()
    }

    private fun stopCloneUserRequestScript(
        amCommand: String,
        sleepCommand: String,
        stopPollLimit: Int,
        stopPollIntervalSeconds: Double,
    ): String {
        require(stopPollLimit in 1..100)
        require(stopPollIntervalSeconds > 0.0 && stopPollIntervalSeconds <= 5.0)
        return """
            uclone_request_stop_user() {
            STOP_AM_COMMAND=${shellQuote(amCommand)}
            STOP_SLEEP_COMMAND=${shellQuote(sleepCommand)}
            STOP_POLL_INTERVAL=${shellQuote(stopPollIntervalSeconds.toString())}
            STOP_CLONE_CONFIRMED=0
            STOP_USER_EXIT=0
            STOP_USER_CLIENT_TERMINATED=0
            STOP_USER_CLIENT_DETACHED=0
            STOP_USER_REAPED=0
            STOP_STATE_BEFORE_REQUEST=${'$'}(clone_state)
            echo "STATE_BEFORE_STOP=${'$'}STOP_STATE_BEFORE_REQUEST"
            case "${'$'}STOP_STATE_BEFORE_REQUEST" in
              *CLONE_STATE_QUERY_UNAVAILABLE*|*CLONE_STATE_QUERY_EMPTY*)
                echo "ERR_CLONE_STATE_QUERY:${'$'}STOP_STATE_BEFORE_REQUEST" >&2
                exit 88
                ;;
              *"User is not started"*|*"not started"*|*SHUTDOWN*)
                STOP_CLONE_CONFIRMED=1
                echo "STOP_CLONE_CONFIRMED=1"
                ;;
              *)
                STOP_OUTPUT_ROOT="${'$'}{ROOT:-${'$'}{TMPDIR:-/data/local/tmp}}"
                mkdir -p "${'$'}STOP_OUTPUT_ROOT/tmp" || { STOP_USER_EXIT=74; return 1; }
                STOP_REQUEST_TOKEN="${'$'}{UCLONE_REQUEST_ID:-manual}"
                case "${'$'}STOP_REQUEST_TOKEN" in *[!A-Za-z0-9_.-]*) STOP_REQUEST_TOKEN=manual ;; esac
                STOP_USER_LOG="${'$'}STOP_OUTPUT_ROOT/tmp/stop_user_${'$'}{STOP_REQUEST_TOKEN}_${'$'}${'$'}.log"
                rm -f "${'$'}STOP_USER_LOG"
                "${'$'}STOP_AM_COMMAND" stop-user "${'$'}CLONE_USER" > "${'$'}STOP_USER_LOG" 2>&1 &
                STOP_USER_PID=${'$'}!
                stop_user_client_running() {
                  kill -0 "${'$'}STOP_USER_PID" 2>/dev/null || return 1
                  STOP_CLIENT_STATE=${'$'}(awk '{ sub(/^[^)]*[)] /, ""); print ${'$'}1 }' "/proc/${'$'}STOP_USER_PID/stat" 2>/dev/null || true)
                  [ "${'$'}STOP_CLIENT_STATE" != "Z" ]
                }
                STOP_WAIT_INDEX=0
                while [ "${'$'}STOP_WAIT_INDEX" -lt $stopPollLimit ]; do
                  STOP_WAIT_STATE=${'$'}(clone_state)
                  echo "WAIT_AFTER_STOP_${'$'}STOP_WAIT_INDEX=${'$'}STOP_WAIT_STATE"
                  case "${'$'}STOP_WAIT_STATE" in
                    *CLONE_STATE_QUERY_UNAVAILABLE*|*CLONE_STATE_QUERY_EMPTY*)
                      STOP_USER_EXIT=88
                      echo "ERR_CLONE_STATE_QUERY:${'$'}STOP_WAIT_STATE" >&2
                      break
                      ;;
                    *"User is not started"*|*"not started"*|*SHUTDOWN*)
                      STOP_CLONE_CONFIRMED=1
                      echo "STOP_CLONE_CONFIRMED=1"
                      break
                      ;;
                  esac
                  if [ "${'$'}STOP_USER_REAPED" != "1" ] && ! stop_user_client_running; then
                    wait "${'$'}STOP_USER_PID" 2>/dev/null || STOP_USER_EXIT=${'$'}?
                    STOP_USER_REAPED=1
                    if [ "${'$'}STOP_USER_EXIT" -ne 0 ]; then
                      STOP_FINAL_STATE=${'$'}(clone_state)
                      case "${'$'}STOP_FINAL_STATE" in
                        *"User is not started"*|*"not started"*|*SHUTDOWN*)
                          STOP_CLONE_CONFIRMED=1
                          echo "STOP_CLONE_CONFIRMED=1"
                          ;;
                      esac
                      break
                    fi
                  fi
                  "${'$'}STOP_SLEEP_COMMAND" "${'$'}STOP_POLL_INTERVAL"
                  STOP_WAIT_INDEX=${'$'}((STOP_WAIT_INDEX + 1))
                done
                if [ "${'$'}STOP_USER_REAPED" != "1" ]; then
                  if stop_user_client_running; then
                    kill "${'$'}STOP_USER_PID" 2>/dev/null || true
                    STOP_KILL_WAIT=0
                    while stop_user_client_running && [ "${'$'}STOP_KILL_WAIT" -lt 8 ]; do
                      "${'$'}STOP_SLEEP_COMMAND" 0.05
                      STOP_KILL_WAIT=${'$'}((STOP_KILL_WAIT + 1))
                    done
                    if stop_user_client_running; then
                      kill -9 "${'$'}STOP_USER_PID" 2>/dev/null || true
                    fi
                  fi
                  if stop_user_client_running; then
                    STOP_USER_CLIENT_DETACHED=1
                  else
                    wait "${'$'}STOP_USER_PID" 2>/dev/null || STOP_USER_EXIT=${'$'}?
                    STOP_USER_REAPED=1
                    STOP_USER_CLIENT_TERMINATED=1
                  fi
                fi
                STOP_USER_OUTPUT=${'$'}(cat "${'$'}STOP_USER_LOG" 2>/dev/null || true)
                rm -f "${'$'}STOP_USER_LOG"
                ;;
            esac
            }
            uclone_request_stop_user || true
            echo "STOP_USER_EXIT=${'$'}STOP_USER_EXIT"
            echo "STOP_USER_CLIENT_TERMINATED=${'$'}STOP_USER_CLIENT_TERMINATED"
            echo "STOP_USER_CLIENT_DETACHED=${'$'}STOP_USER_CLIENT_DETACHED"
            echo "STOP_USER_OUTPUT=${'$'}{STOP_USER_OUTPUT:-}"
        """.trimIndent()
    }

    fun capture(packageName: String, rule: AppRule, settings: UCloneSettings, appPackage: String): String = """
        set -u
        ${WorkspacePathGuard.require(settings.rootDir)}
        PKG=${shellQuote(packageName)}
        APP_PKG=${shellQuote(appPackage)}
        CONFIG_SRC_USER=${settings.cloneUserId}
        ${metricsScript()}
        TS=${'$'}(uclone_unique_stamp) || { echo "ERR_UNIQUE_STAMP" >&2; exit 10; }
        BASE="${'$'}ROOT/snapshots/${'$'}PKG"
        TMP="${'$'}ROOT/tmp/capture_${'$'}{PKG}_${'$'}TS"
        ${storagePreflightScript()}
        uclone_stage_begin PRECHECK
        [ "${'$'}PKG" != "${'$'}APP_PKG" ] || { echo "ERR_SELF_SYNC"; exit 41; }
        mkdir -p "${'$'}ROOT/snapshots" "${'$'}ROOT/rollback" "${'$'}ROOT/logs" "${'$'}ROOT/tmp" "${'$'}ROOT/config" "${'$'}BASE/history" || exit 10
        uclone_remove_tree "${'$'}TMP" || exit 10
        for UCLONE_OLD_TRY in "${'$'}TMP".try_*; do
          [ -e "${'$'}UCLONE_OLD_TRY" ] || continue
          uclone_remove_tree "${'$'}UCLONE_OLD_TRY" || exit 10
        done
        ${TransactionSafetyShell.functions()}
        uclone_transaction_init CAPTURE_SNAPSHOT "${'$'}CONFIG_SRC_USER" ${settings.mainUserId} ${shellQuote(selectedParts(rule))} || exit 77
        uclone_transaction_stage PRECHECKED || exit 77
        uclone_stage_end
        uclone_stage_begin SOURCE_PREPARE
        CAPTURE_REQUIRE_CE=${if (rule.includeCe) "1" else "0"}
        ${ensureCloneCeReadyScript(settings, rule.includeCe, settings.autoUnlockClone, settings.stopCloneAfterTask)}
        capture_cleanup_on_exit() {
          uclone_release_pre_mutation_gates || true
          if command -v cleanup_clone_user >/dev/null 2>&1; then cleanup_clone_user; fi
        }
        trap capture_cleanup_on_exit EXIT
        CANDIDATE_USERS="${'$'}CONFIG_SRC_USER"
        echo "CANDIDATE_USERS=${'$'}CANDIDATE_USERS"
        [ -n "${'$'}CANDIDATE_USERS" ] || { echo "ERR_NO_CLONE_USER_CANDIDATES" >&2; exit 42; }
        UCLONE_ESTIMATED_KB=0
        ${if (rule.includeCe) "uclone_add_first_dir_kb \"/data/user/${'$'}CONFIG_SRC_USER/${'$'}PKG\" \"/data_mirror/data_ce/null/${'$'}CONFIG_SRC_USER/${'$'}PKG\" \"/data_mirror/data_ce/${'$'}CONFIG_SRC_USER/${'$'}PKG\"" else ":"}
        ${if (rule.includeDe) "uclone_add_first_dir_kb \"/data/user_de/${'$'}CONFIG_SRC_USER/${'$'}PKG\" \"/data_mirror/data_de/null/${'$'}CONFIG_SRC_USER/${'$'}PKG\" \"/data_mirror/data_de/${'$'}CONFIG_SRC_USER/${'$'}PKG\"" else ":"}
        ${if (rule.includeExternal) "uclone_add_first_dir_kb \"/data/media/${'$'}CONFIG_SRC_USER/Android/data/${'$'}PKG\" \"/storage/emulated/${'$'}CONFIG_SRC_USER/Android/data/${'$'}PKG\"" else ":"}
        ${if (rule.includeMedia) "uclone_add_first_dir_kb \"/data/media/${'$'}CONFIG_SRC_USER/Android/media/${'$'}PKG\" \"/storage/emulated/${'$'}CONFIG_SRC_USER/Android/media/${'$'}PKG\"" else ":"}
        ${if (rule.includeObb) "uclone_add_first_dir_kb \"/data/media/${'$'}CONFIG_SRC_USER/Android/obb/${'$'}PKG\" \"/storage/emulated/${'$'}CONFIG_SRC_USER/Android/obb/${'$'}PKG\"" else ":"}
        uclone_require_space "${'$'}UCLONE_ESTIMATED_KB" "capture_snapshot"
        ${BackupIntegrityShell.functions()}
        ${snapshotPartCaptureFunctions(rule.excludeCache)}
        ${PermissionStateShell.functions()}
        try_user() {
          TRY_USER="${'$'}1"
          TRY_TMP="${'$'}TMP.try_${'$'}TRY_USER"
          uclone_remove_tree "${'$'}TRY_TMP" || exit 11
          mkdir -p "${'$'}TRY_TMP" || exit 11
          STATE=${'$'}(clone_state)
          echo "PROBE_USER=${'$'}TRY_USER STATE=${'$'}STATE"
          case "${'$'}STATE" in
            *CLONE_STATE_QUERY_UNAVAILABLE*|*CLONE_STATE_QUERY_EMPTY*)
              echo "ERR_CLONE_STATE_QUERY:${'$'}STATE" >&2
              uclone_remove_tree "${'$'}TRY_TMP" || exit 11
              exit 86
              ;;
            *RUNNING_UNLOCKED*) ;;
            *)
              if [ "${'$'}CAPTURE_REQUIRE_CE" = "1" ]; then
                echo "ERR_USER_NOT_UNLOCKED:${'$'}TRY_USER:${'$'}STATE" >&2
                uclone_remove_tree "${'$'}TRY_TMP" || exit 11
                return 1
              fi
              echo "WARN_USER_NOT_UNLOCKED:${'$'}TRY_USER:${'$'}STATE"
              ;;
          esac
          if cmd package list packages --user "${'$'}TRY_USER" 2>/dev/null | grep -qx "package:${'$'}PKG"; then
            echo "PACKAGE_LISTED:${'$'}TRY_USER"
          else
            echo "ERR_PACKAGE_NOT_LISTED:${'$'}TRY_USER" >&2
            uclone_remove_tree "${'$'}TRY_TMP" || exit 11
            return 1
          fi
          TRY_UID=${'$'}(cmd package list packages -U --user "${'$'}TRY_USER" | awk -v p="package:${'$'}PKG" '${'$'}1==p { sub("uid:","",${'$'}2); print ${'$'}2; exit }')
          [ -n "${'$'}TRY_UID" ] || { echo "ERR_SOURCE_UID_MISSING:${'$'}TRY_USER" >&2; return 1; }
          TRY_APK_DIGEST=${'$'}(uclone_package_code_digest "${'$'}TRY_USER" "${'$'}PKG") || { echo "ERR_SOURCE_APK_DIGEST:${'$'}TRY_USER" >&2; return 1; }
          TRY_VERSION_CODE=${'$'}(uclone_package_version_code "${'$'}PKG") || { echo "ERR_SOURCE_VERSION_CODE:${'$'}PKG" >&2; return 1; }
          TRY_VERSION_NAME=${'$'}(uclone_package_version_name "${'$'}PKG") || { echo "ERR_SOURCE_VERSION_NAME:${'$'}PKG" >&2; return 1; }
          uclone_transaction_stage SOURCE_GATE_ACQUIRE || return 1
          uclone_gate_acquire source "${'$'}TRY_USER" "${'$'}TRY_UID" || {
            echo "ERR_SOURCE_GATE_ACQUIRE:${'$'}TRY_USER:${'$'}PKG" >&2
            return 1
          }
          SOURCE_GATE_DIR="${'$'}UCLONE_GATE_DIR"
          COPIED_PARTS=0
          COPIED_ITEMS=0
          CAPTURED_PARTS=0
          CAPTURED_PERMISSIONS=0
          PERMISSION_CAPTURE_STATE=excluded
          ${if (rule.includeCe) "capture_part \"${'$'}TRY_TMP\" ce \"${'$'}TRY_TMP/ce\" \"/data/user/${'$'}TRY_USER/${'$'}PKG\" \"/data_mirror/data_ce/null/${'$'}TRY_USER/${'$'}PKG\" \"/data_mirror/data_ce/${'$'}TRY_USER/${'$'}PKG\"" else "capture_excluded_part \"${'$'}TRY_TMP\" ce"}
          ${if (rule.includeDe) "capture_part \"${'$'}TRY_TMP\" de \"${'$'}TRY_TMP/de\" \"/data/user_de/${'$'}TRY_USER/${'$'}PKG\" \"/data_mirror/data_de/null/${'$'}TRY_USER/${'$'}PKG\" \"/data_mirror/data_de/${'$'}TRY_USER/${'$'}PKG\"" else "capture_excluded_part \"${'$'}TRY_TMP\" de"}
          ${if (rule.includeExternal) "capture_part \"${'$'}TRY_TMP\" external \"${'$'}TRY_TMP/external\" \"/data/media/${'$'}TRY_USER/Android/data/${'$'}PKG\" \"/storage/emulated/${'$'}TRY_USER/Android/data/${'$'}PKG\"" else "capture_excluded_part \"${'$'}TRY_TMP\" external"}
          ${if (rule.includeMedia) "capture_part \"${'$'}TRY_TMP\" media \"${'$'}TRY_TMP/media\" \"/data/media/${'$'}TRY_USER/Android/media/${'$'}PKG\" \"/storage/emulated/${'$'}TRY_USER/Android/media/${'$'}PKG\"" else "capture_excluded_part \"${'$'}TRY_TMP\" media"}
          ${if (rule.includeObb) "capture_part \"${'$'}TRY_TMP\" obb \"${'$'}TRY_TMP/obb\" \"/data/media/${'$'}TRY_USER/Android/obb/${'$'}PKG\" \"/storage/emulated/${'$'}TRY_USER/Android/obb/${'$'}PKG\"" else "capture_excluded_part \"${'$'}TRY_TMP\" obb"}
          ${if (rule.includePermissions) sourcePermissionCaptureScript("${'$'}TRY_TMP/permissions", "${'$'}TRY_USER", settings.permissionRestoreMode) else ":"}
          CE_CAPTURE_STATE=${'$'}(sed -n '1p' "${'$'}TRY_TMP/.state/ce" 2>/dev/null || true)
          if [ "${'$'}CAPTURE_REQUIRE_CE" = "1" ] && [ "${'$'}CE_CAPTURE_STATE" != "data" ] && [ "${'$'}CE_CAPTURE_STATE" != "empty" ]; then
            echo "ERR_CAPTURE_CE_MISSING:${'$'}TRY_USER" >&2
            uclone_remove_tree "${'$'}TRY_TMP" || exit 11
            return 1
          fi
          if [ "${'$'}CAPTURED_PARTS" -gt 0 ] || [ "${'$'}CAPTURED_PERMISSIONS" = "1" ]; then
            uclone_transaction_stage SOURCE_PREPARED || return 1
            if ! uclone_gate_release "${'$'}SOURCE_GATE_DIR"; then
              uclone_transaction_recovery_required
              return 1
            fi
            SOURCE_GATE_DIR=""
            uclone_remove_tree "${'$'}TMP" || exit 14
            mv "${'$'}TRY_TMP" "${'$'}TMP" || exit 14
            DETECTED_USER="${'$'}TRY_USER"
            DETECTED_STATE="${'$'}STATE"
            SOURCE_APK_DIGEST="${'$'}TRY_APK_DIGEST"
            SOURCE_VERSION_CODE="${'$'}TRY_VERSION_CODE"
            SOURCE_VERSION_NAME="${'$'}TRY_VERSION_NAME"
            SOURCE_UID="${'$'}TRY_UID"
            return 0
          fi
          if ! uclone_gate_release "${'$'}SOURCE_GATE_DIR"; then
            uclone_transaction_recovery_required
            return 1
          fi
          SOURCE_GATE_DIR=""
          uclone_remove_tree "${'$'}TRY_TMP" || exit 11
          return 1
        }
        DETECTED_USER=""
        DETECTED_STATE=""
        SOURCE_APK_DIGEST=""
        SOURCE_VERSION_CODE=""
        SOURCE_VERSION_NAME=""
        SOURCE_UID=""
        for U in ${'$'}CANDIDATE_USERS; do
          if try_user "${'$'}U"; then
            break
          fi
        done
        [ -n "${'$'}DETECTED_USER" ] || { echo "ERR_NOTHING_COPIED: no non-empty selected source paths for candidates:${'$'}CANDIDATE_USERS package:${'$'}PKG" >&2; exit 44; }
        SIZE_KB=${'$'}(uclone_tree_kb "${'$'}TMP") || exit 18
        SOURCE_VERSION_NAME_ESC=${'$'}(uclone_json_escape "${'$'}SOURCE_VERSION_NAME") || exit 18
        SOURCE_APP_ID=${'$'}((SOURCE_UID % 100000))
        printf '%s\n' "{\"schemaVersion\":$CURRENT_MANIFEST_SCHEMA,\"integrityMode\":\"FAST_METADATA\",\"packageName\":\"${packageName}\",\"sourceVersionCode\":\"${'$'}SOURCE_VERSION_CODE\",\"sourceVersionName\":\"${'$'}SOURCE_VERSION_NAME_ESC\",\"sourceSigningCertificateSha256\":\"${'$'}UCLONE_SIGNING_CERTIFICATE_SHA256\",\"sourceApkDigest\":\"${'$'}SOURCE_APK_DIGEST\",\"sourceUid\":${'$'}SOURCE_UID,\"sourceAppId\":${'$'}SOURCE_APP_ID,\"stateKind\":\"clone\",\"backupKind\":\"active_snapshot\",\"configuredSourceUser\":${settings.cloneUserId},\"sourceUser\":\"${'$'}DETECTED_USER\",\"sourceUserState\":\"${'$'}DETECTED_STATE\",\"targetUser\":\"${settings.mainUserId}\",\"createdAt\":\"${'$'}TS\",\"includeCe\":${rule.includeCe},\"includeDe\":${rule.includeDe},\"includeExternal\":${rule.includeExternal},\"includeMedia\":${rule.includeMedia},\"includeObb\":${rule.includeObb},\"includePermissions\":${rule.includePermissions},\"permissionCaptureState\":\"${'$'}PERMISSION_CAPTURE_STATE\",\"includeAppWebView\":${rule.includeAppWebView},\"excludeCache\":${rule.excludeCache},\"snapshotSizeKb\":\"${'$'}SIZE_KB\",\"copiedParts\":\"${'$'}COPIED_PARTS\",\"copiedItems\":\"${'$'}COPIED_ITEMS\"}" > "${'$'}TMP/manifest.json" || exit 18
        uclone_record_temp_path "${'$'}TMP"
        uclone_stage_end
        uclone_stage_begin COMMIT
        if [ -d "${'$'}BASE/active" ]; then mv "${'$'}BASE/active" "${'$'}BASE/history/${'$'}TS" || exit 15; fi
        mv "${'$'}TMP" "${'$'}BASE/active" || exit 16
        chmod 700 "${'$'}BASE" "${'$'}BASE/active" "${'$'}BASE/history" >/dev/null 2>&1 || {
          echo "ERR_SNAPSHOT_WORKSPACE_MODE:${'$'}BASE" >&2
          exit 17
        }
        uclone_transaction_complete || exit 77
        uclone_transaction_cleanup_complete || exit 77
        uclone_stage_end
        uclone_emit_metrics
        echo "SNAPSHOT_ACTIVE=${'$'}BASE/active"
        echo "SNAPSHOT_SOURCE_USER=${'$'}DETECTED_USER"
    """.trimIndent()

    fun restore(
        packageName: String,
        settings: UCloneSettings,
        appPackage: String,
        compatibility: RestoreCompatibilityOptions = RestoreCompatibilityOptions(),
    ): String = restoreBody(
        packageName = packageName,
        settings = settings,
        appPackage = appPackage,
        rollbackName = """${'$'}TS""",
        rollbackReason = "恢复到主系统前生成",
        sourceStateKind = "clone",
        syncSwitchMarkerToSource = true,
        compatibility = compatibility,
    )

    fun restoreFromCloneLatest(
        packageName: String,
        rule: AppRule,
        settings: UCloneSettings,
        appPackage: String,
    ): String = """
        echo "COMPOSITE_STEP=CAPTURE_SNAPSHOT_FROM_CLONE"
        (
          ${capture(packageName, rule, settings, appPackage).prependIndent("  ")}
        ) || exit ${'$'}?
        echo "COMPOSITE_STEP=RESTORE_SNAPSHOT_TO_MAIN"
        (
          ${restore(packageName, settings, appPackage).prependIndent("  ")}
        )
    """.trimIndent()

    fun switchFromCloneLatest(
        packageName: String,
        rule: AppRule,
        settings: UCloneSettings,
        appPackage: String,
    ): String = restoreBody(
        packageName = packageName,
        settings = settings,
        appPackage = appPackage,
        rollbackName = """${'$'}TS""",
        rollbackReason = "切换到分身态前生成",
        sourceKind = RestoreSourceKind.SWITCH_TEMP,
        prepareSourceScript = switchTempSourceScript(rule, settings),
        writeSwitchMarker = true,
        sourceStateKind = "clone",
    )

    fun pushMainToClone(
        packageName: String,
        rule: AppRule,
        settings: UCloneSettings,
        appPackage: String,
    ): String = """
        set -u
        ${WorkspacePathGuard.require(settings.rootDir)}
        PKG=${shellQuote(packageName)}
        APP_PKG=${shellQuote(appPackage)}
        SRC_USER=${settings.mainUserId}
        DST_USER=${settings.cloneUserId}
        ${metricsScript()}
        TS=${'$'}(uclone_unique_stamp) || { echo "ERR_UNIQUE_STAMP" >&2; exit 10; }
        PUSH_TEMP="${'$'}ROOT/tmp/push_${'$'}{PKG}_${'$'}TS"
        ROLLBACK_PARENT="${'$'}ROOT/clone_rollback/${'$'}PKG"
        ROLLBACK_LATEST="${'$'}ROLLBACK_PARENT/latest"
        ROLLBACK_PREVIOUS="${'$'}ROLLBACK_PARENT/latest.previous"
        ROLLBACK_TMP="${'$'}ROLLBACK_PARENT/latest.tmp_${'$'}TS"
        ROLLBACK="${'$'}ROLLBACK_TMP"
        TRANSACTION_UNDO=""
        PUSH_REQUIRE_CE=${if (rule.includeCe) "1" else "0"}
        ${storagePreflightScript()}
        uclone_stage_begin PRECHECK
        [ "${'$'}PKG" != "${'$'}APP_PKG" ] || { echo "ERR_SELF_SYNC"; exit 41; }
        [ -n "${'$'}ROOT" ] && [ "${'$'}ROOT" != "/" ] || { echo "ERR_BAD_ROOT:${'$'}ROOT" >&2; exit 71; }
        cleanup_switch_temp() {
          if command -v uclone_release_pre_mutation_gates >/dev/null 2>&1; then
            uclone_release_pre_mutation_gates || true
          fi
          [ -n "${'$'}{PUSH_TEMP:-}" ] || return 0
          case "${'$'}PUSH_TEMP" in
            "${'$'}ROOT"/tmp/push_"${'$'}PKG"_"${'$'}TS") uclone_remove_tree "${'$'}PUSH_TEMP" 2>/dev/null || true ;;
          esac
          [ -n "${'$'}{ROLLBACK_TMP:-}" ] || return 0
          case "${'$'}ROLLBACK_TMP" in
            "${'$'}ROOT"/clone_rollback/"${'$'}PKG"/latest.tmp_"${'$'}TS") uclone_remove_tree "${'$'}ROLLBACK_TMP" 2>/dev/null || true ;;
          esac
          [ -n "${'$'}{TRANSACTION_UNDO:-}" ] || return 0
          case "${'$'}TRANSACTION_UNDO" in
            "${'$'}ROOT"/tmp/undo_clone_"${'$'}PKG"_"${'$'}TS")
              if [ "${'$'}{TARGET_MUTATED:-0}" != "1" ] || [ "${'$'}{TRANSACTION_COMMITTED:-0}" = "1" ] || [ "${'$'}{TRANSACTION_ROLLED_BACK:-0}" = "1" ]; then
                uclone_remove_tree "${'$'}TRANSACTION_UNDO" 2>/dev/null || true
              else
                echo "TRANSACTION_UNDO_RETAINED=${'$'}TRANSACTION_UNDO"
              fi
              ;;
          esac
        }
        ${if (!rule.includeCe) "trap cleanup_switch_temp EXIT" else ":"}
        mkdir -p "${'$'}ROOT/tmp" "${'$'}ROLLBACK_PARENT" || exit 10
        if [ ! -d "${'$'}ROLLBACK_LATEST" ] && [ -d "${'$'}ROLLBACK_PREVIOUS" ]; then
          mv "${'$'}ROLLBACK_PREVIOUS" "${'$'}ROLLBACK_LATEST" || exit 54
          echo "CLONE_ROLLBACK_RECOVERED=${'$'}ROLLBACK_LATEST"
        fi
        uclone_remove_tree "${'$'}PUSH_TEMP" || exit 10
        uclone_remove_tree "${'$'}ROLLBACK_TMP" || exit 10
        CLONE_ROLLBACK_REUSED=0
        ${BackupIntegrityShell.functions()}
        ${TransactionSafetyShell.functions()}
        ${PermissionStateShell.functions()}
        uclone_transaction_init PUSH_MAIN_TO_CLONE "${'$'}SRC_USER" "${'$'}DST_USER" ${shellQuote(selectedParts(rule))} || exit 77
        uclone_transaction_stage PRECHECKED || exit 77
        ${backupReuseValidationScript()}
        ${if (settings.reuseExistingPassiveBackups) """
        if uclone_backup_matches_identity "${'$'}ROLLBACK_LATEST" "${'$'}PKG" "${'$'}DST_USER" "${'$'}DST_USER" clone clone_rollback; then
          CLONE_ROLLBACK_REUSED=1
          echo "CLONE_ROLLBACK_REUSED=${'$'}ROLLBACK_LATEST"
        fi
        """.trimIndent() else ":"}
        if [ "${'$'}CLONE_ROLLBACK_REUSED" = "1" ]; then
          TRANSACTION_UNDO="${'$'}ROOT/tmp/undo_clone_${'$'}{PKG}_${'$'}TS"
          ROLLBACK="${'$'}TRANSACTION_UNDO"
        fi
        ${ensureCloneCeReadyScript(settings, rule.includeCe, settings.autoUnlockClone, settings.stopCloneAfterTask)}
        if cmd package list packages --user "${'$'}SRC_USER" 2>/dev/null | grep -qx "package:${'$'}PKG"; then
          echo "PACKAGE_LISTED_SOURCE:${'$'}SRC_USER"
        else
          echo "ERR_PACKAGE_NOT_LISTED_SOURCE:${'$'}SRC_USER" >&2
          exit 42
        fi
        if cmd package list packages --user "${'$'}DST_USER" 2>/dev/null | grep -qx "package:${'$'}PKG"; then
          echo "PACKAGE_LISTED_TARGET:${'$'}DST_USER"
        else
          echo "ERR_PACKAGE_NOT_LISTED_TARGET:${'$'}DST_USER" >&2
          exit 43
        fi
        DST_UID=${'$'}(cmd package list packages -U --user "${'$'}DST_USER" | awk -v p="package:${'$'}PKG" '${'$'}1==p { sub("uid:","",${'$'}2); print ${'$'}2; exit }')
        [ -n "${'$'}DST_UID" ] || { echo "ERR_TARGET_UID_MISSING" >&2; exit 52; }
        SRC_UID=${'$'}(cmd package list packages -U --user "${'$'}SRC_USER" | awk -v p="package:${'$'}PKG" '${'$'}1==p { sub("uid:","",${'$'}2); print ${'$'}2; exit }')
        [ -n "${'$'}SRC_UID" ] || { echo "ERR_SOURCE_UID_MISSING:${'$'}SRC_USER" >&2; exit 52; }
        SOURCE_APK_DIGEST=${'$'}(uclone_package_code_digest "${'$'}SRC_USER" "${'$'}PKG") || { echo "ERR_SOURCE_APK_DIGEST:${'$'}SRC_USER" >&2; exit 52; }
        TARGET_APK_DIGEST=${'$'}(uclone_package_code_digest "${'$'}DST_USER" "${'$'}PKG") || { echo "ERR_TARGET_APK_DIGEST:${'$'}DST_USER" >&2; exit 52; }
        [ "${'$'}SOURCE_APK_DIGEST" = "${'$'}TARGET_APK_DIGEST" ] || { echo "ERR_CROSS_USER_APK_MISMATCH:${'$'}PKG" >&2; exit 52; }
        PACKAGE_VERSION_CODE=${'$'}(uclone_package_version_code "${'$'}PKG") || { echo "ERR_SOURCE_VERSION_CODE:${'$'}PKG" >&2; exit 52; }
        PACKAGE_VERSION_NAME=${'$'}(uclone_package_version_name "${'$'}PKG") || { echo "ERR_SOURCE_VERSION_NAME:${'$'}PKG" >&2; exit 52; }
        PACKAGE_VERSION_NAME_ESC=${'$'}(uclone_json_escape "${'$'}PACKAGE_VERSION_NAME") || exit 52
        TARGET_APP_ID=${'$'}((DST_UID % 100000))
        echo "PUSH_USERS source=${'$'}SRC_USER target=${'$'}DST_USER sourceUid=${'$'}SRC_UID targetUid=${'$'}DST_UID"
        UCLONE_ESTIMATED_KB=0
        ${if (rule.includeCe) "uclone_add_first_dir_kb \"/data/user/${'$'}SRC_USER/${'$'}PKG\" \"/data_mirror/data_ce/null/${'$'}SRC_USER/${'$'}PKG\" \"/data_mirror/data_ce/${'$'}SRC_USER/${'$'}PKG\"" else ":"}
        ${if (rule.includeDe) "uclone_add_first_dir_kb \"/data/user_de/${'$'}SRC_USER/${'$'}PKG\" \"/data_mirror/data_de/null/${'$'}SRC_USER/${'$'}PKG\" \"/data_mirror/data_de/${'$'}SRC_USER/${'$'}PKG\"" else ":"}
        ${if (rule.includeExternal) "uclone_add_first_dir_kb \"/data/media/${'$'}SRC_USER/Android/data/${'$'}PKG\" \"/storage/emulated/${'$'}SRC_USER/Android/data/${'$'}PKG\"" else ":"}
        ${if (rule.includeMedia) "uclone_add_first_dir_kb \"/data/media/${'$'}SRC_USER/Android/media/${'$'}PKG\" \"/storage/emulated/${'$'}SRC_USER/Android/media/${'$'}PKG\"" else ":"}
        ${if (rule.includeObb) "uclone_add_first_dir_kb \"/data/media/${'$'}SRC_USER/Android/obb/${'$'}PKG\" \"/storage/emulated/${'$'}SRC_USER/Android/obb/${'$'}PKG\"" else ":"}
        PUSH_SOURCE_KB="${'$'}UCLONE_ESTIMATED_KB"
        UCLONE_ESTIMATED_KB=0
        uclone_add_dir_kb "/data/user/${'$'}DST_USER/${'$'}PKG"
        uclone_add_dir_kb "/data/user_de/${'$'}DST_USER/${'$'}PKG"
        uclone_add_dir_kb "/data/media/${'$'}DST_USER/Android/data/${'$'}PKG"
        uclone_add_dir_kb "/data/media/${'$'}DST_USER/Android/media/${'$'}PKG"
        uclone_add_dir_kb "/data/media/${'$'}DST_USER/Android/obb/${'$'}PKG"
        PUSH_TARGET_KB="${'$'}UCLONE_ESTIMATED_KB"
        PUSH_REQUIRED_KB=${'$'}(uclone_decimal_add "${'$'}PUSH_SOURCE_KB" "${'$'}PUSH_TARGET_KB") || PUSH_REQUIRED_KB=""
        uclone_require_space "${'$'}PUSH_REQUIRED_KB" "push_source_and_clone_rollback"
        uclone_stage_end
        force_stop_source_package() {
          am force-stop --user "${'$'}SRC_USER" "${'$'}PKG" >/dev/null 2>&1 || {
            echo "ERR_FORCE_STOP_FAILED:${'$'}SRC_USER:${'$'}PKG" >&2
            return 1
          }
          echo "FORCE_STOP_SOURCE_USER:${'$'}SRC_USER"
        }
        force_stop_target_package() {
          am force-stop --user "${'$'}DST_USER" "${'$'}PKG" >/dev/null 2>&1 || {
            echo "ERR_FORCE_STOP_FAILED:${'$'}DST_USER:${'$'}PKG" >&2
            return 1
          }
          echo "FORCE_STOP_TARGET_USER:${'$'}DST_USER"
        }
        force_stop_package_users() {
          force_stop_source_package || return 1
          force_stop_target_package || return 1
          echo "FORCE_STOP_USERS:${'$'}SRC_USER ${'$'}DST_USER"
        }
        ${snapshotPartCaptureFunctions(rule.excludeCache)}
        backup_dir() {
          uclone_cancel_checkpoint "ROLLBACK_PART_${'$'}3" || exit 93
          SRC="${'$'}1"
          DST="${'$'}2"
          PART_NAME="${'$'}3"
          mkdir -p "${'$'}ROLLBACK/.state" || exit 54
          if [ ! -d "${'$'}SRC" ]; then
            printf '%s\n' "absent" > "${'$'}ROLLBACK/.state/${'$'}PART_NAME" || exit 54
            uclone_write_part_metadata "${'$'}ROLLBACK" "${'$'}PART_NAME" absent || exit 54
            return 0
          fi
          SRC_ITEMS=${'$'}(count_items "${'$'}SRC")
          if [ "${'$'}SRC_ITEMS" -le 0 ]; then
            printf '%s\n' "empty" > "${'$'}ROLLBACK/.state/${'$'}PART_NAME" || exit 54
            uclone_write_part_metadata "${'$'}ROLLBACK" "${'$'}PART_NAME" empty "${'$'}SRC" || exit 54
            echo "SKIP_BACKUP_EMPTY:${'$'}SRC"
            return 0
          fi
          uclone_remove_tree "${'$'}DST" || exit 54
          mkdir -p "${'$'}DST" || exit 54
          uclone_extract_workspace_tree "${'$'}SRC" "${'$'}DST" || exit 55
          BACKUP_ITEMS=${'$'}(count_items "${'$'}DST")
          UCLONE_SCANNED_FILES=${'$'}((UCLONE_SCANNED_FILES + SRC_ITEMS))
          UCLONE_COPIED_FILES=${'$'}((UCLONE_COPIED_FILES + BACKUP_ITEMS))
          BACKUP_SIZE_KB=${'$'}(uclone_tree_kb "${'$'}DST") || exit 63
          uclone_add_written_kb "${'$'}BACKUP_SIZE_KB"
          uclone_record_temp_path "${'$'}DST"
          [ "${'$'}BACKUP_ITEMS" -gt 0 ] || { echo "ERR_BACKUP_EMPTY:${'$'}SRC" >&2; exit 63; }
          printf '%s\n' "data" > "${'$'}ROLLBACK/.state/${'$'}PART_NAME" || exit 54
          uclone_write_part_metadata "${'$'}ROLLBACK" "${'$'}PART_NAME" data "${'$'}SRC" || exit 54
          BACKUP_PARTS=${'$'}((BACKUP_PARTS + 1))
          echo "CLONE_BACKUP:${'$'}SRC ITEMS=${'$'}BACKUP_ITEMS"
        }
        validate_target_path() {
          CHECK_TARGET="${'$'}1"
          [ -n "${'$'}CHECK_TARGET" ] && [ "${'$'}CHECK_TARGET" != "/" ] || { echo "ERR_UNSAFE_TARGET:${'$'}CHECK_TARGET" >&2; exit 66; }
          case "${'$'}CHECK_TARGET" in
            /data/user/${'$'}DST_USER/${'$'}PKG|/data/user_de/${'$'}DST_USER/${'$'}PKG|/data/media/${'$'}DST_USER/Android/data/${'$'}PKG|/data/media/${'$'}DST_USER/Android/media/${'$'}PKG|/data/media/${'$'}DST_USER/Android/obb/${'$'}PKG) ;;
            *) echo "ERR_UNSAFE_TARGET:${'$'}CHECK_TARGET" >&2; exit 66 ;;
          esac
        }
        read_target_context() {
          CONTEXT_TARGET="${'$'}1"
          ls -Zd "${'$'}CONTEXT_TARGET" 2>/dev/null | awk '{print ${'$'}1; exit}'
        }
        target_owner_for() {
          OWNER_KIND="${'$'}2"
          case "${'$'}OWNER_KIND" in
            app) echo "${'$'}DST_UID:${'$'}DST_UID" ;;
            media) echo "${'$'}DST_UID:1078" ;;
            obb) echo "${'$'}DST_UID:1079" ;;
            *) echo "" ;;
          esac
        }
        target_mode_for() {
          MODE_TARGET="${'$'}1"
          MODE_KIND="${'$'}2"
          EXISTING_MODE=${'$'}(stat -c '%a' "${'$'}MODE_TARGET" 2>/dev/null || true)
          case "${'$'}EXISTING_MODE" in ''|*[!0-7]*) ;; *) echo "${'$'}EXISTING_MODE"; return 0 ;; esac
          case "${'$'}MODE_KIND" in app) echo 700 ;; media|obb) echo 2770 ;; *) echo 700 ;; esac
        }
        apply_target_security() {
          SEC_TARGET="${'$'}1"
          SEC_OWNER="${'$'}2"
          SEC_CONTEXT="${'$'}3"
          SEC_MODE="${'$'}4"
          SEC_KIND="${'$'}5"
          case "${'$'}SEC_KIND" in app|media|obb) ;; *) exit 59 ;; esac
          if [ -n "${'$'}SEC_OWNER" ]; then
            chown -hR "${'$'}SEC_OWNER" "${'$'}SEC_TARGET" || exit 59
            OWNER_UID=${'$'}(echo "${'$'}SEC_OWNER" | cut -d: -f1)
            case "${'$'}SEC_KIND:${'$'}OWNER_UID" in
              app:) ;;
              app:*[!0-9]*) ;;
              app:*)
                APP_ID=${'$'}((OWNER_UID % 100000))
                OWNER_USER_ID=${'$'}((OWNER_UID / 100000))
                if [ "${'$'}APP_ID" -ge 10000 ] && [ "${'$'}APP_ID" -le 19999 ]; then
                  CACHE_GID=${'$'}((OWNER_USER_ID * 100000 + 20000 + APP_ID - 10000))
                  if [ -d "${'$'}SEC_TARGET/cache" ]; then
                    chown -hR "${'$'}OWNER_UID:${'$'}CACHE_GID" "${'$'}SEC_TARGET/cache" >/dev/null 2>&1 || {
                      echo "ERR_CACHE_GID_RESTORE:${'$'}SEC_TARGET/cache" >&2
                      exit 59
                    }
                  fi
                  if [ -d "${'$'}SEC_TARGET/code_cache" ]; then
                    chown -hR "${'$'}OWNER_UID:${'$'}CACHE_GID" "${'$'}SEC_TARGET/code_cache" >/dev/null 2>&1 || {
                      echo "ERR_CACHE_GID_RESTORE:${'$'}SEC_TARGET/code_cache" >&2
                      exit 59
                    }
                  fi
                fi
                ;;
              media:*|obb:*) ;;
              *) ;;
            esac
          fi
          if [ -n "${'$'}SEC_CONTEXT" ]; then
            chcon -R -h "${'$'}SEC_CONTEXT" "${'$'}SEC_TARGET" >/dev/null 2>&1 || restorecon -RF "${'$'}SEC_TARGET" >/dev/null 2>&1 || restorecon -R "${'$'}SEC_TARGET" >/dev/null 2>&1 || exit 60
          else
            restorecon -RF "${'$'}SEC_TARGET" >/dev/null 2>&1 || restorecon -R "${'$'}SEC_TARGET" >/dev/null 2>&1 || exit 60
          fi
          chmod "${'$'}SEC_MODE" "${'$'}SEC_TARGET" || exit 59
        }
        clear_target_contents() {
          CLEAR_TARGET="${'$'}1"
          validate_target_path "${'$'}CLEAR_TARGET"
          [ -d "${'$'}CLEAR_TARGET" ] || { echo "ERR_TARGET_MISSING:${'$'}CLEAR_TARGET" >&2; exit 67; }
          uclone_clear_tree_contents "${'$'}CLEAR_TARGET" || exit 68
        }
        restore_part() {
          uclone_cancel_checkpoint "RESTORE_PART_${'$'}4" || exit 93
          SNAP="${'$'}1"
          TARGET="${'$'}2"
          OWNER_KIND="${'$'}3"
          PART_NAME="${'$'}4"
          validate_target_path "${'$'}TARGET"
          PART_STATE_FILE="${'$'}PUSH_TEMP/.state/${'$'}PART_NAME"
          [ -f "${'$'}PART_STATE_FILE" ] || { echo "ERR_PUSH_STATE_MISSING:${'$'}PART_NAME" >&2; exit 64; }
          PART_STATE=${'$'}(sed -n '1p' "${'$'}PART_STATE_FILE" | tr -d '\r')
          uclone_verify_part_metadata "${'$'}PUSH_TEMP" "${'$'}PART_NAME" >/dev/null || {
            echo "ERR_PUSH_INTEGRITY:${'$'}PART_NAME" >&2
            exit 64
          }
          case "${'$'}PART_STATE" in
            excluded)
              [ ! -e "${'$'}SNAP" ] || { echo "ERR_PUSH_STATE_PAYLOAD_CONFLICT:${'$'}PART_NAME:excluded" >&2; exit 64; }
              echo "SKIP_EXCLUDED_PART:${'$'}PART_NAME"
              return 0
              ;;
            absent)
              [ ! -e "${'$'}SNAP" ] || { echo "ERR_PUSH_STATE_PAYLOAD_CONFLICT:${'$'}PART_NAME:absent" >&2; exit 64; }
              uclone_transaction_target_mutating "${'$'}PART_NAME" || exit 77
              TARGET_MUTATED=1
              uclone_remove_tree "${'$'}TARGET" || exit 68
              RESTORED_PARTS=${'$'}((RESTORED_PARTS + 1))
              echo "PUSHED_ABSENT:${'$'}TARGET"
              return 0
              ;;
            empty)
              [ ! -e "${'$'}SNAP" ] || { echo "ERR_PUSH_STATE_PAYLOAD_CONFLICT:${'$'}PART_NAME:empty" >&2; exit 64; }
              SNAP_ITEMS=0
              ;;
            data)
              [ -d "${'$'}SNAP" ] || { echo "ERR_PUSH_DATA_MISSING:${'$'}PART_NAME" >&2; exit 64; }
              SNAP_ITEMS=${'$'}(count_items "${'$'}SNAP") || { echo "ERR_PUSH_PART_UNREADABLE:${'$'}PART_NAME" >&2; exit 64; }
              [ "${'$'}SNAP_ITEMS" -gt 0 ] || { echo "ERR_EMPTY_PUSH_PART:${'$'}SNAP" >&2; exit 64; }
              ;;
            unreadable) echo "ERR_PUSH_PART_UNREADABLE:${'$'}PART_NAME" >&2; exit 64 ;;
            *) echo "ERR_PUSH_STATE_INVALID:${'$'}PART_NAME:${'$'}PART_STATE" >&2; exit 64 ;;
          esac
          TARGET_OWNER=${'$'}(target_owner_for "${'$'}TARGET" "${'$'}OWNER_KIND")
          TARGET_MODE=${'$'}(target_mode_for "${'$'}TARGET" "${'$'}OWNER_KIND")
          uclone_transaction_target_mutating "${'$'}PART_NAME" || exit 77
          TARGET_MUTATED=1
          mkdir -p "${'$'}TARGET" || exit 56
          TARGET_CONTEXT=${'$'}(read_target_context "${'$'}TARGET")
          case "${'$'}TARGET_CONTEXT" in u:object_r:*) ;; *) TARGET_CONTEXT="" ;; esac
          if [ -z "${'$'}TARGET_CONTEXT" ]; then
            restorecon -RF "${'$'}TARGET" >/dev/null 2>&1 || restorecon -R "${'$'}TARGET" >/dev/null 2>&1 || true
            TARGET_CONTEXT=${'$'}(read_target_context "${'$'}TARGET")
            case "${'$'}TARGET_CONTEXT" in u:object_r:*) ;; *) TARGET_CONTEXT="" ;; esac
          fi
          clear_target_contents "${'$'}TARGET"
          if [ "${'$'}PART_STATE" = "data" ]; then
            uclone_extract_target_tree "${'$'}SNAP" "${'$'}TARGET" || exit 58
          fi
          apply_target_security "${'$'}TARGET" "${'$'}TARGET_OWNER" "${'$'}TARGET_CONTEXT" "${'$'}TARGET_MODE" "${'$'}OWNER_KIND"
          TARGET_ITEMS=${'$'}(count_items "${'$'}TARGET")
          if [ "${'$'}PART_STATE" = "data" ]; then
            [ "${'$'}TARGET_ITEMS" -gt 0 ] || { echo "ERR_PUSH_EMPTY:${'$'}TARGET" >&2; exit 65; }
          else
            [ "${'$'}TARGET_ITEMS" -eq 0 ] || { echo "ERR_PUSH_NOT_EMPTY:${'$'}TARGET" >&2; exit 65; }
          fi
          RESTORED_PARTS=${'$'}((RESTORED_PARTS + 1))
          RESTORED_ITEMS=${'$'}((RESTORED_ITEMS + TARGET_ITEMS))
          UCLONE_SCANNED_FILES=${'$'}((UCLONE_SCANNED_FILES + SNAP_ITEMS))
          UCLONE_COPIED_FILES=${'$'}((UCLONE_COPIED_FILES + TARGET_ITEMS))
          TARGET_SIZE_KB=${'$'}(uclone_tree_kb "${'$'}TARGET") || exit 65
          uclone_add_written_kb "${'$'}TARGET_SIZE_KB"
          echo "PUSHED:${'$'}TARGET ITEMS=${'$'}TARGET_ITEMS OWNER=${'$'}TARGET_OWNER CONTEXT=${'$'}TARGET_CONTEXT"
        }
        restore_permission_state_strict() {
          uclone_restore_permission_state "${'$'}1" "${'$'}DST_USER" "${settings.permissionRestoreMode.name}" "${'$'}SRC_USER"
        }
        restore_transaction_permission_state() {
          uclone_restore_permission_state "${'$'}1" "${'$'}DST_USER" EXACT "${'$'}DST_USER"
        }
        restore_permission_state() {
          PERMISSION_SOURCE="${'$'}1"
          PERMISSION_RESTORE_APPLIED=0
          [ -d "${'$'}PERMISSION_SOURCE" ] || { echo "SKIP_PERMISSIONS:${'$'}PERMISSION_SOURCE"; return 0; }
          if ! uclone_permission_capture_valid "${'$'}PERMISSION_SOURCE"; then
            ${if (settings.permissionRestoreMode == PermissionRestoreMode.EXACT) "echo \"ERR_PERMISSION_EXACT_RESTORE:${'$'}PERMISSION_SOURCE\" >&2\nexit 61" else "echo \"WARN_PERMISSION_RESTORE_SKIPPED_INVALID_CAPTURE:${'$'}PERMISSION_SOURCE\"\nreturn 0"}
          fi
          uclone_transaction_target_mutating permissions || exit 77
          ${if (settings.permissionRestoreMode == PermissionRestoreMode.EXACT) """
          restore_permission_state_strict "${'$'}PERMISSION_SOURCE" || {
            echo "ERR_PERMISSION_EXACT_RESTORE:${'$'}PERMISSION_SOURCE" >&2
            exit 61
          }
          """.trimIndent() else """
          if ! restore_permission_state_strict "${'$'}PERMISSION_SOURCE"; then
            echo "WARN_PERMISSION_RESTORE_SKIPPED_INVALID_CAPTURE:${'$'}PERMISSION_SOURCE"
          fi
          """.trimIndent()}
          PERMISSION_RESTORE_APPLIED=1
          return 0
        }
        uclone_stage_begin SOURCE_PREPARE
        uclone_transaction_stage SOURCE_GATE_ACQUIRE || exit 77
        uclone_gate_acquire source "${'$'}SRC_USER" "${'$'}SRC_UID" || { echo "ERR_SOURCE_GATE_ACQUIRE:${'$'}SRC_USER:${'$'}PKG" >&2; exit 77; }
        SOURCE_GATE_DIR="${'$'}UCLONE_GATE_DIR"
        mkdir -p "${'$'}PUSH_TEMP" || exit 11
        uclone_remove_tree "${'$'}ROLLBACK" || exit 11
        mkdir -p "${'$'}ROLLBACK" || exit 11
        COPIED_PARTS=0
        COPIED_ITEMS=0
        CAPTURED_PARTS=0
        CAPTURED_PERMISSIONS=0
        PERMISSION_CAPTURE_STATE=excluded
        BACKUP_PARTS=0
        RESTORED_PARTS=0
        RESTORED_ITEMS=0
        ${if (rule.includeCe) "capture_part \"${'$'}PUSH_TEMP\" ce \"${'$'}PUSH_TEMP/ce\" \"/data/user/${'$'}SRC_USER/${'$'}PKG\" \"/data_mirror/data_ce/null/${'$'}SRC_USER/${'$'}PKG\" \"/data_mirror/data_ce/${'$'}SRC_USER/${'$'}PKG\"" else "capture_excluded_part \"${'$'}PUSH_TEMP\" ce"}
        ${if (rule.includeDe) "capture_part \"${'$'}PUSH_TEMP\" de \"${'$'}PUSH_TEMP/de\" \"/data/user_de/${'$'}SRC_USER/${'$'}PKG\" \"/data_mirror/data_de/null/${'$'}SRC_USER/${'$'}PKG\" \"/data_mirror/data_de/${'$'}SRC_USER/${'$'}PKG\"" else "capture_excluded_part \"${'$'}PUSH_TEMP\" de"}
        ${if (rule.includeExternal) "capture_part \"${'$'}PUSH_TEMP\" external \"${'$'}PUSH_TEMP/external\" \"/data/media/${'$'}SRC_USER/Android/data/${'$'}PKG\" \"/storage/emulated/${'$'}SRC_USER/Android/data/${'$'}PKG\"" else "capture_excluded_part \"${'$'}PUSH_TEMP\" external"}
        ${if (rule.includeMedia) "capture_part \"${'$'}PUSH_TEMP\" media \"${'$'}PUSH_TEMP/media\" \"/data/media/${'$'}SRC_USER/Android/media/${'$'}PKG\" \"/storage/emulated/${'$'}SRC_USER/Android/media/${'$'}PKG\"" else "capture_excluded_part \"${'$'}PUSH_TEMP\" media"}
        ${if (rule.includeObb) "capture_part \"${'$'}PUSH_TEMP\" obb \"${'$'}PUSH_TEMP/obb\" \"/data/media/${'$'}SRC_USER/Android/obb/${'$'}PKG\" \"/storage/emulated/${'$'}SRC_USER/Android/obb/${'$'}PKG\"" else "capture_excluded_part \"${'$'}PUSH_TEMP\" obb"}
        ${if (rule.includePermissions) sourcePermissionCaptureScript("${'$'}PUSH_TEMP/permissions", "${'$'}SRC_USER", settings.permissionRestoreMode) else ":"}
        PUSH_CE_STATE=${'$'}(sed -n '1p' "${'$'}PUSH_TEMP/.state/ce" 2>/dev/null || true)
        if [ "${'$'}PUSH_REQUIRE_CE" = "1" ] && [ "${'$'}PUSH_CE_STATE" != "data" ] && [ "${'$'}PUSH_CE_STATE" != "empty" ]; then
          echo "ERR_PUSH_CE_MISSING:${'$'}SRC_USER" >&2
          exit 44
        fi
        [ "${'$'}CAPTURED_PARTS" -gt 0 ] || [ "${'$'}CAPTURED_PERMISSIONS" = "1" ] || { echo "ERR_NOTHING_COPIED: no selected source parts for main user:${'$'}SRC_USER package:${'$'}PKG" >&2; exit 45; }
        uclone_record_temp_path "${'$'}PUSH_TEMP"
        uclone_transaction_stage SOURCE_PREPARED || exit 77
        if ! uclone_gate_release "${'$'}SOURCE_GATE_DIR"; then
          uclone_transaction_recovery_required
          exit 92
        fi
        SOURCE_GATE_DIR=""
        uclone_stage_end
        uclone_stage_begin TARGET_STOP
        uclone_transaction_stage TARGET_GATE_ACQUIRE || exit 77
        uclone_gate_acquire target "${'$'}DST_USER" "${'$'}DST_UID" || { echo "ERR_TARGET_GATE_ACQUIRE:${'$'}DST_USER:${'$'}PKG" >&2; exit 77; }
        TARGET_GATE_DIR="${'$'}UCLONE_GATE_DIR"
        uclone_transaction_stage TARGET_GATED || exit 77
        UCLONE_TARGET_STOPPED_AT=${'$'}(uclone_now_ms)
        uclone_stage_end
        uclone_stage_begin ROLLBACK_BACKUP
        backup_dir "/data/user/${'$'}DST_USER/${'$'}PKG" "${'$'}ROLLBACK/ce" "ce"
        backup_dir "/data/user_de/${'$'}DST_USER/${'$'}PKG" "${'$'}ROLLBACK/de" "de"
        backup_dir "/data/media/${'$'}DST_USER/Android/data/${'$'}PKG" "${'$'}ROLLBACK/external" "external"
        backup_dir "/data/media/${'$'}DST_USER/Android/media/${'$'}PKG" "${'$'}ROLLBACK/media" "media"
        backup_dir "/data/media/${'$'}DST_USER/Android/obb/${'$'}PKG" "${'$'}ROLLBACK/obb" "obb"
        ${if (rule.includePermissions) "uclone_capture_permission_state \"${'$'}ROLLBACK/permissions\" \"${'$'}DST_USER\" EXACT || { echo \"ERR_TRANSACTION_PERMISSION_CAPTURE:${'$'}DST_USER\" >&2; exit 54; }" else ":"}
        ROLLBACK_SIZE_KB=${'$'}(uclone_tree_kb "${'$'}ROLLBACK") || exit 63
        if [ "${'$'}CLONE_ROLLBACK_REUSED" = "1" ]; then
          printf '%s\n' "{\"schemaVersion\":$CURRENT_MANIFEST_SCHEMA,\"integrityMode\":\"FAST_METADATA\",\"packageName\":\"${'$'}PKG\",\"sourceVersionCode\":\"${'$'}PACKAGE_VERSION_CODE\",\"sourceVersionName\":\"${'$'}PACKAGE_VERSION_NAME_ESC\",\"sourceSigningCertificateSha256\":\"${'$'}UCLONE_SIGNING_CERTIFICATE_SHA256\",\"sourceApkDigest\":\"${'$'}TARGET_APK_DIGEST\",\"sourceUid\":${'$'}DST_UID,\"sourceAppId\":${'$'}TARGET_APP_ID,\"transactionRequestId\":\"${'$'}UCLONE_REQUEST_ID\",\"createdAt\":\"${'$'}TS\",\"stateKind\":\"clone\",\"sourceUser\":\"${'$'}DST_USER\",\"targetUser\":\"${'$'}DST_USER\",\"backupKind\":\"transaction_undo\",\"permissionCaptureState\":\"${if (rule.includePermissions) "valid" else "excluded"}\",\"sizeKb\":\"${'$'}ROLLBACK_SIZE_KB\"}" > "${'$'}ROLLBACK/manifest.json" || exit 53
          echo "TRANSACTION_UNDO_CREATED=${'$'}ROLLBACK state=clone"
        else
          printf '%s\n' "{\"schemaVersion\":$CURRENT_MANIFEST_SCHEMA,\"integrityMode\":\"FAST_METADATA\",\"packageName\":\"${'$'}PKG\",\"sourceVersionCode\":\"${'$'}PACKAGE_VERSION_CODE\",\"sourceVersionName\":\"${'$'}PACKAGE_VERSION_NAME_ESC\",\"sourceSigningCertificateSha256\":\"${'$'}UCLONE_SIGNING_CERTIFICATE_SHA256\",\"sourceApkDigest\":\"${'$'}TARGET_APK_DIGEST\",\"sourceUid\":${'$'}DST_UID,\"sourceAppId\":${'$'}TARGET_APP_ID,\"transactionRequestId\":\"${'$'}UCLONE_REQUEST_ID\",\"rollbackId\":\"latest\",\"createdAt\":\"${'$'}TS\",\"reason\":\"推送到分身前生成\",\"stateKind\":\"clone\",\"sourceUser\":\"${'$'}DST_USER\",\"targetUser\":\"${'$'}DST_USER\",\"backupKind\":\"clone_rollback\",\"retention\":\"latest_only\",\"permissionCaptureState\":\"${if (rule.includePermissions) "valid" else "excluded"}\",\"sizeKb\":\"${'$'}ROLLBACK_SIZE_KB\"}" > "${'$'}ROLLBACK/manifest.json" || exit 53
        fi
        sync
        echo "CLONE_ROLLBACK_PREPARED=${'$'}ROLLBACK backupParts=${'$'}BACKUP_PARTS"
        uclone_transaction_rollback_ready "${'$'}ROLLBACK" || exit 77
        uclone_stage_end
        ${RestoreTransactionShell.guard(
            appUidVariable = "DST_UID",
            includePermissions = rule.includePermissions,
            manageSwitchMarker = false,
        )}
        uclone_stage_begin RESTORE_DATA
        restore_part "${'$'}PUSH_TEMP/ce" "/data/user/${'$'}DST_USER/${'$'}PKG" "app" "ce"
        restore_part "${'$'}PUSH_TEMP/de" "/data/user_de/${'$'}DST_USER/${'$'}PKG" "app" "de"
        restore_part "${'$'}PUSH_TEMP/external" "/data/media/${'$'}DST_USER/Android/data/${'$'}PKG" "media" "external"
        restore_part "${'$'}PUSH_TEMP/media" "/data/media/${'$'}DST_USER/Android/media/${'$'}PKG" "media" "media"
        restore_part "${'$'}PUSH_TEMP/obb" "/data/media/${'$'}DST_USER/Android/obb/${'$'}PKG" "obb" "obb"
        if [ "${'$'}UCLONE_TXN_TARGET_MUTATED" = "true" ]; then
          uclone_transaction_stage TARGET_WRITTEN || exit 77
          uclone_transaction_stage METADATA_RESTORED || exit 77
        fi
        uclone_stage_end
        uclone_stage_begin RESTORE_PERMISSIONS
        ${if (rule.includePermissions) "restore_permission_state \"${'$'}PUSH_TEMP/permissions\"" else ":"}
        [ "${'$'}{PERMISSION_RESTORE_APPLIED:-0}" != "1" ] || uclone_transaction_stage PERMISSIONS_RESTORED || exit 77
        uclone_stage_end
        uclone_stage_begin VERIFY
        [ "${'$'}RESTORED_PARTS" -gt 0 ] || [ "${'$'}{PERMISSION_RESTORE_APPLIED:-0}" = "1" ] || { echo "ERR_NOTHING_PUSHED:${'$'}PUSH_TEMP" >&2; exit 62; }
        uclone_transaction_stage VERIFIED || exit 77
        uclone_stage_end
        uclone_stage_begin COMMIT
        sync
        if [ "${'$'}CLONE_ROLLBACK_REUSED" = "0" ]; then
        if [ -d "${'$'}ROLLBACK_PREVIOUS" ] && [ -d "${'$'}ROLLBACK_LATEST" ]; then
          uclone_remove_tree "${'$'}ROLLBACK_PREVIOUS" || exit 54
        fi
        if [ -d "${'$'}ROLLBACK_LATEST" ]; then
          mv "${'$'}ROLLBACK_LATEST" "${'$'}ROLLBACK_PREVIOUS" || exit 54
        fi
        uclone_transaction_rollback_relocating "${'$'}ROLLBACK_LATEST" || exit 77
        if mv "${'$'}ROLLBACK_TMP" "${'$'}ROLLBACK_LATEST"; then
          ROLLBACK="${'$'}ROLLBACK_LATEST"
          uclone_transaction_rollback_relocated "${'$'}ROLLBACK_LATEST" || exit 77
        else
          if [ ! -d "${'$'}ROLLBACK_LATEST" ] && [ -d "${'$'}ROLLBACK_PREVIOUS" ]; then
            mv "${'$'}ROLLBACK_PREVIOUS" "${'$'}ROLLBACK_LATEST" >/dev/null 2>&1 || true
          fi
          exit 54
        fi
        fi
        sync
        force_stop_package_users || exit 76
        uclone_transaction_commit_data || exit 77
        TRANSACTION_COMMITTED=1
        if ! uclone_gate_release "${'$'}TARGET_GATE_DIR"; then
          uclone_transaction_recovery_required
          exit 92
        fi
        TARGET_GATE_DIR=""
        uclone_transaction_complete || exit 77
        if [ -n "${'$'}TRANSACTION_UNDO" ]; then
          uclone_remove_tree "${'$'}TRANSACTION_UNDO" >/dev/null 2>&1 || echo "WARN_TRANSACTION_UNDO_CLEANUP_FAILED:${'$'}TRANSACTION_UNDO"
          TRANSACTION_UNDO=""
        fi
        uclone_remove_tree "${'$'}ROLLBACK_PREVIOUS" >/dev/null 2>&1 || echo "WARN_CLONE_ROLLBACK_PREVIOUS_CLEANUP_FAILED:${'$'}ROLLBACK_PREVIOUS"
        uclone_transaction_cleanup_complete || exit 77
        UCLONE_TARGET_READY_AT=${'$'}(uclone_now_ms)
        UCLONE_TARGET_DOWNTIME_MS=${'$'}(uclone_elapsed_ms "${'$'}UCLONE_TARGET_READY_AT" "${'$'}UCLONE_TARGET_STOPPED_AT")
        uclone_stage_end
        uclone_emit_metrics
        echo "PUSH_MAIN_TO_CLONE_DONE targetUser=${'$'}DST_USER restoredParts=${'$'}RESTORED_PARTS restoredItems=${'$'}RESTORED_ITEMS copiedParts=${'$'}COPIED_PARTS backupParts=${'$'}BACKUP_PARTS"
    """.trimIndent()

    fun probeCloneCe(settings: UCloneSettings): String = """
        set -u
        CLONE_USER=${settings.cloneUserId}
        UCLONE_STATE_TEMP_ROOT=/data/local/tmp
        ${cloneStateFunction("/system/bin/am")}
        echo "PROBE_CLONE_USER=${'$'}CLONE_USER"
        echo "ROOT_ID=${'$'}(id 2>&1)"
        state_of_clone() {
          clone_state "${'$'}CLONE_USER"
        }
        probe_base_path() {
          LABEL="${'$'}1"
          PATH_VALUE="${'$'}2"
          if [ -d "${'$'}PATH_VALUE" ]; then
            ITEMS=${'$'}(find "${'$'}PATH_VALUE" -xdev -mindepth 1 -maxdepth 1 2>/dev/null | wc -l | tr -d ' ')
            echo "${'$'}LABEL=READABLE items=${'$'}ITEMS path=${'$'}PATH_VALUE"
          else
            echo "${'$'}LABEL=MISSING path=${'$'}PATH_VALUE"
          fi
        }
        STATE=${'$'}(state_of_clone)
        echo "STATE=${'$'}STATE"
        probe_base_path "CE_BASE" "/data/user/${'$'}CLONE_USER"
        probe_base_path "DE_BASE" "/data/user_de/${'$'}CLONE_USER"
        case "${'$'}STATE" in
          *RUNNING_UNLOCKED*)
            echo "USER10_CE_STATE=RUNNING_UNLOCKED"
            echo "USER10_CE_READY=1"
            ;;
          *RUNNING_LOCKED*|RUNNING)
            echo "USER10_CE_STATE=STARTED_LOCKED"
            echo "ERR_USER10_CE_LOCKED:${'$'}STATE" >&2
            exit 80
            ;;
          *"User is not started"*|*"not started"*|*SHUTDOWN*|*STOPPING*)
            echo "USER10_CE_STATE=NOT_STARTED"
            echo "ERR_USER10_NOT_STARTED:${'$'}STATE" >&2
            exit 81
            ;;
          *)
            echo "USER10_CE_STATE=UNKNOWN"
            echo "ERR_USER10_STATE_UNKNOWN:${'$'}STATE" >&2
            exit 82
            ;;
        esac
    """.trimIndent()

    internal fun boundedUserStateProbe(
        userId: Int,
        amCommand: String = "/system/bin/am",
    ): String {
        require(userId >= 0)
        return """
            set -u
            CLONE_USER=$userId
            ${cloneStateFunction(amCommand)}
            clone_state "${'$'}CLONE_USER"
        """.trimIndent()
    }

    fun unlockCloneWithCredential(settings: UCloneSettings): String = """
        set -u
        echo "UNLOCK_CLONE_USER=${settings.cloneUserId}"
        echo "ROOT_ID=${'$'}(id 2>&1)"
        ${ensureCloneCeReadyScript(settings, required = true, autoUnlockAllowed = true, stopAfterTask = false)}
        echo "USER10_CE_STATE=RUNNING_UNLOCKED"
        echo "USER10_CE_READY=1"
        echo "UNLOCK_CLONE_WITH_CREDENTIAL_DONE"
    """.trimIndent()

    internal fun startCloneUser(
        settings: UCloneSettings,
        amCommand: String = "/system/bin/am",
        sleepCommand: String = "sleep",
        startPollLimit: Int = 40,
        startPollIntervalSeconds: Double = 0.25,
    ): String = """
        set -u
        CLONE_USER=${settings.cloneUserId}
        ${cloneStateFunction(amCommand)}
        echo "EXPLICIT_START_CLONE_USER=${'$'}CLONE_USER"
        STATE_BEFORE_START=${'$'}(clone_state)
        echo "STATE_BEFORE_START=${'$'}STATE_BEFORE_START"
        case "${'$'}STATE_BEFORE_START" in
          *CLONE_STATE_QUERY_UNAVAILABLE*|*CLONE_STATE_QUERY_EMPTY*)
            echo "ERR_CLONE_STATE_QUERY:${'$'}STATE_BEFORE_START" >&2
            exit 88
            ;;
          *RUNNING*) echo "START_CLONE_ALREADY_STARTED=${'$'}STATE_BEFORE_START"; exit 0 ;;
        esac
        ${startCloneUserRequestScript(
            amCommand = amCommand,
            sleepCommand = sleepCommand,
            startPollLimit = startPollLimit,
            startPollIntervalSeconds = startPollIntervalSeconds,
            markStartedByTask = false,
            failureExitCode = 88,
        )}
    """.trimIndent()

    internal fun stopCloneUser(
        settings: UCloneSettings,
        amCommand: String = "/system/bin/am",
        sleepCommand: String = "sleep",
        stopPollLimit: Int = 20,
        stopPollIntervalSeconds: Double = 0.25,
    ): String {
        return """
        set -u
        CLONE_USER=${settings.cloneUserId}
        ${cloneStateFunction(amCommand)}
        echo "EXPLICIT_STOP_CLONE_USER=${'$'}CLONE_USER"
        STATE_BEFORE_STOP=${'$'}(clone_state)
        echo "STATE_BEFORE_STOP=${'$'}STATE_BEFORE_STOP"
        case "${'$'}STATE_BEFORE_STOP" in
          *CLONE_STATE_QUERY_UNAVAILABLE*|*CLONE_STATE_QUERY_EMPTY*)
            echo "ERR_CLONE_STATE_QUERY:${'$'}STATE_BEFORE_STOP" >&2
            exit 88
            ;;
          *"User is not started"*|*"not started"*|*SHUTDOWN*) echo "STOP_CLONE_ALREADY_STOPPED=1"; exit 0 ;;
        esac
        ${stopCloneUserRequestScript(
            amCommand = amCommand,
            sleepCommand = sleepCommand,
            stopPollLimit = stopPollLimit,
            stopPollIntervalSeconds = stopPollIntervalSeconds,
        )}
        if [ "${'$'}STOP_CLONE_CONFIRMED" = "1" ]; then
          exit 0
        fi
        if [ "${'$'}STOP_USER_EXIT" -ne 0 ] && [ "${'$'}STOP_USER_CLIENT_TERMINATED" != "1" ]; then
          echo "ERR_STOP_CLONE_REQUEST_FAILED:${'$'}STOP_USER_EXIT:${'$'}(clone_state)" >&2
          exit 86
        fi
        STATE_AFTER_STOP_TIMEOUT=${'$'}(clone_state)
        echo "STATE_AFTER_STOP_TIMEOUT=${'$'}STATE_AFTER_STOP_TIMEOUT"
        echo "ERR_STOP_CLONE_PENDING:${'$'}STATE_AFTER_STOP_TIMEOUT" >&2
        exit 87
        """.trimIndent()
    }

    fun debugCloneSystem(settings: UCloneSettings, appPackage: String): String = """
        set -u
        MAIN_USER=${settings.mainUserId}
        CLONE_USER=${settings.cloneUserId}
        UCLONE_STATE_TEMP_ROOT=/data/local/tmp
        ${cloneStateFunction("/system/bin/am")}
        APP_PKG=${shellQuote(appPackage)}
        ROOT_DIR=${shellQuote(settings.rootDir)}
        echo "DEBUG_CLONE_SYSTEM_BEGIN"
        echo "DEBUG_SCOPE=read_only_no_start_stop_no_delete"
        echo "APP_PACKAGE=${'$'}APP_PKG"
        echo "MAIN_USER=${'$'}MAIN_USER"
        echo "CLONE_USER=${'$'}CLONE_USER"
        echo "ROOT_DIR=${'$'}ROOT_DIR"
        echo "ROOT_ID=${'$'}(id 2>&1)"
        echo "SELINUX=${'$'}(getenforce 2>&1 || echo unavailable)"
        echo "PATH_VALUE=${'$'}PATH"
        echo "SU_PATH=${'$'}(command -v su 2>/dev/null || echo MISSING)"
        echo "MAGISK_PATH=${'$'}(command -v magisk 2>/dev/null || echo MISSING)"
        echo "KSU_PATH=${'$'}(command -v ksud 2>/dev/null || command -v ksu 2>/dev/null || echo MISSING)"

        user_state() {
          clone_state "${'$'}1"
        }
        probe_path() {
          LABEL="${'$'}1"
          PATH_VALUE="${'$'}2"
          echo "PATH_${'$'}{LABEL}=${'$'}PATH_VALUE"
          if [ -e "${'$'}PATH_VALUE" ]; then
            echo "PATH_${'$'}{LABEL}_EXISTS=1"
            ls -ldnZ "${'$'}PATH_VALUE" 2>&1 | sed "s/^/PATH_${'$'}{LABEL}_LS_Z: /" || ls -ldn "${'$'}PATH_VALUE" 2>&1 | sed "s/^/PATH_${'$'}{LABEL}_LS: /" || true
            if [ -d "${'$'}PATH_VALUE" ]; then
              ITEMS=${'$'}(find "${'$'}PATH_VALUE" -xdev -mindepth 1 -maxdepth 1 2>/dev/null | wc -l | tr -d ' ')
              SIZE_KB=${'$'}(du -skx "${'$'}PATH_VALUE" 2>/dev/null | awk '{print ${'$'}1}')
              echo "PATH_${'$'}{LABEL}_ITEMS=${'$'}ITEMS"
              echo "PATH_${'$'}{LABEL}_SIZE_KB=${'$'}SIZE_KB"
              find "${'$'}PATH_VALUE" -xdev -mindepth 1 -maxdepth 2 2>/dev/null | sed -n '1,12p' | sed "s/^/PATH_${'$'}{LABEL}_SAMPLE: /"
            fi
          else
            echo "PATH_${'$'}{LABEL}_EXISTS=0"
          fi
        }
        probe_package_user() {
          USER_ID="${'$'}1"
          echo "PACKAGE_USER_${'$'}{USER_ID}_BEGIN"
          echo "PACKAGE_USER_${'$'}{USER_ID}_STATE=${'$'}(user_state "${'$'}USER_ID")"
          PACKAGE_LINE=${'$'}(/system/bin/cmd package list packages -U --user "${'$'}USER_ID" 2>/dev/null | grep -F "package:${'$'}APP_PKG " || true)
          [ -n "${'$'}PACKAGE_LINE" ] || PACKAGE_LINE=${'$'}(/system/bin/cmd package list packages -U --user "${'$'}USER_ID" 2>/dev/null | grep -Fx "package:${'$'}APP_PKG" || true)
          if [ -n "${'$'}PACKAGE_LINE" ]; then
            echo "PACKAGE_USER_${'$'}{USER_ID}_SELF=${'$'}PACKAGE_LINE"
          else
            echo "PACKAGE_USER_${'$'}{USER_ID}_SELF=MISSING"
          fi
          TOTAL=${'$'}(/system/bin/cmd package list packages --user "${'$'}USER_ID" 2>/dev/null | wc -l | tr -d ' ')
          THIRD_PARTY=${'$'}(/system/bin/cmd package list packages -3 --user "${'$'}USER_ID" 2>/dev/null | wc -l | tr -d ' ')
          SYSTEM=${'$'}(/system/bin/cmd package list packages -s --user "${'$'}USER_ID" 2>/dev/null | wc -l | tr -d ' ')
          echo "PACKAGE_USER_${'$'}{USER_ID}_TOTAL=${'$'}TOTAL"
          echo "PACKAGE_USER_${'$'}{USER_ID}_THIRD_PARTY=${'$'}THIRD_PARTY"
          echo "PACKAGE_USER_${'$'}{USER_ID}_SYSTEM=${'$'}SYSTEM"
          /system/bin/cmd appops get --user "${'$'}USER_ID" "${'$'}APP_PKG" 2>&1 | sed -n '1,30p' | sed "s/^/APPOPS_USER_${'$'}{USER_ID}: /"
          echo "PACKAGE_USER_${'$'}{USER_ID}_END"
        }

        CURRENT_USER=${'$'}(/system/bin/am get-current-user 2>&1 || true)
        echo "CURRENT_USER=${'$'}CURRENT_USER"
        echo "USER_LIST_BEGIN"
        /system/bin/pm list users 2>&1 | sed 's/^/USER_LIST: /' || true
        echo "USER_LIST_END"
        echo "MAIN_STATE=${'$'}(user_state "${'$'}MAIN_USER")"
        echo "CLONE_STATE=${'$'}(user_state "${'$'}CLONE_USER")"

        probe_package_user "${'$'}MAIN_USER"
        probe_package_user "${'$'}CLONE_USER"

        echo "SELF_PROCESS_BEGIN"
        ps -A -o USER,PID,PPID,NAME,ARGS 2>/dev/null | grep -F "${'$'}APP_PKG" | sed 's/^/SELF_PROCESS: /' || echo "SELF_PROCESS: none"
        echo "SELF_PROCESS_END"

        probe_path "ROOT_DIR" "${'$'}ROOT_DIR"
        probe_path "MAIN_SELF_CE" "/data/user/${'$'}MAIN_USER/${'$'}APP_PKG"
        probe_path "CLONE_SELF_CE" "/data/user/${'$'}CLONE_USER/${'$'}APP_PKG"
        probe_path "MAIN_SELF_DE" "/data/user_de/${'$'}MAIN_USER/${'$'}APP_PKG"
        probe_path "CLONE_SELF_DE" "/data/user_de/${'$'}CLONE_USER/${'$'}APP_PKG"
        probe_path "MAIN_SELF_EXTERNAL" "/data/media/${'$'}MAIN_USER/Android/data/${'$'}APP_PKG"
        probe_path "CLONE_SELF_EXTERNAL" "/data/media/${'$'}CLONE_USER/Android/data/${'$'}APP_PKG"
        probe_path "CLONE_CE_BASE" "/data/user/${'$'}CLONE_USER"
        probe_path "CLONE_DE_BASE" "/data/user_de/${'$'}CLONE_USER"
        probe_path "CLONE_MEDIA_BASE" "/data/media/${'$'}CLONE_USER"

        echo "V02_ARTIFACT_COMPAT_BEGIN"
        echo "V02_ARTIFACT_MODEL=backup_records_must_include_source_user_target_user_created_in_user_kind"
        echo "V02_ACTIVE_SNAPSHOT_ROOT=${'$'}ROOT_DIR/snapshots"
        echo "V02_ROLLBACK_ROOT=${'$'}ROOT_DIR/rollback"
        echo "V02_SWITCH_MARKER_ROOT=${'$'}ROOT_DIR/switches"
        probe_path "SNAPSHOTS_ROOT" "${'$'}ROOT_DIR/snapshots"
        probe_path "ROLLBACK_ROOT" "${'$'}ROOT_DIR/rollback"
        probe_path "SWITCH_MARKER_ROOT" "${'$'}ROOT_DIR/switches"
        if [ -d "${'$'}ROOT_DIR/snapshots" ]; then
          find "${'$'}ROOT_DIR/snapshots" -xdev -mindepth 3 -maxdepth 4 -name manifest.json 2>/dev/null | sed -n '1,8p' | while IFS= read -r MANIFEST; do
            echo "V02_SNAPSHOT_MANIFEST=${'$'}MANIFEST"
            sed -n '1p' "${'$'}MANIFEST" 2>/dev/null | sed 's/^/V02_SNAPSHOT_MANIFEST_JSON: /'
          done
        fi
        if [ -d "${'$'}ROOT_DIR/rollback" ]; then
          find "${'$'}ROOT_DIR/rollback" -xdev -mindepth 3 -maxdepth 3 -name manifest.json 2>/dev/null | sed -n '1,8p' | while IFS= read -r MANIFEST; do
            echo "V02_ROLLBACK_MANIFEST=${'$'}MANIFEST"
            sed -n '1p' "${'$'}MANIFEST" 2>/dev/null | sed 's/^/V02_ROLLBACK_MANIFEST_JSON: /'
          done
        fi
        echo "V02_REQUIRED_NEW_MANIFEST_FIELDS=sourceUser,targetUser,createdInUser,backupKind,activeForUsers,restorableToUsers"
        echo "V02_ARTIFACT_COMPAT_END"

        echo "DIRECT_BOOT_HINT_BEGIN"
        case "${'$'}(user_state "${'$'}CLONE_USER")" in
          *RUNNING_UNLOCKED*) echo "CLONE_CE_GATE=READY" ;;
          *RUNNING_LOCKED*|RUNNING) echo "CLONE_CE_GATE=LOCKED" ;;
          *"User is not started"*|*"not started"*|*SHUTDOWN*|*STOPPING*) echo "CLONE_CE_GATE=NOT_STARTED" ;;
          *) echo "CLONE_CE_GATE=UNKNOWN" ;;
        esac
        echo "DIRECT_BOOT_HINT_END"

        echo "V02_PRECHECK_BEGIN"
        if /system/bin/cmd package list packages --user "${'$'}CLONE_USER" 2>/dev/null | grep -qx "package:${'$'}APP_PKG"; then
          echo "V02_SELF_INSTALLED_IN_CLONE=1"
        else
          echo "V02_SELF_INSTALLED_IN_CLONE=0"
        fi
        case "${'$'}CURRENT_USER" in
          "${'$'}CLONE_USER") echo "V02_DEBUG_RAN_FROM_CLONE_USER=1" ;;
          *) echo "V02_DEBUG_RAN_FROM_CLONE_USER=0" ;;
        esac
        case "${'$'}(id 2>&1)" in
          *uid=0*) echo "V02_THIS_RUNTIME_HAS_ROOT=1" ;;
          *) echo "V02_THIS_RUNTIME_HAS_ROOT=0" ;;
        esac
        echo "V02_SYNC_TARGET=main_and_clone_can_create_labeled_backups_and_restore_each_other"
        echo "V02_SYNC_OPEN_QUESTIONS=run this same debug from user0 and from clone user after installing app there"
        echo "V02_PRECHECK_END"
        echo "DEBUG_CLONE_SYSTEM_DONE"
    """.trimIndent()

    fun auditRestoreConsistency(packageName: String, settings: UCloneSettings, appPackage: String): String = """
        set -u
        ${WorkspacePathGuard.require(settings.rootDir)}
        PKG=${shellQuote(packageName)}
        APP_PKG=${shellQuote(appPackage)}
        TARGET_USER=${settings.mainUserId}
        CLONE_USER=${settings.cloneUserId}
        UCLONE_STATE_TEMP_ROOT=/data/local/tmp
        ${cloneStateFunction("/system/bin/am")}
        ${uniqueStampScript()}
        TS=${'$'}(uclone_unique_stamp) || { echo "ERR_UNIQUE_STAMP" >&2; exit 10; }
        OUT="${'$'}ROOT/audit/${'$'}PKG/${'$'}TS"
        mkdir -p "${'$'}OUT" || exit 10
        echo "AUDIT_DIR=${'$'}OUT"
        echo "AUDIT_PACKAGE=${'$'}PKG"
        echo "AUDIT_TARGET_USER=${'$'}TARGET_USER"
        echo "AUDIT_CLONE_USER=${'$'}CLONE_USER"
        run_capture() {
          NAME="${'$'}1"
          shift
          {
            echo "COMMAND:${'$'}*"
            "${'$'}@" 2>&1
            echo "EXIT:${'$'}?"
          } > "${'$'}OUT/${'$'}NAME"
        }
        capture_user_states() {
          echo "target=${'$'}(clone_state "${'$'}TARGET_USER")"
          echo "clone=${'$'}(clone_state "${'$'}CLONE_USER")"
        }
        capture_tree() {
          NAME="${'$'}1"
          PATH_VALUE="${'$'}2"
          {
            echo "PATH:${'$'}PATH_VALUE"
            if [ -d "${'$'}PATH_VALUE" ]; then
              find "${'$'}PATH_VALUE" -xdev -maxdepth 4 2>/dev/null | while IFS= read -r ITEM; do
                ls -ldnZ "${'$'}ITEM" 2>/dev/null || ls -ldn "${'$'}ITEM" 2>/dev/null || true
              done
            else
              echo "MISSING:${'$'}PATH_VALUE"
            fi
          } > "${'$'}OUT/${'$'}NAME"
        }
        capture_lz() {
          NAME="${'$'}1"
          PATH_VALUE="${'$'}2"
          {
            echo "PATH:${'$'}PATH_VALUE"
            if [ -d "${'$'}PATH_VALUE" ]; then
              ls -lanZ "${'$'}PATH_VALUE" 2>/dev/null || ls -lan "${'$'}PATH_VALUE" 2>/dev/null || true
            else
              echo "MISSING:${'$'}PATH_VALUE"
            fi
          } > "${'$'}OUT/${'$'}NAME"
        }
        printf '%s\n' "{\"packageName\":\"${'$'}PKG\",\"appPackage\":\"${'$'}APP_PKG\",\"targetUser\":\"${'$'}TARGET_USER\",\"cloneUser\":\"${'$'}CLONE_USER\",\"createdAt\":\"${'$'}TS\"}" > "${'$'}OUT/manifest.json"
        id > "${'$'}OUT/root_id.txt" 2>&1 || true
        run_capture "uid.txt" sh -c "cmd package list packages -U --user ${'$'}TARGET_USER | grep -F \"package:${'$'}PKG\" || true"
        run_capture "user_state.txt" capture_user_states
        run_capture "package_dump.txt" sh -c "dumpsys package \"${'$'}PKG\" || true"
        run_capture "cmd_package_dump.txt" sh -c "cmd package dump \"${'$'}PKG\" || true"
        run_capture "appops_pkg.txt" sh -c "cmd appops get --user ${'$'}TARGET_USER \"${'$'}PKG\" || true"
        run_capture "appops_uid.txt" sh -c "cmd appops get --uid \"${'$'}PKG\" || true"
        capture_tree "file_tree_ce.txt" "/data/user/${'$'}TARGET_USER/${'$'}PKG"
        capture_tree "file_tree_de.txt" "/data/user_de/${'$'}TARGET_USER/${'$'}PKG"
        capture_tree "file_tree_external.txt" "/data/media/${'$'}TARGET_USER/Android/data/${'$'}PKG"
        capture_tree "file_tree_media.txt" "/data/media/${'$'}TARGET_USER/Android/media/${'$'}PKG"
        capture_tree "file_tree_obb.txt" "/data/media/${'$'}TARGET_USER/Android/obb/${'$'}PKG"
        capture_lz "ls_lZ_ce.txt" "/data/user/${'$'}TARGET_USER/${'$'}PKG"
        capture_lz "ls_lZ_de.txt" "/data/user_de/${'$'}TARGET_USER/${'$'}PKG"
        if [ -f "${'$'}ROOT/snapshots/${'$'}PKG/active/manifest.json" ]; then
          cp "${'$'}ROOT/snapshots/${'$'}PKG/active/manifest.json" "${'$'}OUT/active_manifest.json" 2>/dev/null || true
        else
          echo "MISSING:${'$'}ROOT/snapshots/${'$'}PKG/active/manifest.json" > "${'$'}OUT/active_manifest.json"
        fi
        PACKAGE_PRESENT=0
        cmd package list packages --user "${'$'}TARGET_USER" 2>/dev/null | grep -qx "package:${'$'}PKG" && PACKAGE_PRESENT=1 || true
        CE_PRESENT=0
        [ -d "/data/user/${'$'}TARGET_USER/${'$'}PKG" ] && CE_PRESENT=1 || true
        DE_PRESENT=0
        [ -d "/data/user_de/${'$'}TARGET_USER/${'$'}PKG" ] && DE_PRESENT=1 || true
        CLONE_STATE=${'$'}(clone_state "${'$'}CLONE_USER")
        STATUS="PASS_COLLECTION"
        [ "${'$'}PACKAGE_PRESENT" = "1" ] || STATUS="FAIL_PACKAGE_MISSING"
        {
          echo "# UClone Restore Audit"
          echo
          echo "- status: ${'$'}STATUS"
          echo "- package: ${'$'}PKG"
          echo "- targetUser: ${'$'}TARGET_USER"
          echo "- cloneUser: ${'$'}CLONE_USER"
          echo "- cloneState: ${'$'}CLONE_STATE"
          echo "- cePresent: ${'$'}CE_PRESENT"
          echo "- dePresent: ${'$'}DE_PRESENT"
          echo "- auditDir: ${'$'}OUT"
          echo
          echo "restorecon: not run in this read-only audit"
          echo "delete: not run in this audit"
        } > "${'$'}OUT/summary.md"
        echo "AUDIT_STATUS=${'$'}STATUS"
        echo "AUDIT_SUMMARY=${'$'}OUT/summary.md"
    """.trimIndent()

    fun rollback(
        packageName: String,
        rollbackId: String,
        settings: UCloneSettings,
        appPackage: String,
        rollbackReason: String = "恢复主系统备份前生成",
        compatibility: RestoreCompatibilityOptions = RestoreCompatibilityOptions(),
    ): String {
        requireSafeRollbackId(rollbackId)
        return restoreBody(
            packageName = packageName,
            settings = settings,
            appPackage = appPackage,
            rollbackName = """rollback_${'$'}TS""",
            rollbackReason = rollbackReason,
            sourcePrefix = "${settings.rootDir}/rollback/$packageName/$rollbackId",
            sourceRollbackId = rollbackId,
            syncSwitchMarkerToSource = true,
            compatibility = compatibility,
        )
    }

    fun restoreSwitchMainState(
        packageName: String,
        rollbackId: String,
        rule: AppRule,
        settings: UCloneSettings,
        appPackage: String,
        compatibility: RestoreCompatibilityOptions = RestoreCompatibilityOptions(),
    ): String {
        val restoreMain = rollback(
            packageName = packageName,
            rollbackId = rollbackId,
            settings = settings,
            appPackage = appPackage,
            rollbackReason = "还原主系统态前生成",
            compatibility = compatibility,
        )
        if (!settings.forceUpdateCloneDataBeforeMainRestore) return restoreMain

        val updateClone = pushMainToClone(packageName, rule, settings, appPackage)
        return """
            echo "COMPOSITE_STEP=FORCE_UPDATE_CLONE_DATA"
            (
              ${updateClone.prependIndent("  ")}
            )
            FORCE_UPDATE_EXIT=${'$'}?
            if [ "${'$'}FORCE_UPDATE_EXIT" -ne 0 ]; then
              echo "ERR_FORCE_UPDATE_CLONE_DATA:exit=${'$'}FORCE_UPDATE_EXIT" >&2
              exit "${'$'}FORCE_UPDATE_EXIT"
            fi
            echo "FORCE_UPDATE_CLONE_DATA_DONE=1"
            echo "COMPOSITE_STEP=RESTORE_SWITCH_MAIN_STATE"
            (
              ${restoreMain.prependIndent("  ")}
            )
            RESTORE_MAIN_EXIT=${'$'}?
            if [ "${'$'}RESTORE_MAIN_EXIT" -ne 0 ]; then
              echo "ERR_RESTORE_MAIN_AFTER_CLONE_UPDATE:exit=${'$'}RESTORE_MAIN_EXIT" >&2
              exit "${'$'}RESTORE_MAIN_EXIT"
            fi
            echo "FORCE_UPDATE_CLONE_DATA_AND_MAIN_RESTORE_DONE=1"
        """.trimIndent()
    }

    fun restoreCloneRollback(
        packageName: String,
        settings: UCloneSettings,
        appPackage: String,
        compatibility: RestoreCompatibilityOptions = RestoreCompatibilityOptions(),
    ): String = restoreBody(
        packageName = packageName,
        settings = settings,
        appPackage = appPackage,
        rollbackName = """restore_${'$'}TS""",
        rollbackReason = "恢复分身回滚前生成",
        sourcePrefix = "${settings.rootDir}/clone_rollback/$packageName/latest",
        sourceKind = RestoreSourceKind.CLONE_ROLLBACK,
        prepareSourceScript = ensureCloneCeReadyScript(
            settings,
            required = true,
            autoUnlockAllowed = settings.autoUnlockClone,
            stopAfterTask = settings.stopCloneAfterTask,
        ),
        targetUserId = settings.cloneUserId,
        rollbackRootName = "clone_rollback",
        pruneOldRollbacks = false,
        compatibility = compatibility,
    )

    fun resetSwitchState(packageName: String, settings: UCloneSettings, appPackage: String): String {
        requireSafePackageName(packageName)
        return """
        set -u
        ${WorkspacePathGuard.require(settings.rootDir)}
        PKG=${shellQuote(packageName)}
        APP_PKG=${shellQuote(appPackage)}
        UNKNOWN_SWITCH_MARKER=${shellQuote(UNKNOWN_SWITCH_MARKER)}
        SWITCH_DIR="${'$'}ROOT/switches/${'$'}PKG"
        SWITCH_MARKER="${'$'}ROOT/switches/${'$'}PKG/active"
        [ "${'$'}PKG" != "${'$'}APP_PKG" ] || { echo "ERR_SELF_SYNC"; exit 41; }
        [ -n "${'$'}ROOT" ] && [ "${'$'}ROOT" != "/" ] || { echo "ERR_BAD_ROOT:${'$'}ROOT" >&2; exit 71; }
        case "${'$'}SWITCH_MARKER" in
          "${'$'}ROOT"/switches/"${'$'}PKG"/active) ;;
          *) echo "ERR_BAD_SWITCH_MARKER:${'$'}SWITCH_MARKER" >&2; exit 72 ;;
        esac
        mkdir -p "${'$'}SWITCH_DIR" || exit 73
        chmod 700 "${'$'}SWITCH_DIR" || exit 73
        MARKER_TMP="${'$'}SWITCH_MARKER.tmp_${'$'}${'$'}"
        trap 'rm -f "${'$'}MARKER_TMP"' EXIT
        printf '%s\n' "${'$'}UNKNOWN_SWITCH_MARKER" > "${'$'}MARKER_TMP" || exit 73
        chmod 600 "${'$'}MARKER_TMP" || exit 73
        mv -f "${'$'}MARKER_TMP" "${'$'}SWITCH_MARKER" || exit 73
        sync
        trap - EXIT
        echo "SWITCH_STATE_RESET=unknown MARKER=${'$'}SWITCH_MARKER"
        """.trimIndent()
    }

    fun deleteSnapshot(packageName: String, settings: UCloneSettings, appPackage: String): String = """
        set -u
        ${WorkspacePathGuard.require(settings.rootDir)}
        PKG=${shellQuote(packageName)}
        APP_PKG=${shellQuote(appPackage)}
        ACTIVE="${'$'}ROOT/snapshots/${'$'}PKG/active"
        [ "${'$'}PKG" != "${'$'}APP_PKG" ] || { echo "ERR_SELF_SYNC"; exit 41; }
        [ -n "${'$'}ROOT" ] && [ "${'$'}ROOT" != "/" ] || { echo "ERR_BAD_ROOT:${'$'}ROOT" >&2; exit 71; }
        case "${'$'}ACTIVE" in
          "${'$'}ROOT"/snapshots/"${'$'}PKG"/active) ;;
          *) echo "ERR_BAD_DELETE_TARGET:${'$'}ACTIVE" >&2; exit 72 ;;
        esac
        [ -d "${'$'}ACTIVE" ] || { echo "ERR_SNAPSHOT_MISSING:${'$'}ACTIVE" >&2; exit 73; }
        SIZE_KB=${'$'}(uclone_tree_kb "${'$'}ACTIVE") || exit 74
        ITEMS=${'$'}(uclone_count_tree "${'$'}ACTIVE") || exit 74
        uclone_remove_tree "${'$'}ACTIVE" || exit 74
        [ ! -e "${'$'}ACTIVE" ] || { echo "ERR_DELETE_FAILED:${'$'}ACTIVE" >&2; exit 75; }
        echo "DELETED_SNAPSHOT=${'$'}ACTIVE SIZE_KB=${'$'}SIZE_KB ITEMS=${'$'}ITEMS"
    """.trimIndent()

    fun deleteRestoreBackup(
        packageName: String,
        rollbackId: String,
        settings: UCloneSettings,
        appPackage: String,
    ): String {
        requireSafeRollbackId(rollbackId)
        return """
        set -u
        ${WorkspacePathGuard.require(settings.rootDir)}
        PKG=${shellQuote(packageName)}
        ROLLBACK_ID=${shellQuote(rollbackId)}
        APP_PKG=${shellQuote(appPackage)}
        ROLLBACK_PARENT="${'$'}ROOT/rollback/${'$'}PKG"
        TARGET="${'$'}ROOT/rollback/${'$'}PKG/${'$'}ROLLBACK_ID"
        SWITCH_MARKER="${'$'}ROOT/switches/${'$'}PKG/active"
        [ "${'$'}PKG" != "${'$'}APP_PKG" ] || { echo "ERR_SELF_SYNC"; exit 41; }
        [ -n "${'$'}ROOT" ] && [ "${'$'}ROOT" != "/" ] || { echo "ERR_BAD_ROOT:${'$'}ROOT" >&2; exit 71; }
        validate_rollback_id() {
          CHECK_ROLLBACK_ID="${'$'}1"
          [ -n "${'$'}CHECK_ROLLBACK_ID" ] || { echo "ERR_BAD_ROLLBACK_ID:${'$'}CHECK_ROLLBACK_ID" >&2; exit 73; }
          [ "${'$'}CHECK_ROLLBACK_ID" != "." ] && [ "${'$'}CHECK_ROLLBACK_ID" != ".." ] || { echo "ERR_BAD_ROLLBACK_ID:${'$'}CHECK_ROLLBACK_ID" >&2; exit 73; }
          case "${'$'}CHECK_ROLLBACK_ID" in
            *[!A-Za-z0-9_.-]*) echo "ERR_BAD_ROLLBACK_ID:${'$'}CHECK_ROLLBACK_ID" >&2; exit 73 ;;
          esac
        }
        validate_rollback_id "${'$'}ROLLBACK_ID"
        case "${'$'}TARGET" in
          "${'$'}ROOT"/rollback/"${'$'}PKG"/"${'$'}ROLLBACK_ID") ;;
          *) echo "ERR_BAD_ROLLBACK_TARGET:${'$'}TARGET" >&2; exit 72 ;;
        esac
        case "${'$'}ROLLBACK_PARENT" in
          "${'$'}ROOT"/rollback/"${'$'}PKG") ;;
          *) echo "ERR_BAD_ROLLBACK_PARENT:${'$'}ROLLBACK_PARENT" >&2; exit 72 ;;
        esac
        [ -d "${'$'}TARGET" ] || { echo "ERR_ROLLBACK_MISSING:${'$'}TARGET" >&2; exit 74; }
        if [ -f "${'$'}SWITCH_MARKER" ]; then
          MARKER_ROLLBACK_ID=${'$'}(sed -n '1p' "${'$'}SWITCH_MARKER" | tr -d '\r')
          if [ "${'$'}MARKER_ROLLBACK_ID" = "${'$'}ROLLBACK_ID" ]; then
            echo "ERR_ACTIVE_RETURN_POINT_DELETE:${'$'}TARGET" >&2
            exit 76
          fi
        fi
        SIZE_KB=${'$'}(uclone_tree_kb "${'$'}TARGET") || exit 75
        ITEMS=${'$'}(uclone_count_tree "${'$'}TARGET") || exit 75
        uclone_remove_tree "${'$'}TARGET" || exit 75
        [ ! -e "${'$'}TARGET" ] || { echo "ERR_DELETE_FAILED:${'$'}TARGET" >&2; exit 75; }
        echo "DELETED_RESTORE_BACKUP=${'$'}TARGET SIZE_KB=${'$'}SIZE_KB ITEMS=${'$'}ITEMS"
    """.trimIndent()
    }

    fun deleteCloneRollback(
        packageName: String,
        rollbackId: String,
        settings: UCloneSettings,
        appPackage: String,
    ): String {
        requireSafeRollbackId(rollbackId)
        return """
        set -u
        ${WorkspacePathGuard.require(settings.rootDir)}
        PKG=${shellQuote(packageName)}
        ROLLBACK_ID=${shellQuote(rollbackId)}
        APP_PKG=${shellQuote(appPackage)}
        TARGET="${'$'}ROOT/clone_rollback/${'$'}PKG/${'$'}ROLLBACK_ID"
        [ "${'$'}PKG" != "${'$'}APP_PKG" ] || { echo "ERR_SELF_SYNC"; exit 41; }
        [ -n "${'$'}ROOT" ] && [ "${'$'}ROOT" != "/" ] || { echo "ERR_BAD_ROOT:${'$'}ROOT" >&2; exit 71; }
        [ "${'$'}ROLLBACK_ID" = "latest" ] || { echo "ERR_BAD_CLONE_ROLLBACK_ID:${'$'}ROLLBACK_ID" >&2; exit 72; }
        case "${'$'}TARGET" in
          "${'$'}ROOT"/clone_rollback/"${'$'}PKG"/latest) ;;
          *) echo "ERR_BAD_ROLLBACK_TARGET:${'$'}TARGET" >&2; exit 72 ;;
        esac
        [ -d "${'$'}TARGET" ] || { echo "ERR_ROLLBACK_MISSING:${'$'}TARGET" >&2; exit 74; }
        SIZE_KB=${'$'}(uclone_tree_kb "${'$'}TARGET") || exit 75
        ITEMS=${'$'}(uclone_count_tree "${'$'}TARGET") || exit 75
        uclone_remove_tree "${'$'}TARGET" || exit 75
        echo "DELETED_CLONE_ROLLBACK=${'$'}TARGET SIZE_KB=${'$'}SIZE_KB ITEMS=${'$'}ITEMS"
        """.trimIndent()
    }

    fun resetWorkspace(settings: UCloneSettings): String = """
        set -u
        ${WorkspacePathGuard.require(settings.rootDir)}
        RESET_TARGETS="snapshots rollback clone_rollback switches logs tmp audit transactions config/workspace_owner_root_v1 locks/orphaned"
        DELETED_TARGETS=0
        DELETED_SIZE_KB=0
        for NAME in ${'$'}RESET_TARGETS; do
          TARGET="${'$'}ROOT/${'$'}NAME"
          case "${'$'}TARGET" in
            "${'$'}ROOT"/snapshots|"${'$'}ROOT"/rollback|"${'$'}ROOT"/clone_rollback|"${'$'}ROOT"/switches|"${'$'}ROOT"/logs|"${'$'}ROOT"/tmp|"${'$'}ROOT"/audit|"${'$'}ROOT"/transactions|"${'$'}ROOT"/config/workspace_owner_root_v1|"${'$'}ROOT"/locks/orphaned) ;;
            *) echo "ERR_UNSAFE_RESET_TARGET:${'$'}TARGET" >&2; exit 73 ;;
          esac
          [ -e "${'$'}TARGET" ] || [ -L "${'$'}TARGET" ] || continue
          case "${'$'}NAME" in
            config/workspace_owner_root_v1)
              SIZE_KB=0
              ;;
            *)
              SIZE_KB=${'$'}(uclone_tree_kb "${'$'}TARGET") || { echo "ERR_RESET_SIZE_FAILED:${'$'}TARGET" >&2; exit 74; }
              ;;
          esac
          case "${'$'}SIZE_KB" in ''|*[!0-9]*) SIZE_KB=0 ;; esac
          case "${'$'}NAME" in
            config/workspace_owner_root_v1)
              uclone_remove_file "${'$'}TARGET" || { echo "ERR_RESET_DELETE_FAILED:${'$'}TARGET" >&2; exit 74; }
              ;;
            *)
              uclone_remove_tree "${'$'}TARGET" || { echo "ERR_RESET_DELETE_FAILED:${'$'}TARGET" >&2; exit 74; }
              ;;
          esac
          DELETED_TARGETS=${'$'}((DELETED_TARGETS + 1))
          NEXT_DELETED_SIZE_KB=${'$'}(awk -v TOTAL="${'$'}DELETED_SIZE_KB" -v SIZE_KB="${'$'}SIZE_KB" 'BEGIN { printf "%.0f\n", TOTAL + SIZE_KB }')
          case "${'$'}NEXT_DELETED_SIZE_KB" in ''|*[!0-9]*) NEXT_DELETED_SIZE_KB="${'$'}DELETED_SIZE_KB" ;; esac
          DELETED_SIZE_KB="${'$'}NEXT_DELETED_SIZE_KB"
          echo "RESET_DELETED:${'$'}TARGET SIZE_KB=${'$'}SIZE_KB"
        done
        [ -f "${'$'}ROOT/config/workspace.identity" ] || { echo "ERR_WORKSPACE_IDENTITY_LOST:${'$'}ROOT" >&2; exit 75; }
        echo "RESET_WORKSPACE_DONE root=${'$'}ROOT deletedTargets=${'$'}DELETED_TARGETS sizeKb=${'$'}DELETED_SIZE_KB"
    """.trimIndent()

    private fun switchTempSourceScript(rule: AppRule, settings: UCloneSettings): String = """
        SWITCH_TEMP="${'$'}ACTIVE"
        case "${'$'}SWITCH_TEMP" in
          "${'$'}ROOT"/tmp/switch_"${'$'}PKG"_"${'$'}TS") ;;
          *) echo "ERR_BAD_SWITCH_TEMP:${'$'}SWITCH_TEMP" >&2; exit 72 ;;
        esac
        cleanup_switch_temp() {
          if command -v uclone_release_pre_mutation_gates >/dev/null 2>&1; then
            uclone_release_pre_mutation_gates || true
          fi
          [ -n "${'$'}{SWITCH_TEMP:-}" ] || return 0
          case "${'$'}SWITCH_TEMP" in
            "${'$'}ROOT"/tmp/switch_"${'$'}PKG"_"${'$'}TS")
              uclone_remove_tree "${'$'}SWITCH_TEMP" 2>/dev/null || true
              for UCLONE_OLD_TRY in "${'$'}SWITCH_TEMP".try_*; do [ -e "${'$'}UCLONE_OLD_TRY" ] && uclone_remove_tree "${'$'}UCLONE_OLD_TRY" 2>/dev/null || true; done
              ;;
          esac
        }
        trap cleanup_switch_temp EXIT
        mkdir -p "${'$'}ROOT/tmp" || exit 10
        uclone_remove_tree "${'$'}SWITCH_TEMP" || exit 10
        for UCLONE_OLD_TRY in "${'$'}SWITCH_TEMP".try_*; do
          [ -e "${'$'}UCLONE_OLD_TRY" ] || continue
          uclone_remove_tree "${'$'}UCLONE_OLD_TRY" || exit 10
        done
        SWITCH_REQUIRE_CE=${if (rule.includeCe) "1" else "0"}
        ${ensureCloneCeReadyScript(settings, rule.includeCe, settings.autoUnlockClone, settings.stopCloneAfterTask)}
        CANDIDATE_USERS="${settings.cloneUserId}"
        echo "CANDIDATE_USERS=${'$'}CANDIDATE_USERS"
        [ -n "${'$'}CANDIDATE_USERS" ] || { echo "ERR_NO_CLONE_USER_CANDIDATES" >&2; exit 42; }
        UCLONE_ESTIMATED_KB=0
        ${if (rule.includeCe) "uclone_add_first_dir_kb \"/data/user/${settings.cloneUserId}/${'$'}PKG\" \"/data_mirror/data_ce/null/${settings.cloneUserId}/${'$'}PKG\" \"/data_mirror/data_ce/${settings.cloneUserId}/${'$'}PKG\"" else ":"}
        ${if (rule.includeDe) "uclone_add_first_dir_kb \"/data/user_de/${settings.cloneUserId}/${'$'}PKG\" \"/data_mirror/data_de/null/${settings.cloneUserId}/${'$'}PKG\" \"/data_mirror/data_de/${settings.cloneUserId}/${'$'}PKG\"" else ":"}
        ${if (rule.includeExternal) "uclone_add_first_dir_kb \"/data/media/${settings.cloneUserId}/Android/data/${'$'}PKG\" \"/storage/emulated/${settings.cloneUserId}/Android/data/${'$'}PKG\"" else ":"}
        ${if (rule.includeMedia) "uclone_add_first_dir_kb \"/data/media/${settings.cloneUserId}/Android/media/${'$'}PKG\" \"/storage/emulated/${settings.cloneUserId}/Android/media/${'$'}PKG\"" else ":"}
        ${if (rule.includeObb) "uclone_add_first_dir_kb \"/data/media/${settings.cloneUserId}/Android/obb/${'$'}PKG\" \"/storage/emulated/${settings.cloneUserId}/Android/obb/${'$'}PKG\"" else ":"}
        uclone_require_space "${'$'}UCLONE_ESTIMATED_KB" "switch_source"
        ${snapshotPartCaptureFunctions(rule.excludeCache)}
        try_user() {
          TRY_USER="${'$'}1"
          TRY_TMP="${'$'}SWITCH_TEMP.try_${'$'}TRY_USER"
          uclone_remove_tree "${'$'}TRY_TMP" || exit 11
          mkdir -p "${'$'}TRY_TMP" || exit 11
          STATE=${'$'}(clone_state)
          echo "PROBE_USER=${'$'}TRY_USER STATE=${'$'}STATE"
          case "${'$'}STATE" in
            *CLONE_STATE_QUERY_UNAVAILABLE*|*CLONE_STATE_QUERY_EMPTY*)
              echo "ERR_CLONE_STATE_QUERY:${'$'}STATE" >&2
              uclone_remove_tree "${'$'}TRY_TMP" || exit 11
              exit 86
              ;;
            *RUNNING_UNLOCKED*) ;;
            *)
              echo "ERR_USER_NOT_UNLOCKED:${'$'}TRY_USER:${'$'}STATE" >&2
              uclone_remove_tree "${'$'}TRY_TMP" || exit 11
              return 1
              ;;
          esac
          if cmd package list packages --user "${'$'}TRY_USER" 2>/dev/null | grep -qx "package:${'$'}PKG"; then
            echo "PACKAGE_LISTED:${'$'}TRY_USER"
          else
            echo "ERR_PACKAGE_NOT_LISTED:${'$'}TRY_USER" >&2
            uclone_remove_tree "${'$'}TRY_TMP" || exit 11
            return 1
          fi
          TRY_UID=${'$'}(cmd package list packages -U --user "${'$'}TRY_USER" | awk -v p="package:${'$'}PKG" '${'$'}1==p { sub("uid:","",${'$'}2); print ${'$'}2; exit }')
          [ -n "${'$'}TRY_UID" ] || { echo "ERR_SOURCE_UID_MISSING:${'$'}TRY_USER" >&2; return 1; }
          uclone_transaction_stage SOURCE_GATE_ACQUIRE || return 1
          uclone_gate_acquire source "${'$'}TRY_USER" "${'$'}TRY_UID" || {
            echo "ERR_SOURCE_GATE_ACQUIRE:${'$'}TRY_USER:${'$'}PKG" >&2
            return 1
          }
          SOURCE_GATE_DIR="${'$'}UCLONE_GATE_DIR"
          COPIED_PARTS=0
          COPIED_ITEMS=0
          CAPTURED_PARTS=0
          CAPTURED_PERMISSIONS=0
          PERMISSION_CAPTURE_STATE=excluded
          ${if (rule.includeCe) "capture_part \"${'$'}TRY_TMP\" ce \"${'$'}TRY_TMP/ce\" \"/data/user/${'$'}TRY_USER/${'$'}PKG\" \"/data_mirror/data_ce/null/${'$'}TRY_USER/${'$'}PKG\" \"/data_mirror/data_ce/${'$'}TRY_USER/${'$'}PKG\"" else "capture_excluded_part \"${'$'}TRY_TMP\" ce"}
          ${if (rule.includeDe) "capture_part \"${'$'}TRY_TMP\" de \"${'$'}TRY_TMP/de\" \"/data/user_de/${'$'}TRY_USER/${'$'}PKG\" \"/data_mirror/data_de/null/${'$'}TRY_USER/${'$'}PKG\" \"/data_mirror/data_de/${'$'}TRY_USER/${'$'}PKG\"" else "capture_excluded_part \"${'$'}TRY_TMP\" de"}
          ${if (rule.includeExternal) "capture_part \"${'$'}TRY_TMP\" external \"${'$'}TRY_TMP/external\" \"/data/media/${'$'}TRY_USER/Android/data/${'$'}PKG\" \"/storage/emulated/${'$'}TRY_USER/Android/data/${'$'}PKG\"" else "capture_excluded_part \"${'$'}TRY_TMP\" external"}
          ${if (rule.includeMedia) "capture_part \"${'$'}TRY_TMP\" media \"${'$'}TRY_TMP/media\" \"/data/media/${'$'}TRY_USER/Android/media/${'$'}PKG\" \"/storage/emulated/${'$'}TRY_USER/Android/media/${'$'}PKG\"" else "capture_excluded_part \"${'$'}TRY_TMP\" media"}
          ${if (rule.includeObb) "capture_part \"${'$'}TRY_TMP\" obb \"${'$'}TRY_TMP/obb\" \"/data/media/${'$'}TRY_USER/Android/obb/${'$'}PKG\" \"/storage/emulated/${'$'}TRY_USER/Android/obb/${'$'}PKG\"" else "capture_excluded_part \"${'$'}TRY_TMP\" obb"}
          ${if (rule.includePermissions) sourcePermissionCaptureScript("${'$'}TRY_TMP/permissions", "${'$'}TRY_USER", settings.permissionRestoreMode) else ":"}
          SWITCH_CE_STATE=${'$'}(sed -n '1p' "${'$'}TRY_TMP/.state/ce" 2>/dev/null || true)
          if [ "${'$'}SWITCH_REQUIRE_CE" = "1" ] && [ "${'$'}SWITCH_CE_STATE" != "data" ] && [ "${'$'}SWITCH_CE_STATE" != "empty" ]; then
            echo "ERR_SWITCH_CE_MISSING:${'$'}TRY_USER" >&2
            uclone_remove_tree "${'$'}TRY_TMP" || exit 11
            return 1
          fi
          if [ "${'$'}CAPTURED_PARTS" -gt 0 ] || [ "${'$'}CAPTURED_PERMISSIONS" = "1" ]; then
            uclone_transaction_stage SOURCE_PREPARED || return 1
            if ! uclone_gate_release "${'$'}SOURCE_GATE_DIR"; then
              uclone_transaction_recovery_required
              return 1
            fi
            SOURCE_GATE_DIR=""
            uclone_remove_tree "${'$'}SWITCH_TEMP" || exit 14
            mv "${'$'}TRY_TMP" "${'$'}SWITCH_TEMP" || exit 14
            DETECTED_USER="${'$'}TRY_USER"
            DETECTED_STATE="${'$'}STATE"
            return 0
          fi
          if ! uclone_gate_release "${'$'}SOURCE_GATE_DIR"; then
            uclone_transaction_recovery_required
            return 1
          fi
          SOURCE_GATE_DIR=""
          uclone_remove_tree "${'$'}TRY_TMP" || exit 11
          return 1
        }
        DETECTED_USER=""
        DETECTED_STATE=""
        for U in ${'$'}CANDIDATE_USERS; do
          if try_user "${'$'}U"; then
            break
          fi
        done
        [ -n "${'$'}DETECTED_USER" ] || { echo "ERR_NOTHING_COPIED: no selected source parts for candidates:${'$'}CANDIDATE_USERS package:${'$'}PKG" >&2; exit 44; }
        echo "SWITCH_SOURCE_READY=${'$'}SWITCH_TEMP"
        echo "SWITCH_SOURCE_USER=${'$'}DETECTED_USER"
    """.trimIndent()

    private fun restoreBody(
        packageName: String,
        settings: UCloneSettings,
        appPackage: String,
        rollbackName: String,
        rollbackReason: String,
        sourcePrefix: String = "",
        sourceRollbackId: String? = null,
        sourceKind: RestoreSourceKind = if (sourceRollbackId == null) RestoreSourceKind.ACTIVE else RestoreSourceKind.ROLLBACK,
        prepareSourceScript: String = ":",
        writeSwitchMarker: Boolean = false,
        clearSwitchMarker: Boolean = false,
        targetUserId: Int = settings.mainUserId,
        rollbackRootName: String = "rollback",
        pruneOldRollbacks: Boolean = true,
        compatibility: RestoreCompatibilityOptions = RestoreCompatibilityOptions(),
        sourceStateKind: String = "",
        syncSwitchMarkerToSource: Boolean = false,
    ): String {
        val sourceRoot = sourcePrefix.ifBlank { "${settings.rootDir}/snapshots/$packageName/active" }
        val activeAssignment = when (sourceKind) {
            RestoreSourceKind.SWITCH_TEMP -> "ACTIVE=\"${'$'}ROOT/tmp/switch_${'$'}{PKG}_${'$'}TS\""
            else -> "ACTIVE=${shellQuote(sourceRoot)}"
        }
        val sourceKindToken = when (sourceKind) {
            RestoreSourceKind.ACTIVE -> "active"
            RestoreSourceKind.ROLLBACK -> "rollback"
            RestoreSourceKind.SWITCH_TEMP -> "switch_temp"
            RestoreSourceKind.CLONE_ROLLBACK -> "clone_rollback"
        }
        val transactionSourceUser = when (sourceKind) {
            RestoreSourceKind.ACTIVE,
            RestoreSourceKind.SWITCH_TEMP,
            RestoreSourceKind.CLONE_ROLLBACK,
            -> settings.cloneUserId
            RestoreSourceKind.ROLLBACK -> targetUserId
        }
        sourceRollbackId?.let(::requireSafeRollbackId)
        return """
            set -u
            ${WorkspacePathGuard.require(settings.rootDir)}
            PKG=${shellQuote(packageName)}
            APP_PKG=${shellQuote(appPackage)}
            DST_USER=$targetUserId
            ${metricsScript()}
            TS=${'$'}(uclone_unique_stamp) || { echo "ERR_UNIQUE_STAMP" >&2; exit 10; }
            $activeAssignment
            SOURCE_KIND=${shellQuote(sourceKindToken)}
            SOURCE_ROLLBACK_ID=${shellQuote(sourceRollbackId.orEmpty())}
            SOURCE_STATE_KIND=${shellQuote(sourceStateKind)}
            UCLONE_ALLOW_VERSION_MISMATCH=${if (compatibility.allowVersionMismatch) "1" else "0"}
            UCLONE_ALLOW_LEGACY_IDENTITY=${if (compatibility.allowLegacyIdentity) "1" else "0"}
            ROLLBACK_ID="$rollbackName"
            PERSISTENT_ROLLBACK="${'$'}ROOT/$rollbackRootName/${'$'}PKG/$rollbackName"
            ROLLBACK="${'$'}PERSISTENT_ROLLBACK"
            TRANSACTION_UNDO=""
            ${storagePreflightScript()}
            ${BackupIntegrityShell.functions()}
            ${TransactionSafetyShell.functions()}
            ${PermissionStateShell.functions()}
            uclone_stage_begin PRECHECK
            [ "${'$'}PKG" != "${'$'}APP_PKG" ] || { echo "ERR_SELF_SYNC"; exit 41; }
            [ -n "${'$'}ROOT" ] && [ "${'$'}ROOT" != "/" ] || { echo "ERR_BAD_ROOT:${'$'}ROOT" >&2; exit 71; }
            CURRENT_SWITCH_MARKER="${'$'}ROOT/switches/${'$'}PKG/active"
            UNKNOWN_SWITCH_MARKER=${shellQuote(UNKNOWN_SWITCH_MARKER)}
            CURRENT_STATE_KIND="clone"
            CURRENT_MAIN_ROLLBACK_ID=""
            if [ "${'$'}DST_USER" = "${settings.mainUserId}" ]; then
              CURRENT_STATE_KIND="main"
              if [ -f "${'$'}CURRENT_SWITCH_MARKER" ]; then
                CURRENT_MAIN_ROLLBACK_ID=${'$'}(sed -n '1p' "${'$'}CURRENT_SWITCH_MARKER" | tr -d '\r')
                CURRENT_STATE_KIND="unknown"
                case "${'$'}CURRENT_MAIN_ROLLBACK_ID" in
                  ""|"."|".."|*[!A-Za-z0-9_.-]*) ;;
                  "${'$'}UNKNOWN_SWITCH_MARKER") ;;
                  *)
                    CURRENT_MARKER_MANIFEST="${'$'}ROOT/rollback/${'$'}PKG/${'$'}CURRENT_MAIN_ROLLBACK_ID/manifest.json"
                    if uclone_require_canonical_backup_file "${'$'}CURRENT_MARKER_MANIFEST"; then
                      CURRENT_MARKER_SCHEMA=${'$'}(sed -n 's/.*"schemaVersion":\([0-9][0-9]*\).*/\1/p' "${'$'}CURRENT_MARKER_MANIFEST" | head -1)
                      CURRENT_MARKER_PACKAGE=${'$'}(sed -n 's/.*"packageName":"\([^"]*\)".*/\1/p' "${'$'}CURRENT_MARKER_MANIFEST" | head -1)
                      CURRENT_MARKER_STATE=${'$'}(sed -n 's/.*"stateKind":"\([^"]*\)".*/\1/p' "${'$'}CURRENT_MARKER_MANIFEST" | head -1)
                      CURRENT_MARKER_KIND=${'$'}(sed -n 's/.*"backupKind":"\([^"]*\)".*/\1/p' "${'$'}CURRENT_MARKER_MANIFEST" | head -1)
                      CURRENT_MARKER_SIGNING_CERTIFICATE=${'$'}(sed -n 's/.*"sourceSigningCertificateSha256":"\([0-9a-f,]*\)".*/\1/p' "${'$'}CURRENT_MARKER_MANIFEST" | head -1)
                      CURRENT_MARKER_SOURCE_USER=${'$'}(${manifestUserIdReadCommand("sourceUser", "\"${'$'}CURRENT_MARKER_MANIFEST\"")})
                      CURRENT_MARKER_TARGET_USER=${'$'}(${manifestUserIdReadCommand("targetUser", "\"${'$'}CURRENT_MARKER_MANIFEST\"")})
                      if [ "${'$'}CURRENT_MARKER_SCHEMA" = "$CURRENT_MANIFEST_SCHEMA" ] &&
                        [ "${'$'}CURRENT_MARKER_PACKAGE" = "${'$'}PKG" ] &&
                        [ "${'$'}CURRENT_MARKER_STATE" = "main" ] &&
                        [ "${'$'}CURRENT_MARKER_KIND" = "rollback" ] &&
                        [ "${'$'}CURRENT_MARKER_SIGNING_CERTIFICATE" = "${'$'}UCLONE_SIGNING_CERTIFICATE_SHA256" ] &&
                        [ "${'$'}CURRENT_MARKER_SOURCE_USER" = "${settings.mainUserId}" ] &&
                        [ "${'$'}CURRENT_MARKER_TARGET_USER" = "${settings.mainUserId}" ]; then
                        CURRENT_STATE_KIND="clone"
                      fi
                    fi
                    ;;
                esac
                if [ "${'$'}CURRENT_STATE_KIND" != "clone" ]; then
                  CURRENT_MAIN_ROLLBACK_ID=""
                fi
              fi
            fi
            validate_rollback_id() {
              CHECK_ROLLBACK_ID="${'$'}1"
              [ -n "${'$'}CHECK_ROLLBACK_ID" ] || { echo "ERR_BAD_ROLLBACK_ID:${'$'}CHECK_ROLLBACK_ID" >&2; exit 73; }
              [ "${'$'}CHECK_ROLLBACK_ID" != "." ] && [ "${'$'}CHECK_ROLLBACK_ID" != ".." ] || { echo "ERR_BAD_ROLLBACK_ID:${'$'}CHECK_ROLLBACK_ID" >&2; exit 73; }
              case "${'$'}CHECK_ROLLBACK_ID" in
                *[!A-Za-z0-9_.-]*) echo "ERR_BAD_ROLLBACK_ID:${'$'}CHECK_ROLLBACK_ID" >&2; exit 73 ;;
              esac
            }
            if [ -n "${'$'}SOURCE_ROLLBACK_ID" ]; then
              validate_rollback_id "${'$'}SOURCE_ROLLBACK_ID"
              EXPECTED_ACTIVE="${'$'}ROOT/rollback/${'$'}PKG/${'$'}SOURCE_ROLLBACK_ID"
            elif [ "${'$'}SOURCE_KIND" = "switch_temp" ]; then
              EXPECTED_ACTIVE="${'$'}ROOT/tmp/switch_${'$'}{PKG}_${'$'}TS"
            elif [ "${'$'}SOURCE_KIND" = "clone_rollback" ]; then
              EXPECTED_ACTIVE="${'$'}ROOT/clone_rollback/${'$'}PKG/latest"
            else
              EXPECTED_ACTIVE="${'$'}ROOT/snapshots/${'$'}PKG/active"
            fi
            uclone_transaction_init ${sourceKindToken.uppercase()} $transactionSourceUser "${'$'}DST_USER" ${shellQuote(selectedParts(settings))} || exit 77
            uclone_transaction_stage PRECHECKED || exit 77
            uclone_stage_end
            uclone_stage_begin SOURCE_PREPARE
            $prepareSourceScript
            [ "${'$'}ACTIVE" = "${'$'}EXPECTED_ACTIVE" ] || { echo "ERR_BAD_RESTORE_SOURCE:${'$'}ACTIVE" >&2; exit 72; }
            [ -d "${'$'}ACTIVE" ] || { echo "ERR_SNAPSHOT_MISSING:${'$'}ACTIVE" >&2; exit 51; }
            if [ -e "${'$'}ACTIVE/manifest.json" ] || [ -L "${'$'}ACTIVE/manifest.json" ]; then
              uclone_require_canonical_backup_file "${'$'}ACTIVE/manifest.json" || {
                echo "ERR_UNSAFE_BACKUP_MANIFEST:${'$'}ACTIVE/manifest.json" >&2
                exit 64
              }
            fi
            if [ -z "${'$'}SOURCE_STATE_KIND" ] && [ -f "${'$'}ACTIVE/manifest.json" ]; then
              SOURCE_STATE_KIND=${'$'}(sed -n 's/.*"stateKind":"\([^"]*\)".*/\1/p' "${'$'}ACTIVE/manifest.json" | head -1)
            fi
            ACTIVE_MANIFEST_PACKAGE=${'$'}(sed -n 's/.*"packageName":"\([^"]*\)".*/\1/p' "${'$'}ACTIVE/manifest.json" 2>/dev/null | head -1)
            if [ -f "${'$'}ACTIVE/manifest.json" ]; then
              [ "${'$'}ACTIVE_MANIFEST_PACKAGE" = "${'$'}PKG" ] || { echo "ERR_SNAPSHOT_PACKAGE_MISMATCH:expected=${'$'}PKG:actual=${'$'}ACTIVE_MANIFEST_PACKAGE" >&2; exit 64; }
            fi
            ACTIVE_SCHEMA_VERSION=${'$'}(sed -n 's/.*"schemaVersion":\([0-9][0-9]*\).*/\1/p' "${'$'}ACTIVE/manifest.json" 2>/dev/null | head -1)
            ACTIVE_SOURCE_USER=${'$'}(${manifestUserIdReadCommand("sourceUser", "\"${'$'}ACTIVE/manifest.json\"")})
            ACTIVE_TARGET_USER=${'$'}(${manifestUserIdReadCommand("targetUser", "\"${'$'}ACTIVE/manifest.json\"")})
            ACTIVE_INTEGRITY_REQUIRED=0
            if [ "${'$'}ACTIVE_SCHEMA_VERSION" = "2" ] || [ "${'$'}ACTIVE_SCHEMA_VERSION" = "3" ] || [ "${'$'}ACTIVE_SCHEMA_VERSION" = "4" ] || [ "${'$'}ACTIVE_SCHEMA_VERSION" = "$CURRENT_MANIFEST_SCHEMA" ] || [ -d "${'$'}ACTIVE/.meta" ]; then
              ACTIVE_INTEGRITY_REQUIRED=1
            fi
            case "${'$'}SOURCE_STATE_KIND" in main|clone) ;; *) SOURCE_STATE_KIND="unknown" ;; esac
            echo "RESTORE_SOURCE_STATE=${'$'}SOURCE_STATE_KIND CURRENT_STATE=${'$'}CURRENT_STATE_KIND"
            [ "${'$'}ACTIVE" != "${'$'}ROLLBACK" ] || { echo "ERR_ROLLBACK_SOURCE_CONFLICT:${'$'}ACTIVE" >&2; exit 61; }
            UID_VALUE=${'$'}(cmd package list packages -U --user "${'$'}DST_USER" | awk -v p="package:${'$'}PKG" '${'$'}1==p { sub("uid:","",${'$'}2); print ${'$'}2; exit }')
            [ -n "${'$'}UID_VALUE" ] || { echo "ERR_TARGET_UID_MISSING" >&2; exit 52; }
            TARGET_APK_DIGEST=${'$'}(uclone_package_code_digest "${'$'}DST_USER" "${'$'}PKG") || { echo "ERR_TARGET_APK_DIGEST:${'$'}DST_USER" >&2; exit 52; }
            TARGET_VERSION_CODE=${'$'}(uclone_package_version_code "${'$'}PKG") || { echo "ERR_TARGET_VERSION_CODE:${'$'}PKG" >&2; exit 52; }
            TARGET_VERSION_NAME=${'$'}(uclone_package_version_name "${'$'}PKG") || { echo "ERR_TARGET_VERSION_NAME:${'$'}PKG" >&2; exit 52; }
            TARGET_VERSION_NAME_ESC=${'$'}(uclone_json_escape "${'$'}TARGET_VERSION_NAME") || exit 52
            TARGET_APP_ID=${'$'}((UID_VALUE % 100000))
            if [ "${'$'}SOURCE_KIND" = "switch_temp" ]; then
              :
            elif [ ! -f "${'$'}ACTIVE/manifest.json" ]; then
              if [ "${'$'}UCLONE_ALLOW_LEGACY_IDENTITY" = "1" ]; then
                echo "WARN_LEGACY_PACKAGE_IDENTITY_ALLOWED:${'$'}ACTIVE"
              else
                echo "ERR_LEGACY_PACKAGE_IDENTITY_CONFIRMATION_REQUIRED:${'$'}ACTIVE" >&2
                exit 64
              fi
            else
              ACTIVE_APK_DIGEST=${'$'}(sed -n 's/.*"sourceApkDigest":"\([0-9a-fA-F]*\)".*/\1/p' "${'$'}ACTIVE/manifest.json" | head -1)
              ACTIVE_SIGNING_CERTIFICATE_SHA256=${'$'}(sed -n 's/.*"sourceSigningCertificateSha256":"\([0-9a-f,]*\)".*/\1/p' "${'$'}ACTIVE/manifest.json" | head -1)
              ACTIVE_VERSION_CODE=${'$'}(sed -n 's/.*"sourceVersionCode":"\([0-9][0-9]*\)".*/\1/p' "${'$'}ACTIVE/manifest.json" | head -1)
              ACTIVE_BACKUP_KIND=${'$'}(sed -n 's/.*"backupKind":"\([^"]*\)".*/\1/p' "${'$'}ACTIVE/manifest.json" | head -1)
              if [ "${'$'}ACTIVE_SCHEMA_VERSION" = "$CURRENT_MANIFEST_SCHEMA" ]; then
                case "${'$'}SOURCE_KIND" in
                  active) ACTIVE_EXPECTED_BACKUP_KIND=active_snapshot ;;
                  rollback) ACTIVE_EXPECTED_BACKUP_KIND=rollback ;;
                  clone_rollback) ACTIVE_EXPECTED_BACKUP_KIND=clone_rollback ;;
                  *) echo "ERR_SNAPSHOT_BACKUP_KIND_SOURCE:${'$'}SOURCE_KIND" >&2; exit 64 ;;
                esac
                [ "${'$'}ACTIVE_BACKUP_KIND" = "${'$'}ACTIVE_EXPECTED_BACKUP_KIND" ] || {
                  echo "ERR_SNAPSHOT_BACKUP_KIND_MISMATCH:expected=${'$'}ACTIVE_EXPECTED_BACKUP_KIND:actual=${'$'}ACTIVE_BACKUP_KIND" >&2
                  exit 64
                }
                [ "${'$'}ACTIVE_SOURCE_USER" = "$transactionSourceUser" ] || {
                  echo "ERR_SNAPSHOT_SOURCE_USER_MISMATCH:expected=$transactionSourceUser:actual=${'$'}ACTIVE_SOURCE_USER" >&2
                  exit 64
                }
                [ "${'$'}ACTIVE_TARGET_USER" = "${'$'}DST_USER" ] || {
                  echo "ERR_SNAPSHOT_TARGET_USER_MISMATCH:expected=${'$'}DST_USER:actual=${'$'}ACTIVE_TARGET_USER" >&2
                  exit 64
                }
                [ -n "${'$'}ACTIVE_SIGNING_CERTIFICATE_SHA256" ] && [ "${'$'}ACTIVE_SIGNING_CERTIFICATE_SHA256" = "${'$'}UCLONE_SIGNING_CERTIFICATE_SHA256" ] || {
                  echo "ERR_SNAPSHOT_SIGNATURE_MISMATCH:expected=${'$'}ACTIVE_SIGNING_CERTIFICATE_SHA256:actual=${'$'}UCLONE_SIGNING_CERTIFICATE_SHA256" >&2
                  exit 64
                }
                if [ "${'$'}ACTIVE_VERSION_CODE" != "${'$'}TARGET_VERSION_CODE" ]; then
                  if [ "${'$'}UCLONE_ALLOW_VERSION_MISMATCH" = "1" ]; then
                    echo "WARN_VERSION_MISMATCH_ALLOWED:expected=${'$'}ACTIVE_VERSION_CODE:actual=${'$'}TARGET_VERSION_CODE"
                  else
                    echo "ERR_SNAPSHOT_VERSION_CONFIRMATION_REQUIRED:expected=${'$'}ACTIVE_VERSION_CODE:actual=${'$'}TARGET_VERSION_CODE" >&2
                    exit 64
                  fi
                fi
              elif [ "${'$'}ACTIVE_SCHEMA_VERSION" = "4" ]; then
                [ "${'$'}ACTIVE_SOURCE_USER" = "$transactionSourceUser" ] || {
                  echo "ERR_SNAPSHOT_SOURCE_USER_MISMATCH:expected=$transactionSourceUser:actual=${'$'}ACTIVE_SOURCE_USER" >&2
                  exit 64
                }
                [ "${'$'}ACTIVE_TARGET_USER" = "${'$'}DST_USER" ] || {
                  echo "ERR_SNAPSHOT_TARGET_USER_MISMATCH:expected=${'$'}DST_USER:actual=${'$'}ACTIVE_TARGET_USER" >&2
                  exit 64
                }
                [ -n "${'$'}ACTIVE_SIGNING_CERTIFICATE_SHA256" ] && [ "${'$'}ACTIVE_SIGNING_CERTIFICATE_SHA256" = "${'$'}UCLONE_SIGNING_CERTIFICATE_SHA256" ] || {
                  echo "ERR_SNAPSHOT_SIGNATURE_MISMATCH:expected=${'$'}ACTIVE_SIGNING_CERTIFICATE_SHA256:actual=${'$'}UCLONE_SIGNING_CERTIFICATE_SHA256" >&2
                  exit 64
                }
                if [ "${'$'}ACTIVE_VERSION_CODE" != "${'$'}TARGET_VERSION_CODE" ]; then
                  if [ "${'$'}UCLONE_ALLOW_VERSION_MISMATCH" = "1" ]; then
                    echo "WARN_VERSION_MISMATCH_ALLOWED:expected=${'$'}ACTIVE_VERSION_CODE:actual=${'$'}TARGET_VERSION_CODE"
                  else
                    echo "ERR_SNAPSHOT_VERSION_CONFIRMATION_REQUIRED:expected=${'$'}ACTIVE_VERSION_CODE:actual=${'$'}TARGET_VERSION_CODE" >&2
                    exit 64
                  fi
                fi
              elif [ "${'$'}ACTIVE_SCHEMA_VERSION" = "3" ]; then
                if [ -n "${'$'}ACTIVE_APK_DIGEST" ] && [ "${'$'}ACTIVE_APK_DIGEST" = "${'$'}TARGET_APK_DIGEST" ]; then
                  if [ "${'$'}ACTIVE_VERSION_CODE" != "${'$'}TARGET_VERSION_CODE" ]; then
                    if [ "${'$'}UCLONE_ALLOW_VERSION_MISMATCH" = "1" ]; then
                      echo "WARN_VERSION_MISMATCH_ALLOWED:expected=${'$'}ACTIVE_VERSION_CODE:actual=${'$'}TARGET_VERSION_CODE"
                    else
                      echo "ERR_SNAPSHOT_VERSION_CONFIRMATION_REQUIRED:expected=${'$'}ACTIVE_VERSION_CODE:actual=${'$'}TARGET_VERSION_CODE" >&2
                      exit 64
                    fi
                  fi
                elif [ "${'$'}UCLONE_ALLOW_LEGACY_IDENTITY" = "1" ] && [ "${'$'}UCLONE_ALLOW_VERSION_MISMATCH" = "1" ]; then
                  echo "WARN_LEGACY_PACKAGE_IDENTITY_ALLOWED:${'$'}ACTIVE"
                  echo "WARN_VERSION_MISMATCH_ALLOWED:expected=${'$'}ACTIVE_VERSION_CODE:actual=${'$'}TARGET_VERSION_CODE"
                else
                  echo "ERR_LEGACY_PACKAGE_IDENTITY_CONFIRMATION_REQUIRED:${'$'}ACTIVE" >&2
                  exit 64
                fi
              else
                if [ "${'$'}UCLONE_ALLOW_LEGACY_IDENTITY" = "1" ]; then
                  echo "WARN_LEGACY_PACKAGE_IDENTITY_ALLOWED:${'$'}ACTIVE"
                else
                  echo "ERR_LEGACY_PACKAGE_IDENTITY_CONFIRMATION_REQUIRED:${'$'}ACTIVE" >&2
                  exit 64
                fi
              fi
            fi
            mkdir -p "${'$'}ROOT/$rollbackRootName/${'$'}PKG" "${'$'}ROOT/tmp" || exit 53
            count_items() {
              uclone_count_tree "${'$'}1"
            }
            ROLLBACK_REUSED=0
            ${backupReuseValidationScript()}
            ${if (settings.reuseExistingPassiveBackups && rollbackRootName == "rollback") """
            REUSE_NEWEST_TS=0
            for REUSE_CANDIDATE in "${'$'}ROOT/rollback/${'$'}PKG"/*; do
              [ -d "${'$'}REUSE_CANDIDATE" ] || continue
              [ "${'$'}REUSE_CANDIDATE" != "${'$'}ACTIVE" ] || continue
              uclone_backup_matches_identity "${'$'}REUSE_CANDIDATE" "${'$'}PKG" "${'$'}DST_USER" "${'$'}DST_USER" "${'$'}CURRENT_STATE_KIND" rollback || continue
              REUSE_TS=${'$'}(stat -c %Y "${'$'}REUSE_CANDIDATE" 2>/dev/null || echo 0)
              if [ "${'$'}REUSE_TS" -ge "${'$'}REUSE_NEWEST_TS" ]; then
                PERSISTENT_ROLLBACK="${'$'}REUSE_CANDIDATE"
                ROLLBACK_ID=${'$'}(basename "${'$'}REUSE_CANDIDATE")
                REUSE_NEWEST_TS="${'$'}REUSE_TS"
                ROLLBACK_REUSED=1
              fi
            done
            if [ "${'$'}ROLLBACK_REUSED" = "1" ]; then
              validate_rollback_id "${'$'}ROLLBACK_ID"
              echo "PASSIVE_BACKUP_REUSED=${'$'}PERSISTENT_ROLLBACK state=${'$'}CURRENT_STATE_KIND"
            fi
            """.trimIndent() else ":"}
            [ "${'$'}ACTIVE" != "${'$'}PERSISTENT_ROLLBACK" ] || { echo "ERR_ROLLBACK_SOURCE_CONFLICT:${'$'}ACTIVE" >&2; exit 61; }
            UCLONE_ESTIMATED_KB=0
            uclone_add_dir_kb "${'$'}ACTIVE/ce"
            uclone_add_dir_kb "${'$'}ACTIVE/de"
            uclone_add_dir_kb "${'$'}ACTIVE/external"
            uclone_add_dir_kb "${'$'}ACTIVE/media"
            uclone_add_dir_kb "${'$'}ACTIVE/obb"
            RESTORE_SOURCE_KB="${'$'}UCLONE_ESTIMATED_KB"
            UCLONE_ESTIMATED_KB=0
            uclone_add_dir_kb "/data/user/${'$'}DST_USER/${'$'}PKG"
            uclone_add_dir_kb "/data/user_de/${'$'}DST_USER/${'$'}PKG"
            uclone_add_dir_kb "/data/media/${'$'}DST_USER/Android/data/${'$'}PKG"
            uclone_add_dir_kb "/data/media/${'$'}DST_USER/Android/media/${'$'}PKG"
            uclone_add_dir_kb "/data/media/${'$'}DST_USER/Android/obb/${'$'}PKG"
            RESTORE_TARGET_KB="${'$'}UCLONE_ESTIMATED_KB"
            RESTORE_REQUIRED_KB=${'$'}(uclone_decimal_add "${'$'}RESTORE_SOURCE_KB" "${'$'}RESTORE_TARGET_KB") || RESTORE_REQUIRED_KB=""
            uclone_require_space "${'$'}RESTORE_REQUIRED_KB" "restore_prepared_and_rollback"
            PREPARED_ROOT="${'$'}ROOT/tmp/prepared_${'$'}{PKG}_${'$'}TS"
            PERSISTENT_ROLLBACK_FINALIZED="${'$'}ROLLBACK_REUSED"
            cleanup_restore_prepared() {
              case "${'$'}{PREPARED_ROOT:-}" in
                "${'$'}ROOT"/tmp/prepared_"${'$'}PKG"_"${'$'}TS") uclone_remove_tree "${'$'}PREPARED_ROOT" 2>/dev/null || true ;;
              esac
            }
            cleanup_transaction_undo() {
              case "${'$'}{TRANSACTION_UNDO:-}" in
                "${'$'}ROOT"/tmp/undo_"${'$'}PKG"_"${'$'}TS") uclone_remove_tree "${'$'}TRANSACTION_UNDO" 2>/dev/null || true ;;
              esac
            }
            cleanup_restore_before_transaction() {
              if command -v uclone_release_pre_mutation_gates >/dev/null 2>&1; then
                uclone_release_pre_mutation_gates || true
              fi
              cleanup_restore_prepared
              if [ "${'$'}{PERSISTENT_ROLLBACK_FINALIZED:-0}" != "1" ]; then
                ROLLBACK_SAFE_PREFIX="${'$'}ROOT/$rollbackRootName/${'$'}PKG/"
                case "${'$'}PERSISTENT_ROLLBACK" in
                  "${'$'}ROLLBACK_SAFE_PREFIX"*) uclone_remove_tree "${'$'}PERSISTENT_ROLLBACK" 2>/dev/null || true ;;
                esac
              fi
              if command -v cleanup_on_exit >/dev/null 2>&1; then
                cleanup_on_exit
              elif command -v cleanup_switch_temp >/dev/null 2>&1; then
                cleanup_switch_temp
              fi
            }
            trap cleanup_restore_before_transaction EXIT
            uclone_remove_tree "${'$'}PREPARED_ROOT" || exit 56
            mkdir -p "${'$'}PREPARED_ROOT" || exit 56
            mkdir -p "${'$'}PREPARED_ROOT/.state" || exit 56
            PREPARED_PARTS=0
            ACTIONABLE_PARTS=0
            prepare_restore_part() {
              PREPARE_SRC="${'$'}1"
              PREPARE_NAME="${'$'}2"
              PREPARE_STATE_FILE="${'$'}ACTIVE/.state/${'$'}PREPARE_NAME"
              [ -f "${'$'}PREPARE_STATE_FILE" ] || { echo "ERR_SNAPSHOT_STATE_MISSING:${'$'}PREPARE_NAME" >&2; exit 64; }
              PREPARE_STATE=${'$'}(sed -n '1p' "${'$'}PREPARE_STATE_FILE" | tr -d '\r')
              PREPARE_INTEGRITY_EXIT=0
              uclone_verify_part_metadata "${'$'}ACTIVE" "${'$'}PREPARE_NAME" >/dev/null || PREPARE_INTEGRITY_EXIT=${'$'}?
              case "${'$'}PREPARE_INTEGRITY_EXIT" in
                0) ;;
                2)
                  if [ "${'$'}ACTIVE_INTEGRITY_REQUIRED" = "1" ]; then
                    echo "ERR_SNAPSHOT_INTEGRITY_MISSING:${'$'}PREPARE_NAME" >&2
                    exit 64
                  fi
                  echo "WARN_LEGACY_INTEGRITY_UNVERIFIED:${'$'}PREPARE_NAME"
                  ;;
                *)
                  echo "ERR_SNAPSHOT_INTEGRITY:${'$'}PREPARE_NAME" >&2
                  exit 64
                  ;;
              esac
              case "${'$'}PREPARE_STATE" in
                excluded|absent|empty)
                  [ ! -e "${'$'}PREPARE_SRC" ] || { echo "ERR_SNAPSHOT_STATE_PAYLOAD_CONFLICT:${'$'}PREPARE_NAME:${'$'}PREPARE_STATE" >&2; exit 64; }
                  printf '%s\n' "${'$'}PREPARE_STATE" > "${'$'}PREPARED_ROOT/.state/${'$'}PREPARE_NAME" || exit 56
                  uclone_write_part_metadata "${'$'}PREPARED_ROOT" "${'$'}PREPARE_NAME" "${'$'}PREPARE_STATE" "${'$'}ACTIVE/.meta/${'$'}PREPARE_NAME" || exit 56
                  if [ "${'$'}PREPARE_STATE" != "excluded" ]; then ACTIONABLE_PARTS=${'$'}((ACTIONABLE_PARTS + 1)); fi
                  echo "PREPARED_STATE:${'$'}PREPARE_NAME=${'$'}PREPARE_STATE"
                  return 0
                  ;;
                data) ;;
                unreadable)
                  echo "ERR_SNAPSHOT_PART_UNREADABLE:${'$'}PREPARE_NAME" >&2
                  exit 64
                  ;;
                *)
                  echo "ERR_SNAPSHOT_STATE_INVALID:${'$'}PREPARE_NAME:${'$'}PREPARE_STATE" >&2
                  exit 64
                  ;;
              esac
              [ -d "${'$'}PREPARE_SRC" ] || { echo "ERR_SNAPSHOT_DATA_MISSING:${'$'}PREPARE_NAME" >&2; exit 64; }
              PREPARE_ITEMS=${'$'}(count_items "${'$'}PREPARE_SRC") || { echo "ERR_SNAPSHOT_PART_UNREADABLE:${'$'}PREPARE_NAME" >&2; exit 64; }
              [ "${'$'}PREPARE_ITEMS" -gt 0 ] || { echo "ERR_EMPTY_SNAPSHOT_PART:${'$'}PREPARE_SRC" >&2; exit 64; }
              PREPARE_DST="${'$'}PREPARED_ROOT/${'$'}PREPARE_NAME"
              mkdir -p "${'$'}PREPARE_DST" || exit 56
              uclone_extract_workspace_tree "${'$'}PREPARE_SRC" "${'$'}PREPARE_DST" || exit 57
              PREPARED_ITEMS=${'$'}(count_items "${'$'}PREPARE_DST")
              [ "${'$'}PREPARED_ITEMS" -gt 0 ] || { echo "ERR_EXTRACT_EMPTY:${'$'}PREPARE_SRC" >&2; exit 69; }
              printf '%s\n' data > "${'$'}PREPARED_ROOT/.state/${'$'}PREPARE_NAME" || exit 56
              uclone_write_part_metadata "${'$'}PREPARED_ROOT" "${'$'}PREPARE_NAME" data "${'$'}ACTIVE/.meta/${'$'}PREPARE_NAME" || exit 56
              uclone_verify_part_metadata "${'$'}PREPARED_ROOT" "${'$'}PREPARE_NAME" >/dev/null || { echo "ERR_PREPARED_INTEGRITY:${'$'}PREPARE_NAME" >&2; exit 69; }
              PREPARED_SIZE_KB=${'$'}(uclone_dir_kb "${'$'}PREPARE_DST")
              UCLONE_SCANNED_FILES=${'$'}((UCLONE_SCANNED_FILES + PREPARE_ITEMS))
              UCLONE_COPIED_FILES=${'$'}((UCLONE_COPIED_FILES + PREPARED_ITEMS))
              uclone_add_written_kb "${'$'}PREPARED_SIZE_KB"
              PREPARED_PARTS=${'$'}((PREPARED_PARTS + 1))
              ACTIONABLE_PARTS=${'$'}((ACTIONABLE_PARTS + 1))
              echo "PREPARED:${'$'}PREPARE_NAME ITEMS=${'$'}PREPARED_ITEMS"
            }
            prepare_restore_part "${'$'}ACTIVE/ce" "ce"
            prepare_restore_part "${'$'}ACTIVE/de" "de"
            prepare_restore_part "${'$'}ACTIVE/external" "external"
            prepare_restore_part "${'$'}ACTIVE/media" "media"
            prepare_restore_part "${'$'}ACTIVE/obb" "obb"
            ${if (settings.includePermissions) """
            if uclone_permission_capture_valid "${'$'}ACTIVE/permissions"; then
              ACTIONABLE_PARTS=${'$'}((ACTIONABLE_PARTS + 1))
              echo "PREPARED_PERMISSIONS=${'$'}ACTIVE/permissions"
            ${if (settings.permissionRestoreMode == PermissionRestoreMode.EXACT) "else\n  echo \"ERR_PERMISSION_EXACT_RESTORE:${'$'}ACTIVE/permissions\" >&2\n  exit 61" else "else\n  echo \"WARN_PERMISSION_RESTORE_SKIPPED_INVALID_CAPTURE:${'$'}ACTIVE/permissions\""}
            fi
            """.trimIndent() else ":"}
            [ "${'$'}ACTIONABLE_PARTS" -gt 0 ] || { echo "ERR_NOTHING_PREPARED:${'$'}ACTIVE" >&2; exit 62; }
            uclone_record_temp_path "${'$'}PREPARED_ROOT"
            uclone_stage_end
            force_stop_package_users() {
              am force-stop --user "${'$'}DST_USER" "${'$'}PKG" >/dev/null 2>&1 || {
                echo "ERR_FORCE_STOP_FAILED:${'$'}DST_USER:${'$'}PKG" >&2
                return 1
              }
              echo "FORCE_STOP_USERS:${'$'}DST_USER"
            }
            uclone_stage_begin TARGET_STOP
            uclone_transaction_stage TARGET_GATE_ACQUIRE || exit 77
            uclone_gate_acquire target "${'$'}DST_USER" "${'$'}UID_VALUE" || { echo "ERR_TARGET_GATE_ACQUIRE:${'$'}DST_USER:${'$'}PKG" >&2; exit 77; }
            TARGET_GATE_DIR="${'$'}UCLONE_GATE_DIR"
            uclone_transaction_stage TARGET_GATED || exit 77
            UCLONE_TARGET_STOPPED_AT=${'$'}(uclone_now_ms)
            uclone_stage_end
            BACKUP_PARTS=0
            RESTORED_PARTS=0
            RESTORED_ITEMS=0
            backup_dir() {
              uclone_cancel_checkpoint "ROLLBACK_PART_${'$'}3" || exit 93
              SRC="${'$'}1"
              DST="${'$'}2"
              PART_NAME="${'$'}3"
              mkdir -p "${'$'}ROLLBACK/.state" || exit 54
              if [ ! -d "${'$'}SRC" ]; then
                printf '%s\n' "absent" > "${'$'}ROLLBACK/.state/${'$'}PART_NAME" || exit 54
                uclone_write_part_metadata "${'$'}ROLLBACK" "${'$'}PART_NAME" absent || exit 54
                return 0
              fi
              SRC_ITEMS=${'$'}(count_items "${'$'}SRC")
              if [ "${'$'}SRC_ITEMS" -le 0 ]; then
                printf '%s\n' "empty" > "${'$'}ROLLBACK/.state/${'$'}PART_NAME" || exit 54
                uclone_write_part_metadata "${'$'}ROLLBACK" "${'$'}PART_NAME" empty "${'$'}SRC" || exit 54
                echo "SKIP_BACKUP_EMPTY:${'$'}SRC"
                return 0
              fi
              uclone_remove_tree "${'$'}DST" || exit 54
              mkdir -p "${'$'}DST" || exit 54
              uclone_extract_workspace_tree "${'$'}SRC" "${'$'}DST" || exit 55
              BACKUP_ITEMS=${'$'}(count_items "${'$'}DST")
              [ "${'$'}BACKUP_ITEMS" -gt 0 ] || { echo "ERR_BACKUP_EMPTY:${'$'}SRC" >&2; exit 63; }
              printf '%s\n' "data" > "${'$'}ROLLBACK/.state/${'$'}PART_NAME" || exit 54
              uclone_write_part_metadata "${'$'}ROLLBACK" "${'$'}PART_NAME" data "${'$'}SRC" || exit 54
              BACKUP_PARTS=${'$'}((BACKUP_PARTS + 1))
              UCLONE_SCANNED_FILES=${'$'}((UCLONE_SCANNED_FILES + SRC_ITEMS))
              UCLONE_COPIED_FILES=${'$'}((UCLONE_COPIED_FILES + BACKUP_ITEMS))
              BACKUP_SIZE_KB=${'$'}(uclone_tree_kb "${'$'}DST") || exit 63
              uclone_add_written_kb "${'$'}BACKUP_SIZE_KB"
              uclone_record_temp_path "${'$'}DST"
              echo "BACKUP:${'$'}SRC ITEMS=${'$'}BACKUP_ITEMS"
            }
            backup_permission_state() {
              uclone_capture_permission_state "${'$'}1" "${'$'}DST_USER" EXACT
            }
            validate_target_path() {
              CHECK_TARGET="${'$'}1"
              [ -n "${'$'}CHECK_TARGET" ] && [ "${'$'}CHECK_TARGET" != "/" ] || { echo "ERR_UNSAFE_TARGET:${'$'}CHECK_TARGET" >&2; exit 66; }
              case "${'$'}CHECK_TARGET" in
                /data/user/${'$'}DST_USER/${'$'}PKG|/data/user_de/${'$'}DST_USER/${'$'}PKG|/data/media/${'$'}DST_USER/Android/data/${'$'}PKG|/data/media/${'$'}DST_USER/Android/media/${'$'}PKG|/data/media/${'$'}DST_USER/Android/obb/${'$'}PKG) ;;
                *) echo "ERR_UNSAFE_TARGET:${'$'}CHECK_TARGET" >&2; exit 66 ;;
              esac
            }
            clear_target_contents() {
              CLEAR_TARGET="${'$'}1"
              validate_target_path "${'$'}CLEAR_TARGET"
              [ -d "${'$'}CLEAR_TARGET" ] || { echo "ERR_TARGET_MISSING:${'$'}CLEAR_TARGET" >&2; exit 67; }
              uclone_clear_tree_contents "${'$'}CLEAR_TARGET" || exit 68
            }
            read_target_context() {
              CONTEXT_TARGET="${'$'}1"
              ls -Zd "${'$'}CONTEXT_TARGET" 2>/dev/null | awk '{print ${'$'}1; exit}'
            }
            target_owner_for() {
              OWNER_UID_ARG="${'$'}2"
              OWNER_KIND="${'$'}3"
              if [ -n "${'$'}OWNER_UID_ARG" ]; then
                echo "${'$'}OWNER_UID_ARG:${'$'}OWNER_UID_ARG"
                return 0
              fi
              case "${'$'}OWNER_KIND" in
                app) echo "${'$'}UID_VALUE:${'$'}UID_VALUE"; return 0 ;;
                media) echo "${'$'}UID_VALUE:1078"; return 0 ;;
                obb) echo "${'$'}UID_VALUE:1079"; return 0 ;;
              esac
              echo ""
            }
            target_mode_for() {
              MODE_TARGET="${'$'}1"
              MODE_KIND="${'$'}2"
              EXISTING_MODE=${'$'}(stat -c '%a' "${'$'}MODE_TARGET" 2>/dev/null || true)
              case "${'$'}EXISTING_MODE" in ''|*[!0-7]*) ;; *) echo "${'$'}EXISTING_MODE"; return 0 ;; esac
              case "${'$'}MODE_KIND" in app) echo 700 ;; media|obb) echo 2770 ;; *) echo 700 ;; esac
            }
            apply_target_security() {
              SEC_TARGET="${'$'}1"
              SEC_OWNER="${'$'}2"
              SEC_CONTEXT="${'$'}3"
              SEC_MODE="${'$'}4"
              SEC_KIND="${'$'}5"
              case "${'$'}SEC_KIND" in app|media|obb) ;; *) exit 59 ;; esac
              if [ -n "${'$'}SEC_OWNER" ]; then
                chown -hR "${'$'}SEC_OWNER" "${'$'}SEC_TARGET" || exit 59
                OWNER_UID=${'$'}(echo "${'$'}SEC_OWNER" | cut -d: -f1)
                case "${'$'}SEC_KIND:${'$'}OWNER_UID" in
                  app:) ;;
                  app:*[!0-9]*) ;;
                  app:*)
                    APP_ID=${'$'}((OWNER_UID % 100000))
                    OWNER_USER_ID=${'$'}((OWNER_UID / 100000))
                    if [ "${'$'}APP_ID" -ge 10000 ] && [ "${'$'}APP_ID" -le 19999 ]; then
                      CACHE_GID=${'$'}((OWNER_USER_ID * 100000 + 20000 + APP_ID - 10000))
                      if [ -d "${'$'}SEC_TARGET/cache" ]; then
                        chown -hR "${'$'}OWNER_UID:${'$'}CACHE_GID" "${'$'}SEC_TARGET/cache" >/dev/null 2>&1 || {
                          echo "ERR_CACHE_GID_RESTORE:${'$'}SEC_TARGET/cache" >&2
                          exit 59
                        }
                      fi
                      if [ -d "${'$'}SEC_TARGET/code_cache" ]; then
                        chown -hR "${'$'}OWNER_UID:${'$'}CACHE_GID" "${'$'}SEC_TARGET/code_cache" >/dev/null 2>&1 || {
                          echo "ERR_CACHE_GID_RESTORE:${'$'}SEC_TARGET/code_cache" >&2
                          exit 59
                        }
                      fi
                    fi
                    ;;
                  media:*|obb:*) ;;
                  *) ;;
                esac
              fi
              if [ -n "${'$'}SEC_CONTEXT" ]; then
                chcon -R -h "${'$'}SEC_CONTEXT" "${'$'}SEC_TARGET" >/dev/null 2>&1 || restorecon -RF "${'$'}SEC_TARGET" >/dev/null 2>&1 || restorecon -R "${'$'}SEC_TARGET" >/dev/null 2>&1 || exit 60
              else
                restorecon -RF "${'$'}SEC_TARGET" >/dev/null 2>&1 || restorecon -R "${'$'}SEC_TARGET" >/dev/null 2>&1 || exit 60
              fi
              chmod "${'$'}SEC_MODE" "${'$'}SEC_TARGET" || exit 59
            }
            restore_part() {
              uclone_cancel_checkpoint "RESTORE_PART_${'$'}5" || exit 93
              SNAP="${'$'}1"
              TARGET="${'$'}2"
              OWNER_UID="${'$'}3"
              OWNER_KIND="${'$'}4"
              PART_NAME="${'$'}5"
              validate_target_path "${'$'}TARGET"
              PREPARED_STATE_FILE="${'$'}PREPARED_ROOT/.state/${'$'}PART_NAME"
              [ -f "${'$'}PREPARED_STATE_FILE" ] || { echo "ERR_PREPARED_STATE_MISSING:${'$'}PART_NAME" >&2; exit 69; }
              PREPARED_STATE=${'$'}(sed -n '1p' "${'$'}PREPARED_STATE_FILE" | tr -d '\r')
              uclone_verify_part_metadata "${'$'}PREPARED_ROOT" "${'$'}PART_NAME" >/dev/null || {
                echo "ERR_PREPARED_INTEGRITY:${'$'}PART_NAME" >&2
                exit 69
              }
              case "${'$'}PREPARED_STATE" in
                excluded)
                  echo "SKIP_EXCLUDED_PART:${'$'}PART_NAME"
                  return 0
                  ;;
                absent)
                  uclone_transaction_target_mutating "${'$'}PART_NAME" || exit 77
                  TARGET_MUTATED=1
                  uclone_remove_tree "${'$'}TARGET" || exit 68
                  RESTORED_PARTS=${'$'}((RESTORED_PARTS + 1))
                  echo "RESTORED_ABSENT:${'$'}TARGET"
                  return 0
                  ;;
                empty|data) ;;
                *) echo "ERR_PREPARED_STATE_INVALID:${'$'}PART_NAME:${'$'}PREPARED_STATE" >&2; exit 69 ;;
              esac
              PREPARED="${'$'}PREPARED_ROOT/${'$'}PART_NAME"
              PREPARED_ITEMS=0
              if [ "${'$'}PREPARED_STATE" = "data" ]; then
                [ -d "${'$'}PREPARED" ] || { echo "ERR_PREPARED_PART_MISSING:${'$'}PREPARED" >&2; exit 69; }
                PREPARED_ITEMS=${'$'}(count_items "${'$'}PREPARED") || { echo "ERR_PREPARED_PART_UNREADABLE:${'$'}PREPARED" >&2; exit 69; }
                [ "${'$'}PREPARED_ITEMS" -gt 0 ] || { echo "ERR_PREPARED_PART_EMPTY:${'$'}PREPARED" >&2; exit 69; }
              else
                [ ! -e "${'$'}PREPARED" ] || { echo "ERR_PREPARED_STATE_PAYLOAD_CONFLICT:${'$'}PART_NAME:${'$'}PREPARED_STATE" >&2; exit 69; }
              fi
              SNAP_ITEMS="${'$'}PREPARED_ITEMS"
              TARGET_OWNER=${'$'}(target_owner_for "${'$'}TARGET" "${'$'}OWNER_UID" "${'$'}OWNER_KIND")
              TARGET_MODE=${'$'}(target_mode_for "${'$'}TARGET" "${'$'}OWNER_KIND")
              uclone_transaction_target_mutating "${'$'}PART_NAME" || exit 77
              TARGET_MUTATED=1
              mkdir -p "${'$'}TARGET" || exit 56
              TARGET_CONTEXT=${'$'}(read_target_context "${'$'}TARGET")
              case "${'$'}TARGET_CONTEXT" in u:object_r:*) ;; *) TARGET_CONTEXT="" ;; esac
              if [ -z "${'$'}TARGET_CONTEXT" ]; then
                restorecon -RF "${'$'}TARGET" >/dev/null 2>&1 || restorecon -R "${'$'}TARGET" >/dev/null 2>&1 || true
                TARGET_CONTEXT=${'$'}(read_target_context "${'$'}TARGET")
                case "${'$'}TARGET_CONTEXT" in u:object_r:*) ;; *) TARGET_CONTEXT="" ;; esac
              fi
              clear_target_contents "${'$'}TARGET"
              if [ "${'$'}PREPARED_STATE" = "data" ]; then
                uclone_extract_target_tree "${'$'}PREPARED" "${'$'}TARGET" || exit 58
              fi
              apply_target_security "${'$'}TARGET" "${'$'}TARGET_OWNER" "${'$'}TARGET_CONTEXT" "${'$'}TARGET_MODE" "${'$'}OWNER_KIND"
              TARGET_ITEMS=${'$'}(count_items "${'$'}TARGET")
              if [ "${'$'}PREPARED_STATE" = "data" ]; then
                [ "${'$'}TARGET_ITEMS" -gt 0 ] || { echo "ERR_RESTORE_EMPTY:${'$'}TARGET" >&2; exit 65; }
              else
                [ "${'$'}TARGET_ITEMS" -eq 0 ] || { echo "ERR_RESTORE_NOT_EMPTY:${'$'}TARGET" >&2; exit 65; }
              fi
              RESTORED_PARTS=${'$'}((RESTORED_PARTS + 1))
              RESTORED_ITEMS=${'$'}((RESTORED_ITEMS + TARGET_ITEMS))
              UCLONE_SCANNED_FILES=${'$'}((UCLONE_SCANNED_FILES + SNAP_ITEMS))
              UCLONE_COPIED_FILES=${'$'}((UCLONE_COPIED_FILES + TARGET_ITEMS))
              TARGET_SIZE_KB=${'$'}(uclone_tree_kb "${'$'}TARGET") || exit 65
              uclone_add_written_kb "${'$'}TARGET_SIZE_KB"
              uclone_record_temp_path "${'$'}TARGET"
              echo "RESTORED:${'$'}TARGET ITEMS=${'$'}TARGET_ITEMS OWNER=${'$'}TARGET_OWNER CONTEXT=${'$'}TARGET_CONTEXT"
            }
            restore_permission_state_strict() {
              uclone_restore_permission_state "${'$'}1" "${'$'}DST_USER" "${settings.permissionRestoreMode.name}" "$transactionSourceUser"
            }
            restore_transaction_permission_state() {
              uclone_restore_permission_state "${'$'}1" "${'$'}DST_USER" EXACT "${'$'}DST_USER"
            }
            restore_permission_state() {
              PERMISSION_SOURCE="${'$'}1"
              PERMISSION_RESTORE_APPLIED=0
              [ -d "${'$'}PERMISSION_SOURCE" ] || { echo "SKIP_PERMISSIONS:${'$'}PERMISSION_SOURCE"; return 0; }
              if ! uclone_permission_capture_valid "${'$'}PERMISSION_SOURCE"; then
                ${if (settings.permissionRestoreMode == PermissionRestoreMode.EXACT) "echo \"ERR_PERMISSION_EXACT_RESTORE:${'$'}PERMISSION_SOURCE\" >&2\nexit 61" else "echo \"WARN_PERMISSION_RESTORE_SKIPPED_INVALID_CAPTURE:${'$'}PERMISSION_SOURCE\"\nreturn 0"}
              fi
              uclone_transaction_target_mutating permissions || exit 77
              ${if (settings.permissionRestoreMode == PermissionRestoreMode.EXACT) """
              restore_permission_state_strict "${'$'}PERMISSION_SOURCE" || {
                echo "ERR_PERMISSION_EXACT_RESTORE:${'$'}PERMISSION_SOURCE" >&2
                exit 61
              }
              """.trimIndent() else """
              if ! restore_permission_state_strict "${'$'}PERMISSION_SOURCE"; then
                echo "WARN_PERMISSION_RESTORE_SKIPPED_INVALID_CAPTURE:${'$'}PERMISSION_SOURCE"
              fi
              """.trimIndent()}
              PERMISSION_RESTORE_APPLIED=1
              return 0
            }
            prune_old_rollbacks() {
              ROLLBACK_PARENT="${'$'}ROOT/rollback/${'$'}PKG"
              SWITCH_MARKER_FOR_PRUNE="${'$'}ROOT/switches/${'$'}PKG/active"
              SWITCH_ID_FOR_PRUNE=""
              if [ -f "${'$'}SWITCH_MARKER_FOR_PRUNE" ]; then
                SWITCH_ID_FOR_PRUNE=${'$'}(sed -n '1p' "${'$'}SWITCH_MARKER_FOR_PRUNE" | tr -d '\r')
              fi
              [ -d "${'$'}ROLLBACK_PARENT" ] || return 0
              EXPECTED_CHILD_PREFIX="${'$'}ROOT/rollback/${'$'}PKG/"
              find "${'$'}ROLLBACK_PARENT" -xdev -mindepth 1 -maxdepth 1 -type d 2>/dev/null | while IFS= read -r OLD; do
                OLD_ID=${'$'}(basename "${'$'}OLD")
                [ "${'$'}OLD_ID" = "${'$'}ROLLBACK_ID" ] && continue
                if [ -n "${'$'}SWITCH_ID_FOR_PRUNE" ] && [ "${'$'}OLD_ID" = "${'$'}SWITCH_ID_FOR_PRUNE" ]; then
                  echo "KEPT_ROLLBACK_ACTIVE_RETURN_POINT=${'$'}OLD"
                  continue
                fi
                OLD_STATE_KIND=""
                if [ -f "${'$'}OLD/manifest.json" ]; then
                  OLD_STATE_KIND=${'$'}(sed -n 's/.*"stateKind":"\([^"]*\)".*/\1/p' "${'$'}OLD/manifest.json" | head -1)
                fi
                if [ -n "${'$'}OLD_STATE_KIND" ] && [ "${'$'}OLD_STATE_KIND" != "${'$'}CURRENT_STATE_KIND" ]; then
                  echo "KEPT_ROLLBACK_OTHER_STATE=${'$'}OLD state=${'$'}OLD_STATE_KIND"
                  continue
                fi
                if [ -z "${'$'}OLD_STATE_KIND" ]; then
                  echo "KEPT_ROLLBACK_LEGACY=${'$'}OLD"
                  continue
                fi
                case "${'$'}OLD" in
                  "${'$'}EXPECTED_CHILD_PREFIX"*)
                    uclone_remove_tree "${'$'}OLD" && echo "PRUNED_ROLLBACK=${'$'}OLD" || echo "WARN_PRUNE_ROLLBACK_FAILED:${'$'}OLD"
                    ;;
                  *)
                    echo "WARN_SKIP_BAD_ROLLBACK_PATH:${'$'}OLD"
                    ;;
                esac
              done || exit 70
              if [ -f "${'$'}SWITCH_MARKER_FOR_PRUNE" ]; then
                SWITCH_ID_AFTER_PRUNE=${'$'}(sed -n '1p' "${'$'}SWITCH_MARKER_FOR_PRUNE" | tr -d '\r')
                if [ "${'$'}SWITCH_ID_AFTER_PRUNE" = "${'$'}UNKNOWN_SWITCH_MARKER" ]; then
                  echo "KEPT_SWITCH_MARKER_UNKNOWN=${'$'}SWITCH_MARKER_FOR_PRUNE"
                elif [ ! -d "${'$'}ROLLBACK_PARENT/${'$'}SWITCH_ID_AFTER_PRUNE" ]; then
                  echo "KEPT_SWITCH_MARKER_BROKEN=${'$'}SWITCH_MARKER_FOR_PRUNE id=${'$'}SWITCH_ID_AFTER_PRUNE"
                fi
              fi
            }
            stop_clone_user_after_switch_restore() {
              ${if (settings.stopCloneAfterTask && (writeSwitchMarker || clearSwitchMarker)) """
              if [ "${'$'}{CLONE_STARTED_BY_TASK:-0}" = "1" ]; then
                if command -v cleanup_clone_user >/dev/null 2>&1; then
                  cleanup_clone_user
                else
                  echo "WARN_STOP_CLONE_HELPER_MISSING:reason=switch_restore"
                fi
              else
                echo "STOP_CLONE_AFTER_TASK=0 reason=switch_restore startedByTask=${'$'}{CLONE_STARTED_BY_TASK:-0}"
              fi
              """.trimIndent() else """
              echo "STOP_CLONE_AFTER_TASK=0 reason=switch_restore_disabled"
              """.trimIndent()}
            }
            uclone_stage_begin ROLLBACK_BACKUP
            if [ "${'$'}ROLLBACK_REUSED" = "1" ]; then
              TRANSACTION_UNDO="${'$'}ROOT/tmp/undo_${'$'}{PKG}_${'$'}TS"
              ROLLBACK="${'$'}TRANSACTION_UNDO"
              uclone_remove_tree "${'$'}ROLLBACK" || exit 53
              mkdir -p "${'$'}ROLLBACK" || exit 53
            else
              ROLLBACK="${'$'}PERSISTENT_ROLLBACK"
            fi
            backup_dir "/data/user/${'$'}DST_USER/${'$'}PKG" "${'$'}ROLLBACK/ce" "ce"
            backup_dir "/data/user_de/${'$'}DST_USER/${'$'}PKG" "${'$'}ROLLBACK/de" "de"
            backup_dir "/data/media/${'$'}DST_USER/Android/data/${'$'}PKG" "${'$'}ROLLBACK/external" "external"
            backup_dir "/data/media/${'$'}DST_USER/Android/media/${'$'}PKG" "${'$'}ROLLBACK/media" "media"
            backup_dir "/data/media/${'$'}DST_USER/Android/obb/${'$'}PKG" "${'$'}ROLLBACK/obb" "obb"
            ${if (settings.includePermissions) "backup_permission_state \"${'$'}ROLLBACK/permissions\" || { echo \"ERR_TRANSACTION_PERMISSION_CAPTURE:${'$'}DST_USER\" >&2; exit 54; }" else ":"}
            ROLLBACK_SIZE_KB=${'$'}(uclone_tree_kb "${'$'}ROLLBACK") || exit 63
            if [ "${'$'}ROLLBACK_REUSED" = "1" ]; then
              printf '%s\n' "{\"schemaVersion\":$CURRENT_MANIFEST_SCHEMA,\"integrityMode\":\"FAST_METADATA\",\"packageName\":\"${'$'}PKG\",\"sourceVersionCode\":\"${'$'}TARGET_VERSION_CODE\",\"sourceVersionName\":\"${'$'}TARGET_VERSION_NAME_ESC\",\"sourceSigningCertificateSha256\":\"${'$'}UCLONE_SIGNING_CERTIFICATE_SHA256\",\"sourceApkDigest\":\"${'$'}TARGET_APK_DIGEST\",\"sourceUid\":${'$'}UID_VALUE,\"sourceAppId\":${'$'}TARGET_APP_ID,\"transactionRequestId\":\"${'$'}UCLONE_REQUEST_ID\",\"createdAt\":\"${'$'}TS\",\"stateKind\":\"${'$'}CURRENT_STATE_KIND\",\"sourceUser\":\"${'$'}DST_USER\",\"targetUser\":\"${'$'}DST_USER\",\"backupKind\":\"transaction_undo\",\"permissionCaptureState\":\"${if (settings.includePermissions) "valid" else "excluded"}\",\"sizeKb\":\"${'$'}ROLLBACK_SIZE_KB\"}" > "${'$'}ROLLBACK/manifest.json" || exit 53
              echo "TRANSACTION_UNDO_CREATED=${'$'}ROLLBACK state=${'$'}CURRENT_STATE_KIND"
            else
              printf '%s\n' "{\"schemaVersion\":$CURRENT_MANIFEST_SCHEMA,\"integrityMode\":\"FAST_METADATA\",\"packageName\":\"${'$'}PKG\",\"sourceVersionCode\":\"${'$'}TARGET_VERSION_CODE\",\"sourceVersionName\":\"${'$'}TARGET_VERSION_NAME_ESC\",\"sourceSigningCertificateSha256\":\"${'$'}UCLONE_SIGNING_CERTIFICATE_SHA256\",\"sourceApkDigest\":\"${'$'}TARGET_APK_DIGEST\",\"sourceUid\":${'$'}UID_VALUE,\"sourceAppId\":${'$'}TARGET_APP_ID,\"transactionRequestId\":\"${'$'}UCLONE_REQUEST_ID\",\"rollbackId\":\"${'$'}ROLLBACK_ID\",\"createdAt\":\"${'$'}TS\",\"reason\":\"$rollbackReason\",\"stateKind\":\"${'$'}CURRENT_STATE_KIND\",\"sourceUser\":\"${'$'}DST_USER\",\"targetUser\":\"${'$'}DST_USER\",\"backupKind\":\"$rollbackRootName\",\"permissionCaptureState\":\"${if (settings.includePermissions) "valid" else "excluded"}\",\"sizeKb\":\"${'$'}ROLLBACK_SIZE_KB\"}" > "${'$'}ROLLBACK/manifest.json" || exit 53
              echo "PASSIVE_BACKUP_STATE=${'$'}CURRENT_STATE_KIND"
              PERSISTENT_ROLLBACK_FINALIZED=1
              echo "PASSIVE_BACKUP_CREATED=${'$'}PERSISTENT_ROLLBACK state=${'$'}CURRENT_STATE_KIND"
            fi
            sync
            uclone_transaction_rollback_ready "${'$'}ROLLBACK" || exit 77
            uclone_stage_end
            ${RestoreTransactionShell.guard(
                appUidVariable = "UID_VALUE",
                includePermissions = settings.includePermissions,
                manageSwitchMarker = writeSwitchMarker || clearSwitchMarker || syncSwitchMarkerToSource,
            )}
            uclone_stage_begin RESTORE_DATA
            restore_part "${'$'}ACTIVE/ce" "/data/user/${'$'}DST_USER/${'$'}PKG" "${'$'}UID_VALUE" "app" "ce"
            restore_part "${'$'}ACTIVE/de" "/data/user_de/${'$'}DST_USER/${'$'}PKG" "${'$'}UID_VALUE" "app" "de"
            restore_part "${'$'}ACTIVE/external" "/data/media/${'$'}DST_USER/Android/data/${'$'}PKG" "" "media" "external"
            restore_part "${'$'}ACTIVE/media" "/data/media/${'$'}DST_USER/Android/media/${'$'}PKG" "" "media" "media"
            restore_part "${'$'}ACTIVE/obb" "/data/media/${'$'}DST_USER/Android/obb/${'$'}PKG" "" "obb" "obb"
            if [ "${'$'}UCLONE_TXN_TARGET_MUTATED" = "true" ]; then
              uclone_transaction_stage TARGET_WRITTEN || exit 77
              uclone_transaction_stage METADATA_RESTORED || exit 77
            fi
            uclone_stage_end
            uclone_stage_begin RESTORE_PERMISSIONS
            ${if (settings.includePermissions) "restore_permission_state \"${'$'}ACTIVE/permissions\"" else ":"}
            [ "${'$'}{PERMISSION_RESTORE_APPLIED:-0}" != "1" ] || uclone_transaction_stage PERMISSIONS_RESTORED || exit 77
            uclone_stage_end
            uclone_stage_begin VERIFY
            [ "${'$'}RESTORED_PARTS" -gt 0 ] || [ "${'$'}{PERMISSION_RESTORE_APPLIED:-0}" = "1" ] || { echo "ERR_NOTHING_RESTORED:${'$'}ACTIVE" >&2; exit 62; }
            uclone_transaction_stage VERIFIED || exit 77
            uclone_stage_end
            uclone_stage_begin COMMIT
            ${if (writeSwitchMarker) """
            SWITCH_DIR="${'$'}ROOT/switches/${'$'}PKG"
            write_switch_marker_atomic "${'$'}SWITCH_DIR/active" "${'$'}ROLLBACK_ID" || exit 70
            echo "SWITCH_MARKER=${'$'}SWITCH_DIR/active ROLLBACK_ID=${'$'}ROLLBACK_ID"
            """.trimIndent() else ":"}
            ${if (clearSwitchMarker) """
            SWITCH_MARKER="${'$'}ROOT/switches/${'$'}PKG/active"
            case "${'$'}SWITCH_MARKER" in
              "${'$'}ROOT"/switches/"${'$'}PKG"/active) rm -f "${'$'}SWITCH_MARKER" || exit 70 ;;
              *) echo "ERR_BAD_SWITCH_MARKER:${'$'}SWITCH_MARKER" >&2; exit 70 ;;
            esac
            echo "SWITCH_MARKER_CLEARED=${'$'}SWITCH_MARKER"
            """.trimIndent() else ":"}
            ${if (syncSwitchMarkerToSource) """
            SWITCH_DIR="${'$'}ROOT/switches/${'$'}PKG"
            SWITCH_MARKER="${'$'}SWITCH_DIR/active"
            case "${'$'}SOURCE_STATE_KIND" in
              main)
                rm -f "${'$'}SWITCH_MARKER" || exit 70
                echo "DATA_STATE_UPDATED=main SWITCH_MARKER_CLEARED=${'$'}SWITCH_MARKER"
                ;;
              clone)
                MAIN_ROLLBACK_ID="${'$'}CURRENT_MAIN_ROLLBACK_ID"
                if [ "${'$'}CURRENT_STATE_KIND" = "main" ]; then MAIN_ROLLBACK_ID="${'$'}ROLLBACK_ID"; fi
                if [ "${'$'}CURRENT_STATE_KIND" = "unknown" ] || [ -z "${'$'}MAIN_ROLLBACK_ID" ]; then
                  write_switch_marker_atomic "${'$'}SWITCH_MARKER" "${'$'}UNKNOWN_SWITCH_MARKER" || exit 70
                  echo "WARN_DATA_STATE_REMAINS_UNKNOWN:source=${'$'}ACTIVE reason=main_return_point_missing"
                else
                  validate_rollback_id "${'$'}MAIN_ROLLBACK_ID"
                  if [ -d "${'$'}ROOT/rollback/${'$'}PKG/${'$'}MAIN_ROLLBACK_ID" ]; then
                    write_switch_marker_atomic "${'$'}SWITCH_MARKER" "${'$'}MAIN_ROLLBACK_ID" || exit 70
                    echo "DATA_STATE_UPDATED=clone SWITCH_MARKER=${'$'}SWITCH_MARKER ROLLBACK_ID=${'$'}MAIN_ROLLBACK_ID"
                  else
                    write_switch_marker_atomic "${'$'}SWITCH_MARKER" "${'$'}UNKNOWN_SWITCH_MARKER" || exit 70
                    echo "WARN_DATA_STATE_REMAINS_UNKNOWN:source=${'$'}ACTIVE reason=main_return_point_missing"
                  fi
                fi
                ;;
              *)
                write_switch_marker_atomic "${'$'}SWITCH_MARKER" "${'$'}UNKNOWN_SWITCH_MARKER" || exit 70
                echo "WARN_DATA_STATE_REMAINS_UNKNOWN:source=${'$'}ACTIVE reason=source_state_unknown"
                ;;
            esac
            """.trimIndent() else ":"}
            sync
            force_stop_package_users || exit 76
            uclone_transaction_commit_data || exit 77
            TRANSACTION_COMMITTED=1
            if ! uclone_gate_release "${'$'}TARGET_GATE_DIR"; then
              uclone_transaction_recovery_required
              exit 92
            fi
            TARGET_GATE_DIR=""
            uclone_transaction_complete || exit 77
            cleanup_restore_prepared
            cleanup_transaction_undo
            TRANSACTION_UNDO=""
            uclone_transaction_cleanup_complete || exit 77
            UCLONE_TARGET_READY_AT=${'$'}(uclone_now_ms)
            UCLONE_TARGET_DOWNTIME_MS=${'$'}(uclone_elapsed_ms "${'$'}UCLONE_TARGET_READY_AT" "${'$'}UCLONE_TARGET_STOPPED_AT")
            uclone_stage_end
            ${if (pruneOldRollbacks) "(prune_old_rollbacks) || echo \"WARN_PRUNE_ROLLBACK_FAILED:${'$'}ROLLBACK\"" else ":"}
            ${if (writeSwitchMarker || clearSwitchMarker) "stop_clone_user_after_switch_restore" else ":"}
            uclone_emit_metrics
            echo "ROLLBACK=${'$'}PERSISTENT_ROLLBACK"
            echo "RESTORE_SUMMARY: restoredParts=${'$'}RESTORED_PARTS restoredItems=${'$'}RESTORED_ITEMS backupParts=${'$'}BACKUP_PARTS"
        """.trimIndent()
    }

    private fun requireSafeRollbackId(rollbackId: String) {
        require(rollbackId.isNotBlank() && rollbackId != "." && rollbackId != ".." && rollbackIdPattern.matches(rollbackId)) {
            "Unsafe rollback id: $rollbackId"
        }
    }

    private fun requireSafePackageName(packageName: String) {
        require(androidPackageNamePattern.matches(packageName)) { "Unsafe package name: $packageName" }
    }

}
