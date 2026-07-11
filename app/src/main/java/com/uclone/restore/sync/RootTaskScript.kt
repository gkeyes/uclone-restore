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

    private fun activeTaskScript(task: ActiveRootTask): String = """
        ACTIVE_LOCK_ROOT=${shellQuote("${task.rootDir}/locks")}
        ACTIVE_LOCK="${'$'}ACTIVE_LOCK_ROOT/active_task"
        ACTIVE_STATE="${'$'}ACTIVE_LOCK/state"
        ACTIVE_ACQUIRED=0
        ACTIVE_REQUEST_ID=${shellQuote(task.requestId)}
        ACTIVE_TASK_TYPE=${shellQuote(task.taskType)}
        ACTIVE_PACKAGE=${shellQuote(task.packageName)}
        ACTIVE_STARTED_AT=${task.startedAt}
        ACTIVE_STAGE=PRECHECK
        ACTIVE_BOOT_ID=${'$'}(cat /proc/sys/kernel/random/boot_id 2>/dev/null || echo unknown)
        ACTIVE_PID_START_TICKS=${'$'}(awk '{print ${'$'}22}' /proc/${'$'}${'$'}/stat 2>/dev/null || echo unknown)
        uclone_write_active_state() {
          ACTIVE_STATE_TMP="${'$'}ACTIVE_STATE.tmp.${'$'}${'$'}"
          {
            echo "requestId=${'$'}ACTIVE_REQUEST_ID"
            echo "taskType=${'$'}ACTIVE_TASK_TYPE"
            echo "packageName=${'$'}ACTIVE_PACKAGE"
            echo "pid=${'$'}${'$'}"
            echo "bootId=${'$'}ACTIVE_BOOT_ID"
            echo "pidStartTicks=${'$'}ACTIVE_PID_START_TICKS"
            echo "stage=${'$'}ACTIVE_STAGE"
            echo "startedAt=${'$'}ACTIVE_STARTED_AT"
          } > "${'$'}ACTIVE_STATE_TMP" || return 1
          mv -f "${'$'}ACTIVE_STATE_TMP" "${'$'}ACTIVE_STATE" || return 1
        }
        uclone_active_stage() {
          [ "${'$'}ACTIVE_ACQUIRED" = "1" ] || return 0
          ACTIVE_STAGE="${'$'}1"
          uclone_write_active_state
        }
        active_state_value() {
          awk -F= -v key="${'$'}1" '${'$'}1 == key { sub(/^[^=]*=/, ""); print; exit }' "${'$'}ACTIVE_STATE" 2>/dev/null
        }
        uclone_release_active_task() {
          [ "${'$'}ACTIVE_ACQUIRED" = "1" ] || return 0
          ACTIVE_OWNER_PID=${'$'}(active_state_value pid)
          [ "${'$'}ACTIVE_OWNER_PID" = "${'$'}${'$'}" ] || return 0
          rm -f "${'$'}ACTIVE_STATE"
          rmdir "${'$'}ACTIVE_LOCK" 2>/dev/null || true
          ACTIVE_ACQUIRED=0
        }
        uclone_claim_active_task() {
          mkdir -p "${'$'}ACTIVE_LOCK_ROOT" || return 78
          if ! mkdir "${'$'}ACTIVE_LOCK" 2>/dev/null; then
            EXISTING_PID=${'$'}(active_state_value pid)
            EXISTING_REQUEST=${'$'}(active_state_value requestId)
            EXISTING_BOOT_ID=${'$'}(active_state_value bootId)
            EXISTING_PID_START_TICKS=${'$'}(active_state_value pidStartTicks)
            case "${'$'}EXISTING_PID" in ''|*[!0-9]*) EXISTING_PID=0 ;; esac
            LIVE_PID_START_TICKS=""
            if [ "${'$'}EXISTING_PID" -gt 0 ]; then
              LIVE_PID_START_TICKS=${'$'}(awk '{print ${'$'}22}' "/proc/${'$'}EXISTING_PID/stat" 2>/dev/null || true)
            fi
            if [ "${'$'}EXISTING_BOOT_ID" = "${'$'}ACTIVE_BOOT_ID" ] &&
               [ -n "${'$'}EXISTING_PID_START_TICKS" ] &&
               [ "${'$'}EXISTING_PID_START_TICKS" = "${'$'}LIVE_PID_START_TICKS" ] &&
               kill -0 "${'$'}EXISTING_PID" 2>/dev/null; then
              echo "ERR_ACTIVE_ROOT_TASK:request=${'$'}EXISTING_REQUEST:pid=${'$'}EXISTING_PID" >&2
              return 79
            fi
            ORPHANED_AT=${'$'}(/system/bin/date +%s%3N 2>/dev/null || /system/bin/date +%s)
            ORPHANED_LOCK="${'$'}{ACTIVE_LOCK_ROOT}/orphaned_${'$'}{ORPHANED_AT}_${'$'}{EXISTING_PID}"
            mv "${'$'}ACTIVE_LOCK" "${'$'}ORPHANED_LOCK" || return 78
            echo "UCLONE_RECOVERY:ORPHANED request=${'$'}EXISTING_REQUEST pid=${'$'}EXISTING_PID path=${'$'}ORPHANED_LOCK"
            mkdir "${'$'}ACTIVE_LOCK" || return 78
          fi
          ACTIVE_ACQUIRED=1
          uclone_write_active_state || return 78
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
)
