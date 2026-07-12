package com.uclone.restore.sync

internal object TransactionSafetyShell {
    fun functions(): String = """
        uclone_json_escape() {
          printf '%s' "${'$'}1" | sed 's/\\/\\\\/g; s/"/\\"/g'
        }
        uclone_transaction_write() {
          [ -n "${'$'}{UCLONE_TXN_DIR:-}" ] || return 1
          TXN_JSON="${'$'}UCLONE_TXN_DIR/transaction.json"
          TXN_TMP="${'$'}TXN_JSON.tmp.${'$'}${'$'}"
          TXN_REQUEST_ESC=${'$'}(uclone_json_escape "${'$'}UCLONE_REQUEST_ID") || return 1
          TXN_OPERATION_ESC=${'$'}(uclone_json_escape "${'$'}UCLONE_TXN_OPERATION") || return 1
          TXN_PACKAGE_ESC=${'$'}(uclone_json_escape "${'$'}PKG") || return 1
          TXN_ROLLBACK_ESC=${'$'}(uclone_json_escape "${'$'}{UCLONE_TXN_ROLLBACK:-}") || return 1
          TXN_ROLLBACK_NEXT_ESC=${'$'}(uclone_json_escape "${'$'}{UCLONE_TXN_ROLLBACK_NEXT:-}") || return 1
          TXN_STAGE_ESC=${'$'}(uclone_json_escape "${'$'}UCLONE_TXN_STAGE") || return 1
          TXN_GATE_ESC=${'$'}(uclone_json_escape "${'$'}UCLONE_TXN_GATE_STATE") || return 1
          TXN_MARKER_ESC=${'$'}(uclone_json_escape "${'$'}UCLONE_TXN_MARKER_VALUE") || return 1
          TXN_PARTS_ESC=${'$'}(uclone_json_escape "${'$'}UCLONE_TXN_SELECTED_PARTS") || return 1
          TXN_MODIFIED_PARTS_ESC=${'$'}(uclone_json_escape "${'$'}UCLONE_TXN_MODIFIED_PARTS") || return 1
          TXN_BOOT_ESC=${'$'}(uclone_json_escape "${'$'}UCLONE_TXN_ORIGIN_BOOT_ID") || return 1
          TXN_PID_TICKS_ESC=${'$'}(uclone_json_escape "${'$'}UCLONE_TXN_ORIGIN_PID_START_TICKS") || return 1
          UCLONE_TXN_UPDATED_AT=${'$'}(uclone_now_ms)
          printf '%s\n' "{\"schemaVersion\":2,\"requestId\":\"${'$'}TXN_REQUEST_ESC\",\"operation\":\"${'$'}TXN_OPERATION_ESC\",\"packageName\":\"${'$'}TXN_PACKAGE_ESC\",\"sourceUserId\":${'$'}UCLONE_TXN_SOURCE_USER,\"targetUserId\":${'$'}UCLONE_TXN_TARGET_USER,\"selectedParts\":\"${'$'}TXN_PARTS_ESC\",\"modifiedParts\":\"${'$'}TXN_MODIFIED_PARTS_ESC\",\"originBootId\":\"${'$'}TXN_BOOT_ESC\",\"originRootPid\":${'$'}UCLONE_TXN_ORIGIN_ROOT_PID,\"originRootPidStartTicks\":\"${'$'}TXN_PID_TICKS_ESC\",\"rollbackPath\":\"${'$'}TXN_ROLLBACK_ESC\",\"rollbackPathNext\":\"${'$'}TXN_ROLLBACK_NEXT_ESC\",\"stage\":\"${'$'}TXN_STAGE_ESC\",\"rollbackReady\":${'$'}UCLONE_TXN_ROLLBACK_READY,\"targetMutated\":${'$'}UCLONE_TXN_TARGET_MUTATED,\"committed\":${'$'}UCLONE_TXN_COMMITTED,\"gateState\":\"${'$'}TXN_GATE_ESC\",\"switchMarkerManaged\":${'$'}UCLONE_TXN_MARKER_MANAGED,\"switchMarkerExisted\":${'$'}UCLONE_TXN_MARKER_EXISTED,\"switchMarkerValue\":\"${'$'}TXN_MARKER_ESC\",\"startedAt\":${'$'}UCLONE_TXN_STARTED_AT,\"updatedAt\":${'$'}UCLONE_TXN_UPDATED_AT}" > "${'$'}TXN_TMP" || return 1
          chmod 600 "${'$'}TXN_TMP" || return 1
          sync
          mv -f "${'$'}TXN_TMP" "${'$'}TXN_JSON" || return 1
          sync
          echo "UCLONE_TRANSACTION:stage=${'$'}UCLONE_TXN_STAGE rollbackReady=${'$'}UCLONE_TXN_ROLLBACK_READY targetMutated=${'$'}UCLONE_TXN_TARGET_MUTATED committed=${'$'}UCLONE_TXN_COMMITTED gate=${'$'}UCLONE_TXN_GATE_STATE"
        }
        uclone_transaction_init() {
          UCLONE_TXN_OPERATION="${'$'}1"
          UCLONE_TXN_SOURCE_USER="${'$'}2"
          UCLONE_TXN_TARGET_USER="${'$'}3"
          UCLONE_TXN_SELECTED_PARTS="${'$'}4"
          case "${'$'}UCLONE_REQUEST_ID" in ''|*[!A-Za-z0-9_.-]*) echo "ERR_TRANSACTION_REQUEST_ID:${'$'}UCLONE_REQUEST_ID" >&2; return 1 ;; esac
          case "${'$'}UCLONE_TXN_SELECTED_PARTS" in ''|*[!a-z_,]*) echo "ERR_TRANSACTION_SELECTED_PARTS:${'$'}UCLONE_TXN_SELECTED_PARTS" >&2; return 1 ;; esac
          for UCLONE_TXN_PART in ${'$'}(printf '%s' "${'$'}UCLONE_TXN_SELECTED_PARTS" | tr ',' ' '); do
            case "${'$'}UCLONE_TXN_PART" in ce|de|external|media|obb|permissions) ;; *) echo "ERR_TRANSACTION_SELECTED_PART:${'$'}UCLONE_TXN_PART" >&2; return 1 ;; esac
          done
          UCLONE_TXN_DIR="${'$'}ROOT/transactions/${'$'}UCLONE_REQUEST_ID"
          case "${'$'}UCLONE_TXN_DIR" in "${'$'}ROOT"/transactions/"${'$'}UCLONE_REQUEST_ID") ;; *) return 1 ;; esac
          mkdir -p "${'$'}UCLONE_TXN_DIR/gates" || return 1
          chmod 700 "${'$'}UCLONE_TXN_DIR" "${'$'}UCLONE_TXN_DIR/gates" || return 1
          UCLONE_TXN_STAGE=CREATED
          UCLONE_TXN_ROLLBACK=""
          UCLONE_TXN_ROLLBACK_NEXT=""
          UCLONE_TXN_ROLLBACK_READY=false
          UCLONE_TXN_TARGET_MUTATED=false
          UCLONE_TXN_COMMITTED=false
          UCLONE_TXN_GATE_STATE=NONE
          UCLONE_TXN_MARKER_MANAGED=false
          UCLONE_TXN_MARKER_EXISTED=false
          UCLONE_TXN_MARKER_VALUE=""
          UCLONE_TXN_MODIFIED_PARTS=""
          UCLONE_TXN_STARTED_AT=${'$'}(uclone_now_ms)
          UCLONE_TXN_ORIGIN_BOOT_ID="${'$'}{ACTIVE_BOOT_ID:-unknown}"
          UCLONE_TXN_ORIGIN_ROOT_PID=${'$'}${'$'}
          UCLONE_TXN_ORIGIN_PID_START_TICKS="${'$'}{ACTIVE_PID_START_TICKS:-unknown}"
          uclone_transaction_write
        }
        uclone_transaction_stage() {
          UCLONE_TXN_STAGE="${'$'}1"
          uclone_transaction_write
        }
        uclone_transaction_rollback_ready() {
          UCLONE_TXN_ROLLBACK="${'$'}1"
          UCLONE_TXN_ROLLBACK_READY=true
          UCLONE_TXN_STAGE=ROLLBACK_READY
          uclone_transaction_write
        }
        uclone_transaction_rollback_relocating() {
          UCLONE_TXN_ROLLBACK_NEXT="${'$'}1"
          UCLONE_TXN_STAGE=ROLLBACK_RELOCATING
          uclone_transaction_write
        }
        uclone_transaction_rollback_relocated() {
          UCLONE_TXN_ROLLBACK="${'$'}1"
          UCLONE_TXN_ROLLBACK_NEXT=""
          UCLONE_TXN_STAGE=ROLLBACK_READY
          uclone_transaction_write
        }
        uclone_transaction_switch_marker_before() {
          case "${'$'}1" in true|false) ;; *) return 1 ;; esac
          UCLONE_TXN_MARKER_MANAGED=true
          UCLONE_TXN_MARKER_EXISTED="${'$'}1"
          UCLONE_TXN_MARKER_VALUE="${'$'}2"
          uclone_transaction_write
        }
        uclone_transaction_target_mutating() {
          UCLONE_TXN_MUTATING_PART="${'$'}1"
          case "${'$'}UCLONE_TXN_MUTATING_PART" in ce|de|external|media|obb|permissions) ;; *) return 1 ;; esac
          UCLONE_TXN_MUTATION_CHANGED=0
          if [ "${'$'}UCLONE_TXN_TARGET_MUTATED" != "true" ]; then
            UCLONE_TXN_TARGET_MUTATED=true
            UCLONE_TXN_STAGE=TARGET_MUTATING
            UCLONE_TXN_MUTATION_CHANGED=1
          fi
          case ",${'$'}UCLONE_TXN_MODIFIED_PARTS," in
            *,"${'$'}UCLONE_TXN_MUTATING_PART",*) ;;
            *)
              if [ -n "${'$'}UCLONE_TXN_MODIFIED_PARTS" ]; then
                UCLONE_TXN_MODIFIED_PARTS="${'$'}UCLONE_TXN_MODIFIED_PARTS,${'$'}UCLONE_TXN_MUTATING_PART"
              else
                UCLONE_TXN_MODIFIED_PARTS="${'$'}UCLONE_TXN_MUTATING_PART"
              fi
              UCLONE_TXN_MUTATION_CHANGED=1
              ;;
          esac
          if [ "${'$'}UCLONE_TXN_MUTATION_CHANGED" = "1" ]; then
            uclone_transaction_write || return 1
          fi
        }
        uclone_transaction_commit_data() {
          UCLONE_TXN_COMMITTED=true
          UCLONE_TXN_STAGE=COMMITTED_GATE_HELD
          uclone_transaction_write
        }
        uclone_transaction_complete() {
          UCLONE_TXN_COMMITTED=true
          UCLONE_TXN_STAGE=COMMITTED
          UCLONE_TXN_GATE_STATE=RELEASED
          uclone_transaction_write
        }
        uclone_transaction_cleanup_complete() {
          UCLONE_TXN_STAGE=CLEANED
          uclone_transaction_write
        }
        uclone_transaction_rolled_back() {
          UCLONE_TXN_STAGE=ROLLED_BACK
          UCLONE_TXN_TARGET_MUTATED=false
          UCLONE_TXN_COMMITTED=false
          uclone_transaction_write
        }
        uclone_transaction_finish_rolled_back() {
          UCLONE_TXN_STAGE=ROLLED_BACK_COMPLETE
          UCLONE_TXN_GATE_STATE=RELEASED
          UCLONE_TXN_TARGET_MUTATED=false
          UCLONE_TXN_COMMITTED=false
          uclone_transaction_write
        }
        uclone_transaction_recovery_required() {
          UCLONE_TXN_STAGE=RECOVERY_REQUIRED
          uclone_transaction_write || true
          echo "RECOVERY_REQUIRED:request=${'$'}UCLONE_REQUEST_ID package=${'$'}PKG" >&2
        }
        uclone_package_user_line() {
          GATE_USER="${'$'}1"
          dumpsys package "${'$'}PKG" 2>/dev/null | awk -v user="${'$'}GATE_USER" '
            ${'$'}0 ~ "^[[:space:]]*User " user ":" { print; exit }
          '
        }
        uclone_gate_value() {
          GATE_FILE="${'$'}1"
          GATE_KEY="${'$'}2"
          awk -F= -v key="${'$'}GATE_KEY" '${'$'}1 == key { sub(/^[^=]*=/, ""); print; exit }' "${'$'}GATE_FILE" 2>/dev/null
        }
        uclone_app_process_exists() {
          GATE_UID="${'$'}1"
          for STATUS_FILE in /proc/[0-9]*/status; do
            [ -r "${'$'}STATUS_FILE" ] || continue
            STATUS_UID=${'$'}(awk '/^Uid:/ { print ${'$'}2; exit }' "${'$'}STATUS_FILE" 2>/dev/null)
            [ "${'$'}STATUS_UID" = "${'$'}GATE_UID" ] && return 0
          done
          for CMDLINE_FILE in /proc/[0-9]*/cmdline; do
            [ -r "${'$'}CMDLINE_FILE" ] || continue
            PROCESS_NAME=${'$'}(tr '\000' '\n' < "${'$'}CMDLINE_FILE" 2>/dev/null | sed -n '1p')
            case "${'$'}PROCESS_NAME" in "${'$'}PKG"|"${'$'}PKG":*) return 0 ;; esac
          done
          return 1
        }
        uclone_gate_restore_enabled() {
          RESTORE_USER="${'$'}1"
          RESTORE_ENABLED="${'$'}2"
          case "${'$'}RESTORE_ENABLED" in
            0) /system/bin/cmd package default-state --user "${'$'}RESTORE_USER" "${'$'}PKG" >/dev/null 2>&1 ;;
            1) /system/bin/cmd package enable --user "${'$'}RESTORE_USER" "${'$'}PKG" >/dev/null 2>&1 ;;
            2) /system/bin/cmd package disable --user "${'$'}RESTORE_USER" "${'$'}PKG" >/dev/null 2>&1 ;;
            3) /system/bin/cmd package disable-user --user "${'$'}RESTORE_USER" "${'$'}PKG" >/dev/null 2>&1 ;;
            4) /system/bin/cmd package disable-until-used --user "${'$'}RESTORE_USER" "${'$'}PKG" >/dev/null 2>&1 ;;
            *) return 1 ;;
          esac
        }
        uclone_gate_restore_suspended() {
          RESTORE_USER="${'$'}1"
          RESTORE_SUSPENDED="${'$'}2"
          case "${'$'}RESTORE_SUSPENDED" in
            true) /system/bin/cmd package suspend --user "${'$'}RESTORE_USER" "${'$'}PKG" >/dev/null 2>&1 ;;
            false) /system/bin/cmd package unsuspend --user "${'$'}RESTORE_USER" "${'$'}PKG" >/dev/null 2>&1 ;;
            *) return 1 ;;
          esac
        }
        uclone_gate_can_restore_stopped_state() {
          case "${'$'}(/system/bin/cmd package help 2>&1)" in
            *"unstop [--user USER_ID] PACKAGE"*) return 0 ;;
            *) return 1 ;;
          esac
        }
        uclone_gate_abort_acquire() {
          ABORT_GATE_DIR="${'$'}1"
          if uclone_gate_release "${'$'}ABORT_GATE_DIR"; then
            UCLONE_GATE_DIR=""
            return 0
          fi
          echo "ERR_GATE_ACQUIRE_ROLLBACK:${'$'}ABORT_GATE_DIR" >&2
          uclone_transaction_recovery_required
          return 1
        }
        uclone_gate_is_critical_package() {
          CRITICAL_USER="${'$'}1"
          case "${'$'}PKG" in
            com.android.systemui|com.android.settings|com.miui.home|com.android.launcher*|com.google.android.inputmethod.*|com.android.phone) return 0 ;;
          esac
          if /system/bin/cmd package list packages -s --user "${'$'}CRITICAL_USER" 2>/dev/null |
            grep -Fqx "package:${'$'}PKG"; then
            return 0
          fi
          DEFAULT_IME=${'$'}(/system/bin/settings --user "${'$'}CRITICAL_USER" get secure default_input_method 2>/dev/null || true)
          DEFAULT_IME_PACKAGE=${'$'}(printf '%s\n' "${'$'}DEFAULT_IME" | cut -d/ -f1)
          [ -z "${'$'}DEFAULT_IME_PACKAGE" ] || [ "${'$'}DEFAULT_IME_PACKAGE" = "null" ] ||
            [ "${'$'}DEFAULT_IME_PACKAGE" != "${'$'}PKG" ] || return 0
          HOME_COMPONENT=${'$'}(/system/bin/cmd package resolve-activity --brief --user "${'$'}CRITICAL_USER" \
            -a android.intent.action.MAIN -c android.intent.category.HOME 2>/dev/null | tail -1)
          HOME_PACKAGE=${'$'}(printf '%s\n' "${'$'}HOME_COMPONENT" | cut -d/ -f1)
          [ -z "${'$'}HOME_PACKAGE" ] || [ "${'$'}HOME_PACKAGE" != "${'$'}PKG" ] || return 0
          return 1
        }
        uclone_gate_acquire() {
          GATE_ROLE="${'$'}1"
          GATE_USER="${'$'}2"
          GATE_UID="${'$'}3"
          case "${'$'}GATE_ROLE" in source|target) ;; *) return 1 ;; esac
          if uclone_gate_is_critical_package "${'$'}GATE_USER"; then
            echo "ERR_GATE_CRITICAL_PACKAGE:${'$'}PKG:user=${'$'}GATE_USER" >&2
            return 1
          fi
          SHARED_UID_PACKAGES=${'$'}(/system/bin/cmd package list packages -U --user "${'$'}GATE_USER" 2>/dev/null | awk -v uid="uid:${'$'}GATE_UID" '${'$'}2 == uid { sub(/^package:/, "", ${'$'}1); print ${'$'}1 }')
          SHARED_UID_COUNT=${'$'}(printf '%s\n' "${'$'}SHARED_UID_PACKAGES" | awk 'NF { count++ } END { print count + 0 }')
          [ "${'$'}SHARED_UID_COUNT" = "1" ] && [ "${'$'}SHARED_UID_PACKAGES" = "${'$'}PKG" ] || {
            echo "ERR_GATE_SHARED_UID:user=${'$'}GATE_USER uid=${'$'}GATE_UID packages=${'$'}SHARED_UID_PACKAGES" >&2
            return 1
          }
          GATE_USER_LINE=${'$'}(uclone_package_user_line "${'$'}GATE_USER")
          [ -n "${'$'}GATE_USER_LINE" ] || { echo "ERR_GATE_USER_STATE_MISSING:${'$'}GATE_USER" >&2; return 1; }
          GATE_ENABLED=${'$'}(printf '%s\n' "${'$'}GATE_USER_LINE" | sed -n 's/.* enabled=\([0-4]\).*/\1/p')
          GATE_SUSPENDED=${'$'}(printf '%s\n' "${'$'}GATE_USER_LINE" | sed -n 's/.* suspended=\([^ ]*\).*/\1/p')
          GATE_STOPPED=${'$'}(printf '%s\n' "${'$'}GATE_USER_LINE" | sed -n 's/.* stopped=\([^ ]*\).*/\1/p')
          case "${'$'}GATE_ENABLED" in 0|1|2|3|4) ;; *) echo "ERR_GATE_ENABLED_STATE:${'$'}GATE_ENABLED" >&2; return 1 ;; esac
          case "${'$'}GATE_SUSPENDED" in true|false) ;; *) echo "ERR_GATE_SUSPENDED_STATE:${'$'}GATE_SUSPENDED" >&2; return 1 ;; esac
          case "${'$'}GATE_STOPPED" in true|false) ;; *) echo "ERR_GATE_STOPPED_STATE:${'$'}GATE_STOPPED" >&2; return 1 ;; esac
          if [ "${'$'}GATE_STOPPED" = "false" ] && ! uclone_gate_can_restore_stopped_state; then
            echo "ERR_GATE_UNSTOP_UNSUPPORTED:user=${'$'}GATE_USER" >&2
            return 1
          fi
          GATE_DIR="${'$'}UCLONE_TXN_DIR/gates/${'$'}{GATE_ROLE}_${'$'}GATE_USER"
          mkdir -p "${'$'}GATE_DIR" || return 1
          GATE_STATE="${'$'}GATE_DIR/gate.state"
          GATE_TMP="${'$'}GATE_STATE.tmp.${'$'}${'$'}"
          {
            echo "role=${'$'}GATE_ROLE"
            echo "userId=${'$'}GATE_USER"
            echo "uid=${'$'}GATE_UID"
            echo "packageName=${'$'}PKG"
            echo "previousEnabled=${'$'}GATE_ENABLED"
            echo "previousSuspended=${'$'}GATE_SUSPENDED"
            echo "previousStopped=${'$'}GATE_STOPPED"
            echo "state=PREPARED"
          } > "${'$'}GATE_TMP" || return 1
          chmod 600 "${'$'}GATE_TMP" || return 1
          sync
          mv -f "${'$'}GATE_TMP" "${'$'}GATE_STATE" || return 1
          UCLONE_GATE_DIR="${'$'}GATE_DIR"
          /system/bin/cmd package disable-user --user "${'$'}GATE_USER" "${'$'}PKG" >/dev/null 2>&1 || {
            uclone_gate_abort_acquire "${'$'}GATE_DIR" || true
            return 1
          }
          GATE_WAIT=0
          while [ "${'$'}GATE_WAIT" -lt 20 ]; do
            CURRENT_LINE=${'$'}(uclone_package_user_line "${'$'}GATE_USER")
            CURRENT_ENABLED=${'$'}(printf '%s\n' "${'$'}CURRENT_LINE" | sed -n 's/.* enabled=\([0-4]\).*/\1/p')
            [ "${'$'}CURRENT_ENABLED" = "3" ] && break
            sleep 0.05
            GATE_WAIT=${'$'}((GATE_WAIT + 1))
          done
          [ "${'$'}CURRENT_ENABLED" = "3" ] || {
            uclone_gate_abort_acquire "${'$'}GATE_DIR" || true
            return 1
          }
          /system/bin/am force-stop --user "${'$'}GATE_USER" "${'$'}PKG" >/dev/null 2>&1 || {
            uclone_gate_abort_acquire "${'$'}GATE_DIR" || true
            return 1
          }
          PROCESS_WAIT=0
          while uclone_app_process_exists "${'$'}GATE_UID" && [ "${'$'}PROCESS_WAIT" -lt 20 ]; do
            /system/bin/am force-stop --user "${'$'}GATE_USER" "${'$'}PKG" >/dev/null 2>&1 || true
            sleep 0.05
            PROCESS_WAIT=${'$'}((PROCESS_WAIT + 1))
          done
          if uclone_app_process_exists "${'$'}GATE_UID"; then
            echo "ERR_GATE_PROCESS_STILL_RUNNING:user=${'$'}GATE_USER uid=${'$'}GATE_UID" >&2
            uclone_gate_abort_acquire "${'$'}GATE_DIR" || true
            return 1
          fi
          sed 's/^state=.*/state=HELD/' "${'$'}GATE_STATE" > "${'$'}GATE_TMP" || {
            uclone_gate_abort_acquire "${'$'}GATE_DIR" || true
            return 1
          }
          chmod 600 "${'$'}GATE_TMP" || {
            uclone_gate_abort_acquire "${'$'}GATE_DIR" || true
            return 1
          }
          sync
          mv -f "${'$'}GATE_TMP" "${'$'}GATE_STATE" || {
            uclone_gate_abort_acquire "${'$'}GATE_DIR" || true
            return 1
          }
          case "${'$'}GATE_ROLE" in
            source) UCLONE_TXN_GATE_STATE=SOURCE_HELD ;;
            target) UCLONE_TXN_GATE_STATE=TARGET_HELD ;;
          esac
          uclone_transaction_write || {
            uclone_gate_abort_acquire "${'$'}GATE_DIR" || true
            return 1
          }
          echo "APP_GATE_HELD:role=${'$'}GATE_ROLE user=${'$'}GATE_USER uid=${'$'}GATE_UID previousEnabled=${'$'}GATE_ENABLED suspended=${'$'}GATE_SUSPENDED stopped=${'$'}GATE_STOPPED"
        }
        uclone_gate_release() {
          RELEASE_DIR="${'$'}1"
          RELEASE_FILE="${'$'}RELEASE_DIR/gate.state"
          [ -f "${'$'}RELEASE_FILE" ] || return 1
          RELEASE_USER=${'$'}(uclone_gate_value "${'$'}RELEASE_FILE" userId)
          RELEASE_UID=${'$'}(uclone_gate_value "${'$'}RELEASE_FILE" uid)
          RELEASE_PACKAGE=${'$'}(uclone_gate_value "${'$'}RELEASE_FILE" packageName)
          RELEASE_ENABLED=${'$'}(uclone_gate_value "${'$'}RELEASE_FILE" previousEnabled)
          RELEASE_SUSPENDED=${'$'}(uclone_gate_value "${'$'}RELEASE_FILE" previousSuspended)
          RELEASE_STOPPED=${'$'}(uclone_gate_value "${'$'}RELEASE_FILE" previousStopped)
          case "${'$'}RELEASE_USER:${'$'}RELEASE_UID" in *[!0-9:]*) return 1 ;; esac
          [ "${'$'}RELEASE_PACKAGE" = "${'$'}PKG" ] || return 1
          case "${'$'}RELEASE_ENABLED" in 0|1|2|3|4) ;; *) return 1 ;; esac
          case "${'$'}RELEASE_SUSPENDED:${'$'}RELEASE_STOPPED" in true:true|true:false|false:true|false:false) ;; *) return 1 ;; esac
          RELEASE_LINE=${'$'}(uclone_package_user_line "${'$'}RELEASE_USER")
          RELEASE_CURRENT_SUSPENDED=${'$'}(printf '%s\n' "${'$'}RELEASE_LINE" | sed -n 's/.* suspended=\([^ ]*\).*/\1/p')
          if [ "${'$'}RELEASE_CURRENT_SUSPENDED" != "${'$'}RELEASE_SUSPENDED" ]; then
            uclone_gate_restore_suspended "${'$'}RELEASE_USER" "${'$'}RELEASE_SUSPENDED" || return 1
          fi
          case "${'$'}RELEASE_STOPPED" in
            false) /system/bin/cmd package unstop --user "${'$'}RELEASE_USER" "${'$'}PKG" >/dev/null 2>&1 || return 1 ;;
            true) /system/bin/am force-stop --user "${'$'}RELEASE_USER" "${'$'}PKG" >/dev/null 2>&1 || return 1 ;;
          esac
          uclone_gate_restore_enabled "${'$'}RELEASE_USER" "${'$'}RELEASE_ENABLED" || return 1
          RELEASE_WAIT=0
          while [ "${'$'}RELEASE_WAIT" -lt 20 ]; do
            RELEASE_LINE=${'$'}(uclone_package_user_line "${'$'}RELEASE_USER")
            RELEASE_CURRENT=${'$'}(printf '%s\n' "${'$'}RELEASE_LINE" | sed -n 's/.* enabled=\([0-4]\).*/\1/p')
            RELEASE_CURRENT_SUSPENDED=${'$'}(printf '%s\n' "${'$'}RELEASE_LINE" | sed -n 's/.* suspended=\([^ ]*\).*/\1/p')
            RELEASE_CURRENT_STOPPED=${'$'}(printf '%s\n' "${'$'}RELEASE_LINE" | sed -n 's/.* stopped=\([^ ]*\).*/\1/p')
            [ "${'$'}RELEASE_CURRENT" = "${'$'}RELEASE_ENABLED" ] &&
              [ "${'$'}RELEASE_CURRENT_SUSPENDED" = "${'$'}RELEASE_SUSPENDED" ] &&
              [ "${'$'}RELEASE_CURRENT_STOPPED" = "${'$'}RELEASE_STOPPED" ] && break
            sleep 0.05
            RELEASE_WAIT=${'$'}((RELEASE_WAIT + 1))
          done
          [ "${'$'}RELEASE_CURRENT" = "${'$'}RELEASE_ENABLED" ] &&
            [ "${'$'}RELEASE_CURRENT_SUSPENDED" = "${'$'}RELEASE_SUSPENDED" ] &&
            [ "${'$'}RELEASE_CURRENT_STOPPED" = "${'$'}RELEASE_STOPPED" ] || return 1
          RELEASE_TMP="${'$'}RELEASE_FILE.tmp.${'$'}${'$'}"
          sed 's/^state=.*/state=RELEASED/' "${'$'}RELEASE_FILE" > "${'$'}RELEASE_TMP" || return 1
          chmod 600 "${'$'}RELEASE_TMP" || return 1
          sync
          mv -f "${'$'}RELEASE_TMP" "${'$'}RELEASE_FILE" || return 1
          UCLONE_TXN_GATE_STATE=RELEASED
          uclone_transaction_write || return 1
          echo "APP_GATE_RELEASED:user=${'$'}RELEASE_USER restoredEnabled=${'$'}RELEASE_ENABLED restoredSuspended=${'$'}RELEASE_SUSPENDED restoredStopped=${'$'}RELEASE_STOPPED"
        }
        uclone_release_pre_mutation_gates() {
          RELEASE_SAFE_FAILED=0
          if [ -n "${'$'}{SOURCE_GATE_DIR:-}" ]; then
            if uclone_gate_release "${'$'}SOURCE_GATE_DIR"; then
              SOURCE_GATE_DIR=""
            else
              RELEASE_SAFE_FAILED=1
            fi
          fi
          if [ "${'$'}{TARGET_MUTATED:-0}" != "1" ] && [ -n "${'$'}{TARGET_GATE_DIR:-}" ]; then
            if uclone_gate_release "${'$'}TARGET_GATE_DIR"; then
              TARGET_GATE_DIR=""
            else
              RELEASE_SAFE_FAILED=1
            fi
          fi
          if [ "${'$'}RELEASE_SAFE_FAILED" = "1" ]; then
            uclone_transaction_recovery_required
            return 1
          fi
          if [ "${'$'}{UCLONE_TXN_COMMITTED:-false}" = "false" ] && [ "${'$'}{UCLONE_TXN_TARGET_MUTATED:-false}" = "false" ]; then
            UCLONE_TXN_STAGE=ABORTED
            UCLONE_TXN_GATE_STATE=RELEASED
            uclone_transaction_write || return 1
          fi
          return 0
        }
        uclone_cancel_checkpoint() {
          CANCEL_STAGE="${'$'}1"
          [ -n "${'$'}{UCLONE_TXN_DIR:-}" ] || return 0
          [ ! -f "${'$'}UCLONE_TXN_DIR/cancel.requested" ] || {
            echo "UCLONE_CANCEL_ACCEPTED:stage=${'$'}CANCEL_STAGE"
            return 2
          }
          return 0
        }
    """.trimIndent()
}
