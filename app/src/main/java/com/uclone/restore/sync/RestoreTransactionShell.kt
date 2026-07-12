package com.uclone.restore.sync

internal object RestoreTransactionShell {
    fun guard(
        appUidVariable: String,
        includePermissions: Boolean,
        manageSwitchMarker: Boolean,
    ): String = """
        TRANSACTION_COMMITTED=0
        TRANSACTION_ROLLED_BACK=0
        TARGET_MUTATED=0
        ROLLBACK_READY=1
        TRANSACTION_APP_UID="${'$'}$appUidVariable"
        ${markerStateScript(manageSwitchMarker)}
        restore_rollback_part() {
          RB_SRC="${'$'}1"
          RB_TARGET="${'$'}2"
          RB_KIND="${'$'}3"
          RB_NAME="${'$'}4"
          validate_target_path "${'$'}RB_TARGET"
          uclone_verify_part_metadata "${'$'}ROLLBACK" "${'$'}RB_NAME" >/dev/null || {
            echo "ERR_ROLLBACK_INTEGRITY:${'$'}RB_NAME" >&2
            return 1
          }
          RB_STATE_FILE="${'$'}ROLLBACK/.state/${'$'}RB_NAME"
          [ -f "${'$'}RB_STATE_FILE" ] || { echo "ERR_ROLLBACK_STATE_MISSING:${'$'}RB_NAME" >&2; return 1; }
          RB_STATE=${'$'}(sed -n '1p' "${'$'}RB_STATE_FILE" | tr -d '\r')
          case "${'$'}RB_STATE" in
            absent)
              rm -rf "${'$'}RB_TARGET" || return 1
              echo "AUTO_ROLLBACK_PART_ABSENT:${'$'}RB_TARGET"
              return 0
              ;;
            empty) ;;
            data)
              [ -d "${'$'}RB_SRC" ] || { echo "ERR_ROLLBACK_DATA_MISSING:${'$'}RB_NAME" >&2; return 1; }
              RB_ITEMS=${'$'}(count_items "${'$'}RB_SRC")
              [ "${'$'}RB_ITEMS" -gt 0 ] || { echo "ERR_ROLLBACK_DATA_EMPTY:${'$'}RB_NAME" >&2; return 1; }
              ;;
            *) echo "ERR_ROLLBACK_STATE_INVALID:${'$'}RB_NAME:${'$'}RB_STATE" >&2; return 1 ;;
          esac
          RB_MODE=${'$'}(uclone_part_root_mode "${'$'}ROLLBACK" "${'$'}RB_NAME") || {
            echo "ERR_ROLLBACK_ROOT_MODE_MISSING:${'$'}RB_NAME" >&2
            return 1
          }
          mkdir -p "${'$'}RB_TARGET" || return 1
          RB_CONTEXT=${'$'}(read_target_context "${'$'}RB_TARGET")
          case "${'$'}RB_CONTEXT" in u:object_r:*) ;; *) RB_CONTEXT="" ;; esac
          case "${'$'}RB_KIND" in
            app) RB_OWNER="${'$'}TRANSACTION_APP_UID:${'$'}TRANSACTION_APP_UID" ;;
            media) RB_OWNER="${'$'}TRANSACTION_APP_UID:1078" ;;
            obb) RB_OWNER="${'$'}TRANSACTION_APP_UID:1079" ;;
            *) return 1 ;;
          esac
          clear_target_contents "${'$'}RB_TARGET"
          if [ "${'$'}RB_STATE" = "data" ]; then
            (cd "${'$'}RB_SRC" && tar -cpf - .) | (cd "${'$'}RB_TARGET" && tar -xpf -) || return 1
          fi
          apply_target_security "${'$'}RB_TARGET" "${'$'}RB_OWNER" "${'$'}RB_CONTEXT" "${'$'}RB_MODE" "${'$'}RB_KIND"
          echo "AUTO_ROLLBACK_PART:${'$'}RB_TARGET"
        }
        auto_rollback_target() {
          echo "AUTO_ROLLBACK_BEGIN rollback=${'$'}ROLLBACK"
          uclone_transaction_stage ROLLING_BACK || return 1
          force_stop_package_users || return 1
          (restore_rollback_part "${'$'}ROLLBACK/ce" "/data/user/${'$'}DST_USER/${'$'}PKG" "app" "ce") || return 1
          (restore_rollback_part "${'$'}ROLLBACK/de" "/data/user_de/${'$'}DST_USER/${'$'}PKG" "app" "de") || return 1
          (restore_rollback_part "${'$'}ROLLBACK/external" "/data/media/${'$'}DST_USER/Android/data/${'$'}PKG" "media" "external") || return 1
          (restore_rollback_part "${'$'}ROLLBACK/media" "/data/media/${'$'}DST_USER/Android/media/${'$'}PKG" "media" "media") || return 1
          (restore_rollback_part "${'$'}ROLLBACK/obb" "/data/media/${'$'}DST_USER/Android/obb/${'$'}PKG" "obb" "obb") || return 1
          ${if (includePermissions) "(restore_transaction_permission_state \"${'$'}ROLLBACK/permissions\") || return 1" else ":"}
          ${if (manageSwitchMarker) "restore_previous_switch_marker || return 1" else ":"}
          sync
          force_stop_package_users || return 1
          uclone_transaction_rolled_back || return 1
          if [ -n "${'$'}{TARGET_GATE_DIR:-}" ]; then
            uclone_gate_release "${'$'}TARGET_GATE_DIR" || return 1
            TARGET_GATE_DIR=""
          fi
          uclone_transaction_finish_rolled_back || return 1
          TRANSACTION_ROLLED_BACK=1
          if command -v cleanup_transaction_undo >/dev/null 2>&1; then
            cleanup_transaction_undo
            TRANSACTION_UNDO=""
          fi
          return 0
        }
        transaction_on_exit() {
          TRANSACTION_EXIT_CODE=${'$'}?
          TRANSACTION_EMIT_FAILURE_METRICS=0
          trap - EXIT
          if [ "${'$'}TRANSACTION_EXIT_CODE" -ne 0 ] && [ "${'$'}TARGET_MUTATED" = "1" ] && [ "${'$'}ROLLBACK_READY" = "1" ] && [ "${'$'}TRANSACTION_COMMITTED" != "1" ]; then
            TRANSACTION_EMIT_FAILURE_METRICS=1
            uclone_stage_begin AUTO_ROLLBACK
            if auto_rollback_target; then
              uclone_stage_end
              echo "AUTO_ROLLBACK_SUCCESS originalExit=${'$'}TRANSACTION_EXIT_CODE"
              TRANSACTION_EXIT_CODE=90
            else
              uclone_transaction_recovery_required
              echo "AUTO_ROLLBACK_FAILED originalExit=${'$'}TRANSACTION_EXIT_CODE" >&2
              TRANSACTION_EXIT_CODE=91
            fi
          elif [ "${'$'}TRANSACTION_EXIT_CODE" -ne 0 ] && [ "${'$'}TARGET_MUTATED" != "1" ] && [ -n "${'$'}{TARGET_GATE_DIR:-}" ]; then
            if uclone_gate_release "${'$'}TARGET_GATE_DIR"; then
              TARGET_GATE_DIR=""
            else
              uclone_transaction_recovery_required
              TRANSACTION_EXIT_CODE=92
            fi
          fi
          if [ "${'$'}TRANSACTION_EMIT_FAILURE_METRICS" = "1" ]; then
            UCLONE_TARGET_READY_AT=${'$'}(uclone_now_ms)
            UCLONE_TARGET_DOWNTIME_MS=${'$'}(awk -v FINISHED_AT="${'$'}UCLONE_TARGET_READY_AT" -v STARTED_AT="${'$'}UCLONE_TARGET_STOPPED_AT" 'BEGIN { VALUE = FINISHED_AT - STARTED_AT; if (VALUE < 0) VALUE = 0; printf "%.0f\n", VALUE }')
            uclone_emit_metrics
          fi
          if command -v cleanup_on_exit >/dev/null 2>&1; then
            cleanup_on_exit
          elif command -v cleanup_switch_temp >/dev/null 2>&1; then
            cleanup_switch_temp
          fi
          if command -v cleanup_restore_prepared >/dev/null 2>&1; then
            cleanup_restore_prepared
          fi
          exit "${'$'}TRANSACTION_EXIT_CODE"
        }
        trap transaction_on_exit EXIT
    """.trimIndent()

