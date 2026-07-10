package com.uclone.restore.sync

import com.uclone.restore.model.AppRule
import com.uclone.restore.model.UCloneSettings
import com.uclone.restore.root.shellQuote

object ShellScripts {
    private val rollbackIdPattern = Regex("[A-Za-z0-9_.-]+")
    private enum class RestoreSourceKind {
        ACTIVE,
        ROLLBACK,
        SWITCH_TEMP,
        CLONE_ROLLBACK,
    }

    private fun metricsScript(): String = """
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
        uclone_record_temp_path() {
          [ -d "${'$'}1" ] || return 0
          UCLONE_TEMP_KB=${'$'}(du -sk "${'$'}1" 2>/dev/null | awk '{print ${'$'}1}')
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
          VALUE_KB=${'$'}(du -sk "${'$'}1" 2>/dev/null | awk '{print ${'$'}1}')
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
          (cd "${'$'}SRC" && tar -cpf - .) | (cd "${'$'}DST.tmp" && tar -xpf -) || exit 13
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
        capture_permission_state() {
          PERM_DST="${'$'}1"
          SRC_USER="${'$'}2"
          mkdir -p "${'$'}PERM_DST" || exit 18
          dumpsys package "${'$'}PKG" 2>/dev/null | awk -v user="User ${'$'}SRC_USER:" '
            ${'$'}0 ~ "^    User [0-9]+:" {
              in_user=(${'$'}0 ~ user)
              in_runtime=0
            }
            in_user && ${'$'}0 ~ "^      runtime permissions:" {
              in_runtime=1
              next
            }
            in_runtime && ${'$'}0 ~ "^        android\\.permission\\." {
              name=${'$'}1
              sub(":", "", name)
              granted=(${'$'}0 ~ "granted=true")
              if (granted) print name
              next
            }
            in_runtime && ${'$'}0 !~ "^        " {
              in_runtime=0
            }
          ' | sort -u > "${'$'}PERM_DST/runtime_grants.txt"
          cmd appops get --user "${'$'}SRC_USER" "${'$'}PKG" 2>/dev/null | awk '
            /^[A-Z0-9_()]+: (allow|ignore|deny|default|foreground|ask)/ {
              op=${'$'}1
              sub(":", "", op)
              mode=${'$'}2
              sub(";", "", mode)
              print op " " mode
            }
          ' | sort -u > "${'$'}PERM_DST/appops.txt"
          PERM_COUNT=${'$'}(wc -l < "${'$'}PERM_DST/runtime_grants.txt" | tr -d ' ')
          APPOPS_COUNT=${'$'}(wc -l < "${'$'}PERM_DST/appops.txt" | tr -d ' ')
          echo "PERMISSIONS_CAPTURED:user=${'$'}SRC_USER grants=${'$'}PERM_COUNT appops=${'$'}APPOPS_COUNT"
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
          ${if (rule.includePermissions) "capture_permission_state \"${'$'}TRY_TMP/permissions\" \"${'$'}TRY_USER\"" else ":"}
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
        printf '%s\n' "{\"packageName\":\"${packageName}\",\"configuredSourceUser\":${settings.cloneUserId},\"sourceUser\":\"${'$'}DETECTED_USER\",\"sourceUserState\":\"${'$'}DETECTED_STATE\",\"targetUser\":${settings.mainUserId},\"createdAt\":\"${'$'}TS\",\"includeCe\":${rule.includeCe},\"includeDe\":${rule.includeDe},\"includeExternal\":${rule.includeExternal},\"includeMedia\":${rule.includeMedia},\"includeObb\":${rule.includeObb},\"includePermissions\":${rule.includePermissions},\"includeAppWebView\":${rule.includeAppWebView},\"excludeCache\":${rule.excludeCache},\"snapshotSizeKb\":\"${'$'}SIZE_KB\",\"copiedParts\":\"${'$'}COPIED_PARTS\",\"copiedItems\":\"${'$'}COPIED_ITEMS\"}" > "${'$'}TMP/manifest.json" || exit 18
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

    fun restore(packageName: String, settings: UCloneSettings, appPackage: String): String = restoreBody(
        packageName = packageName,
        settings = settings,
        appPackage = appPackage,
        rollbackName = """${'$'}TS""",
        rollbackReason = "恢复到主系统前生成",
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
            "${'$'}ROOT"/clone_rollback/"${'$'}PKG"/latest.tmp_"${'$'}TS") rm -rf "${'$'}ROLLBACK_TMP" 2>/dev/null || true ;;
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
          (cd "${'$'}SRC" && tar -cpf - .) | (cd "${'$'}DST.tmp" && tar -xpf -) || exit 13
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
        capture_permission_state() {
          PERM_DST="${'$'}1"
          CAPTURE_USER="${'$'}2"
          mkdir -p "${'$'}PERM_DST" || exit 18
          dumpsys package "${'$'}PKG" 2>/dev/null | awk -v user="User ${'$'}CAPTURE_USER:" '
            ${'$'}0 ~ "^    User [0-9]+:" {
              in_user=(${'$'}0 ~ user)
              in_runtime=0
            }
            in_user && ${'$'}0 ~ "^      runtime permissions:" {
              in_runtime=1
              next
            }
            in_runtime && ${'$'}0 ~ "^        android\\.permission\\." {
              name=${'$'}1
              sub(":", "", name)
              granted=(${'$'}0 ~ "granted=true")
              if (granted) print name
              next
            }
            in_runtime && ${'$'}0 !~ "^        " {
              in_runtime=0
            }
          ' | sort -u > "${'$'}PERM_DST/runtime_grants.txt"
          cmd appops get --user "${'$'}CAPTURE_USER" "${'$'}PKG" 2>/dev/null | awk '
            /^[A-Z0-9_()]+: (allow|ignore|deny|default|foreground|ask)/ {
              op=${'$'}1
              sub(":", "", op)
              mode=${'$'}2
              sub(";", "", mode)
              print op " " mode
            }
          ' | sort -u > "${'$'}PERM_DST/appops.txt"
          PERM_COUNT=${'$'}(wc -l < "${'$'}PERM_DST/runtime_grants.txt" | tr -d ' ')
          APPOPS_COUNT=${'$'}(wc -l < "${'$'}PERM_DST/appops.txt" | tr -d ' ')
          echo "PERMISSIONS_CAPTURED:user=${'$'}CAPTURE_USER grants=${'$'}PERM_COUNT appops=${'$'}APPOPS_COUNT"
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
          (cd "${'$'}SRC" && tar -cpf - .) | (cd "${'$'}DST" && tar -xpf -) || exit 55
          BACKUP_ITEMS=${'$'}(count_items "${'$'}DST")
          UCLONE_SCANNED_FILES=${'$'}((UCLONE_SCANNED_FILES + SRC_ITEMS))
          UCLONE_COPIED_FILES=${'$'}((UCLONE_COPIED_FILES + BACKUP_ITEMS))
          BACKUP_SIZE_KB=${'$'}(du -sk "${'$'}DST" 2>/dev/null | awk '{print ${'$'}1}')
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
        restore_permission_state() {
          PERM_SRC="${'$'}1"
          [ -d "${'$'}PERM_SRC" ] || { echo "SKIP_PERMISSIONS:${'$'}PERM_SRC"; return 0; }
          GRANTS_FILE="${'$'}PERM_SRC/runtime_grants.txt"
          APPOPS_FILE="${'$'}PERM_SRC/appops.txt"
          GRANT_COUNT=0
          APPOPS_COUNT=0
          if [ -f "${'$'}GRANTS_FILE" ]; then
            CURRENT_GRANTS="${'$'}ROOT/tmp/current_push_grants_${'$'}{PKG}_${'$'}{TS}.txt"
            dumpsys package "${'$'}PKG" 2>/dev/null | awk -v user="User ${'$'}DST_USER:" '
              ${'$'}0 ~ "^    User [0-9]+:" {
                in_user=(${'$'}0 ~ user)
                in_runtime=0
              }
              in_user && ${'$'}0 ~ "^      runtime permissions:" {
                in_runtime=1
                next
              }
              in_runtime && ${'$'}0 ~ "^        android\\.permission\\." {
                name=${'$'}1
                sub(":", "", name)
                granted=(${'$'}0 ~ "granted=true")
                if (granted) print name
                next
              }
              in_runtime && ${'$'}0 !~ "^        " {
                in_runtime=0
              }
            ' | sort -u > "${'$'}CURRENT_GRANTS"
            while IFS= read -r CURRENT_PERM; do
              [ -n "${'$'}CURRENT_PERM" ] || continue
              grep -Fxq "${'$'}CURRENT_PERM" "${'$'}GRANTS_FILE" 2>/dev/null && continue
              cmd package revoke --user "${'$'}DST_USER" "${'$'}PKG" "${'$'}CURRENT_PERM" >/dev/null 2>&1 || pm revoke --user "${'$'}DST_USER" "${'$'}PKG" "${'$'}CURRENT_PERM" >/dev/null 2>&1 || echo "WARN_REVOKE_FAILED:${'$'}CURRENT_PERM"
            done < "${'$'}CURRENT_GRANTS"
            rm -f "${'$'}CURRENT_GRANTS"
            while IFS= read -r PERM; do
              [ -n "${'$'}PERM" ] || continue
              case "${'$'}PERM" in android.permission.*) ;; *) continue ;; esac
              cmd package grant --user "${'$'}DST_USER" "${'$'}PKG" "${'$'}PERM" >/dev/null 2>&1 || pm grant --user "${'$'}DST_USER" "${'$'}PKG" "${'$'}PERM" >/dev/null 2>&1 || echo "WARN_GRANT_FAILED:${'$'}PERM"
              GRANT_COUNT=${'$'}((GRANT_COUNT + 1))
            done < "${'$'}GRANTS_FILE"
          fi
          if [ -f "${'$'}APPOPS_FILE" ]; then
            cmd appops reset --user "${'$'}DST_USER" "${'$'}PKG" >/dev/null 2>&1 || echo "WARN_APPOPS_RESET_FAILED"
            while read -r OP MODE EXTRA; do
              [ -n "${'$'}OP" ] || continue
              [ -z "${'$'}EXTRA" ] || continue
              case "${'$'}MODE" in allow|ignore|deny|default|foreground|ask) ;; *) continue ;; esac
              cmd appops set --user "${'$'}DST_USER" "${'$'}PKG" "${'$'}OP" "${'$'}MODE" >/dev/null 2>&1 || echo "WARN_APPOPS_FAILED:${'$'}OP:${'$'}MODE"
              APPOPS_COUNT=${'$'}((APPOPS_COUNT + 1))
            done < "${'$'}APPOPS_FILE"
            cmd appops write-settings >/dev/null 2>&1 || echo "WARN_APPOPS_WRITE_SETTINGS_FAILED"
          fi
          echo "PUSHED_PERMISSIONS:grants=${'$'}GRANT_COUNT appops=${'$'}APPOPS_COUNT"
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
        ${if (rule.includePermissions) "capture_permission_state \"${'$'}PUSH_TEMP/permissions\" \"${'$'}SRC_USER\"" else ":"}
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
        ${if (rule.includePermissions) "capture_permission_state \"${'$'}ROLLBACK_TMP/permissions\" \"${'$'}DST_USER\"" else ":"}
        ROLLBACK_SIZE_KB=${'$'}(du -sk "${'$'}ROLLBACK_TMP" 2>/dev/null | awk '{print ${'$'}1}')
        printf '%s\n' "{\"packageName\":\"${'$'}PKG\",\"rollbackId\":\"latest\",\"createdAt\":\"${'$'}TS\",\"reason\":\"推送到分身前生成\",\"sourceUser\":\"${'$'}DST_USER\",\"targetUser\":\"${'$'}DST_USER\",\"backupKind\":\"clone_rollback\",\"retention\":\"latest_only\",\"sizeKb\":\"${'$'}ROLLBACK_SIZE_KB\"}" > "${'$'}ROLLBACK_TMP/manifest.json" || exit 53
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
        ${if (rule.includePermissions) "restore_permission_state \"${'$'}PUSH_TEMP/permissions\"" else ":"}
        uclone_stage_end
        uclone_stage_begin VERIFY
        [ "${'$'}RESTORED_PARTS" -gt 0 ] || { echo "ERR_NOTHING_PUSHED:${'$'}PUSH_TEMP" >&2; exit 62; }
        uclone_stage_end
        uclone_stage_begin COMMIT
        sync
        if [ -d "${'$'}ROLLBACK_PREVIOUS" ] && [ -d "${'$'}ROLLBACK_LATEST" ]; then
          rm -rf "${'$'}ROLLBACK_PREVIOUS" || exit 54
        fi
        if [ -d "${'$'}ROLLBACK_LATEST" ]; then
          mv "${'$'}ROLLBACK_LATEST" "${'$'}ROLLBACK_PREVIOUS" || exit 54
        fi
        if mv "${'$'}ROLLBACK_TMP" "${'$'}ROLLBACK_LATEST"; then
          ROLLBACK="${'$'}ROLLBACK_LATEST"
        else
          if [ ! -d "${'$'}ROLLBACK_LATEST" ] && [ -d "${'$'}ROLLBACK_PREVIOUS" ]; then
            mv "${'$'}ROLLBACK_PREVIOUS" "${'$'}ROLLBACK_LATEST" >/dev/null 2>&1 || true
          fi
          exit 54
        fi
        sync
        force_stop_package_users || exit 76
        TRANSACTION_COMMITTED=1
        rm -rf "${'$'}ROLLBACK_PREVIOUS" >/dev/null 2>&1 || echo "WARN_CLONE_ROLLBACK_PREVIOUS_CLEANUP_FAILED:${'$'}ROLLBACK_PREVIOUS"
        UCLONE_TARGET_READY_AT=${'$'}(uclone_now_ms)
        UCLONE_TARGET_DOWNTIME_MS=${'$'}(uclone_elapsed_ms "${'$'}UCLONE_TARGET_READY_AT" "${'$'}UCLONE_TARGET_STOPPED_AT")
        uclone_stage_end
        uclone_emit_metrics
        echo "PUSH_MAIN_TO_CLONE_DONE targetUser=${'$'}DST_USER restoredParts=${'$'}RESTORED_PARTS restoredItems=${'$'}RESTORED_ITEMS copiedParts=${'$'}COPIED_PARTS backupParts=${'$'}BACKUP_PARTS"
    """.trimIndent()

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
            rollbackName = """rollback_${'$'}TS""",
            rollbackReason = if (clearSwitchMarker) "还原主系统态前生成" else "恢复主系统备份前生成",
            sourcePrefix = "${settings.rootDir}/rollback/$packageName/$rollbackId",
            sourceRollbackId = rollbackId,
            clearSwitchMarker = clearSwitchMarker,
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
              "${'$'}ROOT"/switches/"${'$'}PKG"/active) rm -f "${'$'}SWITCH_MARKER" || exit 76 ;;
              *) echo "ERR_BAD_SWITCH_MARKER:${'$'}SWITCH_MARKER" >&2; exit 76 ;;
            esac
            echo "SWITCH_MARKER_CLEARED=${'$'}SWITCH_MARKER"
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
        RESET_TARGETS="snapshots rollback clone_rollback switches logs tmp audit config"
        DELETED_TARGETS=0
        DELETED_SIZE_KB=0
        for NAME in ${'$'}RESET_TARGETS; do
          TARGET="${'$'}ROOT/${'$'}NAME"
          case "${'$'}TARGET" in
            "${'$'}ROOT"/snapshots|"${'$'}ROOT"/rollback|"${'$'}ROOT"/clone_rollback|"${'$'}ROOT"/switches|"${'$'}ROOT"/logs|"${'$'}ROOT"/tmp|"${'$'}ROOT"/audit|"${'$'}ROOT"/config) ;;
            *) echo "ERR_UNSAFE_RESET_TARGET:${'$'}TARGET" >&2; exit 73 ;;
          esac
          [ -e "${'$'}TARGET" ] || continue
          SIZE_KB=${'$'}(du -sk "${'$'}TARGET" 2>/dev/null | awk '{print ${'$'}1}')
          case "${'$'}SIZE_KB" in ''|*[!0-9]*) SIZE_KB=0 ;; esac
          rm -rf "${'$'}TARGET" || { echo "ERR_RESET_DELETE_FAILED:${'$'}TARGET" >&2; exit 74; }
          DELETED_TARGETS=${'$'}((DELETED_TARGETS + 1))
          NEXT_DELETED_SIZE_KB=${'$'}(awk -v TOTAL="${'$'}DELETED_SIZE_KB" -v SIZE_KB="${'$'}SIZE_KB" 'BEGIN { printf "%.0f\n", TOTAL + SIZE_KB }')
          case "${'$'}NEXT_DELETED_SIZE_KB" in ''|*[!0-9]*) NEXT_DELETED_SIZE_KB="${'$'}DELETED_SIZE_KB" ;; esac
          DELETED_SIZE_KB="${'$'}NEXT_DELETED_SIZE_KB"
          echo "RESET_DELETED:${'$'}TARGET SIZE_KB=${'$'}SIZE_KB"
        done
        echo "RESET_WORKSPACE_DONE root=${'$'}ROOT deletedTargets=${'$'}DELETED_TARGETS sizeKb=${'$'}DELETED_SIZE_KB"
    """.trimIndent()

    private fun switchTempSourceScript(rule: AppRule, settings: UCloneSettings): String = """
        SWITCH_TEMP="${'$'}ACTIVE"
        case "${'$'}SWITCH_TEMP" in
          "${'$'}ROOT"/tmp/switch_"${'$'}PKG"_"${'$'}TS") ;;
          *) echo "ERR_BAD_SWITCH_TEMP:${'$'}SWITCH_TEMP" >&2; exit 72 ;;
        esac
        cleanup_switch_temp() {
          [ -n "${'$'}{SWITCH_TEMP:-}" ] || return 0
          case "${'$'}SWITCH_TEMP" in
            "${'$'}ROOT"/tmp/switch_"${'$'}PKG"_"${'$'}TS") rm -rf "${'$'}SWITCH_TEMP" "${'$'}SWITCH_TEMP".try_* 2>/dev/null || true ;;
          esac
        }
        trap cleanup_switch_temp EXIT
        mkdir -p "${'$'}ROOT/tmp" || exit 10
        rm -rf "${'$'}SWITCH_TEMP" "${'$'}SWITCH_TEMP".try_*
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
        count_items() {
          find "${'$'}1" -mindepth 1 2>/dev/null | wc -l | tr -d ' '
        }
        copy_dir_stream() {
          SRC="${'$'}1"
          DST="${'$'}2"
          SRC_ITEMS=${'$'}(count_items "${'$'}SRC")
          [ "${'$'}SRC_ITEMS" -gt 0 ] || { echo "SKIP_EMPTY:${'$'}SRC"; return 0; }
          rm -rf "${'$'}DST.tmp"
          mkdir -p "${'$'}DST.tmp" || exit 12
          (cd "${'$'}SRC" && tar -cpf - .) | (cd "${'$'}DST.tmp" && tar -xpf -) || exit 13
          rm -rf "${'$'}DST"
          mv "${'$'}DST.tmp" "${'$'}DST" || exit 14
          ${if (rule.excludeCache) "rm -rf \"${'$'}DST/cache\" \"${'$'}DST/code_cache\" 2>/dev/null || true" else ":"}
          DST_ITEMS=${'$'}(count_items "${'$'}DST")
          [ "${'$'}DST_ITEMS" -gt 0 ] || { echo "ERR_COPY_EMPTY:${'$'}SRC" >&2; exit 17; }
          PART_SIZE_KB=${'$'}(du -sk "${'$'}DST" 2>/dev/null | awk '{print ${'$'}1}')
          COPIED_PARTS=${'$'}((COPIED_PARTS + 1))
          COPIED_ITEMS=${'$'}((COPIED_ITEMS + DST_ITEMS))
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
        capture_permission_state() {
          PERM_DST="${'$'}1"
          SRC_USER="${'$'}2"
          mkdir -p "${'$'}PERM_DST" || exit 18
          dumpsys package "${'$'}PKG" 2>/dev/null | awk -v user="User ${'$'}SRC_USER:" '
            ${'$'}0 ~ "^    User [0-9]+:" {
              in_user=(${'$'}0 ~ user)
              in_runtime=0
            }
            in_user && ${'$'}0 ~ "^      runtime permissions:" {
              in_runtime=1
              next
            }
            in_runtime && ${'$'}0 ~ "^        android\\.permission\\." {
              name=${'$'}1
              sub(":", "", name)
              granted=(${'$'}0 ~ "granted=true")
              if (granted) print name
              next
            }
            in_runtime && ${'$'}0 !~ "^        " {
              in_runtime=0
            }
          ' | sort -u > "${'$'}PERM_DST/runtime_grants.txt"
          cmd appops get --user "${'$'}SRC_USER" "${'$'}PKG" 2>/dev/null | awk '
            /^[A-Z0-9_()]+: (allow|ignore|deny|default|foreground|ask)/ {
              op=${'$'}1
              sub(":", "", op)
              mode=${'$'}2
              sub(";", "", mode)
              print op " " mode
            }
          ' | sort -u > "${'$'}PERM_DST/appops.txt"
          PERM_COUNT=${'$'}(wc -l < "${'$'}PERM_DST/runtime_grants.txt" | tr -d ' ')
          APPOPS_COUNT=${'$'}(wc -l < "${'$'}PERM_DST/appops.txt" | tr -d ' ')
          echo "PERMISSIONS_CAPTURED:user=${'$'}SRC_USER grants=${'$'}PERM_COUNT appops=${'$'}APPOPS_COUNT"
        }
        try_user() {
          TRY_USER="${'$'}1"
          TRY_TMP="${'$'}SWITCH_TEMP.try_${'$'}TRY_USER"
          rm -rf "${'$'}TRY_TMP"
          mkdir -p "${'$'}TRY_TMP" || exit 11
          STATE=${'$'}(am get-started-user-state "${'$'}TRY_USER" 2>/dev/null || true)
          echo "PROBE_USER=${'$'}TRY_USER STATE=${'$'}STATE"
          case "${'$'}STATE" in
            *RUNNING_UNLOCKED*) ;;
            *)
              echo "ERR_USER_NOT_UNLOCKED:${'$'}TRY_USER:${'$'}STATE" >&2
              rm -rf "${'$'}TRY_TMP"
              return 1
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
          ${if (rule.includePermissions) "capture_permission_state \"${'$'}TRY_TMP/permissions\" \"${'$'}TRY_USER\"" else ":"}
          if [ "${'$'}SWITCH_REQUIRE_CE" = "1" ] && [ ! -d "${'$'}TRY_TMP/ce" ]; then
            echo "ERR_SWITCH_CE_MISSING:${'$'}TRY_USER" >&2
            rm -rf "${'$'}TRY_TMP"
            return 1
          fi
          if [ "${'$'}COPIED_PARTS" -gt 0 ]; then
            rm -rf "${'$'}SWITCH_TEMP"
            mv "${'$'}TRY_TMP" "${'$'}SWITCH_TEMP" || exit 14
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
        sourceRollbackId?.let(::requireSafeRollbackId)
        return """
            set -u
            ROOT=${shellQuote(settings.rootDir)}
            PKG=${shellQuote(packageName)}
            APP_PKG=${shellQuote(appPackage)}
            DST_USER=$targetUserId
            TS=${'$'}(date +%Y%m%d-%H%M%S)
            $activeAssignment
            SOURCE_KIND=${shellQuote(sourceKindToken)}
            SOURCE_ROLLBACK_ID=${shellQuote(sourceRollbackId.orEmpty())}
            ROLLBACK_ID="$rollbackName"
            ROLLBACK="${'$'}ROOT/$rollbackRootName/${'$'}PKG/$rollbackName"
            ${metricsScript()}
            ${storagePreflightScript()}
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
              EXPECTED_ACTIVE="${'$'}ROOT/tmp/switch_${'$'}{PKG}_${'$'}TS"
            elif [ "${'$'}SOURCE_KIND" = "clone_rollback" ]; then
              EXPECTED_ACTIVE="${'$'}ROOT/clone_rollback/${'$'}PKG/latest"
            else
              EXPECTED_ACTIVE="${'$'}ROOT/snapshots/${'$'}PKG/active"
            fi
            uclone_stage_end
            uclone_stage_begin SOURCE_PREPARE
            $prepareSourceScript
            [ "${'$'}ACTIVE" = "${'$'}EXPECTED_ACTIVE" ] || { echo "ERR_BAD_RESTORE_SOURCE:${'$'}ACTIVE" >&2; exit 72; }
            [ -d "${'$'}ACTIVE" ] || { echo "ERR_SNAPSHOT_MISSING:${'$'}ACTIVE" >&2; exit 51; }
            [ "${'$'}ACTIVE" != "${'$'}ROLLBACK" ] || { echo "ERR_ROLLBACK_SOURCE_CONFLICT:${'$'}ACTIVE" >&2; exit 61; }
            UID_VALUE=${'$'}(cmd package list packages -U --user "${'$'}DST_USER" | awk -v p="package:${'$'}PKG" '${'$'}1==p { sub("uid:","",${'$'}2); print ${'$'}2; exit }')
            [ -n "${'$'}UID_VALUE" ] || { echo "ERR_TARGET_UID_MISSING" >&2; exit 52; }
            mkdir -p "${'$'}ROOT/$rollbackRootName/${'$'}PKG" "${'$'}ROOT/tmp" || exit 53
            count_items() {
              find "${'$'}1" -mindepth 1 2>/dev/null | wc -l | tr -d ' '
            }
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
            ROLLBACK_FINALIZED=0
            cleanup_restore_prepared() {
              case "${'$'}{PREPARED_ROOT:-}" in
                "${'$'}ROOT"/tmp/prepared_"${'$'}PKG"_"${'$'}TS") rm -rf "${'$'}PREPARED_ROOT" 2>/dev/null || true ;;
              esac
            }
            cleanup_restore_before_transaction() {
              cleanup_restore_prepared
              if [ "${'$'}{ROLLBACK_FINALIZED:-0}" != "1" ]; then
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
            trap cleanup_restore_before_transaction EXIT
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
              (cd "${'$'}PREPARE_SRC" && tar -cpf - .) | (cd "${'$'}PREPARE_DST" && tar -xpf -) || exit 57
              PREPARED_ITEMS=${'$'}(count_items "${'$'}PREPARE_DST")
              [ "${'$'}PREPARED_ITEMS" -gt 0 ] || { echo "ERR_EXTRACT_EMPTY:${'$'}PREPARE_SRC" >&2; exit 69; }
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
            [ "${'$'}PREPARED_PARTS" -gt 0 ] || { echo "ERR_NOTHING_PREPARED:${'$'}ACTIVE" >&2; exit 62; }
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
              (cd "${'$'}SRC" && tar -cpf - .) | (cd "${'$'}DST" && tar -xpf -) || exit 55
              BACKUP_ITEMS=${'$'}(count_items "${'$'}DST")
              [ "${'$'}BACKUP_ITEMS" -gt 0 ] || { echo "ERR_BACKUP_EMPTY:${'$'}SRC" >&2; exit 63; }
              printf '%s\n' "data" > "${'$'}ROLLBACK/.state/${'$'}PART_NAME" || exit 54
              BACKUP_PARTS=${'$'}((BACKUP_PARTS + 1))
              UCLONE_SCANNED_FILES=${'$'}((UCLONE_SCANNED_FILES + SRC_ITEMS))
              UCLONE_COPIED_FILES=${'$'}((UCLONE_COPIED_FILES + BACKUP_ITEMS))
              BACKUP_SIZE_KB=${'$'}(du -sk "${'$'}DST" 2>/dev/null | awk '{print ${'$'}1}')
              uclone_add_written_kb "${'$'}BACKUP_SIZE_KB"
              uclone_record_temp_path "${'$'}DST"
              echo "BACKUP:${'$'}SRC ITEMS=${'$'}BACKUP_ITEMS"
            }
            backup_permission_state() {
              PERM_DST="${'$'}1"
              mkdir -p "${'$'}PERM_DST" || exit 54
              dumpsys package "${'$'}PKG" 2>/dev/null | awk -v user="User ${'$'}DST_USER:" '
                ${'$'}0 ~ "^    User [0-9]+:" {
                  in_user=(${'$'}0 ~ user)
                  in_runtime=0
                }
                in_user && ${'$'}0 ~ "^      runtime permissions:" {
                  in_runtime=1
                  next
                }
                in_runtime && ${'$'}0 ~ "^        android\\.permission\\." {
                  name=${'$'}1
                  sub(":", "", name)
                  granted=(${'$'}0 ~ "granted=true")
                  if (granted) print name
                  next
                }
                in_runtime && ${'$'}0 !~ "^        " {
                  in_runtime=0
                }
              ' | sort -u > "${'$'}PERM_DST/runtime_grants.txt"
              cmd appops get --user "${'$'}DST_USER" "${'$'}PKG" 2>/dev/null | awk '
                /^[A-Z0-9_()]+: (allow|ignore|deny|default|foreground|ask)/ {
                  op=${'$'}1
                  sub(":", "", op)
                  mode=${'$'}2
                  sub(";", "", mode)
                  print op " " mode
                }
              ' | sort -u > "${'$'}PERM_DST/appops.txt"
              echo "BACKUP_PERMISSIONS:${'$'}PERM_DST"
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
            restore_part() {
              SNAP="${'$'}1"
              TARGET="${'$'}2"
              OWNER_UID="${'$'}3"
              OWNER_KIND="${'$'}4"
              PART_NAME="${'$'}5"
              validate_target_path "${'$'}TARGET"
              PREPARED="${'$'}PREPARED_ROOT/${'$'}PART_NAME"
              if [ ! -d "${'$'}PREPARED" ]; then
                [ -d "${'$'}SNAP" ] && { echo "ERR_PREPARED_PART_MISSING:${'$'}PREPARED" >&2; exit 69; }
                echo "SKIP_PART:${'$'}SNAP"
                return 0
              fi
              PREPARED_ITEMS=${'$'}(count_items "${'$'}PREPARED")
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
              (cd "${'$'}PREPARED" && tar -cpf - .) | (cd "${'$'}TARGET" && tar -xpf -) || exit 58
              apply_target_security "${'$'}TARGET" "${'$'}TARGET_OWNER" "${'$'}TARGET_CONTEXT"
              TARGET_ITEMS=${'$'}(count_items "${'$'}TARGET")
              [ "${'$'}TARGET_ITEMS" -gt 0 ] || { echo "ERR_RESTORE_EMPTY:${'$'}TARGET" >&2; exit 65; }
              RESTORED_PARTS=${'$'}((RESTORED_PARTS + 1))
              RESTORED_ITEMS=${'$'}((RESTORED_ITEMS + TARGET_ITEMS))
              UCLONE_SCANNED_FILES=${'$'}((UCLONE_SCANNED_FILES + SNAP_ITEMS))
              UCLONE_COPIED_FILES=${'$'}((UCLONE_COPIED_FILES + TARGET_ITEMS))
              TARGET_SIZE_KB=${'$'}(du -sk "${'$'}TARGET" 2>/dev/null | awk '{print ${'$'}1}')
              uclone_add_written_kb "${'$'}TARGET_SIZE_KB"
              uclone_record_temp_path "${'$'}TARGET"
              echo "RESTORED:${'$'}TARGET ITEMS=${'$'}TARGET_ITEMS OWNER=${'$'}TARGET_OWNER CONTEXT=${'$'}TARGET_CONTEXT"
            }
            restore_permission_state() {
              PERM_SRC="${'$'}1"
              [ -d "${'$'}PERM_SRC" ] || { echo "SKIP_PERMISSIONS:${'$'}PERM_SRC"; return 0; }
              GRANTS_FILE="${'$'}PERM_SRC/runtime_grants.txt"
              APPOPS_FILE="${'$'}PERM_SRC/appops.txt"
              GRANT_COUNT=0
              APPOPS_COUNT=0
              if [ -f "${'$'}GRANTS_FILE" ]; then
                CURRENT_GRANTS="${'$'}ROOT/tmp/current_grants_${'$'}{PKG}_${'$'}{TS}.txt"
                dumpsys package "${'$'}PKG" 2>/dev/null | awk -v user="User ${'$'}DST_USER:" '
                  ${'$'}0 ~ "^    User [0-9]+:" {
                    in_user=(${'$'}0 ~ user)
                    in_runtime=0
                  }
                  in_user && ${'$'}0 ~ "^      runtime permissions:" {
                    in_runtime=1
                    next
                  }
                  in_runtime && ${'$'}0 ~ "^        android\\.permission\\." {
                    name=${'$'}1
                    sub(":", "", name)
                    granted=(${'$'}0 ~ "granted=true")
                    if (granted) print name
                    next
                  }
                  in_runtime && ${'$'}0 !~ "^        " {
                    in_runtime=0
                  }
                ' | sort -u > "${'$'}CURRENT_GRANTS"
                while IFS= read -r CURRENT_PERM; do
                  [ -n "${'$'}CURRENT_PERM" ] || continue
                  grep -Fxq "${'$'}CURRENT_PERM" "${'$'}GRANTS_FILE" 2>/dev/null && continue
                  cmd package revoke --user "${'$'}DST_USER" "${'$'}PKG" "${'$'}CURRENT_PERM" >/dev/null 2>&1 || pm revoke --user "${'$'}DST_USER" "${'$'}PKG" "${'$'}CURRENT_PERM" >/dev/null 2>&1 || echo "WARN_REVOKE_FAILED:${'$'}CURRENT_PERM"
                done < "${'$'}CURRENT_GRANTS"
                rm -f "${'$'}CURRENT_GRANTS"
                while IFS= read -r PERM; do
                  [ -n "${'$'}PERM" ] || continue
                  case "${'$'}PERM" in android.permission.*) ;; *) continue ;; esac
                  cmd package grant --user "${'$'}DST_USER" "${'$'}PKG" "${'$'}PERM" >/dev/null 2>&1 || pm grant --user "${'$'}DST_USER" "${'$'}PKG" "${'$'}PERM" >/dev/null 2>&1 || echo "WARN_GRANT_FAILED:${'$'}PERM"
                  GRANT_COUNT=${'$'}((GRANT_COUNT + 1))
                done < "${'$'}GRANTS_FILE"
              fi
              if [ -f "${'$'}APPOPS_FILE" ]; then
                cmd appops reset --user "${'$'}DST_USER" "${'$'}PKG" >/dev/null 2>&1 || echo "WARN_APPOPS_RESET_FAILED"
                while read -r OP MODE EXTRA; do
                  [ -n "${'$'}OP" ] || continue
                  [ -z "${'$'}EXTRA" ] || continue
                  case "${'$'}MODE" in allow|ignore|deny|default|foreground|ask) ;; *) continue ;; esac
                  cmd appops set --user "${'$'}DST_USER" "${'$'}PKG" "${'$'}OP" "${'$'}MODE" >/dev/null 2>&1 || echo "WARN_APPOPS_FAILED:${'$'}OP:${'$'}MODE"
                  APPOPS_COUNT=${'$'}((APPOPS_COUNT + 1))
                done < "${'$'}APPOPS_FILE"
                cmd appops write-settings >/dev/null 2>&1 || echo "WARN_APPOPS_WRITE_SETTINGS_FAILED"
              fi
              echo "RESTORED_PERMISSIONS:grants=${'$'}GRANT_COUNT appops=${'$'}APPOPS_COUNT"
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
              find "${'$'}ROLLBACK_PARENT" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | while IFS= read -r OLD; do
                OLD_ID=${'$'}(basename "${'$'}OLD")
                [ "${'$'}OLD_ID" = "${'$'}ROLLBACK_ID" ] && continue
                case "${'$'}OLD" in
                  "${'$'}EXPECTED_CHILD_PREFIX"*)
                    rm -rf "${'$'}OLD" && echo "PRUNED_ROLLBACK=${'$'}OLD" || echo "WARN_PRUNE_ROLLBACK_FAILED:${'$'}OLD"
                    if [ -n "${'$'}SWITCH_ID_FOR_PRUNE" ] && [ "${'$'}OLD_ID" = "${'$'}SWITCH_ID_FOR_PRUNE" ]; then
                      case "${'$'}SWITCH_MARKER_FOR_PRUNE" in
                        "${'$'}ROOT"/switches/"${'$'}PKG"/active) rm -f "${'$'}SWITCH_MARKER_FOR_PRUNE" && echo "SWITCH_MARKER_CLEARED=${'$'}SWITCH_MARKER_FOR_PRUNE" || exit 70 ;;
                        *) echo "ERR_BAD_SWITCH_MARKER:${'$'}SWITCH_MARKER_FOR_PRUNE" >&2; exit 70 ;;
                      esac
                    fi
                    ;;
                  *)
                    echo "WARN_SKIP_BAD_ROLLBACK_PATH:${'$'}OLD"
                    ;;
                esac
              done || exit 70
              if [ -f "${'$'}SWITCH_MARKER_FOR_PRUNE" ]; then
                SWITCH_ID_AFTER_PRUNE=${'$'}(sed -n '1p' "${'$'}SWITCH_MARKER_FOR_PRUNE" | tr -d '\r')
                if [ "${'$'}SWITCH_ID_AFTER_PRUNE" != "${'$'}ROLLBACK_ID" ] || [ ! -d "${'$'}ROLLBACK_PARENT/${'$'}SWITCH_ID_AFTER_PRUNE" ]; then
                  case "${'$'}SWITCH_MARKER_FOR_PRUNE" in
                    "${'$'}ROOT"/switches/"${'$'}PKG"/active) rm -f "${'$'}SWITCH_MARKER_FOR_PRUNE" && echo "SWITCH_MARKER_CLEARED=${'$'}SWITCH_MARKER_FOR_PRUNE" || exit 70 ;;
                    *) echo "ERR_BAD_SWITCH_MARKER:${'$'}SWITCH_MARKER_FOR_PRUNE" >&2; exit 70 ;;
                  esac
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
            backup_dir "/data/user/${'$'}DST_USER/${'$'}PKG" "${'$'}ROLLBACK/ce" "ce"
            backup_dir "/data/user_de/${'$'}DST_USER/${'$'}PKG" "${'$'}ROLLBACK/de" "de"
            backup_dir "/data/media/${'$'}DST_USER/Android/data/${'$'}PKG" "${'$'}ROLLBACK/external" "external"
            backup_dir "/data/media/${'$'}DST_USER/Android/media/${'$'}PKG" "${'$'}ROLLBACK/media" "media"
            backup_dir "/data/media/${'$'}DST_USER/Android/obb/${'$'}PKG" "${'$'}ROLLBACK/obb" "obb"
            ${if (settings.includePermissions) "backup_permission_state \"${'$'}ROLLBACK/permissions\"" else ":"}
            ROLLBACK_SIZE_KB=${'$'}(du -sk "${'$'}ROLLBACK" 2>/dev/null | awk '{print ${'$'}1}')
            printf '%s\n' "{\"packageName\":\"${'$'}PKG\",\"rollbackId\":\"${'$'}ROLLBACK_ID\",\"createdAt\":\"${'$'}TS\",\"reason\":\"$rollbackReason\",\"targetUser\":\"${'$'}DST_USER\",\"backupKind\":\"$rollbackRootName\",\"sizeKb\":\"${'$'}ROLLBACK_SIZE_KB\"}" > "${'$'}ROLLBACK/manifest.json" || exit 53
            sync
            ROLLBACK_FINALIZED=1
            uclone_stage_end
            ${RestoreTransactionShell.guard(
                appUidVariable = "UID_VALUE",
                includePermissions = settings.includePermissions,
                manageSwitchMarker = writeSwitchMarker || clearSwitchMarker,
            )}
            uclone_stage_begin RESTORE_DATA
            restore_part "${'$'}ACTIVE/ce" "/data/user/${'$'}DST_USER/${'$'}PKG" "${'$'}UID_VALUE" "app" "ce"
            restore_part "${'$'}ACTIVE/de" "/data/user_de/${'$'}DST_USER/${'$'}PKG" "${'$'}UID_VALUE" "app" "de"
            restore_part "${'$'}ACTIVE/external" "/data/media/${'$'}DST_USER/Android/data/${'$'}PKG" "" "media" "external"
            restore_part "${'$'}ACTIVE/media" "/data/media/${'$'}DST_USER/Android/media/${'$'}PKG" "" "media" "media"
            restore_part "${'$'}ACTIVE/obb" "/data/media/${'$'}DST_USER/Android/obb/${'$'}PKG" "" "media" "obb"
            uclone_stage_end
            uclone_stage_begin RESTORE_PERMISSIONS
            ${if (settings.includePermissions) "restore_permission_state \"${'$'}ACTIVE/permissions\"" else ":"}
            uclone_stage_end
            uclone_stage_begin VERIFY
            [ "${'$'}RESTORED_PARTS" -gt 0 ] || { echo "ERR_NOTHING_RESTORED:${'$'}ACTIVE" >&2; exit 62; }
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
            sync
            force_stop_package_users || exit 76
            TRANSACTION_COMMITTED=1
            UCLONE_TARGET_READY_AT=${'$'}(uclone_now_ms)
            UCLONE_TARGET_DOWNTIME_MS=${'$'}(uclone_elapsed_ms "${'$'}UCLONE_TARGET_READY_AT" "${'$'}UCLONE_TARGET_STOPPED_AT")
            uclone_stage_end
            ${if (pruneOldRollbacks) "(prune_old_rollbacks) || echo \"WARN_PRUNE_ROLLBACK_FAILED:${'$'}ROLLBACK\"" else ":"}
            ${if (writeSwitchMarker || clearSwitchMarker) "stop_clone_user_after_switch_restore" else ":"}
            uclone_emit_metrics
            echo "ROLLBACK=${'$'}ROLLBACK"
            echo "RESTORE_SUMMARY: restoredParts=${'$'}RESTORED_PARTS restoredItems=${'$'}RESTORED_ITEMS backupParts=${'$'}BACKUP_PARTS"
        """.trimIndent()
    }

    private fun requireSafeRollbackId(rollbackId: String) {
        require(rollbackId.isNotBlank() && rollbackId != "." && rollbackId != ".." && rollbackIdPattern.matches(rollbackId)) {
            "Unsafe rollback id: $rollbackId"
        }
    }

}
