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
        requestId: String,
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
            REQUEST_ID=${shellQuote(requestId)}
            SRC_USER=$sourceUserId
            DST_USER=$targetUserId
            UCLONE_REQUEST_ID="${'$'}REQUEST_ID"
            $permissionFunctions
            install_stage() {
              command -v uclone_active_stage >/dev/null 2>&1 && uclone_active_stage "${'$'}1"
              echo "UCLONE_STAGE_BEGIN:${'$'}1"
            }
            package_listed() {
              /system/bin/cmd package list packages --user "${'$'}1" 2>/dev/null | awk -v expected="package:${'$'}PKG" '
                ${'$'}0 == expected { found=1; exit }
                END { exit(found ? 0 : 1) }
              '
            }
            install_stage PRECHECK
            [ "${'$'}PKG" != "${'$'}APP_PKG" ] || { echo "ERR_SELF_INSTALL" >&2; exit 90; }
            [ "${'$'}SRC_USER" != "${'$'}DST_USER" ] || { echo "ERR_INSTALL_SAME_USER" >&2; exit 90; }
            package_listed "${'$'}SRC_USER" || { echo "ERR_INSTALL_SOURCE_MISSING:user=${'$'}SRC_USER" >&2; exit 91; }
            if package_listed "${'$'}DST_USER"; then
              echo "INSTALL_ALREADY_PRESENT:user=${'$'}DST_USER"
            else
              install_stage INSTALL_PACKAGE
              INSTALL_OUTPUT=${'$'}(/system/bin/cmd package install-existing --user "${'$'}DST_USER" "${'$'}PKG" 2>&1)
              INSTALL_EXIT=${'$'}?
              printf '%s\n' "${'$'}INSTALL_OUTPUT" | sed 's/^/INSTALL_OUTPUT=/'
              if [ "${'$'}INSTALL_EXIT" -ne 0 ]; then
                PM_OUTPUT=${'$'}(/system/bin/pm install-existing --user "${'$'}DST_USER" "${'$'}PKG" 2>&1)
                PM_EXIT=${'$'}?
                printf '%s\n' "${'$'}PM_OUTPUT" | sed 's/^/INSTALL_FALLBACK_OUTPUT=/'
                if [ "${'$'}PM_EXIT" -ne 0 ]; then
                  if package_listed "${'$'}DST_USER"; then
                    echo "WARN_INSTALL_COMMAND_REPORTED_FAILURE:user=${'$'}DST_USER"
                  else
                    echo "ERR_INSTALL_EXISTING_FAILED:user=${'$'}DST_USER" >&2
                    exit 92
                  fi
                fi
              fi
              INSTALL_WAIT=0
              while ! package_listed "${'$'}DST_USER"; do
                [ "${'$'}INSTALL_WAIT" -lt 10 ] || { echo "ERR_INSTALL_VERIFY_TIMEOUT:user=${'$'}DST_USER" >&2; exit 93; }
                sleep 1
                INSTALL_WAIT=${'$'}((INSTALL_WAIT + 1))
                echo "INSTALL_VERIFY_WAIT:${'$'}INSTALL_WAIT"
              done
            fi
            install_stage VERIFY
            TARGET_PACKAGE_LINE=${'$'}(/system/bin/cmd package list packages -U --user "${'$'}DST_USER" 2>/dev/null | awk -v expected="package:${'$'}PKG" '
              ${'$'}1 == expected { print; exit }
            ')
            if [ -z "${'$'}TARGET_PACKAGE_LINE" ]; then
              echo "WARN_INSTALL_UID_UNKNOWN:user=${'$'}DST_USER"
              TARGET_PACKAGE_LINE="package:${'$'}PKG uid:unknown"
            fi
            echo "INSTALL_VERIFIED:user=${'$'}DST_USER ${'$'}TARGET_PACKAGE_LINE"
            $followUp
        """.trimIndent()
    }

    private fun permissionMigration(): String = """
        install_stage RESTORE_PERMISSIONS
        PERM_DIR="${'$'}ROOT/tmp/install_permissions_${'$'}{PKG}_${'$'}{REQUEST_ID}"
        rm -rf "${'$'}PERM_DIR"
        if uclone_capture_permission_state "${'$'}PERM_DIR" "${'$'}SRC_USER"; then
          if uclone_restore_permission_state "${'$'}PERM_DIR" "${'$'}DST_USER" MERGE; then
            echo "INSTALL_PERMISSIONS_DONE targetUser=${'$'}DST_USER mode=MERGE"
          else
            echo "WARN_INSTALL_PERMISSIONS_RESTORE_FAILED:user=${'$'}DST_USER"
            echo "INSTALL_PERMISSIONS_DONE targetUser=${'$'}DST_USER mode=MERGE status=partial"
          fi
        else
          echo "WARN_INSTALL_PERMISSIONS_CAPTURE_FAILED:user=${'$'}SRC_USER"
          echo "INSTALL_PERMISSIONS_DONE targetUser=${'$'}DST_USER mode=MERGE status=partial"
        fi
        rm -rf "${'$'}PERM_DIR"
        install_stage COMMIT
    """.trimIndent()

    private fun syncData(syncScript: String): String = """
        install_stage SOURCE_PREPARE
        echo "INSTALL_SYNC_BEGIN targetUser=${'$'}DST_USER"
        (
          ${syncScript.prependIndent("  ")}
        )
        SYNC_EXIT=${'$'}?
        if [ "${'$'}SYNC_EXIT" -ne 0 ]; then
          echo "WARN_INSTALL_SYNC_FAILED:targetUser=${'$'}DST_USER:exit=${'$'}SYNC_EXIT"
          if [ "${'$'}SYNC_EXIT" -eq 91 ]; then
            echo "INSTALL_PARTIAL_FATAL targetUser=${'$'}DST_USER"
            exit 91
          fi
          echo "INSTALL_PARTIAL_SUCCESS targetUser=${'$'}DST_USER"
          exit 0
        fi
        install_stage COMMIT
        echo "INSTALL_SYNC_DONE targetUser=${'$'}DST_USER"
    """.trimIndent()
}
