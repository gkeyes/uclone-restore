package com.uclone.restore.sync

internal object FilesystemSafetyShell {
    fun functions(): String = """
        uclone_assert_single_filesystem() {
          UCLONE_FS_ROOT="${'$'}1"
          [ -d "${'$'}UCLONE_FS_ROOT" ] && [ ! -L "${'$'}UCLONE_FS_ROOT" ] || {
            echo "ERR_UNSAFE_FILESYSTEM_ROOT:${'$'}UCLONE_FS_ROOT" >&2
            return 1
          }
          UCLONE_FS_DEVICE=${'$'}(stat -c '%d' "${'$'}UCLONE_FS_ROOT" 2>/dev/null || true)
          case "${'$'}UCLONE_FS_DEVICE" in ''|*[!0-9]*)
            echo "ERR_FILESYSTEM_DEVICE_UNAVAILABLE:${'$'}UCLONE_FS_ROOT" >&2
            return 1
            ;;
          esac
          UCLONE_MOUNTINFO_PATH=${'$'}{UCLONE_MOUNTINFO_PATH:-/proc/self/mountinfo}
          [ -r "${'$'}UCLONE_MOUNTINFO_PATH" ] || {
            echo "ERR_FILESYSTEM_MOUNTINFO_UNAVAILABLE:${'$'}UCLONE_FS_ROOT" >&2
            return 1
          }
          UCLONE_MOUNTINFO_EXIT=0
          UCLONE_NESTED_MOUNT=${'$'}(
            awk -v root="${'$'}UCLONE_FS_ROOT" '
              ${'$'}5 != root && index(${'$'}5, root "/") == 1 { print ${'$'}5; exit }
            ' "${'$'}UCLONE_MOUNTINFO_PATH" 2>/dev/null
          ) || UCLONE_MOUNTINFO_EXIT=${'$'}?
          [ "${'$'}UCLONE_MOUNTINFO_EXIT" -eq 0 ] || {
            echo "ERR_FILESYSTEM_MOUNTINFO_UNAVAILABLE:${'$'}UCLONE_FS_ROOT" >&2
            return 1
          }
          [ -z "${'$'}UCLONE_NESTED_MOUNT" ] || {
            echo "ERR_CROSS_FILESYSTEM_PATH:${'$'}UCLONE_FS_ROOT" >&2
            return 1
          }
        }
        uclone_count_tree() {
          UCLONE_COUNT_ROOT="${'$'}1"
          uclone_assert_single_filesystem "${'$'}UCLONE_COUNT_ROOT" || return 1
          UCLONE_COUNT_VALUE=${'$'}(find "${'$'}UCLONE_COUNT_ROOT" -xdev -mindepth 1 -print 2>/dev/null | wc -l | tr -d ' ') || return 1
          case "${'$'}UCLONE_COUNT_VALUE" in ''|*[!0-9]*) return 1 ;; esac
          echo "${'$'}UCLONE_COUNT_VALUE"
        }
        uclone_tree_kb() {
          UCLONE_SIZE_ROOT="${'$'}1"
          uclone_assert_single_filesystem "${'$'}UCLONE_SIZE_ROOT" || return 1
          UCLONE_SIZE_KB=${'$'}(du -skx "${'$'}UCLONE_SIZE_ROOT" 2>/dev/null | awk '{print ${'$'}1}') || return 1
          case "${'$'}UCLONE_SIZE_KB" in ''|*[!0-9]*) return 1 ;; esac
          echo "${'$'}UCLONE_SIZE_KB"
        }
        uclone_archive_tree() {
          UCLONE_ARCHIVE_SOURCE="${'$'}1"
          uclone_assert_single_filesystem "${'$'}UCLONE_ARCHIVE_SOURCE" || return 1
          (
            cd "${'$'}UCLONE_ARCHIVE_SOURCE" || exit 1
            tar -cpf - .
          )
        }
        uclone_extract_workspace_tree() {
          UCLONE_EXTRACT_SOURCE="${'$'}1"
          UCLONE_EXTRACT_DESTINATION="${'$'}2"
          [ -d "${'$'}UCLONE_EXTRACT_DESTINATION" ] && [ ! -L "${'$'}UCLONE_EXTRACT_DESTINATION" ] || return 1
          # A normal POSIX pipeline reports only tar's exit status. Require pipefail so a
          # failed source archive cannot be mistaken for a successful partial extraction.
          (
            set -o pipefail 2>/dev/null || {
              echo "ERR_PIPEFAIL_UNAVAILABLE" >&2
              exit 125
            }
            uclone_archive_tree "${'$'}UCLONE_EXTRACT_SOURCE" |
              (cd "${'$'}UCLONE_EXTRACT_DESTINATION" && tar -xopf -)
          )
        }
        uclone_extract_target_tree() {
          UCLONE_EXTRACT_SOURCE="${'$'}1"
          UCLONE_EXTRACT_DESTINATION="${'$'}2"
          [ -d "${'$'}UCLONE_EXTRACT_DESTINATION" ] && [ ! -L "${'$'}UCLONE_EXTRACT_DESTINATION" ] || return 1
          # See uclone_extract_workspace_tree: never accept a truncated tar stream.
          (
            set -o pipefail 2>/dev/null || {
              echo "ERR_PIPEFAIL_UNAVAILABLE" >&2
              exit 125
            }
            uclone_archive_tree "${'$'}UCLONE_EXTRACT_SOURCE" |
              (cd "${'$'}UCLONE_EXTRACT_DESTINATION" && tar -xpf -)
          )
        }
        uclone_clear_tree_contents() {
          UCLONE_CLEAR_ROOT="${'$'}1"
          uclone_assert_single_filesystem "${'$'}UCLONE_CLEAR_ROOT" || return 1
          find "${'$'}UCLONE_CLEAR_ROOT" -xdev -mindepth 1 -depth -delete >/dev/null
        }
        uclone_remove_tree() {
          UCLONE_REMOVE_ROOT="${'$'}1"
          [ -e "${'$'}UCLONE_REMOVE_ROOT" ] || [ -L "${'$'}UCLONE_REMOVE_ROOT" ] || return 0
          [ -d "${'$'}UCLONE_REMOVE_ROOT" ] && [ ! -L "${'$'}UCLONE_REMOVE_ROOT" ] || {
            echo "ERR_UNSAFE_REMOVE_ROOT:${'$'}UCLONE_REMOVE_ROOT" >&2
            return 1
          }
          uclone_assert_single_filesystem "${'$'}UCLONE_REMOVE_ROOT" || return 1
          find "${'$'}UCLONE_REMOVE_ROOT" -xdev -depth -delete >/dev/null
        }
        uclone_remove_file() {
          UCLONE_REMOVE_FILE="${'$'}1"
          [ -e "${'$'}UCLONE_REMOVE_FILE" ] || [ -L "${'$'}UCLONE_REMOVE_FILE" ] || return 0
          [ -f "${'$'}UCLONE_REMOVE_FILE" ] && [ ! -L "${'$'}UCLONE_REMOVE_FILE" ] || {
            echo "ERR_UNSAFE_REMOVE_FILE:${'$'}UCLONE_REMOVE_FILE" >&2
            return 1
          }
          rm -f "${'$'}UCLONE_REMOVE_FILE"
        }
    """.trimIndent()
}
