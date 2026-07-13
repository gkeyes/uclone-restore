package com.uclone.restore.sync

import com.uclone.restore.model.AppRule
import com.uclone.restore.model.CrossUserInstallMode
import com.uclone.restore.model.UCloneSettings
import com.uclone.restore.root.shellQuote

internal object CrossUserInstallScripts {
    fun build(
        packageName: String,
        targetUserId: Int,
        mode: CrossUserInstallMode,
        rule: AppRule,
        settings: UCloneSettings,
        appPackage: String,
    ): String {
        require(targetUserId == settings.mainUserId || targetUserId == settings.cloneUserId)
        val sourceUserId = if (targetUserId == settings.mainUserId) settings.cloneUserId else settings.mainUserId
        val permissionFunctions = if (mode == CrossUserInstallMode.INSTALL_WITH_PERMISSIONS) {
            PermissionStateShell.functions()
        } else {
            ":"
        }
        val followUp = when (mode) {
            CrossUserInstallMode.INSTALL_ONLY -> """
                install_stage COMMIT
                echo "INSTALL_ONLY_DONE targetUser=${'$'}DST_USER"
            """.trimIndent()
            CrossUserInstallMode.INSTALL_WITH_PERMISSIONS -> permissionMigration()
            CrossUserInstallMode.INSTALL_AND_SYNC -> syncData(
                if (targetUserId == settings.cloneUserId) {
                    ShellScripts.pushMainToClone(packageName, rule, settings, appPackage)
                } else {
                    ShellScripts.restoreFromCloneLatest(packageName, rule, settings, appPackage)
                },
            )
        }
        return """
            set -u
            ROOT=${shellQuote(settings.rootDir)}
            PKG=${shellQuote(packageName)}
            APP_PKG=${shellQuote(appPackage)}
            SRC_USER=$sourceUserId
            DST_USER=$targetUserId
            $permissionFunctions
            install_stage() {
              echo "UCLONE_STAGE_BEGIN:${'$'}1"
            }
            package_query() {
              PACKAGE_QUERY_USER="${'$'}1"
              PACKAGE_QUERY_OUTPUT=${'$'}(/system/bin/cmd package list packages --user "${'$'}PACKAGE_QUERY_USER" "${'$'}PKG" 2>&1)
              PACKAGE_QUERY_COMMAND_EXIT=${'$'}?
              if [ "${'$'}PACKAGE_QUERY_COMMAND_EXIT" -ne 0 ]; then
                printf '%s\n' "${'$'}PACKAGE_QUERY_OUTPUT" | sed 's/^/PACKAGE_QUERY_OUTPUT=/'
                return 2
              fi
              printf '%s\n' "${'$'}PACKAGE_QUERY_OUTPUT" | awk -v expected="package:${'$'}PKG" '
                NF { nonempty=1 }
                /^package:/ { package_line=1 }
                ${'$'}0 == expected { found=1 }
                END {
                  if (found) exit 0
                  if (!nonempty || package_line) exit 1
                  exit 2
                }
              '
            }
            install_stage PRECHECK
            [ "${'$'}PKG" != "${'$'}APP_PKG" ] || { echo "ERR_SELF_INSTALL" >&2; exit 90; }
            [ "${'$'}SRC_USER" != "${'$'}DST_USER" ] || { echo "ERR_INSTALL_SAME_USER" >&2; exit 90; }
            package_query "${'$'}SRC_USER"
            SOURCE_PACKAGE_STATUS=${'$'}?
            case "${'$'}SOURCE_PACKAGE_STATUS" in
              0) ;;
              1) echo "ERR_INSTALL_SOURCE_MISSING:user=${'$'}SRC_USER" >&2; exit 91 ;;
              *) echo "ERR_INSTALL_PACKAGE_QUERY:user=${'$'}SRC_USER exit=${'$'}PACKAGE_QUERY_COMMAND_EXIT" >&2; exit 95 ;;
            esac
            package_query "${'$'}DST_USER"
            TARGET_PACKAGE_STATUS=${'$'}?
            case "${'$'}TARGET_PACKAGE_STATUS" in
              0)
                echo "INSTALL_ALREADY_PRESENT:user=${'$'}DST_USER"
                ;;
              1)
                install_stage SOURCE_PREPARE
                INSTALL_OUTPUT=${'$'}(/system/bin/cmd package install-existing --user "${'$'}DST_USER" "${'$'}PKG" 2>&1)
                INSTALL_EXIT=${'$'}?
                printf '%s\n' "${'$'}INSTALL_OUTPUT" | sed 's/^/INSTALL_OUTPUT=/'
                if [ "${'$'}INSTALL_EXIT" -ne 0 ]; then
                  echo "ERR_INSTALL_EXISTING_FAILED:user=${'$'}DST_USER exit=${'$'}INSTALL_EXIT" >&2
                  exit 92
                fi
                package_query "${'$'}DST_USER"
                VERIFY_PACKAGE_STATUS=${'$'}?
                case "${'$'}VERIFY_PACKAGE_STATUS" in
                  0) ;;
                  1) echo "ERR_INSTALL_VERIFY_MISSING:user=${'$'}DST_USER" >&2; exit 93 ;;
                  *) echo "ERR_INSTALL_PACKAGE_QUERY:user=${'$'}DST_USER exit=${'$'}PACKAGE_QUERY_COMMAND_EXIT" >&2; exit 95 ;;
                esac
                ;;
              *) echo "ERR_INSTALL_PACKAGE_QUERY:user=${'$'}DST_USER exit=${'$'}PACKAGE_QUERY_COMMAND_EXIT" >&2; exit 95 ;;
            esac
            install_stage VERIFY
            TARGET_PACKAGE_OUTPUT=${'$'}(/system/bin/cmd package list packages -U --user "${'$'}DST_USER" "${'$'}PKG" 2>&1)
            TARGET_PACKAGE_EXIT=${'$'}?
            if [ "${'$'}TARGET_PACKAGE_EXIT" -ne 0 ]; then
              printf '%s\n' "${'$'}TARGET_PACKAGE_OUTPUT" | sed 's/^/TARGET_PACKAGE_QUERY_OUTPUT=/'
              echo "ERR_INSTALL_PACKAGE_QUERY:user=${'$'}DST_USER exit=${'$'}TARGET_PACKAGE_EXIT" >&2
              exit 95
            fi
            TARGET_PACKAGE_LINE=${'$'}(printf '%s\n' "${'$'}TARGET_PACKAGE_OUTPUT" | awk -v expected="package:${'$'}PKG" '
              ${'$'}1 == expected { print; exit }
            ')
            [ -n "${'$'}TARGET_PACKAGE_LINE" ] || { echo "ERR_INSTALL_UID_MISSING:user=${'$'}DST_USER" >&2; exit 93; }
            echo "INSTALL_VERIFIED:user=${'$'}DST_USER ${'$'}TARGET_PACKAGE_LINE"
            $followUp
        """.trimIndent()
    }

