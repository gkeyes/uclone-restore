package com.uclone.restore.sync

import com.uclone.restore.root.shellQuote

internal object RootTaskScript {
    fun wrap(
        logPath: String,
        header: String,
        body: String,
        startedAt: Long,
        activeTask: ActiveRootTask,
    ): String {
        val logDir = logPath.substringBeforeLast('/')
        val activeTaskScript = activeTaskScript(activeTask)
        return """
            set -o pipefail || exit 73
            ${WorkspacePathGuard.require(activeTask.rootDir)}
            LOG_DIR=${shellQuote(logDir)}
            LOG_PATH=${shellQuote(logPath)}
            ROOT_ID=${'$'}(/system/bin/id 2>&1)
            LOG_DIR_READY=1
            /system/bin/mkdir -p "${'$'}LOG_DIR" || LOG_DIR_READY=0
            $activeTaskScript
            run_uclone_task() {
              TASK_EXIT=0
              /system/bin/printf '%s' ${shellQuote(header)}
              echo "ROOT=${'$'}ROOT_ID"
              case "${'$'}ROOT_ID" in
                *uid=0*)
                  if [ "${'$'}LOG_DIR_READY" != "1" ]; then
                    echo "ERR_LOG_DIR_CREATE:${'$'}LOG_DIR" >&2
                    TASK_EXIT=74
                  else
                    uclone_claim_active_task
                    TASK_EXIT=${'$'}?
                    if [ "${'$'}TASK_EXIT" = "0" ]; then
                      (
                        $body
                      )
                      TASK_EXIT=${'$'}?
                    fi
                  fi
                  ;;
                *)
                  echo "ERR_ROOT_UNAVAILABLE:${'$'}ROOT_ID" >&2
                  TASK_EXIT=126
                  ;;
              esac
              TASK_END=${'$'}(/system/bin/date +%s%3N 2>/dev/null || true)
              case "${'$'}TASK_END" in ''|*[!0-9]*) TASK_END=${'$'}(/system/bin/date +%s | awk '{ printf "%.0f\n", ${'$'}1 * 1000 }') ;; esac
              echo "END=${'$'}TASK_END"
              echo "END_LOCAL=${'$'}(/system/bin/date '+%Y-%m-%d %H:%M:%S.%3N %z' 2>/dev/null || true)"
              TASK_DURATION_MS=${'$'}(awk -v FINISHED_AT="${'$'}TASK_END" -v STARTED_AT="$startedAt" 'BEGIN { VALUE = FINISHED_AT - STARTED_AT; if (VALUE < 0) VALUE = 0; printf "%.0f\n", VALUE }')
              echo "DURATION_MS=${'$'}TASK_DURATION_MS"
              echo "EXIT=${'$'}TASK_EXIT"
              return "${'$'}TASK_EXIT"
            }
            if [ "${'$'}LOG_DIR_READY" = "1" ]; then
              run_uclone_task 2>&1 | /system/bin/tee -a "${'$'}LOG_PATH"
            else
              run_uclone_task 2>&1
            fi
            exit ${'$'}?
        """.trimIndent()
    }

