package com.uclone.restore.sync

import com.uclone.restore.root.shellQuote

internal object RootTaskScript {
    fun wrap(logPath: String, header: String, body: String, startedAt: Long): String {
        val logDir = logPath.substringBeforeLast('/')
        return """
            set -o pipefail || exit 73
            LOG_DIR=${shellQuote(logDir)}
            LOG_PATH=${shellQuote(logPath)}
            ROOT_ID=${'$'}(/system/bin/id 2>&1)
            LOG_DIR_READY=1
            /system/bin/mkdir -p "${'$'}LOG_DIR" || LOG_DIR_READY=0
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
                    (
                      $body
                    )
                    TASK_EXIT=${'$'}?
                  fi
                  ;;
                *)
                  echo "ERR_ROOT_UNAVAILABLE:${'$'}ROOT_ID" >&2
                  TASK_EXIT=126
                  ;;
              esac
              TASK_END=${'$'}(/system/bin/date +%s%3N 2>/dev/null || true)
              case "${'$'}TASK_END" in ''|*[!0-9]*) TASK_END=${'$'}(( $(/system/bin/date +%s) * 1000 )) ;; esac
              echo "END=${'$'}TASK_END"
              echo "END_LOCAL=${'$'}(/system/bin/date '+%Y-%m-%d %H:%M:%S.%3N %z' 2>/dev/null || true)"
              echo "DURATION_MS=${'$'}((TASK_END - $startedAt))"
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
}