    private fun markerStateScript(enabled: Boolean): String {
        if (!enabled) return ":"
        return """
            SWITCH_MARKER_PATH="${'$'}ROOT/switches/${'$'}PKG/active"
            SWITCH_MARKER_BEFORE_EXISTS=0
            SWITCH_MARKER_BEFORE_VALUE=""
            if [ -f "${'$'}SWITCH_MARKER_PATH" ]; then
              SWITCH_MARKER_BEFORE_VALUE=${'$'}(sed -n '1p' "${'$'}SWITCH_MARKER_PATH" | tr -d '\r')
              validate_rollback_id "${'$'}SWITCH_MARKER_BEFORE_VALUE"
              SWITCH_MARKER_BEFORE_EXISTS=1
            fi
            if [ "${'$'}SWITCH_MARKER_BEFORE_EXISTS" = "1" ]; then
              uclone_transaction_switch_marker_before true "${'$'}SWITCH_MARKER_BEFORE_VALUE" || exit 77
            else
              uclone_transaction_switch_marker_before false "" || exit 77
            fi
            write_switch_marker_atomic() {
              MARKER_PATH="${'$'}1"
              MARKER_VALUE="${'$'}2"
              [ "${'$'}MARKER_PATH" = "${'$'}ROOT/switches/${'$'}PKG/active" ] || return 1
              validate_rollback_id "${'$'}MARKER_VALUE"
              MARKER_DIR=${'$'}(dirname "${'$'}MARKER_PATH")
              MARKER_TMP="${'$'}MARKER_PATH.tmp_${'$'}TS"
              mkdir -p "${'$'}MARKER_DIR" || return 1
              printf '%s\n' "${'$'}MARKER_VALUE" > "${'$'}MARKER_TMP" || return 1
              chmod 600 "${'$'}MARKER_TMP" || return 1
              sync
              mv -f "${'$'}MARKER_TMP" "${'$'}MARKER_PATH" || return 1
            }
            restore_previous_switch_marker() {
              if [ "${'$'}SWITCH_MARKER_BEFORE_EXISTS" = "1" ]; then
                write_switch_marker_atomic "${'$'}SWITCH_MARKER_PATH" "${'$'}SWITCH_MARKER_BEFORE_VALUE" || return 1
              else
                rm -f "${'$'}SWITCH_MARKER_PATH" "${'$'}SWITCH_MARKER_PATH.tmp_${'$'}TS" || return 1
              fi
            }
        """.trimIndent()
    }
}
