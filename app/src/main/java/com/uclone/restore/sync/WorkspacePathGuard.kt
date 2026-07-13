package com.uclone.restore.sync

import com.uclone.restore.root.shellQuote

internal object WorkspacePathGuard {
    const val DEFAULT_ROOT = "/data/adb/uclone"

    fun require(rootDir: String): String = """
        UCLONE_WORKSPACE_EXPECTED=${shellQuote(rootDir)}
        ${workspaceOwnershipGuard()}
        ${FilesystemSafetyShell.functions()}
        case "${'$'}UCLONE_WORKSPACE_EXPECTED" in
          /*) ;;
          *) echo "ERR_WORKSPACE_NOT_ABSOLUTE:${'$'}UCLONE_WORKSPACE_EXPECTED" >&2; exit 75 ;;
        esac
        case "${'$'}UCLONE_WORKSPACE_EXPECTED" in
          /|/data|/data/adb) echo "ERR_UNSAFE_WORKSPACE_ROOT:${'$'}UCLONE_WORKSPACE_EXPECTED" >&2; exit 75 ;;
        esac
        case "${'$'}UCLONE_WORKSPACE_EXPECTED" in
          *[[:space:]]*) echo "ERR_WORKSPACE_WHITESPACE:${'$'}UCLONE_WORKSPACE_EXPECTED" >&2; exit 75 ;;
        esac
        case "${'$'}UCLONE_WORKSPACE_EXPECTED" in
          *[\\\\]*) echo "ERR_WORKSPACE_BACKSLASH:${'$'}UCLONE_WORKSPACE_EXPECTED" >&2; exit 75 ;;
        esac
        [ ! -L "${'$'}UCLONE_WORKSPACE_EXPECTED" ] || {
          echo "ERR_WORKSPACE_ROOT_SYMLINK:${'$'}UCLONE_WORKSPACE_EXPECTED" >&2
          exit 75
        }
        if [ "${'$'}UCLONE_WORKSPACE_EXPECTED" = "$DEFAULT_ROOT" ]; then
          mkdir -p "${'$'}UCLONE_WORKSPACE_EXPECTED" || exit 75
        else
          [ -d "${'$'}UCLONE_WORKSPACE_EXPECTED" ] || {
            echo "ERR_WORKSPACE_MISSING:${'$'}UCLONE_WORKSPACE_EXPECTED" >&2
            exit 75
          }
        fi
        UCLONE_WORKSPACE_REAL=${'$'}(readlink -f "${'$'}UCLONE_WORKSPACE_EXPECTED" 2>/dev/null || true)
        [ -n "${'$'}UCLONE_WORKSPACE_REAL" ] && [ "${'$'}UCLONE_WORKSPACE_REAL" = "${'$'}UCLONE_WORKSPACE_EXPECTED" ] || {
          echo "ERR_WORKSPACE_NOT_CANONICAL:${'$'}UCLONE_WORKSPACE_EXPECTED:${'$'}UCLONE_WORKSPACE_REAL" >&2
          exit 75
        }
        uclone_guard_workspace_directory "${'$'}UCLONE_WORKSPACE_REAL" || exit 75
        uclone_assert_single_filesystem "${'$'}UCLONE_WORKSPACE_REAL" || exit 75
        UCLONE_CONFIG_DIR="${'$'}UCLONE_WORKSPACE_REAL/config"
        [ ! -L "${'$'}UCLONE_CONFIG_DIR" ] || {
          echo "ERR_WORKSPACE_SYMLINK:${'$'}UCLONE_CONFIG_DIR" >&2
          exit 75
        }
        if [ "${'$'}UCLONE_WORKSPACE_REAL" = "$DEFAULT_ROOT" ]; then
          mkdir -p "${'$'}UCLONE_CONFIG_DIR" || exit 75
        else
          [ -d "${'$'}UCLONE_CONFIG_DIR" ] || {
            echo "ERR_UNTRUSTED_WORKSPACE:${'$'}UCLONE_WORKSPACE_REAL" >&2
            exit 75
          }
        fi
        UCLONE_CONFIG_REAL=${'$'}(readlink -f "${'$'}UCLONE_CONFIG_DIR" 2>/dev/null || true)
        [ "${'$'}UCLONE_CONFIG_REAL" = "${'$'}UCLONE_WORKSPACE_REAL/config" ] || {
          echo "ERR_UNSAFE_WORKSPACE_TARGET:${'$'}UCLONE_CONFIG_DIR" >&2
          exit 75
        }
        uclone_guard_workspace_directory "${'$'}UCLONE_CONFIG_DIR" || exit 75
        UCLONE_IDENTITY="${'$'}UCLONE_CONFIG_DIR/workspace.identity"
        [ ! -L "${'$'}UCLONE_IDENTITY" ] || {
          echo "ERR_WORKSPACE_IDENTITY_SYMLINK:${'$'}UCLONE_IDENTITY" >&2
          exit 75
        }
        if [ "${'$'}UCLONE_WORKSPACE_REAL" = "$DEFAULT_ROOT" ] && [ ! -e "${'$'}UCLONE_IDENTITY" ]; then
          UCLONE_IDENTITY_TMP="${'$'}UCLONE_IDENTITY.tmp.${'$'}${'$'}"
          (
            umask 077
            printf '%s\n' 'com.uclone.restore.workspace.v1' > "${'$'}UCLONE_IDENTITY_TMP" &&
              chown 0:0 "${'$'}UCLONE_IDENTITY_TMP" &&
              chmod 600 "${'$'}UCLONE_IDENTITY_TMP" &&
              mv -f "${'$'}UCLONE_IDENTITY_TMP" "${'$'}UCLONE_IDENTITY"
          ) || exit 75
        fi
        [ -f "${'$'}UCLONE_IDENTITY" ] && [ ! -L "${'$'}UCLONE_IDENTITY" ] || {
          echo "ERR_UNTRUSTED_WORKSPACE:${'$'}UCLONE_WORKSPACE_REAL" >&2
          exit 75
        }
        UCLONE_IDENTITY_OWNER=${'$'}(stat -c '%u:%g' "${'$'}UCLONE_IDENTITY" 2>/dev/null || true)
        UCLONE_IDENTITY_MODE=${'$'}(stat -c '%a' "${'$'}UCLONE_IDENTITY" 2>/dev/null || true)
        [ "${'$'}UCLONE_IDENTITY_OWNER" = "0:0" ] && [ "${'$'}UCLONE_IDENTITY_MODE" = "600" ] &&
          [ "${'$'}(sed -n '1p' "${'$'}UCLONE_IDENTITY" 2>/dev/null)" = "com.uclone.restore.workspace.v1" ] || {
          echo "ERR_UNTRUSTED_WORKSPACE_IDENTITY:${'$'}UCLONE_IDENTITY" >&2
          exit 75
        }
        for UCLONE_MANAGED_NAME in snapshots rollback clone_rollback switches logs tmp audit config locks transactions; do
          UCLONE_MANAGED_PATH="${'$'}UCLONE_WORKSPACE_REAL/${'$'}UCLONE_MANAGED_NAME"
          if [ -e "${'$'}UCLONE_MANAGED_PATH" ] || [ -L "${'$'}UCLONE_MANAGED_PATH" ]; then
            [ ! -L "${'$'}UCLONE_MANAGED_PATH" ] || {
              echo "ERR_WORKSPACE_SYMLINK:${'$'}UCLONE_MANAGED_PATH" >&2
              exit 75
            }
            UCLONE_MANAGED_REAL=${'$'}(readlink -f "${'$'}UCLONE_MANAGED_PATH" 2>/dev/null || true)
            [ "${'$'}UCLONE_MANAGED_REAL" = "${'$'}UCLONE_WORKSPACE_REAL/${'$'}UCLONE_MANAGED_NAME" ] || {
              echo "ERR_UNSAFE_WORKSPACE_TARGET:${'$'}UCLONE_MANAGED_PATH" >&2
              exit 75
            }
            uclone_guard_workspace_directory "${'$'}UCLONE_MANAGED_PATH" || exit 75
          fi
        done
        ${structuralPathGuard()}
        ROOT="${'$'}UCLONE_WORKSPACE_REAL"
        ROOT_REAL="${'$'}UCLONE_WORKSPACE_REAL"
    """.trimIndent()

