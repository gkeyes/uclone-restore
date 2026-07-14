package com.uclone.restore.sync

import com.uclone.restore.model.AppRule
import com.uclone.restore.model.CloneReturnPlan
import com.uclone.restore.model.SwitchSafetyMode
import com.uclone.restore.model.UCloneSettings
import com.uclone.restore.model.cloneReturnPlan
import com.uclone.restore.root.shellQuote

object ShellScripts {
    private val rollbackIdPattern = Regex("[A-Za-z0-9_.-]+")
    private enum class RestoreSourceKind {
        ACTIVE,
        ROLLBACK,
        SWITCH_TEMP,
        CLONE_ROLLBACK,
    }

    private enum class RollbackProtection {
        FRESH,
        EXISTING,
        NONE,
    }

    internal fun metricsScript(): String = """
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
          UCLONE_STAGE_STARTED_AT=${'$'}(uclone_now_ms)
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
        uclone_record_temp_kb() {
          UCLONE_TEMP_KB="${'$'}1"
          case "${'$'}UCLONE_TEMP_KB" in ''|*[!0-9]*) return 0 ;; esac
          UCLONE_TEMP_BYTES=${'$'}(awk -v KB="${'$'}UCLONE_TEMP_KB" 'BEGIN { printf "%.0f\n", KB * 1024 }')
          case "${'$'}UCLONE_TEMP_BYTES" in ''|*[!0-9]*) return 0 ;; esac
          awk -v CURRENT="${'$'}UCLONE_TEMP_BYTES" -v PEAK="${'$'}UCLONE_PEAK_TEMPORARY_BYTES" 'BEGIN { exit !(CURRENT > PEAK) }' && UCLONE_PEAK_TEMPORARY_BYTES="${'$'}UCLONE_TEMP_BYTES"
        }
        uclone_record_temp_path() {
          [ -d "${'$'}1" ] || return 0
          UCLONE_TEMP_KB=${'$'}(du -sk "${'$'}1" 2>/dev/null | awk '{print ${'$'}1}')
          uclone_record_temp_kb "${'$'}UCLONE_TEMP_KB"
        }
        uclone_elapsed_ms() {
          awk -v FINISHED_AT="${'$'}1" -v STARTED_AT="${'$'}2" 'BEGIN { VALUE = FINISHED_AT - STARTED_AT; if (VALUE < 0) VALUE = 0; printf "%.0f\n", VALUE }'
        }
        uclone_perf_emit() {
          UCLONE_PERF_PHASE="${'$'}1"
          UCLONE_PERF_PART="${'$'}2"
          UCLONE_PERF_STARTED_AT="${'$'}3"
          UCLONE_PERF_FINISHED_AT=${'$'}(uclone_now_ms)
          UCLONE_PERF_DURATION_MS=${'$'}(uclone_elapsed_ms "${'$'}UCLONE_PERF_FINISHED_AT" "${'$'}UCLONE_PERF_STARTED_AT")
          echo "UCLONE_PERF:phase=${'$'}UCLONE_PERF_PHASE part=${'$'}UCLONE_PERF_PART started_at=${'$'}UCLONE_PERF_STARTED_AT finished_at=${'$'}UCLONE_PERF_FINISHED_AT duration_ms=${'$'}UCLONE_PERF_DURATION_MS"
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
        uclone_decimal_max() {
          LEFT="${'$'}1"
          RIGHT="${'$'}2"
          case "${'$'}LEFT:${'$'}RIGHT" in *[!0-9:]*) return 1 ;; esac
          [ "${'$'}{#LEFT}" -le 15 ] && [ "${'$'}{#RIGHT}" -le 15 ] || return 1
          awk -v LEFT="${'$'}LEFT" -v RIGHT="${'$'}RIGHT" 'BEGIN { printf "%.0f\n", (LEFT > RIGHT ? LEFT : RIGHT) }'
        }
        uclone_dir_kb() {
          [ -d "${'$'}1" ] || { echo 0; return 0; }
          VALUE_KB=${'$'}(du -sk "${'$'}1" 2>/dev/null | awk '{print ${'$'}1}')
          case "${'$'}VALUE_KB" in ''|*[!0-9]*) echo 0 ;; *) echo "${'$'}VALUE_KB" ;; esac
        }
        uclone_dir_kb_strict() {
          [ -d "${'$'}1" ] || { echo 0; return 0; }
          [ ! -L "${'$'}1" ] || return 1
          VALUE_DU_OUTPUT=${'$'}(du -sk "${'$'}1" 2>/dev/null) || return 1
          VALUE_KB=${'$'}(printf '%s\n' "${'$'}VALUE_DU_OUTPUT" | awk 'NR == 1 { print ${'$'}1 } END { if (NR != 1) exit 1 }') || return 1
          case "${'$'}VALUE_KB" in ''|*[!0-9]*) return 1 ;; *) echo "${'$'}VALUE_KB" ;; esac
        }
        uclone_live_source_dir_kb_strict() {
          if [ -L "${'$'}1" ]; then
            VALUE_REAL_PATH=${'$'}(readlink -f "${'$'}1" 2>/dev/null) || return 1
            [ -n "${'$'}VALUE_REAL_PATH" ] && [ -d "${'$'}VALUE_REAL_PATH" ] && [ ! -L "${'$'}VALUE_REAL_PATH" ] || return 1
            uclone_dir_kb_strict "${'$'}VALUE_REAL_PATH"
            return ${'$'}?
          fi
          uclone_dir_kb_strict "${'$'}1"
        }
        uclone_validate_direct_source() {
          VALUE_ROOT="${'$'}1"
          VALUE_MODE="${'$'}2"
          shift 2
          [ -d "${'$'}VALUE_ROOT" ] && [ ! -L "${'$'}VALUE_ROOT" ] || return 1
          case "${'$'}VALUE_MODE" in
            live|managed) ;;
            *) return 1 ;;
          esac
          for VALUE_PART in "${'$'}@"; do
            case "${'$'}VALUE_PART" in ce|de|external|media|obb) ;; *) return 1 ;; esac
            VALUE_PART_PATH="${'$'}VALUE_ROOT/${'$'}VALUE_PART"
            if [ -e "${'$'}VALUE_PART_PATH" ] || [ -L "${'$'}VALUE_PART_PATH" ]; then
              [ -d "${'$'}VALUE_PART_PATH" ] || return 1
              [ "${'$'}VALUE_MODE" = "live" ] || [ ! -L "${'$'}VALUE_PART_PATH" ] || return 1
            fi
          done
        }
        uclone_read_size_hint() {
          VALUE_ROOT="${'$'}1"
          VALUE_PART="${'$'}2"
          case "${'$'}VALUE_PART" in ce|de|external|media|obb) ;; *) return 1 ;; esac
          [ -d "${'$'}VALUE_ROOT" ] && [ ! -L "${'$'}VALUE_ROOT" ] || return 1
          VALUE_HINT_DIR="${'$'}VALUE_ROOT/.size_kb"
          [ -d "${'$'}VALUE_HINT_DIR" ] && [ ! -L "${'$'}VALUE_HINT_DIR" ] || return 1
          VALUE_HINT="${'$'}VALUE_HINT_DIR/${'$'}VALUE_PART"
          [ -f "${'$'}VALUE_HINT" ] && [ ! -L "${'$'}VALUE_HINT" ] || return 1
          VALUE_HINT_LINES=${'$'}(awk 'END { print NR + 0 }' "${'$'}VALUE_HINT" 2>/dev/null) || return 1
          [ "${'$'}VALUE_HINT_LINES" -eq 1 ] 2>/dev/null || return 1
          VALUE_KB=${'$'}(sed -n '1p' "${'$'}VALUE_HINT" 2>/dev/null) || return 1
          case "${'$'}VALUE_KB" in ''|*[!0-9]*) return 1 ;; esac
          [ "${'$'}{#VALUE_KB}" -le 15 ] || return 1
          [ "${'$'}VALUE_KB" -gt 0 ] 2>/dev/null || return 1
          echo "${'$'}VALUE_KB"
        }
        uclone_write_size_hint() {
          VALUE_ROOT="${'$'}1"
          VALUE_PART="${'$'}2"
          VALUE_KB="${'$'}3"
          case "${'$'}VALUE_PART" in ce|de|external|media|obb) ;; *) return 1 ;; esac
          case "${'$'}VALUE_KB" in ''|*[!0-9]*) return 1 ;; esac
          [ "${'$'}{#VALUE_KB}" -le 15 ] || return 1
          [ "${'$'}VALUE_KB" -gt 0 ] 2>/dev/null || return 1
          [ -d "${'$'}VALUE_ROOT" ] && [ ! -L "${'$'}VALUE_ROOT" ] || return 1
          VALUE_HINT_DIR="${'$'}VALUE_ROOT/.size_kb"
          if [ -e "${'$'}VALUE_HINT_DIR" ] || [ -L "${'$'}VALUE_HINT_DIR" ]; then
            [ -d "${'$'}VALUE_HINT_DIR" ] && [ ! -L "${'$'}VALUE_HINT_DIR" ] || return 1
          else
            mkdir "${'$'}VALUE_HINT_DIR" || return 1
          fi
          VALUE_HINT="${'$'}VALUE_HINT_DIR/${'$'}VALUE_PART"
          [ ! -L "${'$'}VALUE_HINT" ] || return 1
          VALUE_HINT_TMP="${'$'}{VALUE_HINT}.tmp_${'$'}${'$'}"
          printf '%s\n' "${'$'}VALUE_KB" > "${'$'}VALUE_HINT_TMP" || return 1
          chmod 600 "${'$'}VALUE_HINT_TMP" >/dev/null 2>&1 || true
          mv -f "${'$'}VALUE_HINT_TMP" "${'$'}VALUE_HINT" || return 1
        }
        uclone_add_dir_kb() {
          VALUE_KB=${'$'}(uclone_dir_kb "${'$'}1")
          NEXT_ESTIMATED_KB=${'$'}(uclone_decimal_add "${'$'}UCLONE_ESTIMATED_KB" "${'$'}VALUE_KB") || NEXT_ESTIMATED_KB=""
          case "${'$'}NEXT_ESTIMATED_KB" in ''|*[!0-9]*) UCLONE_ESTIMATED_KB=""; return 1 ;; esac
          UCLONE_ESTIMATED_KB="${'$'}NEXT_ESTIMATED_KB"
        }
        uclone_add_dir_kb_strict() {
          VALUE_KB=${'$'}(uclone_dir_kb_strict "${'$'}1") || return 1
          NEXT_ESTIMATED_KB=${'$'}(uclone_decimal_add "${'$'}UCLONE_ESTIMATED_KB" "${'$'}VALUE_KB") || NEXT_ESTIMATED_KB=""
          case "${'$'}NEXT_ESTIMATED_KB" in ''|*[!0-9]*) UCLONE_ESTIMATED_KB=""; return 1 ;; esac
          UCLONE_ESTIMATED_KB="${'$'}NEXT_ESTIMATED_KB"
        }
        uclone_add_live_source_dir_kb_strict() {
          VALUE_KB=${'$'}(uclone_live_source_dir_kb_strict "${'$'}1") || return 1
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

    internal fun ensureCloneCeReadyScript(
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
            clone_state() {
              /system/bin/am get-started-user-state "${'$'}CLONE_USER" 2>&1 || true
            }
            wait_for_clone_state() {
              WAIT_LABEL="${'$'}1"
              WAIT_LIMIT="${'$'}2"
              WAIT_INDEX=0
              while [ "${'$'}WAIT_INDEX" -lt "${'$'}WAIT_LIMIT" ]; do
                WAIT_STATE=${'$'}(clone_state)
                echo "${'$'}{WAIT_LABEL}_${'$'}{WAIT_INDEX}=${'$'}WAIT_STATE"
                case "${'$'}WAIT_STATE" in
                  *RUNNING_UNLOCKED*) return 0 ;;
                esac
                sleep 0.25
                WAIT_INDEX=${'$'}((WAIT_INDEX + 1))
              done
              return 1
            }
            ${cloneCleanupFunction(stopAfterTask)}
            cleanup_on_exit() {
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
                    STATE_AFTER_START=${'$'}(clone_state)
                    echo "STATE_AFTER_START=${'$'}STATE_AFTER_START"
                    ;;
                esac
                STATE_BEFORE_VERIFY=${'$'}(clone_state)
                echo "STATE_BEFORE_VERIFY=${'$'}STATE_BEFORE_VERIFY"
                case "${'$'}STATE_BEFORE_VERIFY" in
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
                    wait_for_clone_state "WAIT_AFTER_VERIFY" 120 || {
                      STATE_AFTER_VERIFY_WAIT=${'$'}(clone_state)
                      echo "STATE_AFTER_VERIFY_WAIT=${'$'}STATE_AFTER_VERIFY_WAIT"
                      echo "ERR_CLONE_UNLOCK_TIMEOUT:${'$'}STATE_AFTER_VERIFY_WAIT" >&2
                      exit 85
                    }
                    echo "STATE_AFTER_VERIFY=${'$'}(clone_state)"
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
              *RUNNING*)
                START_CLONE_READY=1
                echo "START_CLONE_CONFIRMED=${'$'}START_STATE_BEFORE_REQUEST"
                echo "START_CLONE_OWNERSHIP=preexisting"
                ;;
              *)
                echo "START_USER_BEGIN"
                START_USER_EXIT=0
                START_USER_OUTPUT=${'$'}("${'$'}START_AM_COMMAND" start-user "${'$'}CLONE_USER" 2>&1) || START_USER_EXIT=${'$'}?
                echo "START_USER_EXIT=${'$'}START_USER_EXIT"
                echo "START_USER_OUTPUT=${'$'}START_USER_OUTPUT"
                START_WAIT_INDEX=0
                while [ "${'$'}START_WAIT_INDEX" -lt $startPollLimit ]; do
                  START_WAIT_STATE=${'$'}(clone_state)
                  echo "WAIT_AFTER_START_${'$'}START_WAIT_INDEX=${'$'}START_WAIT_STATE"
                  case "${'$'}START_WAIT_STATE" in
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
              echo "STATE_BEFORE_STOP=${'$'}(clone_state)"
              STOP_USER_EXIT=0
              STOP_USER_OUTPUT=${'$'}(/system/bin/am stop-user "${'$'}CLONE_USER" 2>&1) || STOP_USER_EXIT=${'$'}?
              echo "STOP_USER_EXIT=${'$'}STOP_USER_EXIT"
              echo "STOP_USER_OUTPUT=${'$'}STOP_USER_OUTPUT"
              if [ "${'$'}STOP_USER_EXIT" -ne 0 ]; then
                echo "WARN_STOP_CLONE_REQUEST_FAILED:${'$'}STOP_USER_EXIT:${'$'}(clone_state)"
                return 0
              fi
              STOP_WAIT_INDEX=0
              while [ "${'$'}STOP_WAIT_INDEX" -lt 20 ]; do
                STOP_WAIT_STATE=${'$'}(clone_state)
                echo "WAIT_AFTER_STOP_${'$'}STOP_WAIT_INDEX=${'$'}STOP_WAIT_STATE"
                case "${'$'}STOP_WAIT_STATE" in
                  *"User is not started"*|*"not started"*|*SHUTDOWN*)
                    echo "STOP_CLONE_CONFIRMED=1"
                    CLONE_STOPPED_AFTER_TASK=1
                    return 0
                    ;;
                esac
                sleep 0.25
                STOP_WAIT_INDEX=${'$'}((STOP_WAIT_INDEX + 1))
              done
              echo "WARN_STOP_CLONE_PENDING:${'$'}(clone_state)"
            }
        """.trimIndent()
    }

    fun capture(packageName: String, rule: AppRule, settings: UCloneSettings, appPackage: String): String = """
        set -u
        ROOT=${shellQuote(settings.rootDir)}
        PKG=${shellQuote(packageName)}
        APP_PKG=${shellQuote(appPackage)}
        CONFIG_SRC_USER=${settings.cloneUserId}
        TS=${'$'}(date +%Y%m%d-%H%M%S)
        BASE="${'$'}ROOT/snapshots/${'$'}PKG"
        TMP="${'$'}ROOT/tmp/capture_${'$'}{PKG}_${'$'}TS"
        ${metricsScript()}
        ${storagePreflightScript()}
        ${PermissionStateShell.functions()}
        uclone_stage_begin PRECHECK
        [ "${'$'}PKG" != "${'$'}APP_PKG" ] || { echo "ERR_SELF_SYNC"; exit 41; }
        mkdir -p "${'$'}ROOT/snapshots" "${'$'}ROOT/rollback" "${'$'}ROOT/logs" "${'$'}ROOT/tmp" "${'$'}ROOT/config" "${'$'}BASE/history" || exit 10
        rm -rf "${'$'}TMP" "${'$'}TMP".try_*
        uclone_stage_end
        uclone_stage_begin SOURCE_PREPARE
        CAPTURE_REQUIRE_CE=${if (rule.includeCe) "1" else "0"}
        ${ensureCloneCeReadyScript(settings, rule.includeCe, settings.autoUnlockClone, settings.stopCloneAfterTask)}
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
        count_items() {
          find "${'$'}1" -mindepth 1 2>/dev/null | wc -l | tr -d ' '
        }
        copy_dir_stream() {
          SRC="${'$'}1"
          DST="${'$'}2"
          SRC_ITEMS=${'$'}(count_items "${'$'}SRC")
          UCLONE_SCANNED_FILES=${'$'}((UCLONE_SCANNED_FILES + SRC_ITEMS))
          [ "${'$'}SRC_ITEMS" -gt 0 ] || { echo "SKIP_EMPTY:${'$'}SRC"; return 0; }
          rm -rf "${'$'}DST.tmp"
          mkdir -p "${'$'}DST.tmp" || exit 12
          (cd "${'$'}SRC" && tar -cpf - .) | (cd "${'$'}DST.tmp" && tar -xopf -) || exit 13
          rm -rf "${'$'}DST"
          mv "${'$'}DST.tmp" "${'$'}DST" || exit 14
          ${if (rule.excludeCache) "rm -rf \"${'$'}DST/cache\" \"${'$'}DST/code_cache\" 2>/dev/null || true" else ":"}
          DST_ITEMS=${'$'}(count_items "${'$'}DST")
          [ "${'$'}DST_ITEMS" -gt 0 ] || { echo "ERR_COPY_EMPTY:${'$'}SRC" >&2; exit 17; }
          PART_SIZE_KB=${'$'}(uclone_dir_kb_strict "${'$'}DST") || exit 18
          uclone_write_size_hint "${'$'}TRY_TMP" "${'$'}(basename "${'$'}DST")" "${'$'}PART_SIZE_KB" || exit 18
          COPIED_PARTS=${'$'}((COPIED_PARTS + 1))
          COPIED_ITEMS=${'$'}((COPIED_ITEMS + DST_ITEMS))
          UCLONE_COPIED_FILES=${'$'}((UCLONE_COPIED_FILES + DST_ITEMS))
          uclone_add_written_kb "${'$'}PART_SIZE_KB"
          uclone_record_temp_path "${'$'}DST"
          echo "COPIED:${'$'}SRC ITEMS=${'$'}DST_ITEMS SIZE_KB=${'$'}PART_SIZE_KB"
        }
        copy_first_nonempty() {
          DST="${'$'}1"
          shift
          for SRC in "${'$'}@"; do
            [ -n "${'$'}SRC" ] || continue
            if [ -d "${'$'}SRC" ]; then
              SRC_ITEMS=${'$'}(count_items "${'$'}SRC")
              echo "PROBE_PATH:${'$'}SRC ITEMS=${'$'}SRC_ITEMS"
              if [ "${'$'}SRC_ITEMS" -gt 0 ]; then
                copy_dir_stream "${'$'}SRC" "${'$'}DST"
                return 0
              fi
              echo "SKIP_EMPTY:${'$'}SRC"
            else
              echo "SKIP_MISSING:${'$'}SRC"
            fi
          done
          return 0
        }
        try_user() {
          TRY_USER="${'$'}1"
          TRY_TMP="${'$'}TMP.try_${'$'}TRY_USER"
          rm -rf "${'$'}TRY_TMP"
          mkdir -p "${'$'}TRY_TMP" || exit 11
          STATE=${'$'}(am get-started-user-state "${'$'}TRY_USER" 2>/dev/null || true)
          echo "PROBE_USER=${'$'}TRY_USER STATE=${'$'}STATE"
          case "${'$'}STATE" in
            *RUNNING_UNLOCKED*) ;;
            *)
              if [ "${'$'}CAPTURE_REQUIRE_CE" = "1" ]; then
                echo "ERR_USER_NOT_UNLOCKED:${'$'}TRY_USER:${'$'}STATE" >&2
                rm -rf "${'$'}TRY_TMP"
                return 1
              fi
              echo "WARN_USER_NOT_UNLOCKED:${'$'}TRY_USER:${'$'}STATE"
              ;;
          esac
          if cmd package list packages --user "${'$'}TRY_USER" 2>/dev/null | grep -qx "package:${'$'}PKG"; then
            echo "PACKAGE_LISTED:${'$'}TRY_USER"
          else
            echo "ERR_PACKAGE_NOT_LISTED:${'$'}TRY_USER" >&2
            rm -rf "${'$'}TRY_TMP"
            return 1
          fi
          am force-stop --user "${'$'}TRY_USER" "${'$'}PKG" >/dev/null 2>&1 || {
            echo "ERR_FORCE_STOP_FAILED:${'$'}TRY_USER:${'$'}PKG" >&2
            rm -rf "${'$'}TRY_TMP"
            return 1
          }
          COPIED_PARTS=0
          COPIED_ITEMS=0
          ${if (rule.includeCe) "copy_first_nonempty \"${'$'}TRY_TMP/ce\" \"/data/user/${'$'}TRY_USER/${'$'}PKG\" \"/data_mirror/data_ce/null/${'$'}TRY_USER/${'$'}PKG\" \"/data_mirror/data_ce/${'$'}TRY_USER/${'$'}PKG\"" else ":"}
          ${if (rule.includeDe) "copy_first_nonempty \"${'$'}TRY_TMP/de\" \"/data/user_de/${'$'}TRY_USER/${'$'}PKG\" \"/data_mirror/data_de/null/${'$'}TRY_USER/${'$'}PKG\" \"/data_mirror/data_de/${'$'}TRY_USER/${'$'}PKG\"" else ":"}
          ${if (rule.includeExternal) "copy_first_nonempty \"${'$'}TRY_TMP/external\" \"/data/media/${'$'}TRY_USER/Android/data/${'$'}PKG\" \"/storage/emulated/${'$'}TRY_USER/Android/data/${'$'}PKG\"" else ":"}
          ${if (rule.includeMedia) "copy_first_nonempty \"${'$'}TRY_TMP/media\" \"/data/media/${'$'}TRY_USER/Android/media/${'$'}PKG\" \"/storage/emulated/${'$'}TRY_USER/Android/media/${'$'}PKG\"" else ":"}
          ${if (rule.includeObb) "copy_first_nonempty \"${'$'}TRY_TMP/obb\" \"/data/media/${'$'}TRY_USER/Android/obb/${'$'}PKG\" \"/storage/emulated/${'$'}TRY_USER/Android/obb/${'$'}PKG\"" else ":"}
          ${if (rule.includePermissions) "uclone_capture_permission_state \"${'$'}TRY_TMP/permissions\" \"${'$'}TRY_USER\" || echo \"WARN_PERMISSION_CAPTURE_SKIPPED:${'$'}TRY_USER\"" else ":"}
          if [ "${'$'}CAPTURE_REQUIRE_CE" = "1" ] && [ ! -d "${'$'}TRY_TMP/ce" ]; then
            echo "ERR_CAPTURE_CE_MISSING:${'$'}TRY_USER" >&2
            rm -rf "${'$'}TRY_TMP"
            return 1
          fi
          if [ "${'$'}COPIED_PARTS" -gt 0 ]; then
            rm -rf "${'$'}TMP"
            mv "${'$'}TRY_TMP" "${'$'}TMP" || exit 14
            DETECTED_USER="${'$'}TRY_USER"
            DETECTED_STATE="${'$'}STATE"
            return 0
          fi
          rm -rf "${'$'}TRY_TMP"
          return 1
        }
        DETECTED_USER=""
        DETECTED_STATE=""
        for U in ${'$'}CANDIDATE_USERS; do
          if try_user "${'$'}U"; then
            break
          fi
        done
        [ -n "${'$'}DETECTED_USER" ] || { echo "ERR_NOTHING_COPIED: no non-empty selected source paths for candidates:${'$'}CANDIDATE_USERS package:${'$'}PKG" >&2; exit 44; }
        SIZE_KB=${'$'}(du -sk "${'$'}TMP" 2>/dev/null | awk '{print ${'$'}1}')
        printf '%s\n' "{\"packageName\":\"${packageName}\",\"configuredSourceUser\":${settings.cloneUserId},\"sourceUser\":\"${'$'}DETECTED_USER\",\"sourceUserState\":\"${'$'}DETECTED_STATE\",\"targetUser\":\"${settings.mainUserId}\",\"stateKind\":\"CLONE\",\"backupKind\":\"active_snapshot\",\"createdAt\":\"${'$'}TS\",\"includeCe\":${rule.includeCe},\"includeDe\":${rule.includeDe},\"includeExternal\":${rule.includeExternal},\"includeMedia\":${rule.includeMedia},\"includeObb\":${rule.includeObb},\"includePermissions\":${rule.includePermissions},\"includeAppWebView\":${rule.includeAppWebView},\"excludeCache\":${rule.excludeCache},\"snapshotSizeKb\":\"${'$'}SIZE_KB\",\"copiedParts\":\"${'$'}COPIED_PARTS\",\"copiedItems\":\"${'$'}COPIED_ITEMS\"}" > "${'$'}TMP/manifest.json" || exit 18
        uclone_record_temp_path "${'$'}TMP"
        uclone_stage_end
        uclone_stage_begin COMMIT
        if [ -d "${'$'}BASE/active" ]; then mv "${'$'}BASE/active" "${'$'}BASE/history/${'$'}TS" || exit 15; fi
        mv "${'$'}TMP" "${'$'}BASE/active" || exit 16
        chmod 700 "${'$'}BASE" "${'$'}BASE/active" "${'$'}BASE/history" >/dev/null 2>&1 || true
        uclone_stage_end
        uclone_emit_metrics
        echo "SNAPSHOT_ACTIVE=${'$'}BASE/active"
        echo "SNAPSHOT_SOURCE_USER=${'$'}DETECTED_USER"
    """.trimIndent()

    fun updateMainReturnPoint(
        packageName: String,
        settings: UCloneSettings,
        appPackage: String,
    ): String = """
        set -u
        ROOT=${shellQuote(settings.rootDir)}
        PKG=${shellQuote(packageName)}
        APP_PKG=${shellQuote(appPackage)}
        MAIN_USER=${settings.mainUserId}
        TS=${'$'}(date +%Y%m%d-%H%M%S)
        BASE="${'$'}ROOT/rollback/${'$'}PKG"
        FINAL="${'$'}BASE/persistent_main"
        PREVIOUS="${'$'}BASE/persistent_main.previous"
        TMP="${'$'}ROOT/tmp/main_return_${'$'}{PKG}_${'$'}{TS}_${'$'}${'$'}"
        ${metricsScript()}
        ${storagePreflightScript()}
        ${PermissionStateShell.functions()}
        ${StateBackupShell.functions()}
        uclone_stage_begin PRECHECK
        [ "${'$'}PKG" != "${'$'}APP_PKG" ] || { echo "ERR_SELF_SYNC" >&2; exit 41; }
        case "${'$'}ROOT" in "/"*) ;; *) echo "ERR_UNSAFE_WORKSPACE_ROOT:${'$'}ROOT" >&2; exit 71 ;; esac
        case "${'$'}ROOT" in /|/data|/data/adb) echo "ERR_UNSAFE_WORKSPACE_ROOT:${'$'}ROOT" >&2; exit 71 ;; esac
        [ ! -L "${'$'}ROOT" ] || { echo "ERR_WORKSPACE_SYMLINK:${'$'}ROOT" >&2; exit 71; }
        ROOT_REAL=${'$'}(readlink -f "${'$'}ROOT" 2>/dev/null || true)
        [ -n "${'$'}ROOT_REAL" ] && [ "${'$'}ROOT_REAL" = "${'$'}ROOT" ] || {
          echo "ERR_UNSAFE_WORKSPACE_ROOT:${'$'}ROOT" >&2
          exit 71
        }
        if [ "${'$'}ROOT_REAL" != "/data/adb/uclone" ]; then
          IDENTITY="${'$'}ROOT_REAL/config/workspace.identity"
          [ -f "${'$'}IDENTITY" ] && [ ! -L "${'$'}IDENTITY" ] &&
            [ "${'$'}(sed -n '1p' "${'$'}IDENTITY" 2>/dev/null | tr -d '\r')" = "com.uclone.restore.workspace.v1" ] || {
              echo "ERR_UNTRUSTED_WORKSPACE:${'$'}ROOT_REAL" >&2
              exit 71
            }
        fi
        validate_main_return_workspace_path() {
          MANAGED_NAME="${'$'}1"
          case "${'$'}MANAGED_NAME" in rollback|tmp|switches) ;; *) return 1 ;; esac
          MANAGED_PATH="${'$'}ROOT_REAL/${'$'}MANAGED_NAME"
          [ ! -e "${'$'}MANAGED_PATH" ] && [ ! -L "${'$'}MANAGED_PATH" ] && return 0
          [ -d "${'$'}MANAGED_PATH" ] && [ ! -L "${'$'}MANAGED_PATH" ] || {
            echo "ERR_WORKSPACE_SYMLINK:${'$'}MANAGED_PATH" >&2
            return 1
          }
          MANAGED_REAL=${'$'}(readlink -f "${'$'}MANAGED_PATH" 2>/dev/null || true)
          [ "${'$'}MANAGED_REAL" = "${'$'}ROOT_REAL/${'$'}MANAGED_NAME" ] || {
            echo "ERR_UNSAFE_WORKSPACE_PATH:${'$'}MANAGED_PATH" >&2
            return 1
          }
        }
        for MANAGED_NAME in rollback tmp switches; do
          validate_main_return_workspace_path "${'$'}MANAGED_NAME" || exit 71
        done
        CURRENT_STATE=${'$'}(uclone_current_main_state)
        uclone_confirmed_main_state || {
          echo "ERR_MAIN_RETURN_UPDATE_STATE:expected=CONFIRMED_MAIN actual=${'$'}CURRENT_STATE" >&2
          exit 74
        }
        cmd package list packages --user "${'$'}MAIN_USER" 2>/dev/null | grep -qx "package:${'$'}PKG" || {
          echo "ERR_PACKAGE_NOT_LISTED:${'$'}MAIN_USER:${'$'}PKG" >&2
          exit 43
        }
        mkdir -p "${'$'}ROOT/rollback" "${'$'}ROOT/tmp" "${'$'}BASE" || exit 53
        validate_main_return_workspace_path rollback || exit 71
        validate_main_return_workspace_path tmp || exit 71
        [ ! -L "${'$'}BASE" ] || { echo "ERR_MAIN_RETURN_SYMLINK:${'$'}BASE" >&2; exit 73; }
        BASE_REAL=${'$'}(readlink -f "${'$'}BASE" 2>/dev/null || true)
        [ "${'$'}BASE_REAL" = "${'$'}ROOT_REAL/rollback/${'$'}PKG" ] || {
          echo "ERR_MAIN_RETURN_BAD_PATH:${'$'}BASE" >&2
          exit 73
        }
        for MANAGED_PATH in "${'$'}FINAL" "${'$'}PREVIOUS"; do
          [ ! -L "${'$'}MANAGED_PATH" ] || { echo "ERR_MAIN_RETURN_SYMLINK:${'$'}MANAGED_PATH" >&2; exit 73; }
          [ ! -e "${'$'}MANAGED_PATH" ] || [ -d "${'$'}MANAGED_PATH" ] || {
            echo "ERR_MAIN_RETURN_BAD_PATH:${'$'}MANAGED_PATH" >&2
            exit 73
          }
        done
        UCLONE_ESTIMATED_KB=0
        uclone_add_dir_kb "/data/user/${'$'}MAIN_USER/${'$'}PKG"
        uclone_add_dir_kb "/data/user_de/${'$'}MAIN_USER/${'$'}PKG"
        uclone_add_dir_kb "/data/media/${'$'}MAIN_USER/Android/data/${'$'}PKG"
        uclone_add_dir_kb "/data/media/${'$'}MAIN_USER/Android/media/${'$'}PKG"
        uclone_add_dir_kb "/data/media/${'$'}MAIN_USER/Android/obb/${'$'}PKG"
        uclone_require_space "${'$'}UCLONE_ESTIMATED_KB" "update_main_return_point"
        uclone_stage_end
        cleanup_main_return_update() {
          case "${'$'}{TMP:-}" in
            "${'$'}ROOT"/tmp/main_return_"${'$'}PKG"_*) rm -rf "${'$'}TMP" 2>/dev/null || true ;;
          esac
          if [ ! -e "${'$'}FINAL" ] && [ -d "${'$'}PREVIOUS" ] && [ ! -L "${'$'}PREVIOUS" ]; then
            mv "${'$'}PREVIOUS" "${'$'}FINAL" >/dev/null 2>&1 || true
          fi
        }
        trap cleanup_main_return_update EXIT
        count_items() {
          find "${'$'}1" -mindepth 1 2>/dev/null | wc -l | tr -d ' '
        }
        backup_main_part() {
          SRC="${'$'}1"
          PART_NAME="${'$'}2"
          DST="${'$'}TMP/${'$'}PART_NAME"
          mkdir -p "${'$'}TMP/.state" || exit 54
          if [ ! -d "${'$'}SRC" ]; then
            printf '%s\n' absent > "${'$'}TMP/.state/${'$'}PART_NAME" || exit 54
            echo "MAIN_RETURN_PART:${'$'}PART_NAME state=absent"
            return 0
          fi
          [ ! -L "${'$'}SRC" ] || { echo "ERR_MAIN_RETURN_SOURCE_SYMLINK:${'$'}SRC" >&2; exit 73; }
          SRC_ITEMS=${'$'}(count_items "${'$'}SRC")
          UCLONE_SCANNED_FILES=${'$'}((UCLONE_SCANNED_FILES + SRC_ITEMS))
          if [ "${'$'}SRC_ITEMS" -le 0 ]; then
            printf '%s\n' empty > "${'$'}TMP/.state/${'$'}PART_NAME" || exit 54
            echo "MAIN_RETURN_PART:${'$'}PART_NAME state=empty"
            return 0
          fi
          mkdir -p "${'$'}DST" || exit 54
          (cd "${'$'}SRC" && tar -cpf - .) | (cd "${'$'}DST" && tar -xopf -) || exit 55
          DST_ITEMS=${'$'}(count_items "${'$'}DST")
          [ "${'$'}DST_ITEMS" -gt 0 ] || { echo "ERR_MAIN_RETURN_COPY_EMPTY:${'$'}SRC" >&2; exit 63; }
          printf '%s\n' data > "${'$'}TMP/.state/${'$'}PART_NAME" || exit 54
          PART_SIZE_KB=${'$'}(uclone_dir_kb_strict "${'$'}DST") || exit 53
          uclone_write_size_hint "${'$'}TMP" "${'$'}PART_NAME" "${'$'}PART_SIZE_KB" || exit 53
          UCLONE_COPIED_FILES=${'$'}((UCLONE_COPIED_FILES + DST_ITEMS))
          uclone_add_written_kb "${'$'}PART_SIZE_KB"
          echo "MAIN_RETURN_PART:${'$'}PART_NAME state=data items=${'$'}DST_ITEMS sizeKb=${'$'}PART_SIZE_KB"
        }
        uclone_stage_begin SOURCE_PREPARE
        rm -rf "${'$'}TMP"
        mkdir -p "${'$'}TMP" || exit 54
        am force-stop --user "${'$'}MAIN_USER" "${'$'}PKG" >/dev/null 2>&1 || {
          echo "ERR_FORCE_STOP_FAILED:${'$'}MAIN_USER:${'$'}PKG" >&2
          exit 76
        }
        backup_main_part "/data/user/${'$'}MAIN_USER/${'$'}PKG" ce
        backup_main_part "/data/user_de/${'$'}MAIN_USER/${'$'}PKG" de
        backup_main_part "/data/media/${'$'}MAIN_USER/Android/data/${'$'}PKG" external
        backup_main_part "/data/media/${'$'}MAIN_USER/Android/media/${'$'}PKG" media
        backup_main_part "/data/media/${'$'}MAIN_USER/Android/obb/${'$'}PKG" obb
        ${if (settings.includePermissions) "uclone_capture_permission_state \"${'$'}TMP/permissions\" \"${'$'}MAIN_USER\" || echo \"WARN_PERMISSION_CAPTURE_SKIPPED:${'$'}MAIN_USER\"" else ":"}
        SIZE_KB=${'$'}(du -sk "${'$'}TMP" 2>/dev/null | awk '{print ${'$'}1}')
        printf '%s\n' "{\"packageName\":\"${packageName}\",\"rollbackId\":\"persistent_main\",\"createdAt\":\"${'$'}TS\",\"reason\":\"用户更新固定 MAIN 返回点\",\"sourceUser\":\"${'$'}MAIN_USER\",\"targetUser\":\"${'$'}MAIN_USER\",\"stateKind\":\"MAIN\",\"backupKind\":\"persistent_state\",\"sizeKb\":\"${'$'}SIZE_KB\"}" > "${'$'}TMP/manifest.json" || exit 53
        chmod 700 "${'$'}TMP" >/dev/null 2>&1 || true
        chmod 600 "${'$'}TMP/manifest.json" "${'$'}TMP/.state/"* >/dev/null 2>&1 || true
        sync
        uclone_record_temp_path "${'$'}TMP"
        uclone_stage_end
        uclone_stage_begin VERIFY
        uclone_valid_state_backup "${'$'}TMP" MAIN || {
          echo "ERR_MAIN_RETURN_VERIFY_FAILED:${'$'}TMP" >&2
          exit 64
        }
        echo "MAIN_RETURN_VERIFIED:path=${'$'}TMP sizeKb=${'$'}SIZE_KB"
        uclone_stage_end
        uclone_stage_begin COMMIT
        rm -rf "${'$'}PREVIOUS" || exit 53
        HAD_PREVIOUS=0
        if [ -d "${'$'}FINAL" ]; then
          mv "${'$'}FINAL" "${'$'}PREVIOUS" || exit 53
          HAD_PREVIOUS=1
        fi
        if ! mv "${'$'}TMP" "${'$'}FINAL"; then
          [ "${'$'}HAD_PREVIOUS" = "0" ] || mv "${'$'}PREVIOUS" "${'$'}FINAL" >/dev/null 2>&1 || true
          echo "ERR_MAIN_RETURN_COMMIT_FAILED:${'$'}FINAL" >&2
          exit 53
        fi
        TMP=""
        sync
        if ! uclone_valid_state_backup "${'$'}FINAL" MAIN; then
          rm -rf "${'$'}FINAL"
          [ "${'$'}HAD_PREVIOUS" = "0" ] || mv "${'$'}PREVIOUS" "${'$'}FINAL" >/dev/null 2>&1 || true
          echo "ERR_MAIN_RETURN_COMMIT_VERIFY_FAILED:${'$'}FINAL" >&2
          exit 64
        fi
        rm -rf "${'$'}PREVIOUS" || echo "WARN_MAIN_RETURN_PREVIOUS_CLEANUP_FAILED:${'$'}PREVIOUS"
        sync
        uclone_stage_end
        trap - EXIT
        uclone_emit_metrics
        echo "MAIN_RETURN_POINT_UPDATED:path=${'$'}FINAL sizeKb=${'$'}SIZE_KB"
    """.trimIndent()

    fun restore(packageName: String, settings: UCloneSettings, appPackage: String): String = restoreBody(
        packageName = packageName,
        settings = settings,
        appPackage = appPackage,
        rollbackName = """${'$'}RUN_ID""",
        rollbackReason = "恢复到主系统前生成",
        directSource = true,
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
        rollbackName = """${'$'}RUN_ID""",
        rollbackReason = "切换到分身态前生成",
        sourceKind = RestoreSourceKind.SWITCH_TEMP,
        prepareSourceScript = switchLiveSourceScript(rule, settings),
        writeSwitchMarker = true,
        directSource = true,
        sourceStateAware = true,
        directSourceExcludeCache = rule.excludeCache,
        restoreRule = rule,
        backupCopyPassLabel = "main_transaction_undo",
        restoreCopyPassLabel = "clone_to_main",
        expectedCopyPasses = 2,
    )

    fun pushMainToClone(
        packageName: String,
        rule: AppRule,
        settings: UCloneSettings,
        appPackage: String,
    ): String = """
        set -u
        ROOT=${shellQuote(settings.rootDir)}
        PKG=${shellQuote(packageName)}
        APP_PKG=${shellQuote(appPackage)}
        SRC_USER=${settings.mainUserId}
        DST_USER=${settings.cloneUserId}
        TS=${'$'}(date +%Y%m%d-%H%M%S)
        PUSH_TEMP="${'$'}ROOT/tmp/push_${'$'}{PKG}_${'$'}TS"
        ROLLBACK_PARENT="${'$'}ROOT/clone_rollback/${'$'}PKG"
        ROLLBACK_LATEST="${'$'}ROLLBACK_PARENT/latest"
        ROLLBACK_PREVIOUS="${'$'}ROLLBACK_PARENT/latest.previous"
        ROLLBACK_TMP="${'$'}ROLLBACK_PARENT/latest.tmp_${'$'}TS"
        ROLLBACK="${'$'}ROLLBACK_TMP"
        PUSH_REQUIRE_CE=${if (rule.includeCe) "1" else "0"}
        ${metricsScript()}
        ${storagePreflightScript()}
        ${PermissionStateShell.functions()}
        uclone_stage_begin PRECHECK
        [ "${'$'}PKG" != "${'$'}APP_PKG" ] || { echo "ERR_SELF_SYNC"; exit 41; }
        [ -n "${'$'}ROOT" ] && [ "${'$'}ROOT" != "/" ] || { echo "ERR_BAD_ROOT:${'$'}ROOT" >&2; exit 71; }
        cleanup_switch_temp() {
          [ -n "${'$'}{PUSH_TEMP:-}" ] || return 0
          case "${'$'}PUSH_TEMP" in
            "${'$'}ROOT"/tmp/push_"${'$'}PKG"_"${'$'}TS") rm -rf "${'$'}PUSH_TEMP" 2>/dev/null || true ;;
          esac
          [ -n "${'$'}{ROLLBACK_TMP:-}" ] || return 0
          case "${'$'}ROLLBACK_TMP" in
            "${'$'}ROOT"/clone_rollback/"${'$'}PKG"/latest.tmp_"${'$'}TS")
              if [ "${'$'}{TRANSACTION_ROLLBACK_PRESERVE:-0}" = "1" ]; then
                echo "CLONE_ROLLBACK_PRESERVED=${'$'}ROLLBACK_TMP"
              else
                rm -rf "${'$'}ROLLBACK_TMP" 2>/dev/null || true
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
        rm -rf "${'$'}PUSH_TEMP" "${'$'}ROLLBACK_TMP"
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
        count_items() {
          find "${'$'}1" -mindepth 1 2>/dev/null | wc -l | tr -d ' '
        }
        copy_dir_stream() {
          SRC="${'$'}1"
          DST="${'$'}2"
          SRC_ITEMS=${'$'}(count_items "${'$'}SRC")
          UCLONE_SCANNED_FILES=${'$'}((UCLONE_SCANNED_FILES + SRC_ITEMS))
          [ "${'$'}SRC_ITEMS" -gt 0 ] || { echo "SKIP_EMPTY:${'$'}SRC"; return 0; }
          rm -rf "${'$'}DST.tmp"
          mkdir -p "${'$'}DST.tmp" || exit 12
          (cd "${'$'}SRC" && tar -cpf - .) | (cd "${'$'}DST.tmp" && tar -xopf -) || exit 13
          rm -rf "${'$'}DST"
          mv "${'$'}DST.tmp" "${'$'}DST" || exit 14
          ${if (rule.excludeCache) "rm -rf \"${'$'}DST/cache\" \"${'$'}DST/code_cache\" 2>/dev/null || true" else ":"}
          DST_ITEMS=${'$'}(count_items "${'$'}DST")
          [ "${'$'}DST_ITEMS" -gt 0 ] || { echo "ERR_COPY_EMPTY:${'$'}SRC" >&2; exit 17; }
          PART_SIZE_KB=${'$'}(du -sk "${'$'}DST" 2>/dev/null | awk '{print ${'$'}1}')
          COPIED_PARTS=${'$'}((COPIED_PARTS + 1))
          COPIED_ITEMS=${'$'}((COPIED_ITEMS + DST_ITEMS))
          UCLONE_COPIED_FILES=${'$'}((UCLONE_COPIED_FILES + DST_ITEMS))
          uclone_add_written_kb "${'$'}PART_SIZE_KB"
          uclone_record_temp_path "${'$'}DST"
          echo "COPIED:${'$'}SRC ITEMS=${'$'}DST_ITEMS SIZE_KB=${'$'}PART_SIZE_KB"
        }
        copy_first_nonempty() {
          DST="${'$'}1"
          shift
          for SRC in "${'$'}@"; do
            [ -n "${'$'}SRC" ] || continue
            if [ -d "${'$'}SRC" ]; then
              SRC_ITEMS=${'$'}(count_items "${'$'}SRC")
              echo "PROBE_PATH:${'$'}SRC ITEMS=${'$'}SRC_ITEMS"
              if [ "${'$'}SRC_ITEMS" -gt 0 ]; then
                copy_dir_stream "${'$'}SRC" "${'$'}DST"
                return 0
              fi
              echo "SKIP_EMPTY:${'$'}SRC"
            else
              echo "SKIP_MISSING:${'$'}SRC"
            fi
          done
          return 0
        }
        backup_dir() {
          SRC="${'$'}1"
          DST="${'$'}2"
          PART_NAME="${'$'}3"
          mkdir -p "${'$'}ROLLBACK/.state" || exit 54
          if [ ! -d "${'$'}SRC" ]; then
            printf '%s\n' "absent" > "${'$'}ROLLBACK/.state/${'$'}PART_NAME" || exit 54
            return 0
          fi
          SRC_ITEMS=${'$'}(count_items "${'$'}SRC")
          if [ "${'$'}SRC_ITEMS" -le 0 ]; then
            printf '%s\n' "empty" > "${'$'}ROLLBACK/.state/${'$'}PART_NAME" || exit 54
            echo "SKIP_BACKUP_EMPTY:${'$'}SRC"
            return 0
          fi
          rm -rf "${'$'}DST"
          mkdir -p "${'$'}DST" || exit 54
          (cd "${'$'}SRC" && tar -cpf - .) | (cd "${'$'}DST" && tar -xopf -) || exit 55
          BACKUP_ITEMS=${'$'}(count_items "${'$'}DST")
          UCLONE_SCANNED_FILES=${'$'}((UCLONE_SCANNED_FILES + SRC_ITEMS))
          UCLONE_COPIED_FILES=${'$'}((UCLONE_COPIED_FILES + BACKUP_ITEMS))
          BACKUP_SIZE_KB=${'$'}(uclone_dir_kb_strict "${'$'}DST") || exit 53
          uclone_write_size_hint "${'$'}ROLLBACK_TMP" "${'$'}PART_NAME" "${'$'}BACKUP_SIZE_KB" || exit 53
          uclone_add_written_kb "${'$'}BACKUP_SIZE_KB"
          uclone_record_temp_path "${'$'}DST"
          [ "${'$'}BACKUP_ITEMS" -gt 0 ] || { echo "ERR_BACKUP_EMPTY:${'$'}SRC" >&2; exit 63; }
          printf '%s\n' "data" > "${'$'}ROLLBACK/.state/${'$'}PART_NAME" || exit 54
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
          TARGET="${'$'}1"
          OWNER_KIND="${'$'}2"
          EXISTING_OWNER=${'$'}(stat -c '%u:%g' "${'$'}TARGET" 2>/dev/null || true)
          case "${'$'}EXISTING_OWNER" in
            *:*) echo "${'$'}EXISTING_OWNER"; return 0 ;;
          esac
          case "${'$'}OWNER_KIND" in
            app) echo "${'$'}DST_UID:${'$'}DST_UID" ;;
            media) echo "${'$'}DST_UID:1078" ;;
            *) echo "" ;;
          esac
        }
        apply_target_security() {
          SEC_TARGET="${'$'}1"
          SEC_OWNER="${'$'}2"
          SEC_CONTEXT="${'$'}3"
          if [ -n "${'$'}SEC_OWNER" ]; then
            chown -hR "${'$'}SEC_OWNER" "${'$'}SEC_TARGET" || exit 59
            OWNER_UID=${'$'}(echo "${'$'}SEC_OWNER" | cut -d: -f1)
            case "${'$'}OWNER_UID" in
              ''|*[!0-9]*) ;;
              *)
                APP_ID=${'$'}((OWNER_UID % 100000))
                CACHE_GID=${'$'}((20000 + APP_ID))
                [ -d "${'$'}SEC_TARGET/cache" ] && chown -hR "${'$'}OWNER_UID:${'$'}CACHE_GID" "${'$'}SEC_TARGET/cache" >/dev/null 2>&1 || true
                [ -d "${'$'}SEC_TARGET/code_cache" ] && chown -hR "${'$'}OWNER_UID:${'$'}CACHE_GID" "${'$'}SEC_TARGET/code_cache" >/dev/null 2>&1 || true
                ;;
            esac
          fi
          if [ -n "${'$'}SEC_CONTEXT" ]; then
            chcon -R -h "${'$'}SEC_CONTEXT" "${'$'}SEC_TARGET" >/dev/null 2>&1 || restorecon -RF "${'$'}SEC_TARGET" >/dev/null 2>&1 || restorecon -R "${'$'}SEC_TARGET" >/dev/null 2>&1 || exit 60
          else
            restorecon -RF "${'$'}SEC_TARGET" >/dev/null 2>&1 || restorecon -R "${'$'}SEC_TARGET" >/dev/null 2>&1 || exit 60
          fi
        }
        clear_target_contents() {
          CLEAR_TARGET="${'$'}1"
          validate_target_path "${'$'}CLEAR_TARGET"
          [ -d "${'$'}CLEAR_TARGET" ] || { echo "ERR_TARGET_MISSING:${'$'}CLEAR_TARGET" >&2; exit 67; }
          find "${'$'}CLEAR_TARGET" -mindepth 1 -maxdepth 1 -exec rm -rf {} \; || exit 68
        }
        restore_part() {
          SNAP="${'$'}1"
          TARGET="${'$'}2"
          OWNER_KIND="${'$'}3"
          [ -d "${'$'}SNAP" ] || { echo "SKIP_PART:${'$'}SNAP"; return 0; }
          validate_target_path "${'$'}TARGET"
          SNAP_ITEMS=${'$'}(count_items "${'$'}SNAP")
          [ "${'$'}SNAP_ITEMS" -gt 0 ] || { echo "ERR_EMPTY_PUSH_PART:${'$'}SNAP" >&2; exit 64; }
          TARGET_OWNER=${'$'}(target_owner_for "${'$'}TARGET" "${'$'}OWNER_KIND")
          mkdir -p "${'$'}TARGET" || exit 56
          TARGET_CONTEXT=${'$'}(read_target_context "${'$'}TARGET")
          case "${'$'}TARGET_CONTEXT" in u:object_r:*) ;; *) TARGET_CONTEXT="" ;; esac
          if [ -z "${'$'}TARGET_CONTEXT" ]; then
            restorecon -RF "${'$'}TARGET" >/dev/null 2>&1 || restorecon -R "${'$'}TARGET" >/dev/null 2>&1 || true
            TARGET_CONTEXT=${'$'}(read_target_context "${'$'}TARGET")
            case "${'$'}TARGET_CONTEXT" in u:object_r:*) ;; *) TARGET_CONTEXT="" ;; esac
          fi
          TARGET_MUTATED=1
          clear_target_contents "${'$'}TARGET"
          (cd "${'$'}SNAP" && tar -cpf - .) | (cd "${'$'}TARGET" && tar -xpf -) || exit 58
          apply_target_security "${'$'}TARGET" "${'$'}TARGET_OWNER" "${'$'}TARGET_CONTEXT"
          TARGET_ITEMS=${'$'}(count_items "${'$'}TARGET")
          [ "${'$'}TARGET_ITEMS" -gt 0 ] || { echo "ERR_PUSH_EMPTY:${'$'}TARGET" >&2; exit 65; }
          RESTORED_PARTS=${'$'}((RESTORED_PARTS + 1))
          RESTORED_ITEMS=${'$'}((RESTORED_ITEMS + TARGET_ITEMS))
          UCLONE_SCANNED_FILES=${'$'}((UCLONE_SCANNED_FILES + SNAP_ITEMS))
          UCLONE_COPIED_FILES=${'$'}((UCLONE_COPIED_FILES + TARGET_ITEMS))
          TARGET_SIZE_KB=${'$'}(du -sk "${'$'}TARGET" 2>/dev/null | awk '{print ${'$'}1}')
          uclone_add_written_kb "${'$'}TARGET_SIZE_KB"
          echo "PUSHED:${'$'}TARGET ITEMS=${'$'}TARGET_ITEMS OWNER=${'$'}TARGET_OWNER CONTEXT=${'$'}TARGET_CONTEXT"
        }
        uclone_stage_begin SOURCE_PREPARE
        force_stop_source_package || exit 76
        mkdir -p "${'$'}PUSH_TEMP" "${'$'}ROLLBACK_TMP" || exit 11
        COPIED_PARTS=0
        COPIED_ITEMS=0
        BACKUP_PARTS=0
        RESTORED_PARTS=0
        RESTORED_ITEMS=0
        ${if (rule.includeCe) "copy_first_nonempty \"${'$'}PUSH_TEMP/ce\" \"/data/user/${'$'}SRC_USER/${'$'}PKG\" \"/data_mirror/data_ce/null/${'$'}SRC_USER/${'$'}PKG\" \"/data_mirror/data_ce/${'$'}SRC_USER/${'$'}PKG\"" else ":"}
        ${if (rule.includeDe) "copy_first_nonempty \"${'$'}PUSH_TEMP/de\" \"/data/user_de/${'$'}SRC_USER/${'$'}PKG\" \"/data_mirror/data_de/null/${'$'}SRC_USER/${'$'}PKG\" \"/data_mirror/data_de/${'$'}SRC_USER/${'$'}PKG\"" else ":"}
        ${if (rule.includeExternal) "copy_first_nonempty \"${'$'}PUSH_TEMP/external\" \"/data/media/${'$'}SRC_USER/Android/data/${'$'}PKG\" \"/storage/emulated/${'$'}SRC_USER/Android/data/${'$'}PKG\"" else ":"}
        ${if (rule.includeMedia) "copy_first_nonempty \"${'$'}PUSH_TEMP/media\" \"/data/media/${'$'}SRC_USER/Android/media/${'$'}PKG\" \"/storage/emulated/${'$'}SRC_USER/Android/media/${'$'}PKG\"" else ":"}
        ${if (rule.includeObb) "copy_first_nonempty \"${'$'}PUSH_TEMP/obb\" \"/data/media/${'$'}SRC_USER/Android/obb/${'$'}PKG\" \"/storage/emulated/${'$'}SRC_USER/Android/obb/${'$'}PKG\"" else ":"}
        ${if (rule.includePermissions) "uclone_capture_permission_state \"${'$'}PUSH_TEMP/permissions\" \"${'$'}SRC_USER\" || echo \"WARN_PERMISSION_CAPTURE_SKIPPED:${'$'}SRC_USER\"" else ":"}
        if [ "${'$'}PUSH_REQUIRE_CE" = "1" ] && [ ! -d "${'$'}PUSH_TEMP/ce" ]; then
          echo "ERR_PUSH_CE_MISSING:${'$'}SRC_USER" >&2
          exit 44
        fi
        [ "${'$'}COPIED_PARTS" -gt 0 ] || { echo "ERR_NOTHING_COPIED: no non-empty selected source paths for main user:${'$'}SRC_USER package:${'$'}PKG" >&2; exit 45; }
        uclone_record_temp_path "${'$'}PUSH_TEMP"
        uclone_stage_end
        uclone_stage_begin TARGET_STOP
        force_stop_target_package || exit 76
        UCLONE_TARGET_STOPPED_AT=${'$'}(uclone_now_ms)
        uclone_stage_end
        uclone_stage_begin ROLLBACK_BACKUP
        backup_dir "/data/user/${'$'}DST_USER/${'$'}PKG" "${'$'}ROLLBACK_TMP/ce" "ce"
        backup_dir "/data/user_de/${'$'}DST_USER/${'$'}PKG" "${'$'}ROLLBACK_TMP/de" "de"
        backup_dir "/data/media/${'$'}DST_USER/Android/data/${'$'}PKG" "${'$'}ROLLBACK_TMP/external" "external"
        backup_dir "/data/media/${'$'}DST_USER/Android/media/${'$'}PKG" "${'$'}ROLLBACK_TMP/media" "media"
        backup_dir "/data/media/${'$'}DST_USER/Android/obb/${'$'}PKG" "${'$'}ROLLBACK_TMP/obb" "obb"
        ${if (rule.includePermissions) "uclone_capture_permission_state \"${'$'}ROLLBACK_TMP/permissions\" \"${'$'}DST_USER\" || echo \"WARN_PERMISSION_CAPTURE_SKIPPED:${'$'}DST_USER\"" else ":"}
        ROLLBACK_SIZE_KB=${'$'}(du -sk "${'$'}ROLLBACK_TMP" 2>/dev/null | awk '{print ${'$'}1}')
        printf '%s\n' "{\"packageName\":\"${'$'}PKG\",\"rollbackId\":\"latest\",\"createdAt\":\"${'$'}TS\",\"reason\":\"推送到分身前生成\",\"sourceUser\":\"${'$'}DST_USER\",\"targetUser\":\"${'$'}DST_USER\",\"stateKind\":\"CLONE\",\"backupKind\":\"clone_rollback\",\"retention\":\"latest_only\",\"sizeKb\":\"${'$'}ROLLBACK_SIZE_KB\"}" > "${'$'}ROLLBACK_TMP/manifest.json" || exit 53
        sync
        echo "CLONE_ROLLBACK_PREPARED=${'$'}ROLLBACK_TMP backupParts=${'$'}BACKUP_PARTS"
        uclone_stage_end
        ${RestoreTransactionShell.guard(
            appUidVariable = "DST_UID",
            includePermissions = rule.includePermissions,
            manageSwitchMarker = false,
        )}
        uclone_stage_begin RESTORE_DATA
        restore_part "${'$'}PUSH_TEMP/ce" "/data/user/${'$'}DST_USER/${'$'}PKG" "app"
        restore_part "${'$'}PUSH_TEMP/de" "/data/user_de/${'$'}DST_USER/${'$'}PKG" "app"
        restore_part "${'$'}PUSH_TEMP/external" "/data/media/${'$'}DST_USER/Android/data/${'$'}PKG" "media"
        restore_part "${'$'}PUSH_TEMP/media" "/data/media/${'$'}DST_USER/Android/media/${'$'}PKG" "media"
        restore_part "${'$'}PUSH_TEMP/obb" "/data/media/${'$'}DST_USER/Android/obb/${'$'}PKG" "media"
        uclone_stage_end
        uclone_stage_begin RESTORE_PERMISSIONS
        ${if (rule.includePermissions) "uclone_restore_permission_state \"${'$'}PUSH_TEMP/permissions\" \"${'$'}DST_USER\"" else ":"}
        uclone_stage_end
        uclone_stage_begin VERIFY
        [ "${'$'}RESTORED_PARTS" -gt 0 ] || { echo "ERR_NOTHING_PUSHED:${'$'}PUSH_TEMP" >&2; exit 62; }
        uclone_stage_end
        uclone_stage_begin COMMIT
        sync
        force_stop_package_users || exit 76
        TRANSACTION_COMMITTED=1
        CLONE_ROLLBACK_COMMITTED=0
        rm -rf "${'$'}ROLLBACK_PREVIOUS" >/dev/null 2>&1 || true
        if [ ! -e "${'$'}ROLLBACK_LATEST" ] ||
           { [ -d "${'$'}ROLLBACK_LATEST" ] && [ ! -L "${'$'}ROLLBACK_LATEST" ] && mv "${'$'}ROLLBACK_LATEST" "${'$'}ROLLBACK_PREVIOUS"; }; then
          if mv "${'$'}ROLLBACK_TMP" "${'$'}ROLLBACK_LATEST"; then
            CLONE_ROLLBACK_COMMITTED=1
            ROLLBACK="${'$'}ROLLBACK_LATEST"
            ROLLBACK_TMP=""
          else
            [ ! -d "${'$'}ROLLBACK_PREVIOUS" ] || mv "${'$'}ROLLBACK_PREVIOUS" "${'$'}ROLLBACK_LATEST" >/dev/null 2>&1 || true
          fi
        fi
        if [ "${'$'}CLONE_ROLLBACK_COMMITTED" = "1" ]; then
          rm -rf "${'$'}ROLLBACK_PREVIOUS" >/dev/null 2>&1 || echo "WARN_CLONE_ROLLBACK_PREVIOUS_CLEANUP_FAILED:${'$'}ROLLBACK_PREVIOUS"
          echo "CLONE_ROLLBACK_COMMITTED=${'$'}ROLLBACK_LATEST"
        else
          ROLLBACK="${'$'}ROLLBACK_TMP"
          ROLLBACK_TMP=""
          echo "WARN_CLONE_ROLLBACK_COMMIT_FAILED:exactRollback=${'$'}ROLLBACK"
        fi
        sync
        UCLONE_TARGET_READY_AT=${'$'}(uclone_now_ms)
        UCLONE_TARGET_DOWNTIME_MS=${'$'}(uclone_elapsed_ms "${'$'}UCLONE_TARGET_READY_AT" "${'$'}UCLONE_TARGET_STOPPED_AT")
        uclone_stage_end
        uclone_emit_metrics
        echo "PUSH_MAIN_TO_CLONE_DONE targetUser=${'$'}DST_USER restoredParts=${'$'}RESTORED_PARTS restoredItems=${'$'}RESTORED_ITEMS copiedParts=${'$'}COPIED_PARTS backupParts=${'$'}BACKUP_PARTS"
    """.trimIndent()

    fun pushMainToCloneThenRestoreMain(
        packageName: String,
        rollbackId: String,
        rule: AppRule,
        settings: UCloneSettings,
        appPackage: String,
    ): String = when (settings.cloneReturnPlan()) {
        CloneReturnPlan.SYNC_SAFE -> restoreBody(
            packageName = packageName,
            settings = settings,
            appPackage = appPackage,
            rollbackName = """switch_checkpoint_${'$'}RUN_ID""",
            rollbackReason = "安全返回主数据前保存当前分数据",
            sourcePrefix = "${settings.rootDir}/rollback/$packageName/$rollbackId",
            sourceRollbackId = rollbackId,
            sourceKind = RestoreSourceKind.ROLLBACK,
            prepareSourceScript = OptimizedSwitchPreparationShell.safeReturn(rule, settings, rollbackId),
            clearSwitchMarker = true,
            directSource = true,
            sourceStateAware = true,
            restoreRule = rule,
            rollbackProtection = RollbackProtection.EXISTING,
            cleanupRollbackOnSuccess = true,
            restoreCopyPassLabel = "fixed_main_to_user0",
            expectedCopyPasses = 3,
            pruneOldRollbacks = false,
        )

        CloneReturnPlan.SYNC_FAST -> restoreBody(
            packageName = packageName,
            settings = settings,
            appPackage = appPackage,
            rollbackName = """dangerous_no_rollback_${'$'}RUN_ID""",
            rollbackReason = "危险快速返回不生成本地回滚",
            sourcePrefix = "${settings.rootDir}/rollback/$packageName/$rollbackId",
            sourceRollbackId = rollbackId,
            sourceKind = RestoreSourceKind.ROLLBACK,
            prepareSourceScript = OptimizedSwitchPreparationShell.dangerousReturn(rule, settings, rollbackId),
            clearSwitchMarker = true,
            directSource = true,
            sourceStateAware = true,
            restoreRule = rule,
            rollbackProtection = RollbackProtection.NONE,
            restoreCopyPassLabel = "fixed_main_to_user0",
            expectedCopyPasses = 2,
            pruneOldRollbacks = false,
        )

        CloneReturnPlan.DISCARD_SAFE -> restoreBody(
            packageName = packageName,
            settings = settings,
            appPackage = appPackage,
            rollbackName = """switch_checkpoint_${'$'}RUN_ID""",
            rollbackReason = "丢弃当前分数据前保存临时检查点",
            sourcePrefix = "${settings.rootDir}/rollback/$packageName/$rollbackId",
            sourceRollbackId = rollbackId,
            sourceKind = RestoreSourceKind.ROLLBACK,
            prepareSourceScript = OptimizedSwitchPreparationShell.discardReturn(rule, settings, rollbackId, safe = true),
            clearSwitchMarker = true,
            directSource = true,
            sourceStateAware = true,
            restoreRule = rule,
            rollbackProtection = RollbackProtection.FRESH,
            cleanupRollbackOnSuccess = true,
            backupCopyPassLabel = "clone_discard_checkpoint",
            restoreCopyPassLabel = "fixed_main_to_user0",
            expectedCopyPasses = 2,
            pruneOldRollbacks = false,
            manageCloneLifecycleAfterTask = false,
        )

        CloneReturnPlan.DISCARD_FAST -> restoreBody(
            packageName = packageName,
            settings = settings,
            appPackage = appPackage,
            rollbackName = """dangerous_discard_no_rollback_${'$'}RUN_ID""",
            rollbackReason = "危险丢弃当前分数据且不生成回滚",
            sourcePrefix = "${settings.rootDir}/rollback/$packageName/$rollbackId",
            sourceRollbackId = rollbackId,
            sourceKind = RestoreSourceKind.ROLLBACK,
            prepareSourceScript = OptimizedSwitchPreparationShell.discardReturn(rule, settings, rollbackId, safe = false),
            clearSwitchMarker = true,
            directSource = true,
            sourceStateAware = true,
            restoreRule = rule,
            rollbackProtection = RollbackProtection.NONE,
            restoreCopyPassLabel = "fixed_main_to_user0",
            expectedCopyPasses = 1,
            pruneOldRollbacks = false,
            manageCloneLifecycleAfterTask = false,
        )
    }

    fun probeCloneCe(settings: UCloneSettings): String = """
        set -u
        CLONE_USER=${settings.cloneUserId}
        echo "PROBE_CLONE_USER=${'$'}CLONE_USER"
        echo "ROOT_ID=${'$'}(id 2>&1)"
        state_of_clone() {
          am get-started-user-state "${'$'}CLONE_USER" 2>&1 || true
        }
        probe_base_path() {
          LABEL="${'$'}1"
          PATH_VALUE="${'$'}2"
          if [ -d "${'$'}PATH_VALUE" ]; then
            ITEMS=${'$'}(find "${'$'}PATH_VALUE" -mindepth 1 -maxdepth 1 2>/dev/null | wc -l | tr -d ' ')
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
        AM_COMMAND=${shellQuote(amCommand)}
        clone_state() {
          "${'$'}AM_COMMAND" get-started-user-state "${'$'}CLONE_USER" 2>&1 || true
        }
        echo "EXPLICIT_START_CLONE_USER=${'$'}CLONE_USER"
        STATE_BEFORE_START=${'$'}(clone_state)
        echo "STATE_BEFORE_START=${'$'}STATE_BEFORE_START"
        case "${'$'}STATE_BEFORE_START" in
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
        require(stopPollLimit in 1..100)
        require(stopPollIntervalSeconds > 0.0 && stopPollIntervalSeconds <= 5.0)
        return """
        set -u
        CLONE_USER=${settings.cloneUserId}
        AM_COMMAND=${shellQuote(amCommand)}
        SLEEP_COMMAND=${shellQuote(sleepCommand)}
        STOP_POLL_INTERVAL=${shellQuote(stopPollIntervalSeconds.toString())}
        clone_state() {
          "${'$'}AM_COMMAND" get-started-user-state "${'$'}CLONE_USER" 2>&1 || true
        }
        echo "EXPLICIT_STOP_CLONE_USER=${'$'}CLONE_USER"
        STATE_BEFORE_STOP=${'$'}(clone_state)
        echo "STATE_BEFORE_STOP=${'$'}STATE_BEFORE_STOP"
        case "${'$'}STATE_BEFORE_STOP" in
          *"User is not started"*|*"not started"*|*SHUTDOWN*) echo "STOP_CLONE_ALREADY_STOPPED=1"; exit 0 ;;
        esac
        STOP_USER_EXIT=0
        STOP_USER_OUTPUT=${'$'}("${'$'}AM_COMMAND" stop-user "${'$'}CLONE_USER" 2>&1) || STOP_USER_EXIT=${'$'}?
        echo "STOP_USER_EXIT=${'$'}STOP_USER_EXIT"
        echo "STOP_USER_OUTPUT=${'$'}STOP_USER_OUTPUT"
        if [ "${'$'}STOP_USER_EXIT" -ne 0 ]; then
          echo "ERR_STOP_CLONE_REQUEST_FAILED:${'$'}STOP_USER_EXIT:${'$'}(clone_state)" >&2
          exit 86
        fi
        STOP_WAIT_INDEX=0
        while [ "${'$'}STOP_WAIT_INDEX" -lt $stopPollLimit ]; do
          STOP_WAIT_STATE=${'$'}(clone_state)
          echo "WAIT_AFTER_STOP_${'$'}STOP_WAIT_INDEX=${'$'}STOP_WAIT_STATE"
          case "${'$'}STOP_WAIT_STATE" in
            *"User is not started"*|*"not started"*|*SHUTDOWN*) echo "STOP_CLONE_CONFIRMED=1"; exit 0 ;;
          esac
          "${'$'}SLEEP_COMMAND" "${'$'}STOP_POLL_INTERVAL"
          STOP_WAIT_INDEX=${'$'}((STOP_WAIT_INDEX + 1))
        done
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
          /system/bin/am get-started-user-state "${'$'}1" 2>&1 || true
        }
        probe_path() {
          LABEL="${'$'}1"
          PATH_VALUE="${'$'}2"
          echo "PATH_${'$'}{LABEL}=${'$'}PATH_VALUE"
          if [ -e "${'$'}PATH_VALUE" ]; then
            echo "PATH_${'$'}{LABEL}_EXISTS=1"
            ls -ldnZ "${'$'}PATH_VALUE" 2>&1 | sed "s/^/PATH_${'$'}{LABEL}_LS_Z: /" || ls -ldn "${'$'}PATH_VALUE" 2>&1 | sed "s/^/PATH_${'$'}{LABEL}_LS: /" || true
            if [ -d "${'$'}PATH_VALUE" ]; then
              ITEMS=${'$'}(find "${'$'}PATH_VALUE" -mindepth 1 -maxdepth 1 2>/dev/null | wc -l | tr -d ' ')
              SIZE_KB=${'$'}(du -sk "${'$'}PATH_VALUE" 2>/dev/null | awk '{print ${'$'}1}')
              echo "PATH_${'$'}{LABEL}_ITEMS=${'$'}ITEMS"
              echo "PATH_${'$'}{LABEL}_SIZE_KB=${'$'}SIZE_KB"
              find "${'$'}PATH_VALUE" -mindepth 1 -maxdepth 2 2>/dev/null | sed -n '1,12p' | sed "s/^/PATH_${'$'}{LABEL}_SAMPLE: /"
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
          find "${'$'}ROOT_DIR/snapshots" -mindepth 3 -maxdepth 4 -name manifest.json 2>/dev/null | sed -n '1,8p' | while IFS= read -r MANIFEST; do
            echo "V02_SNAPSHOT_MANIFEST=${'$'}MANIFEST"
            sed -n '1p' "${'$'}MANIFEST" 2>/dev/null | sed 's/^/V02_SNAPSHOT_MANIFEST_JSON: /'
          done
        fi
        if [ -d "${'$'}ROOT_DIR/rollback" ]; then
          find "${'$'}ROOT_DIR/rollback" -mindepth 3 -maxdepth 3 -name manifest.json 2>/dev/null | sed -n '1,8p' | while IFS= read -r MANIFEST; do
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
        ROOT=${shellQuote(settings.rootDir)}
        PKG=${shellQuote(packageName)}
        APP_PKG=${shellQuote(appPackage)}
        TARGET_USER=${settings.mainUserId}
        CLONE_USER=${settings.cloneUserId}
        TS=${'$'}(date +%Y%m%d-%H%M%S)
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
        capture_tree() {
          NAME="${'$'}1"
          PATH_VALUE="${'$'}2"
          {
            echo "PATH:${'$'}PATH_VALUE"
            if [ -d "${'$'}PATH_VALUE" ]; then
              find "${'$'}PATH_VALUE" -maxdepth 4 2>/dev/null | while IFS= read -r ITEM; do
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
        run_capture "user_state.txt" sh -c "am get-started-user-state ${'$'}TARGET_USER; am get-started-user-state ${'$'}CLONE_USER"
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
        CLONE_STATE=${'$'}(am get-started-user-state "${'$'}CLONE_USER" 2>&1 || true)
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
        clearSwitchMarker: Boolean = false,
    ): String {
        requireSafeRollbackId(rollbackId)
        return restoreBody(
            packageName = packageName,
            settings = settings,
            appPackage = appPackage,
            rollbackName = """rollback_${'$'}RUN_ID""",
            rollbackReason = if (clearSwitchMarker) "还原主系统态前生成" else "恢复主系统备份前生成",
            sourcePrefix = "${settings.rootDir}/rollback/$packageName/$rollbackId",
            sourceRollbackId = rollbackId,
            clearSwitchMarker = clearSwitchMarker,
            directSource = true,
        )
    }

    fun restoreCloneRollback(
        packageName: String,
        settings: UCloneSettings,
        appPackage: String,
    ): String = restoreBody(
        packageName = packageName,
        settings = settings,
        appPackage = appPackage,
        rollbackName = """restore_${'$'}RUN_ID""",
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
        directSource = true,
    )

    fun deleteSnapshot(packageName: String, settings: UCloneSettings, appPackage: String): String = """
        set -u
        ROOT=${shellQuote(settings.rootDir)}
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
        SIZE_KB=${'$'}(du -sk "${'$'}ACTIVE" 2>/dev/null | awk '{print ${'$'}1}')
        ITEMS=${'$'}(find "${'$'}ACTIVE" -mindepth 1 2>/dev/null | wc -l | tr -d ' ')
        rm -rf "${'$'}ACTIVE" || exit 74
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
        ROOT=${shellQuote(settings.rootDir)}
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
        SIZE_KB=${'$'}(du -sk "${'$'}TARGET" 2>/dev/null | awk '{print ${'$'}1}')
        ITEMS=${'$'}(find "${'$'}TARGET" -mindepth 1 2>/dev/null | wc -l | tr -d ' ')
        rm -rf "${'$'}TARGET" || exit 75
        [ ! -e "${'$'}TARGET" ] || { echo "ERR_DELETE_FAILED:${'$'}TARGET" >&2; exit 75; }
        if [ -f "${'$'}SWITCH_MARKER" ]; then
          MARKER_ROLLBACK_ID=${'$'}(sed -n '1p' "${'$'}SWITCH_MARKER" | tr -d '\r')
          if [ "${'$'}MARKER_ROLLBACK_ID" = "${'$'}ROLLBACK_ID" ]; then
            case "${'$'}SWITCH_MARKER" in
              "${'$'}ROOT"/switches/"${'$'}PKG"/active)
                SWITCH_MARKER_TMP="${'$'}SWITCH_MARKER.unknown_${'$'}${'$'}"
                printf '%s\n' ${shellQuote(UNKNOWN_SWITCH_MARKER)} > "${'$'}SWITCH_MARKER_TMP" || exit 76
                chmod 600 "${'$'}SWITCH_MARKER_TMP" || exit 76
                mv -f "${'$'}SWITCH_MARKER_TMP" "${'$'}SWITCH_MARKER" || exit 76
                ;;
              *) echo "ERR_BAD_SWITCH_MARKER:${'$'}SWITCH_MARKER" >&2; exit 76 ;;
            esac
            echo "SWITCH_MARKER_UNKNOWN=${'$'}SWITCH_MARKER"
          fi
        fi
        echo "DELETED_RESTORE_BACKUP=${'$'}TARGET SIZE_KB=${'$'}SIZE_KB ITEMS=${'$'}ITEMS"
    """.trimIndent()
    }

    fun resetWorkspace(settings: UCloneSettings): String = """
        set -u
        ROOT=${shellQuote(settings.rootDir)}
        [ -n "${'$'}ROOT" ] && [ "${'$'}ROOT" != "/" ] || { echo "ERR_BAD_ROOT:${'$'}ROOT" >&2; exit 71; }
        ROOT_REAL=${'$'}(readlink -f "${'$'}ROOT" 2>/dev/null || true)
        [ -n "${'$'}ROOT_REAL" ] && [ "${'$'}ROOT_REAL" = "${'$'}ROOT" ] || { echo "ERR_RESET_ROOT_NOT_CANONICAL:${'$'}ROOT:${'$'}ROOT_REAL" >&2; exit 71; }
        ROOT_NAME=${'$'}(basename "${'$'}ROOT" | tr '[:upper:]' '[:lower:]')
        case "${'$'}ROOT_NAME" in
          *uclone*) ;;
          *) echo "ERR_RESET_ROOT_NOT_UCLONE:${'$'}ROOT" >&2; exit 72 ;;
        esac
        mkdir -p "${'$'}ROOT" || exit 10
        UNKNOWN_PACKAGES=""
        if [ -d "${'$'}ROOT/switches" ]; then
          for ACTIVE_MARKER in "${'$'}ROOT"/switches/?*/active; do
            [ -e "${'$'}ACTIVE_MARKER" ] || [ -L "${'$'}ACTIVE_MARKER" ] || continue
            ACTIVE_PACKAGE=${'$'}(basename "${'$'}(dirname "${'$'}ACTIVE_MARKER")")
            case "${'$'}ACTIVE_PACKAGE" in
              ''|*[!A-Za-z0-9_.-]*) ;;
              *) UNKNOWN_PACKAGES="${'$'}UNKNOWN_PACKAGES ${'$'}ACTIVE_PACKAGE" ;;
            esac
          done
        fi
        RESET_TARGETS="snapshots rollback clone_rollback switches logs tmp audit config"
        DELETED_TARGETS=0
        DELETED_SIZE_KB=0
        for NAME in ${'$'}RESET_TARGETS; do
          TARGET="${'$'}ROOT/${'$'}NAME"
          case "${'$'}TARGET" in
            "${'$'}ROOT"/snapshots|"${'$'}ROOT"/rollback|"${'$'}ROOT"/clone_rollback|"${'$'}ROOT"/switches|"${'$'}ROOT"/logs|"${'$'}ROOT"/tmp|"${'$'}ROOT"/audit|"${'$'}ROOT"/config) ;;
            *) echo "ERR_UNSAFE_RESET_TARGET:${'$'}TARGET" >&2; exit 73 ;;
          esac
          [ ! -L "${'$'}TARGET" ] || { echo "ERR_RESET_TARGET_SYMLINK:${'$'}TARGET" >&2; exit 73; }
          [ -e "${'$'}TARGET" ] || continue
          [ -d "${'$'}TARGET" ] || { echo "ERR_RESET_TARGET_NOT_DIRECTORY:${'$'}TARGET" >&2; exit 73; }
          SIZE_KB=${'$'}(du -sk "${'$'}TARGET" 2>/dev/null | awk '{print ${'$'}1}')
          case "${'$'}SIZE_KB" in ''|*[!0-9]*) SIZE_KB=0 ;; esac
          rm -rf "${'$'}TARGET" || { echo "ERR_RESET_DELETE_FAILED:${'$'}TARGET" >&2; exit 74; }
          DELETED_TARGETS=${'$'}((DELETED_TARGETS + 1))
          NEXT_DELETED_SIZE_KB=${'$'}(awk -v TOTAL="${'$'}DELETED_SIZE_KB" -v SIZE_KB="${'$'}SIZE_KB" 'BEGIN { printf "%.0f\n", TOTAL + SIZE_KB }')
          case "${'$'}NEXT_DELETED_SIZE_KB" in ''|*[!0-9]*) NEXT_DELETED_SIZE_KB="${'$'}DELETED_SIZE_KB" ;; esac
          DELETED_SIZE_KB="${'$'}NEXT_DELETED_SIZE_KB"
          echo "RESET_DELETED:${'$'}TARGET SIZE_KB=${'$'}SIZE_KB"
        done
        for ACTIVE_PACKAGE in ${'$'}UNKNOWN_PACKAGES; do
          UNKNOWN_MARKER_DIR="${'$'}ROOT/switches/${'$'}ACTIVE_PACKAGE"
          mkdir -p "${'$'}UNKNOWN_MARKER_DIR" || exit 75
          printf '%s\n' ${shellQuote(UNKNOWN_SWITCH_MARKER)} > "${'$'}UNKNOWN_MARKER_DIR/active" || exit 75
          chmod 600 "${'$'}UNKNOWN_MARKER_DIR/active" || exit 75
          echo "RESET_STATE_UNKNOWN:${'$'}ACTIVE_PACKAGE"
        done
        sync
        echo "RESET_WORKSPACE_DONE root=${'$'}ROOT deletedTargets=${'$'}DELETED_TARGETS sizeKb=${'$'}DELETED_SIZE_KB"
    """.trimIndent()

    private fun switchLiveSourceScript(rule: AppRule, settings: UCloneSettings): String = """
        SWITCH_TEMP="${'$'}ACTIVE"
        SWITCH_TEMP_CREATED=0
        case "${'$'}SWITCH_TEMP" in
          "${'$'}ROOT"/tmp/switch_"${'$'}PKG"_"${'$'}RUN_ID") ;;
          *) echo "ERR_BAD_SWITCH_TEMP:${'$'}SWITCH_TEMP" >&2; exit 72 ;;
        esac
        cleanup_switch_temp() {
          [ "${'$'}{SWITCH_TEMP_CREATED:-0}" = "1" ] || return 0
          case "${'$'}{SWITCH_TEMP:-}" in
            "${'$'}ROOT"/tmp/switch_"${'$'}PKG"_"${'$'}RUN_ID") rm -rf "${'$'}SWITCH_TEMP" 2>/dev/null || true ;;
          esac
        }
        trap cleanup_switch_temp EXIT
        mkdir -p "${'$'}ROOT/tmp" || exit 10
        if [ -e "${'$'}SWITCH_TEMP" ] || [ -L "${'$'}SWITCH_TEMP" ]; then
          echo "ERR_SWITCH_TEMP_COLLISION:${'$'}SWITCH_TEMP" >&2
          exit 11
        fi
        mkdir "${'$'}SWITCH_TEMP" || exit 11
        SWITCH_TEMP_CREATED=1
        SWITCH_REQUIRE_CE=${if (rule.includeCe) "1" else "0"}
        ${PermissionStateShell.functions()}
        ${ensureCloneCeReadyScript(settings, rule.includeCe, settings.autoUnlockClone, settings.stopCloneAfterTask)}
        TRY_USER=${settings.cloneUserId}
        STATE=${'$'}(am get-started-user-state "${'$'}TRY_USER" 2>/dev/null || true)
        echo "PROBE_USER=${'$'}TRY_USER STATE=${'$'}STATE"
        case "${'$'}STATE" in
          *RUNNING_UNLOCKED*) ;;
          *) echo "ERR_USER_NOT_UNLOCKED:${'$'}TRY_USER:${'$'}STATE" >&2; exit 82 ;;
        esac
        cmd package list packages --user "${'$'}TRY_USER" 2>/dev/null | grep -qx "package:${'$'}PKG" || {
          echo "ERR_PACKAGE_NOT_LISTED:${'$'}TRY_USER" >&2
          exit 42
        }
        am force-stop --user "${'$'}TRY_USER" "${'$'}PKG" >/dev/null 2>&1 || {
          echo "ERR_FORCE_STOP_FAILED:${'$'}TRY_USER:${'$'}PKG" >&2
          exit 76
        }
        count_items() {
          [ -d "${'$'}1" ] || { echo 0; return 0; }
          (cd "${'$'}1" && find . -mindepth 1 2>/dev/null | wc -l | tr -d ' ')
        }
        record_live_part() {
          LINK_NAME="${'$'}1"
          shift
          RECORDED_PARTS=${'$'}((RECORDED_PARTS + 1))
          for SRC in "${'$'}@"; do
            [ -d "${'$'}SRC" ] || { echo "SKIP_MISSING:${'$'}SRC"; continue; }
            SRC_ITEMS=${'$'}(count_items "${'$'}SRC")
            uclone_set_source_items "${'$'}LINK_NAME" "${'$'}SRC_ITEMS"
            echo "PROBE_PATH:${'$'}SRC ITEMS=${'$'}SRC_ITEMS"
            if [ "${'$'}SRC_ITEMS" -le 0 ]; then
              printf '%s\n' empty > "${'$'}SWITCH_TEMP/.state/${'$'}LINK_NAME" || exit 14
              echo "LIVE_SOURCE_STATE:${'$'}LINK_NAME state=empty path=${'$'}SRC"
              return 0
            fi
            ln -s "${'$'}SRC" "${'$'}SWITCH_TEMP/${'$'}LINK_NAME" || exit 14
            printf '%s\n' data > "${'$'}SWITCH_TEMP/.state/${'$'}LINK_NAME" || exit 14
            LINKED_PARTS=${'$'}((LINKED_PARTS + 1))
            echo "LIVE_SOURCE_STATE:${'$'}LINK_NAME state=data path=${'$'}SRC items=${'$'}SRC_ITEMS"
            return 0
          done
          uclone_set_source_items "${'$'}LINK_NAME" 0
          printf '%s\n' absent > "${'$'}SWITCH_TEMP/.state/${'$'}LINK_NAME" || exit 14
          echo "LIVE_SOURCE_STATE:${'$'}LINK_NAME state=absent"
          return 0
        }
        LINKED_PARTS=0
        RECORDED_PARTS=0
        mkdir -p "${'$'}SWITCH_TEMP/.state" || exit 14
        ${if (rule.includeCe) "record_live_part ce \"/data/user/${'$'}TRY_USER/${'$'}PKG\" \"/data_mirror/data_ce/null/${'$'}TRY_USER/${'$'}PKG\" \"/data_mirror/data_ce/${'$'}TRY_USER/${'$'}PKG\"" else ":"}
        ${if (rule.includeDe) "record_live_part de \"/data/user_de/${'$'}TRY_USER/${'$'}PKG\" \"/data_mirror/data_de/null/${'$'}TRY_USER/${'$'}PKG\" \"/data_mirror/data_de/${'$'}TRY_USER/${'$'}PKG\"" else ":"}
        ${if (rule.includeExternal) "record_live_part external \"/data/media/${'$'}TRY_USER/Android/data/${'$'}PKG\" \"/storage/emulated/${'$'}TRY_USER/Android/data/${'$'}PKG\"" else ":"}
        ${if (rule.includeMedia) "record_live_part media \"/data/media/${'$'}TRY_USER/Android/media/${'$'}PKG\" \"/storage/emulated/${'$'}TRY_USER/Android/media/${'$'}PKG\"" else ":"}
        ${if (rule.includeObb) "record_live_part obb \"/data/media/${'$'}TRY_USER/Android/obb/${'$'}PKG\" \"/storage/emulated/${'$'}TRY_USER/Android/obb/${'$'}PKG\"" else ":"}
        ${if (rule.includePermissions) "uclone_capture_permission_state \"${'$'}SWITCH_TEMP/permissions\" \"${'$'}TRY_USER\" || echo \"WARN_PERMISSION_CAPTURE_SKIPPED:${'$'}TRY_USER\"" else ":"}
        if [ "${'$'}SWITCH_REQUIRE_CE" = "1" ]; then
          SWITCH_CE_STATE=${'$'}(sed -n '1p' "${'$'}SWITCH_TEMP/.state/ce" 2>/dev/null | tr -d '\r')
          case "${'$'}SWITCH_CE_STATE" in
            data|empty|absent) ;;
            *) echo "ERR_SWITCH_CE_STATE:${'$'}TRY_USER:${'$'}SWITCH_CE_STATE" >&2; exit 44 ;;
          esac
        fi
        [ "${'$'}RECORDED_PARTS" -gt 0 ] || {
          echo "ERR_NO_DATA_PART_SELECTED:user=${'$'}TRY_USER package=${'$'}PKG" >&2
          exit 44
        }
        echo "SWITCH_SOURCE_READY=${'$'}SWITCH_TEMP mode=live"
        echo "SWITCH_SOURCE_USER=${'$'}TRY_USER"
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
        directSource: Boolean = false,
        sourceStateAware: Boolean = false,
        directSourceExcludeCache: Boolean = false,
        restoreRule: AppRule? = null,
        rollbackProtection: RollbackProtection = RollbackProtection.FRESH,
        cleanupRollbackOnSuccess: Boolean = false,
        backupCopyPassLabel: String? = null,
        restoreCopyPassLabel: String? = null,
        expectedCopyPasses: Int? = null,
        manageCloneLifecycleAfterTask: Boolean = writeSwitchMarker || clearSwitchMarker,
    ): String {
        val sourceRoot = sourcePrefix.ifBlank { "${settings.rootDir}/snapshots/$packageName/active" }
        val selectedRestoreParts = buildList {
            if (restoreRule?.includeCe != false) add("ce")
            if (restoreRule?.includeDe != false) add("de")
            if (restoreRule?.includeExternal != false) add("external")
            if (restoreRule?.includeMedia != false) add("media")
            if (restoreRule?.includeObb != false) add("obb")
        }.joinToString(" ")
        val restorePermissions = restoreRule?.includePermissions ?: settings.includePermissions
        val activeAssignment = when (sourceKind) {
            RestoreSourceKind.SWITCH_TEMP -> "ACTIVE=\"${'$'}ROOT/tmp/switch_${'$'}{PKG}_${'$'}RUN_ID\""
            else -> "ACTIVE=${shellQuote(sourceRoot)}"
        }
        val sourceKindToken = when (sourceKind) {
            RestoreSourceKind.ACTIVE -> "active"
            RestoreSourceKind.ROLLBACK -> "rollback"
            RestoreSourceKind.SWITCH_TEMP -> "switch_temp"
            RestoreSourceKind.CLONE_ROLLBACK -> "clone_rollback"
        }
        sourceRollbackId?.let(::requireSafeRollbackId)
        check(rollbackProtection != RollbackProtection.EXISTING || clearSwitchMarker) {
            "Existing transaction rollback is only valid for a CLONE to MAIN return"
        }
        check(rollbackProtection != RollbackProtection.NONE || clearSwitchMarker) {
            "No-rollback protection is only valid for the explicit dangerous return mode"
        }
        return """
            set -u
            ROOT=${shellQuote(settings.rootDir)}
            PKG=${shellQuote(packageName)}
            APP_PKG=${shellQuote(appPackage)}
            DST_USER=$targetUserId
            TS=${'$'}(date +%Y%m%d-%H%M%S)
            RUN_ID="${'$'}{TS}_${'$'}${'$'}"
            $activeAssignment
            SOURCE_KIND=${shellQuote(sourceKindToken)}
            SOURCE_ROLLBACK_ID=${shellQuote(sourceRollbackId.orEmpty())}
            MAIN_RETURN_POLICY=${shellQuote(settings.mainReturnPointPolicy.name)}
            MANAGE_MAIN_STATE=${if (targetUserId == settings.mainUserId && rollbackRootName == "rollback") "1" else "0"}
            EXPLICIT_SWITCH_TO_CLONE=${if (writeSwitchMarker) "1" else "0"}
            EXPLICIT_RESTORE_MAIN=${if (clearSwitchMarker) "1" else "0"}
            ROLLBACK_ID="$rollbackName"
            ROLLBACK="${'$'}ROOT/$rollbackRootName/${'$'}PKG/$rollbackName"
            ROLLBACK_CREATED=0
            ROLLBACK_FINALIZED=${if (rollbackProtection == RollbackProtection.FRESH) "0" else "1"}
            UCLONE_COPY_PASSES=0
            uclone_copy_pass_begin() {
              UCLONE_COPY_PASS_LABEL="${'$'}1"
              UCLONE_COPY_PASSES=${'$'}((UCLONE_COPY_PASSES + 1))
              echo "UCLONE_COPY_PASS_BEGIN:index=${'$'}UCLONE_COPY_PASSES label=${'$'}UCLONE_COPY_PASS_LABEL"
            }
            uclone_copy_pass_end() {
              echo "UCLONE_COPY_PASS_END:index=${'$'}UCLONE_COPY_PASSES label=${'$'}1"
            }
            ${metricsScript()}
            ${storagePreflightScript()}
            ${PermissionStateShell.functions()}
            ${StateBackupShell.functions()}
            uclone_stage_begin PRECHECK
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
            if [ -n "${'$'}SOURCE_ROLLBACK_ID" ]; then
              validate_rollback_id "${'$'}SOURCE_ROLLBACK_ID"
              EXPECTED_ACTIVE="${'$'}ROOT/rollback/${'$'}PKG/${'$'}SOURCE_ROLLBACK_ID"
            elif [ "${'$'}SOURCE_KIND" = "switch_temp" ]; then
              EXPECTED_ACTIVE="${'$'}ROOT/tmp/switch_${'$'}{PKG}_${'$'}RUN_ID"
            elif [ "${'$'}SOURCE_KIND" = "clone_rollback" ]; then
              EXPECTED_ACTIVE="${'$'}ROOT/clone_rollback/${'$'}PKG/latest"
            else
              EXPECTED_ACTIVE="${'$'}ROOT/snapshots/${'$'}PKG/active"
            fi
            uclone_stage_end
            uclone_stage_begin SOURCE_PREPARE
            UCLONE_SOURCE_ITEMS_CE=""
            UCLONE_SOURCE_ITEMS_DE=""
            UCLONE_SOURCE_ITEMS_EXTERNAL=""
            UCLONE_SOURCE_ITEMS_MEDIA=""
            UCLONE_SOURCE_ITEMS_OBB=""
            uclone_set_source_items() {
              UCLONE_SOURCE_PART="${'$'}1"
              UCLONE_SOURCE_COUNT="${'$'}2"
              case "${'$'}UCLONE_SOURCE_PART" in
                ce) UCLONE_SOURCE_ITEMS_CE="${'$'}UCLONE_SOURCE_COUNT" ;;
                de) UCLONE_SOURCE_ITEMS_DE="${'$'}UCLONE_SOURCE_COUNT" ;;
                external) UCLONE_SOURCE_ITEMS_EXTERNAL="${'$'}UCLONE_SOURCE_COUNT" ;;
                media) UCLONE_SOURCE_ITEMS_MEDIA="${'$'}UCLONE_SOURCE_COUNT" ;;
                obb) UCLONE_SOURCE_ITEMS_OBB="${'$'}UCLONE_SOURCE_COUNT" ;;
                *) return 1 ;;
              esac
            }
            uclone_get_source_items() {
              case "${'$'}1" in
                ce) echo "${'$'}UCLONE_SOURCE_ITEMS_CE" ;;
                de) echo "${'$'}UCLONE_SOURCE_ITEMS_DE" ;;
                external) echo "${'$'}UCLONE_SOURCE_ITEMS_EXTERNAL" ;;
                media) echo "${'$'}UCLONE_SOURCE_ITEMS_MEDIA" ;;
                obb) echo "${'$'}UCLONE_SOURCE_ITEMS_OBB" ;;
                *) echo "" ;;
              esac
            }
            UCLONE_SOURCE_MATERIALIZE_STARTED_AT=${'$'}(uclone_now_ms)
            $prepareSourceScript
            uclone_perf_emit source_materialize all "${'$'}UCLONE_SOURCE_MATERIALIZE_STARTED_AT"
            UCLONE_SOURCE_VALIDATE_STARTED_AT=${'$'}(uclone_now_ms)
            [ "${'$'}ACTIVE" = "${'$'}EXPECTED_ACTIVE" ] || { echo "ERR_BAD_RESTORE_SOURCE:${'$'}ACTIVE" >&2; exit 72; }
            [ -d "${'$'}ACTIVE" ] || { echo "ERR_SNAPSHOT_MISSING:${'$'}ACTIVE" >&2; exit 51; }
            [ "${'$'}ACTIVE" != "${'$'}ROLLBACK" ] || { echo "ERR_ROLLBACK_SOURCE_CONFLICT:${'$'}ACTIVE" >&2; exit 61; }
            SOURCE_STATE=${'$'}(uclone_manifest_state_kind "${'$'}ACTIVE" 2>/dev/null || true)
            case "${'$'}SOURCE_STATE" in
              MAIN|CLONE) ;;
              *)
                case "${'$'}SOURCE_KIND" in
                  active|switch_temp|clone_rollback) SOURCE_STATE=CLONE ;;
                  rollback)
                    SOURCE_STATE=UNKNOWN
                    CURRENT_RETURN_ID=${'$'}(uclone_read_main_return_id 2>/dev/null || true)
                    if [ -n "${'$'}SOURCE_ROLLBACK_ID" ] && [ "${'$'}CURRENT_RETURN_ID" = "${'$'}SOURCE_ROLLBACK_ID" ]; then
                      SOURCE_STATE=MAIN
                    fi
                    ;;
                  *) SOURCE_STATE=UNKNOWN ;;
                esac
                ;;
            esac
            PREVIOUS_MAIN_RETURN_ID=""
            CURRENT_TARGET_STATE=CLONE
            CURRENT_MAIN_CONFIRMED=0
            if [ "${'$'}MANAGE_MAIN_STATE" = "1" ]; then
              PREVIOUS_MAIN_RETURN_ID=${'$'}(uclone_read_main_return_id 2>/dev/null || true)
              CURRENT_TARGET_STATE=${'$'}(uclone_current_main_state)
              if [ "${'$'}CURRENT_TARGET_STATE" = "MAIN" ] && uclone_confirmed_main_state; then
                CURRENT_MAIN_CONFIRMED=1
              fi
              if [ "${'$'}EXPLICIT_SWITCH_TO_CLONE" = "1" ] && [ "${'$'}CURRENT_TARGET_STATE" != "MAIN" ]; then
                echo "ERR_STATE_MISMATCH:expected=MAIN actual=${'$'}CURRENT_TARGET_STATE" >&2
                exit 74
              fi
              if [ "${'$'}EXPLICIT_RESTORE_MAIN" = "1" ] && [ "${'$'}CURRENT_TARGET_STATE" != "CLONE" ]; then
                echo "ERR_STATE_MISMATCH:expected=CLONE actual=${'$'}CURRENT_TARGET_STATE" >&2
                exit 74
              fi
            fi
            NEXT_MAIN_STATE="${'$'}SOURCE_STATE"
            [ "${'$'}EXPLICIT_SWITCH_TO_CLONE" != "1" ] || NEXT_MAIN_STATE=CLONE
            [ "${'$'}EXPLICIT_RESTORE_MAIN" != "1" ] || NEXT_MAIN_STATE=MAIN
            if [ "${'$'}MANAGE_MAIN_STATE" = "1" ] &&
               [ "${'$'}CURRENT_TARGET_STATE" = "MAIN" ] &&
               [ "${'$'}NEXT_MAIN_STATE" = "CLONE" ]; then
              PERSISTENT_MAIN_DIR="${'$'}ROOT/rollback/${'$'}PKG/persistent_main"
              if [ -e "${'$'}PERSISTENT_MAIN_DIR" ] || [ -L "${'$'}PERSISTENT_MAIN_DIR" ]; then
                uclone_valid_state_backup "${'$'}PERSISTENT_MAIN_DIR" MAIN || {
                  echo "ERR_MAIN_RETURN_INVALID:${'$'}PERSISTENT_MAIN_DIR" >&2
                  exit 53
                }
              fi
            fi
            if [ "${'$'}MANAGE_MAIN_STATE" = "1" ] &&
               [ "${'$'}CURRENT_TARGET_STATE" = "MAIN" ] &&
               [ "${'$'}NEXT_MAIN_STATE" = "CLONE" ] &&
               [ "${'$'}EXPLICIT_SWITCH_TO_CLONE" != "1" ]; then
              uclone_valid_state_backup "${'$'}ROOT/rollback/${'$'}PKG/persistent_main" MAIN || {
                echo "ERR_MAIN_RETURN_MISSING:normal_switch_required" >&2
                exit 53
              }
            fi
            echo "DATA_STATE_TRANSITION:current=${'$'}CURRENT_TARGET_STATE source=${'$'}SOURCE_STATE next=${'$'}NEXT_MAIN_STATE"
            UID_VALUE=${'$'}(cmd package list packages -U --user "${'$'}DST_USER" | awk -v p="package:${'$'}PKG" '${'$'}1==p { sub("uid:","",${'$'}2); print ${'$'}2; exit }')
            [ -n "${'$'}UID_VALUE" ] || { echo "ERR_TARGET_UID_MISSING" >&2; exit 52; }
            mkdir -p "${'$'}ROOT/$rollbackRootName/${'$'}PKG" "${'$'}ROOT/tmp" || exit 53
            count_items() {
              [ -d "${'$'}1" ] || { echo 0; return 0; }
              (cd "${'$'}1" && find . -mindepth 1 2>/dev/null | wc -l | tr -d ' ')
            }
            has_items() {
              [ -d "${'$'}1" ] || return 1
              [ -n "${'$'}(find "${'$'}1" -mindepth 1 -print -quit 2>/dev/null)" ]
            }
            UCLONE_ESTIMATED_KB=0
            ${if (directSource) """
            uclone_validate_direct_source "${'$'}ACTIVE" "${if (sourceKind == RestoreSourceKind.SWITCH_TEMP) "live" else "managed"}" $selectedRestoreParts || {
              echo "ERR_BAD_RESTORE_SOURCE_LAYOUT:${'$'}ACTIVE" >&2
              exit 72
            }
            for RESTORE_SOURCE_PART in $selectedRestoreParts; do
              if RESTORE_PART_KB=${'$'}(uclone_read_size_hint "${'$'}ACTIVE" "${'$'}RESTORE_SOURCE_PART"); then
                echo "RESTORE_SOURCE_SIZE_HINT:${'$'}RESTORE_SOURCE_PART=${'$'}RESTORE_PART_KB"
              else
                RESTORE_PART_KB=${'$'}(${if (sourceKind == RestoreSourceKind.SWITCH_TEMP) "uclone_live_source_dir_kb_strict" else "uclone_dir_kb_strict"} "${'$'}ACTIVE/${'$'}RESTORE_SOURCE_PART") || {
                  echo "ERR_SPACE_ESTIMATE:restore_source:${'$'}RESTORE_SOURCE_PART" >&2
                  exit 75
                }
                echo "RESTORE_SOURCE_SIZE_SCAN:${'$'}RESTORE_SOURCE_PART=${'$'}RESTORE_PART_KB"
              fi
              UCLONE_ESTIMATED_KB=${'$'}(uclone_decimal_add "${'$'}UCLONE_ESTIMATED_KB" "${'$'}RESTORE_PART_KB") || { echo "ERR_SPACE_ESTIMATE:restore_source:${'$'}RESTORE_SOURCE_PART" >&2; exit 75; }
            done
            RESTORE_SOURCE_KB="${'$'}UCLONE_ESTIMATED_KB"
            """.trimIndent() else """
            uclone_add_dir_kb "${'$'}ACTIVE/ce"
            uclone_add_dir_kb "${'$'}ACTIVE/de"
            uclone_add_dir_kb "${'$'}ACTIVE/external"
            uclone_add_dir_kb "${'$'}ACTIVE/media"
            uclone_add_dir_kb "${'$'}ACTIVE/obb"
            RESTORE_SOURCE_KB="${'$'}UCLONE_ESTIMATED_KB"
            """.trimIndent()}
            RESTORE_TARGET_CE_KB=0
            RESTORE_TARGET_DE_KB=0
            RESTORE_TARGET_EXTERNAL_KB=0
            RESTORE_TARGET_MEDIA_KB=0
            RESTORE_TARGET_OBB_KB=0
            ${if (rollbackProtection == RollbackProtection.FRESH) """
            ${if (restoreRule?.includeCe != false) "RESTORE_TARGET_CE_KB=${'$'}(uclone_dir_kb_strict \"/data/user/${'$'}DST_USER/${'$'}PKG\") || { echo \"ERR_SPACE_ESTIMATE:restore_target:ce\" >&2; exit 75; }" else ":"}
            ${if (restoreRule?.includeDe != false) "RESTORE_TARGET_DE_KB=${'$'}(uclone_dir_kb_strict \"/data/user_de/${'$'}DST_USER/${'$'}PKG\") || { echo \"ERR_SPACE_ESTIMATE:restore_target:de\" >&2; exit 75; }" else ":"}
            ${if (restoreRule?.includeExternal != false) "RESTORE_TARGET_EXTERNAL_KB=${'$'}(uclone_dir_kb_strict \"/data/media/${'$'}DST_USER/Android/data/${'$'}PKG\") || { echo \"ERR_SPACE_ESTIMATE:restore_target:external\" >&2; exit 75; }" else ":"}
            ${if (restoreRule?.includeMedia != false) "RESTORE_TARGET_MEDIA_KB=${'$'}(uclone_dir_kb_strict \"/data/media/${'$'}DST_USER/Android/media/${'$'}PKG\") || { echo \"ERR_SPACE_ESTIMATE:restore_target:media\" >&2; exit 75; }" else ":"}
            ${if (restoreRule?.includeObb != false) "RESTORE_TARGET_OBB_KB=${'$'}(uclone_dir_kb_strict \"/data/media/${'$'}DST_USER/Android/obb/${'$'}PKG\") || { echo \"ERR_SPACE_ESTIMATE:restore_target:obb\" >&2; exit 75; }" else ":"}
            """.trimIndent() else ":"}
            RESTORE_TARGET_KB=0
            for RESTORE_PART_KB in \
              "${'$'}RESTORE_TARGET_CE_KB" \
              "${'$'}RESTORE_TARGET_DE_KB" \
              "${'$'}RESTORE_TARGET_EXTERNAL_KB" \
              "${'$'}RESTORE_TARGET_MEDIA_KB" \
              "${'$'}RESTORE_TARGET_OBB_KB"; do
              RESTORE_TARGET_KB=${'$'}(uclone_decimal_add "${'$'}RESTORE_TARGET_KB" "${'$'}RESTORE_PART_KB") || { RESTORE_TARGET_KB=""; break; }
            done
            RESTORE_REQUIRED_KB=${'$'}(${if (directSource) "uclone_decimal_max" else "uclone_decimal_add"} "${'$'}RESTORE_SOURCE_KB" "${'$'}RESTORE_TARGET_KB") || RESTORE_REQUIRED_KB=""
            uclone_require_space "${'$'}RESTORE_REQUIRED_KB" "${if (directSource) "restore_direct" else "restore_prepared_and_rollback"}"
            PREPARED_ROOT=${if (directSource) "\"${'$'}ACTIVE\"" else "\"${'$'}ROOT/tmp/prepared_${'$'}{PKG}_${'$'}TS\""}
            cleanup_restore_prepared() {
              ${if (directSource) ":" else """
              case "${'$'}{PREPARED_ROOT:-}" in
                "${'$'}ROOT"/tmp/prepared_"${'$'}PKG"_"${'$'}TS") rm -rf "${'$'}PREPARED_ROOT" 2>/dev/null || true ;;
              esac
              """.trimIndent()}
            }
            cleanup_restore_before_transaction() {
              cleanup_restore_prepared
              if [ "${'$'}{ROLLBACK_CREATED:-0}" = "1" ] && [ "${'$'}{ROLLBACK_FINALIZED:-0}" != "1" ]; then
                ROLLBACK_SAFE_PREFIX="${'$'}ROOT/$rollbackRootName/${'$'}PKG/"
                case "${'$'}ROLLBACK" in
                  "${'$'}ROLLBACK_SAFE_PREFIX"*) rm -rf "${'$'}ROLLBACK" 2>/dev/null || true ;;
                esac
              fi
              if command -v cleanup_on_exit >/dev/null 2>&1; then
                cleanup_on_exit
              elif command -v cleanup_switch_temp >/dev/null 2>&1; then
                cleanup_switch_temp
              fi
            }
            create_fresh_rollback_dir() {
              if [ -e "${'$'}ROLLBACK" ] || [ -L "${'$'}ROLLBACK" ]; then
                echo "ERR_ROLLBACK_ID_COLLISION:${'$'}ROLLBACK" >&2
                return 54
              fi
              mkdir -p "${'$'}ROLLBACK" || return 54
              ROLLBACK_CREATED=1
              mkdir "${'$'}ROLLBACK/.state" || return 54
            }
            trap cleanup_restore_before_transaction EXIT
            ${if (directSource && sourceStateAware) """
            PREPARED_PARTS=0
            for PREPARED_NAME in $selectedRestoreParts; do
              PREPARED_STATE_FILE="${'$'}PREPARED_ROOT/.state/${'$'}PREPARED_NAME"
              [ -f "${'$'}PREPARED_STATE_FILE" ] && [ ! -L "${'$'}PREPARED_STATE_FILE" ] || continue
              PREPARED_STATE=${'$'}(sed -n '1p' "${'$'}PREPARED_STATE_FILE" | tr -d '\r')
              case "${'$'}PREPARED_STATE" in data|empty|absent) ;; *) echo "ERR_SOURCE_STATE_INVALID:${'$'}PREPARED_NAME:${'$'}PREPARED_STATE" >&2; exit 69 ;; esac
              if [ "${'$'}PREPARED_STATE" = "data" ]; then
                [ -d "${'$'}PREPARED_ROOT/${'$'}PREPARED_NAME" ] || { echo "ERR_SOURCE_DATA_MISSING:${'$'}PREPARED_NAME" >&2; exit 69; }
                PREPARED_ITEMS=${'$'}(uclone_get_source_items "${'$'}PREPARED_NAME")
                case "${'$'}PREPARED_ITEMS" in
                  ''|*[!0-9]*)
                    PREPARED_ITEMS=${'$'}(count_items "${'$'}PREPARED_ROOT/${'$'}PREPARED_NAME")
                    uclone_set_source_items "${'$'}PREPARED_NAME" "${'$'}PREPARED_ITEMS"
                    ;;
                esac
                [ "${'$'}PREPARED_ITEMS" -gt 0 ] || { echo "ERR_SOURCE_DATA_EMPTY:${'$'}PREPARED_NAME" >&2; exit 69; }
              else
                uclone_set_source_items "${'$'}PREPARED_NAME" 0
              fi
              PREPARED_PARTS=${'$'}((PREPARED_PARTS + 1))
              echo "DIRECT_SOURCE_STATE:${'$'}PREPARED_NAME state=${'$'}PREPARED_STATE"
            done
            """.trimIndent() else if (directSource) """
            PREPARED_PARTS=0
            for PREPARED_NAME in ce de external media obb; do
              [ -d "${'$'}PREPARED_ROOT/${'$'}PREPARED_NAME" ] || continue
              PREPARED_ITEMS=${'$'}(count_items "${'$'}PREPARED_ROOT/${'$'}PREPARED_NAME")
              [ "${'$'}PREPARED_ITEMS" -gt 0 ] || continue
              uclone_set_source_items "${'$'}PREPARED_NAME" "${'$'}PREPARED_ITEMS"
              PREPARED_PARTS=${'$'}((PREPARED_PARTS + 1))
              echo "DIRECT_SOURCE:${'$'}PREPARED_NAME ITEMS=${'$'}PREPARED_ITEMS"
            done
            """.trimIndent() else """
            UCLONE_PREPARED_MATERIALIZE_STARTED_AT=${'$'}(uclone_now_ms)
            rm -rf "${'$'}PREPARED_ROOT"
            mkdir -p "${'$'}PREPARED_ROOT" || exit 56
            PREPARED_PARTS=0
            prepare_restore_part() {
              PREPARE_SRC="${'$'}1"
              PREPARE_NAME="${'$'}2"
              [ -d "${'$'}PREPARE_SRC" ] || return 0
              PREPARE_ITEMS=${'$'}(count_items "${'$'}PREPARE_SRC")
              [ "${'$'}PREPARE_ITEMS" -gt 0 ] || { echo "ERR_EMPTY_SNAPSHOT_PART:${'$'}PREPARE_SRC" >&2; exit 64; }
              PREPARE_DST="${'$'}PREPARED_ROOT/${'$'}PREPARE_NAME"
              mkdir -p "${'$'}PREPARE_DST" || exit 56
              (cd "${'$'}PREPARE_SRC" && tar -cpf - .) | (cd "${'$'}PREPARE_DST" && tar -xopf -) || exit 57
              PREPARED_ITEMS=${'$'}(count_items "${'$'}PREPARE_DST")
              [ "${'$'}PREPARED_ITEMS" -gt 0 ] || { echo "ERR_EXTRACT_EMPTY:${'$'}PREPARE_SRC" >&2; exit 69; }
              uclone_set_source_items "${'$'}PREPARE_NAME" "${'$'}PREPARED_ITEMS"
              PREPARED_SIZE_KB=${'$'}(uclone_dir_kb "${'$'}PREPARE_DST")
              UCLONE_SCANNED_FILES=${'$'}((UCLONE_SCANNED_FILES + PREPARE_ITEMS))
              UCLONE_COPIED_FILES=${'$'}((UCLONE_COPIED_FILES + PREPARED_ITEMS))
              uclone_add_written_kb "${'$'}PREPARED_SIZE_KB"
              PREPARED_PARTS=${'$'}((PREPARED_PARTS + 1))
              echo "PREPARED:${'$'}PREPARE_NAME ITEMS=${'$'}PREPARED_ITEMS"
            }
            prepare_restore_part "${'$'}ACTIVE/ce" "ce"
            prepare_restore_part "${'$'}ACTIVE/de" "de"
            prepare_restore_part "${'$'}ACTIVE/external" "external"
            prepare_restore_part "${'$'}ACTIVE/media" "media"
            prepare_restore_part "${'$'}ACTIVE/obb" "obb"
            uclone_record_temp_path "${'$'}PREPARED_ROOT"
            uclone_perf_emit source_materialize prepared "${'$'}UCLONE_PREPARED_MATERIALIZE_STARTED_AT"
            """.trimIndent()}
            [ "${'$'}PREPARED_PARTS" -gt 0 ] || { echo "ERR_NOTHING_PREPARED:${'$'}ACTIVE" >&2; exit 62; }
            uclone_perf_emit source_validate all "${'$'}UCLONE_SOURCE_VALIDATE_STARTED_AT"
            uclone_stage_end
            force_stop_package_users() {
              am force-stop --user "${'$'}DST_USER" "${'$'}PKG" >/dev/null 2>&1 || {
                echo "ERR_FORCE_STOP_FAILED:${'$'}DST_USER:${'$'}PKG" >&2
                return 1
              }
              echo "FORCE_STOP_USERS:${'$'}DST_USER"
            }
            uclone_stage_begin TARGET_STOP
            force_stop_package_users || exit 76
            UCLONE_TARGET_STOPPED_AT=${'$'}(uclone_now_ms)
            uclone_stage_end
            BACKUP_PARTS=0
            RESTORED_PARTS=0
            RESTORED_ITEMS=0
            backup_dir() {
              SRC="${'$'}1"
              DST="${'$'}2"
              PART_NAME="${'$'}3"
              BACKUP_SIZE_KB="${'$'}{4:-0}"
              case "${'$'}BACKUP_SIZE_KB" in ''|*[!0-9]*) BACKUP_SIZE_KB=0 ;; esac
              mkdir -p "${'$'}ROLLBACK/.state" || exit 54
              if [ ! -d "${'$'}SRC" ]; then
                printf '%s\n' "absent" > "${'$'}ROLLBACK/.state/${'$'}PART_NAME" || exit 54
                return 0
              fi
              SRC_ITEMS=${'$'}(count_items "${'$'}SRC")
              if [ "${'$'}SRC_ITEMS" -le 0 ]; then
                printf '%s\n' "empty" > "${'$'}ROLLBACK/.state/${'$'}PART_NAME" || exit 54
                echo "SKIP_BACKUP_EMPTY:${'$'}SRC"
                return 0
              fi
              rm -rf "${'$'}DST"
              mkdir -p "${'$'}DST" || exit 54
              UCLONE_UNDO_COPY_STARTED_AT=${'$'}(uclone_now_ms)
              UCLONE_UNDO_COPY_EXIT=0
              (cd "${'$'}SRC" && tar -cpf - .) | (cd "${'$'}DST" && tar -xopf -) || UCLONE_UNDO_COPY_EXIT=${'$'}?
              uclone_perf_emit transaction_undo_copy "${'$'}PART_NAME" "${'$'}UCLONE_UNDO_COPY_STARTED_AT"
              [ "${'$'}UCLONE_UNDO_COPY_EXIT" -eq 0 ] || exit 55
              UCLONE_UNDO_VERIFY_STARTED_AT=${'$'}(uclone_now_ms)
              if ! has_items "${'$'}DST"; then
                uclone_perf_emit post_copy_verify "${'$'}PART_NAME" "${'$'}UCLONE_UNDO_VERIFY_STARTED_AT"
                echo "ERR_BACKUP_EMPTY:${'$'}SRC" >&2
                exit 63
              fi
              uclone_perf_emit post_copy_verify "${'$'}PART_NAME" "${'$'}UCLONE_UNDO_VERIFY_STARTED_AT"
              BACKUP_ITEMS="${'$'}SRC_ITEMS"
              printf '%s\n' "data" > "${'$'}ROLLBACK/.state/${'$'}PART_NAME" || exit 54
              BACKUP_PARTS=${'$'}((BACKUP_PARTS + 1))
              UCLONE_SCANNED_FILES=${'$'}((UCLONE_SCANNED_FILES + SRC_ITEMS))
              UCLONE_COPIED_FILES=${'$'}((UCLONE_COPIED_FILES + BACKUP_ITEMS))
              uclone_add_written_kb "${'$'}BACKUP_SIZE_KB"
              uclone_record_temp_kb "${'$'}BACKUP_SIZE_KB"
              echo "BACKUP:${'$'}SRC ITEMS=${'$'}BACKUP_ITEMS"
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
              find "${'$'}CLEAR_TARGET" -mindepth 1 -maxdepth 1 -exec rm -rf {} \; || exit 68
            }
            read_target_context() {
              CONTEXT_TARGET="${'$'}1"
              ls -Zd "${'$'}CONTEXT_TARGET" 2>/dev/null | awk '{print ${'$'}1; exit}'
            }
            target_owner_for() {
              OWNER_TARGET="${'$'}1"
              OWNER_UID_ARG="${'$'}2"
              OWNER_KIND="${'$'}3"
              if [ -n "${'$'}OWNER_UID_ARG" ]; then
                echo "${'$'}OWNER_UID_ARG:${'$'}OWNER_UID_ARG"
                return 0
              fi
              case "${'$'}OWNER_KIND" in
                app) echo "${'$'}UID_VALUE:${'$'}UID_VALUE"; return 0 ;;
                media) echo "${'$'}UID_VALUE:1078"; return 0 ;;
              esac
              EXISTING_OWNER=${'$'}(stat -c '%u:%g' "${'$'}OWNER_TARGET" 2>/dev/null || true)
              case "${'$'}EXISTING_OWNER" in
                *:*) echo "${'$'}EXISTING_OWNER" ;;
                *) echo "" ;;
              esac
            }
            apply_target_security() {
              SEC_TARGET="${'$'}1"
              SEC_OWNER="${'$'}2"
              SEC_CONTEXT="${'$'}3"
              SEC_PART="${'$'}{4:-all}"
              UCLONE_OWNER_FIX_STARTED_AT=${'$'}(uclone_now_ms)
              if [ -n "${'$'}SEC_OWNER" ]; then
                if ! chown -hR "${'$'}SEC_OWNER" "${'$'}SEC_TARGET"; then
                  uclone_perf_emit owner_fix "${'$'}SEC_PART" "${'$'}UCLONE_OWNER_FIX_STARTED_AT"
                  exit 59
                fi
                OWNER_UID=${'$'}(echo "${'$'}SEC_OWNER" | cut -d: -f1)
                case "${'$'}OWNER_UID" in
                  ''|*[!0-9]*) ;;
                  *)
                    APP_ID=${'$'}((OWNER_UID % 100000))
                    CACHE_GID=${'$'}((20000 + APP_ID))
                    [ -d "${'$'}SEC_TARGET/cache" ] && chown -hR "${'$'}OWNER_UID:${'$'}CACHE_GID" "${'$'}SEC_TARGET/cache" >/dev/null 2>&1 || true
                    [ -d "${'$'}SEC_TARGET/code_cache" ] && chown -hR "${'$'}OWNER_UID:${'$'}CACHE_GID" "${'$'}SEC_TARGET/code_cache" >/dev/null 2>&1 || true
                    ;;
                esac
              fi
              uclone_perf_emit owner_fix "${'$'}SEC_PART" "${'$'}UCLONE_OWNER_FIX_STARTED_AT"
              UCLONE_SELINUX_FIX_STARTED_AT=${'$'}(uclone_now_ms)
              UCLONE_SELINUX_FIX_EXIT=0
              if [ -n "${'$'}SEC_CONTEXT" ]; then
                chcon -R -h "${'$'}SEC_CONTEXT" "${'$'}SEC_TARGET" >/dev/null 2>&1 || restorecon -RF "${'$'}SEC_TARGET" >/dev/null 2>&1 || restorecon -R "${'$'}SEC_TARGET" >/dev/null 2>&1 || UCLONE_SELINUX_FIX_EXIT=${'$'}?
              else
                restorecon -RF "${'$'}SEC_TARGET" >/dev/null 2>&1 || restorecon -R "${'$'}SEC_TARGET" >/dev/null 2>&1 || UCLONE_SELINUX_FIX_EXIT=${'$'}?
              fi
              uclone_perf_emit selinux_fix "${'$'}SEC_PART" "${'$'}UCLONE_SELINUX_FIX_STARTED_AT"
              [ "${'$'}UCLONE_SELINUX_FIX_EXIT" -eq 0 ] || exit 60
            }
            restore_part() {
              SNAP="${'$'}1"
              TARGET="${'$'}2"
              OWNER_UID="${'$'}3"
              OWNER_KIND="${'$'}4"
              PART_NAME="${'$'}5"
              validate_target_path "${'$'}TARGET"
              PREPARED="${'$'}PREPARED_ROOT/${'$'}PART_NAME"
              ${if (sourceStateAware) """
              SOURCE_STATE_FILE="${'$'}PREPARED_ROOT/.state/${'$'}PART_NAME"
              if [ ! -f "${'$'}SOURCE_STATE_FILE" ] || [ -L "${'$'}SOURCE_STATE_FILE" ]; then
                echo "SKIP_UNSELECTED_PART:${'$'}PART_NAME"
                return 0
              fi
              SOURCE_STATE=${'$'}(sed -n '1p' "${'$'}SOURCE_STATE_FILE" | tr -d '\r')
              case "${'$'}SOURCE_STATE" in
                absent)
                  TARGET_MUTATED=1
                  rm -rf "${'$'}TARGET" || exit 68
                  RESTORED_PARTS=${'$'}((RESTORED_PARTS + 1))
                  echo "RESTORED_STATE:${'$'}PART_NAME state=absent target=${'$'}TARGET"
                  return 0
                  ;;
                empty)
                  TARGET_OWNER=${'$'}(target_owner_for "${'$'}TARGET" "${'$'}OWNER_UID" "${'$'}OWNER_KIND")
                  mkdir -p "${'$'}TARGET" || exit 56
                  TARGET_CONTEXT=${'$'}(read_target_context "${'$'}TARGET")
                  case "${'$'}TARGET_CONTEXT" in u:object_r:*) ;; *) TARGET_CONTEXT="" ;; esac
                  TARGET_MUTATED=1
                  clear_target_contents "${'$'}TARGET"
                  apply_target_security "${'$'}TARGET" "${'$'}TARGET_OWNER" "${'$'}TARGET_CONTEXT" "${'$'}PART_NAME"
                  RESTORED_PARTS=${'$'}((RESTORED_PARTS + 1))
                  echo "RESTORED_STATE:${'$'}PART_NAME state=empty target=${'$'}TARGET"
                  return 0
                  ;;
                data) ;;
                *) echo "ERR_SOURCE_STATE_INVALID:${'$'}PART_NAME:${'$'}SOURCE_STATE" >&2; exit 69 ;;
              esac
              """.trimIndent() else ":"}
              if [ ! -d "${'$'}PREPARED" ]; then
                [ -d "${'$'}SNAP" ] && { echo "ERR_PREPARED_PART_MISSING:${'$'}PREPARED" >&2; exit 69; }
                echo "SKIP_PART:${'$'}SNAP"
                return 0
              fi
              PREPARED_ITEMS=${'$'}(uclone_get_source_items "${'$'}PART_NAME")
              case "${'$'}PREPARED_ITEMS" in
                ''|*[!0-9]*)
                  PREPARED_ITEMS=${'$'}(count_items "${'$'}PREPARED")
                  uclone_set_source_items "${'$'}PART_NAME" "${'$'}PREPARED_ITEMS"
                  ;;
              esac
              [ "${'$'}PREPARED_ITEMS" -gt 0 ] || { echo "ERR_PREPARED_PART_EMPTY:${'$'}PREPARED" >&2; exit 69; }
              SNAP_ITEMS="${'$'}PREPARED_ITEMS"
              TARGET_OWNER=${'$'}(target_owner_for "${'$'}TARGET" "${'$'}OWNER_UID" "${'$'}OWNER_KIND")
              mkdir -p "${'$'}TARGET" || exit 56
              TARGET_CONTEXT=${'$'}(read_target_context "${'$'}TARGET")
              case "${'$'}TARGET_CONTEXT" in u:object_r:*) ;; *) TARGET_CONTEXT="" ;; esac
              if [ -z "${'$'}TARGET_CONTEXT" ]; then
                restorecon -RF "${'$'}TARGET" >/dev/null 2>&1 || restorecon -R "${'$'}TARGET" >/dev/null 2>&1 || true
                TARGET_CONTEXT=${'$'}(read_target_context "${'$'}TARGET")
                case "${'$'}TARGET_CONTEXT" in u:object_r:*) ;; *) TARGET_CONTEXT="" ;; esac
              fi
              TARGET_MUTATED=1
              clear_target_contents "${'$'}TARGET"
              UCLONE_RESTORE_COPY_STARTED_AT=${'$'}(uclone_now_ms)
              UCLONE_RESTORE_COPY_EXIT=0
              (cd "${'$'}PREPARED" && tar -cpf - .) | (cd "${'$'}TARGET" && tar -xpf -) || UCLONE_RESTORE_COPY_EXIT=${'$'}?
              uclone_perf_emit restore_copy "${'$'}PART_NAME" "${'$'}UCLONE_RESTORE_COPY_STARTED_AT"
              [ "${'$'}UCLONE_RESTORE_COPY_EXIT" -eq 0 ] || exit 58
              ${if (directSourceExcludeCache) """
              case "${'$'}PART_NAME" in
                ce|de) rm -rf "${'$'}TARGET/cache" "${'$'}TARGET/code_cache" 2>/dev/null || true ;;
              esac
              """.trimIndent() else ":"}
              apply_target_security "${'$'}TARGET" "${'$'}TARGET_OWNER" "${'$'}TARGET_CONTEXT" "${'$'}PART_NAME"
              UCLONE_RESTORE_VERIFY_STARTED_AT=${'$'}(uclone_now_ms)
              if ! has_items "${'$'}TARGET"; then
                uclone_perf_emit post_copy_verify "${'$'}PART_NAME" "${'$'}UCLONE_RESTORE_VERIFY_STARTED_AT"
                echo "ERR_RESTORE_EMPTY:${'$'}TARGET" >&2
                exit 65
              fi
              ${if (directSourceExcludeCache) """
              case "${'$'}PART_NAME" in
                ce|de) TARGET_ITEMS=${'$'}(count_items "${'$'}TARGET") ;;
                *) TARGET_ITEMS="${'$'}PREPARED_ITEMS" ;;
              esac
              """.trimIndent() else "TARGET_ITEMS=\"${'$'}PREPARED_ITEMS\""}
              uclone_perf_emit post_copy_verify "${'$'}PART_NAME" "${'$'}UCLONE_RESTORE_VERIFY_STARTED_AT"
              RESTORED_PARTS=${'$'}((RESTORED_PARTS + 1))
              RESTORED_ITEMS=${'$'}((RESTORED_ITEMS + TARGET_ITEMS))
              UCLONE_SCANNED_FILES=${'$'}((UCLONE_SCANNED_FILES + SNAP_ITEMS))
              UCLONE_COPIED_FILES=${'$'}((UCLONE_COPIED_FILES + TARGET_ITEMS))
              echo "RESTORED:${'$'}TARGET ITEMS=${'$'}TARGET_ITEMS OWNER=${'$'}TARGET_OWNER CONTEXT=${'$'}TARGET_CONTEXT"
            }
            prune_old_rollbacks() {
              ROLLBACK_PARENT="${'$'}ROOT/rollback/${'$'}PKG"
              SOURCE_ROLLBACK_ID_FOR_PRUNE=${shellQuote(if (sourceKind == RestoreSourceKind.ROLLBACK) sourceRollbackId.orEmpty() else "")}
              SWITCH_MARKER_FOR_PRUNE="${'$'}ROOT/switches/${'$'}PKG/active"
              SWITCH_ID_FOR_PRUNE=""
              if [ -f "${'$'}SWITCH_MARKER_FOR_PRUNE" ]; then
                SWITCH_ID_FOR_PRUNE=${'$'}(sed -n '1p' "${'$'}SWITCH_MARKER_FOR_PRUNE" | tr -d '\r')
              fi
              [ -d "${'$'}ROLLBACK_PARENT" ] || return 0
              EXPECTED_CHILD_PREFIX="${'$'}ROOT/rollback/${'$'}PKG/"
              find "${'$'}ROLLBACK_PARENT" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | while IFS= read -r OLD; do
                OLD_ID=${'$'}(basename "${'$'}OLD")
                [ "${'$'}OLD_ID" = "${'$'}ROLLBACK_ID" ] && continue
                [ -n "${'$'}SOURCE_ROLLBACK_ID_FOR_PRUNE" ] && [ "${'$'}OLD_ID" = "${'$'}SOURCE_ROLLBACK_ID_FOR_PRUNE" ] && continue
                [ -n "${'$'}SWITCH_ID_FOR_PRUNE" ] && [ "${'$'}OLD_ID" = "${'$'}SWITCH_ID_FOR_PRUNE" ] && continue
                case "${'$'}OLD_ID" in persistent_main|persistent_clone|persistent_main.previous|persistent_clone.previous) continue ;; esac
                case "${'$'}OLD" in
                  "${'$'}EXPECTED_CHILD_PREFIX"*)
                    rm -rf "${'$'}OLD" && echo "PRUNED_ROLLBACK=${'$'}OLD" || echo "WARN_PRUNE_ROLLBACK_FAILED:${'$'}OLD"
                    ;;
                  *)
                    echo "WARN_SKIP_BAD_ROLLBACK_PATH:${'$'}OLD"
                    ;;
                esac
              done || exit 70
              if [ -f "${'$'}SWITCH_MARKER_FOR_PRUNE" ]; then
                SWITCH_ID_AFTER_PRUNE=${'$'}(sed -n '1p' "${'$'}SWITCH_MARKER_FOR_PRUNE" | tr -d '\r')
                case "${'$'}SWITCH_ID_AFTER_PRUNE" in
                  "${'$'}UCLONE_UNKNOWN_STATE_MARKER"|"${'$'}UCLONE_MAIN_STATE_MARKER") : ;;
                  *)
                    if [ ! -f "${'$'}ROLLBACK_PARENT/${'$'}SWITCH_ID_AFTER_PRUNE/manifest.json" ]; then
                      case "${'$'}SWITCH_MARKER_FOR_PRUNE" in
                        "${'$'}ROOT"/switches/"${'$'}PKG"/active)
                          write_switch_marker_atomic "${'$'}SWITCH_MARKER_FOR_PRUNE" "${'$'}UCLONE_UNKNOWN_STATE_MARKER" || exit 70
                          echo "SWITCH_MARKER_UNKNOWN=${'$'}SWITCH_MARKER_FOR_PRUNE"
                          ;;
                        *) echo "ERR_BAD_SWITCH_MARKER:${'$'}SWITCH_MARKER_FOR_PRUNE" >&2; exit 70 ;;
                      esac
                    fi
                    ;;
                esac
              fi
            }
            ${if (manageCloneLifecycleAfterTask) """
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
            """.trimIndent() else ":"}
            uclone_stage_begin ROLLBACK_BACKUP
            ${backupCopyPassLabel?.let { "uclone_copy_pass_begin ${shellQuote(it)}" } ?: ":"}
            ${when (rollbackProtection) {
                RollbackProtection.FRESH -> """
            create_fresh_rollback_dir || exit ${'$'}?
            ${if (restoreRule?.includeCe != false) "backup_dir \"/data/user/${'$'}DST_USER/${'$'}PKG\" \"${'$'}ROLLBACK/ce\" \"ce\" \"${'$'}RESTORE_TARGET_CE_KB\"" else "printf '%s\\n' unselected > \"${'$'}ROLLBACK/.state/ce\" || exit 54"}
            ${if (restoreRule?.includeDe != false) "backup_dir \"/data/user_de/${'$'}DST_USER/${'$'}PKG\" \"${'$'}ROLLBACK/de\" \"de\" \"${'$'}RESTORE_TARGET_DE_KB\"" else "printf '%s\\n' unselected > \"${'$'}ROLLBACK/.state/de\" || exit 54"}
            ${if (restoreRule?.includeExternal != false) "backup_dir \"/data/media/${'$'}DST_USER/Android/data/${'$'}PKG\" \"${'$'}ROLLBACK/external\" \"external\" \"${'$'}RESTORE_TARGET_EXTERNAL_KB\"" else "printf '%s\\n' unselected > \"${'$'}ROLLBACK/.state/external\" || exit 54"}
            ${if (restoreRule?.includeMedia != false) "backup_dir \"/data/media/${'$'}DST_USER/Android/media/${'$'}PKG\" \"${'$'}ROLLBACK/media\" \"media\" \"${'$'}RESTORE_TARGET_MEDIA_KB\"" else "printf '%s\\n' unselected > \"${'$'}ROLLBACK/.state/media\" || exit 54"}
            ${if (restoreRule?.includeObb != false) "backup_dir \"/data/media/${'$'}DST_USER/Android/obb/${'$'}PKG\" \"${'$'}ROLLBACK/obb\" \"obb\" \"${'$'}RESTORE_TARGET_OBB_KB\"" else "printf '%s\\n' unselected > \"${'$'}ROLLBACK/.state/obb\" || exit 54"}
            ${if (restorePermissions) "uclone_capture_permission_state \"${'$'}ROLLBACK/permissions\" \"${'$'}DST_USER\" || echo \"WARN_PERMISSION_CAPTURE_SKIPPED:${'$'}DST_USER\"" else ":"}
            ROLLBACK_SIZE_KB="${'$'}RESTORE_TARGET_KB"
            uclone_record_temp_kb "${'$'}ROLLBACK_SIZE_KB"
            printf '%s\n' "{\"packageName\":\"${'$'}PKG\",\"rollbackId\":\"${'$'}ROLLBACK_ID\",\"createdAt\":\"${'$'}TS\",\"reason\":\"$rollbackReason\",\"targetUser\":\"${'$'}DST_USER\",\"stateKind\":\"${'$'}CURRENT_TARGET_STATE\",\"backupKind\":\"transaction_undo\",\"sizeKb\":\"${'$'}ROLLBACK_SIZE_KB\"}" > "${'$'}ROLLBACK/manifest.json" || exit 53
            UCLONE_DURABILITY_STARTED_AT=${'$'}(uclone_now_ms)
            sync
            uclone_perf_emit durability_barrier transaction_undo "${'$'}UCLONE_DURABILITY_STARTED_AT"
            ROLLBACK_FINALIZED=1
            """.trimIndent()
                RollbackProtection.EXISTING -> """
            uclone_valid_state_backup "${'$'}ROLLBACK" CLONE || {
              echo "ERR_SWITCH_CHECKPOINT_INVALID:${'$'}ROLLBACK" >&2
              exit 53
            }
            echo "SWITCH_CHECKPOINT_READY=${'$'}ROLLBACK"
            """.trimIndent()
                RollbackProtection.NONE -> """
            echo "DANGEROUS_NO_LOCAL_ROLLBACK=1"
            """.trimIndent()
            }}
            ${backupCopyPassLabel?.let { "uclone_copy_pass_end ${shellQuote(it)}" } ?: ":"}
            uclone_stage_end
            ${RestoreTransactionShell.guard(
                appUidVariable = "UID_VALUE",
                includePermissions = restorePermissions,
                manageSwitchMarker = targetUserId == settings.mainUserId && rollbackRootName == "rollback",
                rollbackReady = rollbackProtection != RollbackProtection.NONE,
                failClosedWithoutRollback = rollbackProtection == RollbackProtection.NONE,
                restoreCe = restoreRule?.includeCe != false,
                restoreDe = restoreRule?.includeDe != false,
                restoreExternal = restoreRule?.includeExternal != false,
                restoreMedia = restoreRule?.includeMedia != false,
                restoreObb = restoreRule?.includeObb != false,
            )}
            ${if (targetUserId == settings.mainUserId && rollbackRootName == "rollback") "stage_switch_marker_unknown || exit 70" else ":"}
            uclone_stage_begin RESTORE_DATA
            ${restoreCopyPassLabel?.let { "uclone_copy_pass_begin ${shellQuote(it)}" } ?: ":"}
            ${if (restoreRule?.includeCe != false) "restore_part \"${'$'}ACTIVE/ce\" \"/data/user/${'$'}DST_USER/${'$'}PKG\" \"${'$'}UID_VALUE\" \"app\" \"ce\"" else ":"}
            ${if (restoreRule?.includeDe != false) "restore_part \"${'$'}ACTIVE/de\" \"/data/user_de/${'$'}DST_USER/${'$'}PKG\" \"${'$'}UID_VALUE\" \"app\" \"de\"" else ":"}
            ${if (restoreRule?.includeExternal != false) "restore_part \"${'$'}ACTIVE/external\" \"/data/media/${'$'}DST_USER/Android/data/${'$'}PKG\" \"\" \"media\" \"external\"" else ":"}
            ${if (restoreRule?.includeMedia != false) "restore_part \"${'$'}ACTIVE/media\" \"/data/media/${'$'}DST_USER/Android/media/${'$'}PKG\" \"\" \"media\" \"media\"" else ":"}
            ${if (restoreRule?.includeObb != false) "restore_part \"${'$'}ACTIVE/obb\" \"/data/media/${'$'}DST_USER/Android/obb/${'$'}PKG\" \"\" \"media\" \"obb\"" else ":"}
            uclone_add_written_kb "${'$'}RESTORE_SOURCE_KB"
            ${restoreCopyPassLabel?.let { "uclone_copy_pass_end ${shellQuote(it)}" } ?: ":"}
            uclone_stage_end
            uclone_stage_begin RESTORE_PERMISSIONS
            ${if (restorePermissions) "uclone_restore_permission_state \"${'$'}ACTIVE/permissions\" \"${'$'}DST_USER\"" else ":"}
            uclone_stage_end
            uclone_stage_begin VERIFY
            [ "${'$'}RESTORED_PARTS" -gt 0 ] || { echo "ERR_NOTHING_RESTORED:${'$'}ACTIVE" >&2; exit 62; }
            ${expectedCopyPasses?.let { """
            [ "${'$'}UCLONE_COPY_PASSES" -eq $it ] || {
              echo "ERR_COPY_PASS_CONTRACT:expected=$it actual=${'$'}UCLONE_COPY_PASSES" >&2
              exit 89
            }
            echo "UCLONE_COPY_PASS_CONTRACT:expected=$it actual=${'$'}UCLONE_COPY_PASSES"
            """.trimIndent() } ?: ":"}
            uclone_stage_end
            uclone_stage_begin COMMIT
            ORIGINAL_TRANSACTION_ID="${'$'}ROLLBACK_ID"
            ORIGINAL_TRANSACTION_DIR="${'$'}ROLLBACK"
            UCLONE_STATE_BACKUP_ID=""
            UCLONE_STATE_BACKUP_PATH=""
            UCLONE_STATE_BACKUP_REUSED=0
            TRANSACTION_UNDO_DISPOSITION=${when (rollbackProtection) {
                RollbackProtection.FRESH -> "retained"
                RollbackProtection.EXISTING -> "switch_checkpoint"
                RollbackProtection.NONE -> "unavailable_dangerous"
            }}
            FINAL_STATE_PUBLISHED=1
            ${if (targetUserId == settings.mainUserId && rollbackRootName == "rollback") """
            if [ "${'$'}CURRENT_TARGET_STATE" = "MAIN" ] && [ "${'$'}NEXT_MAIN_STATE" = "CLONE" ]; then
              uclone_select_transaction_state_backup \
                "${'$'}ORIGINAL_TRANSACTION_DIR" \
                "${'$'}ORIGINAL_TRANSACTION_ID" \
                "${'$'}CURRENT_TARGET_STATE" \
                "${'$'}MAIN_RETURN_POLICY" \
                "${'$'}CURRENT_MAIN_CONFIRMED" || exit 53
              if [ "${'$'}EXPLICIT_SWITCH_TO_CLONE" != "1" ] && [ "${'$'}UCLONE_STATE_BACKUP_REUSED" != "1" ]; then
                echo "ERR_MAIN_RETURN_AUTO_INIT_FORBIDDEN" >&2
                exit 53
              fi
            fi
            """.trimIndent() else ":"}
            UCLONE_DURABILITY_STARTED_AT=${'$'}(uclone_now_ms)
            sync
            uclone_perf_emit durability_barrier commit "${'$'}UCLONE_DURABILITY_STARTED_AT"
            force_stop_package_users || exit 76
            UCLONE_READY_TO_COMMIT=1
            ${if (targetUserId == settings.mainUserId && rollbackRootName == "rollback") """
            if [ "${'$'}CURRENT_TARGET_STATE" = "MAIN" ] && [ "${'$'}NEXT_MAIN_STATE" = "CLONE" ]; then
              if [ "${'$'}UCLONE_STATE_BACKUP_REUSED" != "1" ]; then
                if [ "${'$'}EXPLICIT_SWITCH_TO_CLONE" = "1" ] &&
                   uclone_promote_transaction_state_backup "${'$'}ORIGINAL_TRANSACTION_DIR" "${'$'}ORIGINAL_TRANSACTION_ID" "${'$'}CURRENT_TARGET_STATE" "${'$'}DST_USER"; then
                  TRANSACTION_UNDO_DISPOSITION=promoted_to_state_backup
                  ROLLBACK="${'$'}UCLONE_STATE_BACKUP_PATH"
                else
                  echo "ERR_STATE_BACKUP_PROMOTION_FAILED:state=${'$'}CURRENT_TARGET_STATE path=${'$'}ORIGINAL_TRANSACTION_DIR" >&2
                  exit 53
                fi
              fi
              echo "MAIN_RETURN_READY:path=${'$'}UCLONE_STATE_BACKUP_PATH reused=${'$'}UCLONE_STATE_BACKUP_REUSED"
            elif [ "${'$'}CURRENT_TARGET_STATE" = "CLONE" ] && [ "${if (rollbackProtection == RollbackProtection.NONE) "1" else "0"}" != "1" ]; then
              echo "CLONE_TRANSACTION_UNDO_RETAINED:path=${'$'}ORIGINAL_TRANSACTION_DIR"
            fi
            SWITCH_DIR="${'$'}ROOT/switches/${'$'}PKG"
            SWITCH_MARKER="${'$'}SWITCH_DIR/active"
            case "${'$'}NEXT_MAIN_STATE" in
              MAIN)
                if write_switch_marker_atomic "${'$'}SWITCH_MARKER" "${'$'}UCLONE_MAIN_STATE_MARKER"; then
                  echo "DATA_STATE_COMMITTED=MAIN marker=confirmed"
                else
                  FINAL_STATE_PUBLISHED=0
                  echo "WARN_DATA_STATE_MARKER_COMMIT_FAILED:state=MAIN" >&2
                fi
                ;;
              CLONE)
                NEXT_MAIN_RETURN_ID=""
                if [ "${'$'}CURRENT_TARGET_STATE" = "MAIN" ]; then
                  NEXT_MAIN_RETURN_ID="${'$'}UCLONE_STATE_BACKUP_ID"
                elif [ "${'$'}CURRENT_TARGET_STATE" = "CLONE" ] && [ -n "${'$'}PREVIOUS_MAIN_RETURN_ID" ]; then
                  NEXT_MAIN_RETURN_ID="${'$'}PREVIOUS_MAIN_RETURN_ID"
                else
                  NEXT_MAIN_RETURN_ID="${'$'}UCLONE_UNKNOWN_STATE_MARKER"
                fi
                if [ "${'$'}NEXT_MAIN_RETURN_ID" != "${'$'}UCLONE_UNKNOWN_STATE_MARKER" ] &&
                   uclone_valid_state_backup "${'$'}ROOT/rollback/${'$'}PKG/${'$'}NEXT_MAIN_RETURN_ID" MAIN &&
                   write_switch_marker_atomic "${'$'}SWITCH_MARKER" "${'$'}NEXT_MAIN_RETURN_ID"; then
                  echo "DATA_STATE_COMMITTED=CLONE mainReturnPoint=${'$'}NEXT_MAIN_RETURN_ID"
                else
                  FINAL_STATE_PUBLISHED=0
                  write_switch_marker_atomic "${'$'}SWITCH_MARKER" "${'$'}UCLONE_UNKNOWN_STATE_MARKER" >/dev/null 2>&1 || true
                  echo "WARN_DATA_STATE_MARKER_COMMIT_FAILED:state=CLONE mainReturnPoint=${'$'}NEXT_MAIN_RETURN_ID" >&2
                  echo "DATA_STATE_COMMITTED=UNKNOWN"
                fi
                ;;
              *)
                if write_switch_marker_atomic "${'$'}SWITCH_MARKER" "${'$'}UCLONE_UNKNOWN_STATE_MARKER"; then
                  echo "DATA_STATE_COMMITTED=UNKNOWN"
                else
                  FINAL_STATE_PUBLISHED=0
                  echo "WARN_DATA_STATE_MARKER_COMMIT_FAILED:state=UNKNOWN" >&2
                fi
                ;;
            esac
            """.trimIndent() else ":"}
            if [ "${'$'}FINAL_STATE_PUBLISHED" != "1" ]; then
              if [ "${'$'}TRANSACTION_UNDO_DISPOSITION" = "promoted_to_state_backup" ]; then
                if uclone_revert_promoted_state_backup "${'$'}ORIGINAL_TRANSACTION_DIR"; then
                  ROLLBACK="${'$'}ORIGINAL_TRANSACTION_DIR"
                  TRANSACTION_UNDO_DISPOSITION=promotion_reverted
                  echo "MAIN_RETURN_PROMOTION_REVERTED:path=${'$'}ORIGINAL_TRANSACTION_DIR"
                else
                  echo "ERR_MAIN_RETURN_PROMOTION_REVERT_FAILED:path=${'$'}ORIGINAL_TRANSACTION_DIR" >&2
                fi
              fi
              exit 70
            fi
            TRANSACTION_COMMITTED=1
            ${if (targetUserId == settings.mainUserId && rollbackRootName == "rollback") """
            if [ "${'$'}CURRENT_TARGET_STATE" = "MAIN" ] && [ "${'$'}NEXT_MAIN_STATE" = "CLONE" ]; then
              echo "MAIN_RETURN_COMMITTED:path=${'$'}UCLONE_STATE_BACKUP_PATH reused=${'$'}UCLONE_STATE_BACKUP_REUSED"
            fi
            if [ "${'$'}TRANSACTION_UNDO_DISPOSITION" = "promoted_to_state_backup" ]; then
              rm -f "${'$'}{UCLONE_PROMOTION_ORIGINAL_MANIFEST:-}" >/dev/null 2>&1 || true
              rm -rf "${'$'}UCLONE_STATE_BACKUP_PATH.previous" >/dev/null 2>&1 || echo "WARN_STATE_BACKUP_PREVIOUS_CLEANUP:${'$'}UCLONE_STATE_BACKUP_PATH.previous"
            fi
            if [ "${'$'}UCLONE_STATE_BACKUP_REUSED" = "1" ]; then
              if rm -rf "${'$'}ORIGINAL_TRANSACTION_DIR"; then
                TRANSACTION_UNDO_DISPOSITION=cleaned_after_commit
              else
                echo "WARN_TRANSACTION_UNDO_CLEANUP_FAILED:${'$'}ORIGINAL_TRANSACTION_DIR"
              fi
            fi
            ${if (cleanupRollbackOnSuccess) """
            case "${'$'}ORIGINAL_TRANSACTION_DIR" in
              "${'$'}ROOT"/rollback/"${'$'}PKG"/switch_checkpoint_*)
                if rm -rf "${'$'}ORIGINAL_TRANSACTION_DIR"; then
                  TRANSACTION_UNDO_DISPOSITION=cleaned_after_commit
                  echo "SWITCH_CHECKPOINT_CLEANED:${'$'}ORIGINAL_TRANSACTION_DIR"
                else
                  echo "WARN_SWITCH_CHECKPOINT_CLEANUP_FAILED:${'$'}ORIGINAL_TRANSACTION_DIR"
                fi
                ;;
              *) echo "WARN_SWITCH_CHECKPOINT_BAD_PATH:${'$'}ORIGINAL_TRANSACTION_DIR" ;;
            esac
            """.trimIndent() else ":"}
            """.trimIndent() else ":"}
            UCLONE_TARGET_READY_AT=${'$'}(uclone_now_ms)
            UCLONE_TARGET_DOWNTIME_MS=${'$'}(uclone_elapsed_ms "${'$'}UCLONE_TARGET_READY_AT" "${'$'}UCLONE_TARGET_STOPPED_AT")
            uclone_stage_end
            ${if (pruneOldRollbacks) "(prune_old_rollbacks) || echo \"WARN_PRUNE_ROLLBACK_FAILED:${'$'}ROLLBACK\"" else ":"}
            ${if (manageCloneLifecycleAfterTask) "stop_clone_user_after_switch_restore" else ":"}
            uclone_emit_metrics
            ${expectedCopyPasses?.let { "echo \"UCLONE_COPY_PASSES=${'$'}UCLONE_COPY_PASSES plan=${'$'}{UCLONE_RETURN_PLAN:-${settings.switchSafetyMode.name}}\"" } ?: ":"}
            echo "TRANSACTION_UNDO=${'$'}ORIGINAL_TRANSACTION_DIR disposition=${'$'}TRANSACTION_UNDO_DISPOSITION"
            echo "RESTORE_SUMMARY: restoredParts=${'$'}RESTORED_PARTS restoredItems=${'$'}RESTORED_ITEMS backupParts=${'$'}BACKUP_PARTS"
        """.trimIndent()
    }

    private fun requireSafeRollbackId(rollbackId: String) {
        require(rollbackId.isNotBlank() && rollbackId != "." && rollbackId != ".." && rollbackIdPattern.matches(rollbackId)) {
            "Unsafe rollback id: $rollbackId"
        }
    }

}