    private fun permissionMigration(): String = """
        install_stage RESTORE_PERMISSIONS
        PERMISSION_TEMP="${'$'}ROOT/tmp/install_permissions_${'$'}{PKG}_${'$'}${'$'}"
        mkdir -p "${'$'}ROOT/tmp" || exit 94
        case "${'$'}PERMISSION_TEMP" in
          "${'$'}ROOT"/tmp/install_permissions_"${'$'}PKG"_*) ;;
          *) echo "ERR_INSTALL_PERMISSION_TEMP_PATH:${'$'}PERMISSION_TEMP" >&2; exit 94 ;;
        esac
        rm -rf "${'$'}PERMISSION_TEMP" || exit 94
        if uclone_capture_permission_state "${'$'}PERMISSION_TEMP" "${'$'}SRC_USER"; then
          uclone_restore_permission_state "${'$'}PERMISSION_TEMP" "${'$'}DST_USER" || true
        else
          echo "WARN_INSTALL_PERMISSIONS_CAPTURE_FAILED:user=${'$'}SRC_USER"
        fi
        rm -rf "${'$'}PERMISSION_TEMP" >/dev/null 2>&1 || echo "WARN_INSTALL_PERMISSION_TEMP_CLEANUP:${'$'}PERMISSION_TEMP"
        install_stage COMMIT
        echo "INSTALL_PERMISSIONS_DONE targetUser=${'$'}DST_USER mode=MERGE"
    """.trimIndent()

    private fun syncData(syncScript: String): String = """
        install_stage SOURCE_PREPARE
        echo "INSTALL_SYNC_BEGIN targetUser=${'$'}DST_USER"
        (
        ${syncScript.prependIndent("  ")}
        )
        INSTALL_SYNC_EXIT=${'$'}?
        if [ "${'$'}INSTALL_SYNC_EXIT" -ne 0 ]; then
          echo "WARN_INSTALL_SYNC_FAILED:targetUser=${'$'}DST_USER:exit=${'$'}INSTALL_SYNC_EXIT"
          if [ "${'$'}INSTALL_SYNC_EXIT" -eq 91 ]; then
            echo "INSTALL_PACKAGE_PRESERVED targetUser=${'$'}DST_USER"
            exit 91
          fi
          echo "INSTALL_PARTIAL_SUCCESS targetUser=${'$'}DST_USER"
          exit 0
        fi
        install_stage COMMIT
        echo "INSTALL_SYNC_DONE targetUser=${'$'}DST_USER"
    """.trimIndent()
}