    fun inspect(rootDir: String): String = """
        UCLONE_WORKSPACE_EXPECTED=${shellQuote(rootDir)}
        ${workspaceOwnershipGuard()}
        ${FilesystemSafetyShell.functions()}
        case "${'$'}UCLONE_WORKSPACE_EXPECTED" in
          /*) ;;
          *) echo "ERR_WORKSPACE_NOT_ABSOLUTE:${'$'}UCLONE_WORKSPACE_EXPECTED" >&2; exit 75 ;;
        esac
        case "${'$'}UCLONE_WORKSPACE_EXPECTED" in
          /|/data|/data/adb) echo "ERR_UNSAFE_WORKSPACE_ROOT:${'$'}UCLONE_WORKSPACE_EXPECTED" >&2; exit 75 ;;
        esac
        case "${'$'}UCLONE_WORKSPACE_EXPECTED" in
          *[[:space:]]*) echo "ERR_WORKSPACE_WHITESPACE:${'$'}UCLONE_WORKSPACE_EXPECTED" >&2; exit 75 ;;
        esac
        case "${'$'}UCLONE_WORKSPACE_EXPECTED" in
          *[\\\\]*) echo "ERR_WORKSPACE_BACKSLASH:${'$'}UCLONE_WORKSPACE_EXPECTED" >&2; exit 75 ;;
        esac
        [ ! -L "${'$'}UCLONE_WORKSPACE_EXPECTED" ] || {
          echo "ERR_WORKSPACE_ROOT_SYMLINK:${'$'}UCLONE_WORKSPACE_EXPECTED" >&2
          exit 75
        }
        UCLONE_WORKSPACE_MISSING=0
        if [ ! -e "${'$'}UCLONE_WORKSPACE_EXPECTED" ]; then
          [ "${'$'}UCLONE_WORKSPACE_EXPECTED" = "$DEFAULT_ROOT" ] || {
            echo "ERR_WORKSPACE_MISSING:${'$'}UCLONE_WORKSPACE_EXPECTED" >&2
            exit 75
          }
          UCLONE_WORKSPACE_REAL="${'$'}UCLONE_WORKSPACE_EXPECTED"
          UCLONE_WORKSPACE_MISSING=1
        else
          [ -d "${'$'}UCLONE_WORKSPACE_EXPECTED" ] || {
            echo "ERR_WORKSPACE_NOT_DIRECTORY:${'$'}UCLONE_WORKSPACE_EXPECTED" >&2
            exit 75
          }
          UCLONE_WORKSPACE_REAL=${'$'}(readlink -f "${'$'}UCLONE_WORKSPACE_EXPECTED" 2>/dev/null || true)
          [ -n "${'$'}UCLONE_WORKSPACE_REAL" ] && [ "${'$'}UCLONE_WORKSPACE_REAL" = "${'$'}UCLONE_WORKSPACE_EXPECTED" ] || {
            echo "ERR_WORKSPACE_NOT_CANONICAL:${'$'}UCLONE_WORKSPACE_EXPECTED:${'$'}UCLONE_WORKSPACE_REAL" >&2
            exit 75
          }
          uclone_guard_workspace_directory "${'$'}UCLONE_WORKSPACE_REAL" || exit 75
          uclone_assert_single_filesystem "${'$'}UCLONE_WORKSPACE_REAL" || exit 75
          UCLONE_CONFIG_DIR="${'$'}UCLONE_WORKSPACE_REAL/config"
          UCLONE_IDENTITY="${'$'}UCLONE_CONFIG_DIR/workspace.identity"
          if [ -e "${'$'}UCLONE_CONFIG_DIR" ] || [ -L "${'$'}UCLONE_CONFIG_DIR" ]; then
            [ -d "${'$'}UCLONE_CONFIG_DIR" ] && [ ! -L "${'$'}UCLONE_CONFIG_DIR" ] || {
              echo "ERR_WORKSPACE_SYMLINK:${'$'}UCLONE_CONFIG_DIR" >&2
              exit 75
            }
            UCLONE_CONFIG_REAL=${'$'}(readlink -f "${'$'}UCLONE_CONFIG_DIR" 2>/dev/null || true)
            [ "${'$'}UCLONE_CONFIG_REAL" = "${'$'}UCLONE_WORKSPACE_REAL/config" ] || {
              echo "ERR_UNSAFE_WORKSPACE_TARGET:${'$'}UCLONE_CONFIG_DIR" >&2
              exit 75
            }
            uclone_guard_workspace_directory "${'$'}UCLONE_CONFIG_DIR" || exit 75
          fi
          if [ "${'$'}UCLONE_WORKSPACE_REAL" != "$DEFAULT_ROOT" ] || [ -e "${'$'}UCLONE_IDENTITY" ] || [ -L "${'$'}UCLONE_IDENTITY" ]; then
            [ -f "${'$'}UCLONE_IDENTITY" ] && [ ! -L "${'$'}UCLONE_IDENTITY" ] || {
              echo "ERR_UNTRUSTED_WORKSPACE:${'$'}UCLONE_WORKSPACE_REAL" >&2
              exit 75
            }
            UCLONE_IDENTITY_OWNER=${'$'}(stat -c '%u:%g' "${'$'}UCLONE_IDENTITY" 2>/dev/null || true)
            UCLONE_IDENTITY_MODE=${'$'}(stat -c '%a' "${'$'}UCLONE_IDENTITY" 2>/dev/null || true)
            [ "${'$'}UCLONE_IDENTITY_OWNER" = "0:0" ] && [ "${'$'}UCLONE_IDENTITY_MODE" = "600" ] &&
              [ "${'$'}(sed -n '1p' "${'$'}UCLONE_IDENTITY" 2>/dev/null)" = "com.uclone.restore.workspace.v1" ] || {
              echo "ERR_UNTRUSTED_WORKSPACE_IDENTITY:${'$'}UCLONE_IDENTITY" >&2
              exit 75
            }
          fi
          for UCLONE_MANAGED_NAME in snapshots rollback clone_rollback switches logs tmp audit config locks transactions; do
            UCLONE_MANAGED_PATH="${'$'}UCLONE_WORKSPACE_REAL/${'$'}UCLONE_MANAGED_NAME"
            if [ -e "${'$'}UCLONE_MANAGED_PATH" ] || [ -L "${'$'}UCLONE_MANAGED_PATH" ]; then
              [ ! -L "${'$'}UCLONE_MANAGED_PATH" ] || {
                echo "ERR_WORKSPACE_SYMLINK:${'$'}UCLONE_MANAGED_PATH" >&2
                exit 75
              }
              UCLONE_MANAGED_REAL=${'$'}(readlink -f "${'$'}UCLONE_MANAGED_PATH" 2>/dev/null || true)
              [ "${'$'}UCLONE_MANAGED_REAL" = "${'$'}UCLONE_WORKSPACE_REAL/${'$'}UCLONE_MANAGED_NAME" ] || {
                echo "ERR_UNSAFE_WORKSPACE_TARGET:${'$'}UCLONE_MANAGED_PATH" >&2
                exit 75
              }
              uclone_guard_workspace_directory "${'$'}UCLONE_MANAGED_PATH" || exit 75
            fi
          done
          ${structuralPathGuard()}
        fi
        ROOT="${'$'}UCLONE_WORKSPACE_REAL"
        ROOT_REAL="${'$'}UCLONE_WORKSPACE_REAL"
    """.trimIndent()