    internal fun activeTaskScript(task: ActiveRootTask): String = """
        ACTIVE_LOCK_ROOT=${shellQuote("${task.rootDir}/locks")}
        ACTIVE_CLAIM="${'$'}ACTIVE_LOCK_ROOT/active_task.claim"
        ACTIVE_LEGACY_LOCK="${'$'}ACTIVE_LOCK_ROOT/active_task"
        ACTIVE_LEGACY_STATE="${'$'}ACTIVE_LEGACY_LOCK/state"
        ACTIVE_ORPHANED_ROOT="${'$'}ACTIVE_LOCK_ROOT/orphaned"
        ACTIVE_STATE="${'$'}ACTIVE_CLAIM"
        ACTIVE_ACQUIRED=0
        ACTIVE_REQUEST_ID=${shellQuote(task.requestId)}
        ACTIVE_TASK_TYPE=${shellQuote(task.taskType)}
        ACTIVE_TASK_TYPE_LINE=${shellQuote("taskType=${task.taskType}")}
        ACTIVE_PACKAGE=${shellQuote(task.packageName)}
        ACTIVE_RECOVERY_TRANSACTION_ID=${shellQuote(task.recoveryTransactionId.orEmpty())}
        ACTIVE_STARTED_AT=${task.startedAt}
        ACTIVE_STAGE=PRECHECK
        UCLONE_REQUEST_ID="${'$'}ACTIVE_REQUEST_ID"
        UCLONE_TASK_TYPE="${'$'}ACTIVE_TASK_TYPE"
        ACTIVE_BOOT_ID=${'$'}(cat /proc/sys/kernel/random/boot_id 2>/dev/null || echo unknown)
        ACTIVE_PID_START_TICKS=${'$'}(awk '{print ${'$'}22}' /proc/${'$'}${'$'}/stat 2>/dev/null || echo unknown)
        uclone_write_state_file() {
          ACTIVE_WRITE_TARGET="${'$'}1"
          {
            echo "requestId=${'$'}ACTIVE_REQUEST_ID"
            echo "${'$'}ACTIVE_TASK_TYPE_LINE"
            echo "packageName=${'$'}ACTIVE_PACKAGE"
            echo "recoveryTransactionId=${'$'}ACTIVE_RECOVERY_TRANSACTION_ID"
            echo "pid=${'$'}${'$'}"
            echo "bootId=${'$'}ACTIVE_BOOT_ID"
            echo "pidStartTicks=${'$'}ACTIVE_PID_START_TICKS"
            echo "stage=${'$'}ACTIVE_STAGE"
            echo "startedAt=${'$'}ACTIVE_STARTED_AT"
          } > "${'$'}ACTIVE_WRITE_TARGET" || return 1
        }
        active_state_value() {
          ACTIVE_VALUE_KEY="${'$'}1"
          ACTIVE_VALUE_FILE="${'$'}{2:-${'$'}ACTIVE_STATE}"
          awk -F= -v key="${'$'}ACTIVE_VALUE_KEY" '${'$'}1 == key { sub(/^[^=]*=/, ""); print; exit }' "${'$'}ACTIVE_VALUE_FILE" 2>/dev/null
        }
        uclone_state_is_live() {
          ACTIVE_OBSERVED_STATE="${'$'}1"
          EXISTING_PID=${'$'}(active_state_value pid "${'$'}ACTIVE_OBSERVED_STATE")
          EXISTING_REQUEST=${'$'}(active_state_value requestId "${'$'}ACTIVE_OBSERVED_STATE")
          EXISTING_BOOT_ID=${'$'}(active_state_value bootId "${'$'}ACTIVE_OBSERVED_STATE")
          EXISTING_PID_START_TICKS=${'$'}(active_state_value pidStartTicks "${'$'}ACTIVE_OBSERVED_STATE")
          case "${'$'}EXISTING_PID" in ''|*[!0-9]*) EXISTING_PID=0 ;; esac
          LIVE_PID_START_TICKS=""
          if [ "${'$'}EXISTING_PID" -gt 1 ]; then
            LIVE_PID_START_TICKS=${'$'}(awk '{print ${'$'}22}' "/proc/${'$'}EXISTING_PID/stat" 2>/dev/null || true)
          fi
          [ "${'$'}EXISTING_BOOT_ID" = "${'$'}ACTIVE_BOOT_ID" ] &&
            [ -n "${'$'}EXISTING_PID_START_TICKS" ] &&
            [ "${'$'}EXISTING_PID_START_TICKS" = "${'$'}LIVE_PID_START_TICKS" ] &&
            kill -0 "${'$'}EXISTING_PID" 2>/dev/null
        }
        uclone_write_active_state() {
          [ "${'$'}ACTIVE_ACQUIRED" = "1" ] || return 1
          ACTIVE_OWNER_PID=${'$'}(active_state_value pid "${'$'}ACTIVE_CLAIM")
          ACTIVE_OWNER_REQUEST=${'$'}(active_state_value requestId "${'$'}ACTIVE_CLAIM")
          [ "${'$'}ACTIVE_OWNER_PID" = "${'$'}${'$'}" ] && [ "${'$'}ACTIVE_OWNER_REQUEST" = "${'$'}ACTIVE_REQUEST_ID" ] || return 1
          ACTIVE_STATE_TMP="${'$'}ACTIVE_LOCK_ROOT/.active_task.state.${'$'}${'$'}"
          uclone_write_state_file "${'$'}ACTIVE_STATE_TMP" || return 1
          chmod 600 "${'$'}ACTIVE_STATE_TMP" || return 1
          mv -f "${'$'}ACTIVE_STATE_TMP" "${'$'}ACTIVE_CLAIM" || return 1
        }
        uclone_active_stage() {
          [ "${'$'}ACTIVE_ACQUIRED" = "1" ] || return 0
          ACTIVE_STAGE="${'$'}1"
          uclone_write_active_state
        }
        uclone_release_active_task() {
          [ "${'$'}ACTIVE_ACQUIRED" = "1" ] || return 0
          ACTIVE_OWNER_PID=${'$'}(active_state_value pid "${'$'}ACTIVE_CLAIM")
          ACTIVE_OWNER_REQUEST=${'$'}(active_state_value requestId "${'$'}ACTIVE_CLAIM")
          [ "${'$'}ACTIVE_OWNER_PID" = "${'$'}${'$'}" ] && [ "${'$'}ACTIVE_OWNER_REQUEST" = "${'$'}ACTIVE_REQUEST_ID" ] || return 0
          rm -f "${'$'}ACTIVE_CLAIM"
          ACTIVE_ACQUIRED=0
        }
        uclone_claim_active_task() {
          mkdir -p "${'$'}ACTIVE_LOCK_ROOT" "${'$'}ACTIVE_ORPHANED_ROOT" || return 78
          if [ -d "${'$'}ACTIVE_LEGACY_LOCK" ]; then
            ACTIVE_INITIALIZING_WAIT=0
            while [ ! -f "${'$'}ACTIVE_LEGACY_STATE" ] && [ "${'$'}ACTIVE_INITIALIZING_WAIT" -lt 10 ]; do
              sleep 0.05
              ACTIVE_INITIALIZING_WAIT=${'$'}((ACTIVE_INITIALIZING_WAIT + 1))
            done
            if [ ! -f "${'$'}ACTIVE_LEGACY_STATE" ]; then
              ACTIVE_LOCK_MTIME=${'$'}(stat -c %Y "${'$'}ACTIVE_LEGACY_LOCK" 2>/dev/null || echo 0)
              ACTIVE_NOW_SECONDS=${'$'}(/system/bin/date +%s 2>/dev/null || echo 0)
              case "${'$'}ACTIVE_LOCK_MTIME:${'$'}ACTIVE_NOW_SECONDS" in *[!0-9:]*) ACTIVE_INITIALIZING_AGE=0 ;; *) ACTIVE_INITIALIZING_AGE=${'$'}((ACTIVE_NOW_SECONDS - ACTIVE_LOCK_MTIME)) ;; esac
              if [ "${'$'}ACTIVE_INITIALIZING_AGE" -lt 5 ]; then
                echo "ERR_ACTIVE_ROOT_TASK_INITIALIZING:age=${'$'}ACTIVE_INITIALIZING_AGE" >&2
                return 79
              fi
            fi
            if [ -f "${'$'}ACTIVE_LEGACY_STATE" ] && uclone_state_is_live "${'$'}ACTIVE_LEGACY_STATE"; then
              echo "ERR_ACTIVE_ROOT_TASK:request=${'$'}EXISTING_REQUEST:pid=${'$'}EXISTING_PID" >&2
              return 79
            fi
            ORPHANED_AT=${'$'}(/system/bin/date +%s%3N 2>/dev/null || /system/bin/date +%s)
            ORPHANED_LOCK="${'$'}{ACTIVE_ORPHANED_ROOT}/legacy_${'$'}{ORPHANED_AT}_${'$'}{EXISTING_PID}"
            mv "${'$'}ACTIVE_LEGACY_LOCK" "${'$'}ORPHANED_LOCK" || return 78
            echo "UCLONE_RECOVERY:ORPHANED request=${'$'}EXISTING_REQUEST pid=${'$'}EXISTING_PID path=${'$'}ORPHANED_LOCK"
          fi
          ACTIVE_CLAIM_TMP="${'$'}ACTIVE_LOCK_ROOT/.active_task.claim.${'$'}${'$'}"
          uclone_write_state_file "${'$'}ACTIVE_CLAIM_TMP" || return 78
          chmod 600 "${'$'}ACTIVE_CLAIM_TMP" || { rm -f "${'$'}ACTIVE_CLAIM_TMP"; return 78; }
          ACTIVE_CLAIM_ATTEMPTS=0
          while [ "${'$'}ACTIVE_CLAIM_ATTEMPTS" -lt 3 ]; do
            if ln "${'$'}ACTIVE_CLAIM_TMP" "${'$'}ACTIVE_CLAIM" 2>/dev/null; then
              ACTIVE_ACQUIRED=1
              break
            fi
            if [ -L "${'$'}ACTIVE_CLAIM" ]; then
              rm -f "${'$'}ACTIVE_CLAIM_TMP"
              echo "ERR_ACTIVE_ROOT_TASK_UNSAFE_CLAIM:${'$'}ACTIVE_CLAIM" >&2
              return 78
            fi
            if [ -f "${'$'}ACTIVE_CLAIM" ] && uclone_state_is_live "${'$'}ACTIVE_CLAIM"; then
              rm -f "${'$'}ACTIVE_CLAIM_TMP"
              echo "ERR_ACTIVE_ROOT_TASK:request=${'$'}EXISTING_REQUEST:pid=${'$'}EXISTING_PID" >&2
              return 79
            fi
            ACTIVE_CLAIM_ATTEMPTS=${'$'}((ACTIVE_CLAIM_ATTEMPTS + 1))
            if [ -f "${'$'}ACTIVE_CLAIM" ]; then
              ORPHANED_AT=${'$'}(/system/bin/date +%s%3N 2>/dev/null || /system/bin/date +%s)
              ORPHANED_CLAIM="${'$'}{ACTIVE_ORPHANED_ROOT}/claim_${'$'}{ORPHANED_AT}_${'$'}{EXISTING_PID}_${'$'}{ACTIVE_CLAIM_ATTEMPTS}"
              if mv "${'$'}ACTIVE_CLAIM" "${'$'}ORPHANED_CLAIM" 2>/dev/null; then
                echo "UCLONE_RECOVERY:ORPHANED request=${'$'}EXISTING_REQUEST pid=${'$'}EXISTING_PID path=${'$'}ORPHANED_CLAIM"
              fi
            fi
          done
          rm -f "${'$'}ACTIVE_CLAIM_TMP"
          [ "${'$'}ACTIVE_ACQUIRED" = "1" ] || return 78
          trap 'uclone_release_active_task' EXIT
          echo "ROOT_TASK_PID=${'$'}${'$'}"
          return 0
        }
    """.trimIndent()
}

internal data class ActiveRootTask(
    val rootDir: String,
    val requestId: String,
    val taskType: String,
    val packageName: String,
    val startedAt: Long,
    val recoveryTransactionId: String? = null,
)