    private fun workspaceOwnershipGuard(): String = """
        uclone_guard_workspace_directory() {
          UCLONE_GUARD_DIRECTORY="${'$'}1"
          [ -d "${'$'}UCLONE_GUARD_DIRECTORY" ] && [ ! -L "${'$'}UCLONE_GUARD_DIRECTORY" ] || {
            echo "ERR_UNTRUSTED_WORKSPACE_DIRECTORY:${'$'}UCLONE_GUARD_DIRECTORY" >&2
            return 1
          }
          UCLONE_GUARD_OWNER=${'$'}(stat -c '%u' "${'$'}UCLONE_GUARD_DIRECTORY" 2>/dev/null || true)
          UCLONE_GUARD_MODE=${'$'}(stat -c '%a' "${'$'}UCLONE_GUARD_DIRECTORY" 2>/dev/null || true)
          [ "${'$'}UCLONE_GUARD_OWNER" = "0" ] || {
            echo "ERR_UNTRUSTED_WORKSPACE_OWNER:${'$'}UCLONE_GUARD_DIRECTORY:${'$'}UCLONE_GUARD_OWNER" >&2
            return 1
          }
          case "${'$'}UCLONE_GUARD_MODE" in
            [0-7][0145][0145]|[0-7][0-7][0145][0145]) ;;
            *)
              echo "ERR_UNTRUSTED_WORKSPACE_MODE:${'$'}UCLONE_GUARD_DIRECTORY:${'$'}UCLONE_GUARD_MODE" >&2
              return 1
              ;;
          esac
        }
    """.trimIndent()

    private fun structuralPathGuard(): String = """
        uclone_guard_structural_path() {
          UCLONE_STRUCTURAL_PATH="${'$'}1"
          if [ ! -e "${'$'}UCLONE_STRUCTURAL_PATH" ] && [ ! -L "${'$'}UCLONE_STRUCTURAL_PATH" ]; then
            return 0
          fi
          [ ! -L "${'$'}UCLONE_STRUCTURAL_PATH" ] || {
            echo "ERR_WORKSPACE_STRUCTURAL_SYMLINK:${'$'}UCLONE_STRUCTURAL_PATH" >&2
            return 1
          }
          UCLONE_STRUCTURAL_REAL=${'$'}(readlink -f "${'$'}UCLONE_STRUCTURAL_PATH" 2>/dev/null || true)
          [ "${'$'}UCLONE_STRUCTURAL_REAL" = "${'$'}UCLONE_STRUCTURAL_PATH" ] || {
            echo "ERR_UNSAFE_WORKSPACE_STRUCTURAL_PATH:${'$'}UCLONE_STRUCTURAL_PATH:${'$'}UCLONE_STRUCTURAL_REAL" >&2
            return 1
          }
        }
        uclone_guard_structural_glob() {
          for UCLONE_STRUCTURAL_CANDIDATE in "${'$'}@"; do
            uclone_guard_structural_path "${'$'}UCLONE_STRUCTURAL_CANDIDATE" || return 1
          done
        }
        UCLONE_GLOB_STAR='*'
        uclone_guard_structural_glob "${'$'}UCLONE_WORKSPACE_REAL"/snapshots/${'$'}UCLONE_GLOB_STAR || exit 75
        uclone_guard_structural_glob "${'$'}UCLONE_WORKSPACE_REAL"/snapshots/${'$'}UCLONE_GLOB_STAR/active "${'$'}UCLONE_WORKSPACE_REAL"/snapshots/${'$'}UCLONE_GLOB_STAR/history || exit 75
        uclone_guard_structural_glob "${'$'}UCLONE_WORKSPACE_REAL"/snapshots/${'$'}UCLONE_GLOB_STAR/history/${'$'}UCLONE_GLOB_STAR || exit 75
        uclone_guard_structural_glob "${'$'}UCLONE_WORKSPACE_REAL"/rollback/${'$'}UCLONE_GLOB_STAR || exit 75
        uclone_guard_structural_glob "${'$'}UCLONE_WORKSPACE_REAL"/rollback/${'$'}UCLONE_GLOB_STAR/${'$'}UCLONE_GLOB_STAR || exit 75
        uclone_guard_structural_glob "${'$'}UCLONE_WORKSPACE_REAL"/clone_rollback/${'$'}UCLONE_GLOB_STAR || exit 75
        uclone_guard_structural_glob "${'$'}UCLONE_WORKSPACE_REAL"/clone_rollback/${'$'}UCLONE_GLOB_STAR/${'$'}UCLONE_GLOB_STAR || exit 75
        uclone_guard_structural_glob "${'$'}UCLONE_WORKSPACE_REAL"/switches/${'$'}UCLONE_GLOB_STAR "${'$'}UCLONE_WORKSPACE_REAL"/switches/${'$'}UCLONE_GLOB_STAR/active || exit 75
        uclone_guard_structural_glob "${'$'}UCLONE_WORKSPACE_REAL"/logs/${'$'}UCLONE_GLOB_STAR "${'$'}UCLONE_WORKSPACE_REAL"/tmp/${'$'}UCLONE_GLOB_STAR "${'$'}UCLONE_WORKSPACE_REAL"/audit/${'$'}UCLONE_GLOB_STAR || exit 75
        uclone_guard_structural_glob "${'$'}UCLONE_WORKSPACE_REAL"/locks/${'$'}UCLONE_GLOB_STAR "${'$'}UCLONE_WORKSPACE_REAL"/locks/${'$'}UCLONE_GLOB_STAR/${'$'}UCLONE_GLOB_STAR || exit 75
        uclone_guard_structural_glob "${'$'}UCLONE_WORKSPACE_REAL"/transactions/${'$'}UCLONE_GLOB_STAR || exit 75
        uclone_guard_structural_glob "${'$'}UCLONE_WORKSPACE_REAL"/transactions/${'$'}UCLONE_GLOB_STAR/transaction.json "${'$'}UCLONE_WORKSPACE_REAL"/transactions/${'$'}UCLONE_GLOB_STAR/gates || exit 75
        uclone_guard_structural_glob "${'$'}UCLONE_WORKSPACE_REAL"/transactions/${'$'}UCLONE_GLOB_STAR/gates/${'$'}UCLONE_GLOB_STAR "${'$'}UCLONE_WORKSPACE_REAL"/transactions/${'$'}UCLONE_GLOB_STAR/gates/${'$'}UCLONE_GLOB_STAR/gate.state || exit 75
    """.trimIndent()
}
