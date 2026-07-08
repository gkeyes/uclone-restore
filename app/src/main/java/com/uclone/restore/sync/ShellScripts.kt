package com.uclone.restore.sync

import com.uclone.restore.model.AppRule
import com.uclone.restore.model.UCloneSettings
import com.uclone.restore.root.shellQuote

object ShellScripts {
    fun capture(packageName: String, rule: AppRule, settings: UCloneSettings, appPackage: String): String = """
        set -u
        ROOT=${shellQuote(settings.rootDir)}
        PKG=${shellQuote(packageName)}
        APP_PKG=${shellQuote(appPackage)}
        CONFIG_SRC_USER=${settings.cloneUserId}
        TS=${'$'}(date +%Y%m%d-%H%M%S)
        BASE="${'$'}ROOT/snapshots/${'$'}PKG"
        TMP="${'$'}ROOT/tmp/capture_${'$'}{PKG}_${'$'}TS"
        [ "${'$'}PKG" != "${'$'}APP_PKG" ] || { echo "ERR_SELF_SYNC"; exit 41; }
        mkdir -p "${'$'}ROOT/snapshots" "${'$'}ROOT/rollback" "${'$'}ROOT/logs" "${'$'}ROOT/tmp" "${'$'}ROOT/config" "${'$'}BASE/history" || exit 10
        rm -rf "${'$'}TMP" "${'$'}TMP".try_*
        CANDIDATE_USERS=""
        add_candidate_user() {
          U="${'$'}1"
          [ -n "${'$'}U" ] || return 0
          [ "${'$'}U" != "${settings.mainUserId}" ] || return 0
          case " ${'$'}CANDIDATE_USERS " in
            *" ${'$'}U "*) ;;
            *) CANDIDATE_USERS="${'$'}CANDIDATE_USERS ${'$'}U" ;;
          esac
        }
        add_candidate_user "${'$'}CONFIG_SRC_USER"
        for U in ${'$'}(pm list users 2>/dev/null | sed -n 's/.*UserInfo{\([0-9][0-9]*\):.*/\1/p'); do
          add_candidate_user "${'$'}U"
        done
        add_candidate_user 999
        echo "CANDIDATE_USERS=${'$'}CANDIDATE_USERS"
        [ -n "${'$'}CANDIDATE_USERS" ] || { echo "ERR_NO_CLONE_USER_CANDIDATES" >&2; exit 42; }
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
        try_user() {
          TRY_USER="${'$'}1"
          TRY_TMP="${'$'}TMP.try_${'$'}TRY_USER"
          rm -rf "${'$'}TRY_TMP"
          mkdir -p "${'$'}TRY_TMP" || exit 11
          STATE=${'$'}(am get-started-user-state "${'$'}TRY_USER" 2>/dev/null || true)
          echo "PROBE_USER=${'$'}TRY_USER STATE=${'$'}STATE"
          case "${'$'}STATE" in *RUNNING_UNLOCKED*) ;; *) echo "WARN_USER_NOT_UNLOCKED:${'$'}TRY_USER:${'$'}STATE" ;; esac
          if cmd package list packages --user "${'$'}TRY_USER" 2>/dev/null | grep -qx "package:${'$'}PKG"; then
            echo "PACKAGE_LISTED:${'$'}TRY_USER"
          else
            echo "WARN_PACKAGE_NOT_LISTED:${'$'}TRY_USER"
          fi
          am force-stop --user "${'$'}TRY_USER" "${'$'}PKG" >/dev/null 2>&1 || true
          COPIED_PARTS=0
          COPIED_ITEMS=0
          ${if (rule.includeCe) "copy_first_nonempty \"${'$'}TRY_TMP/ce\" \"/data/user/${'$'}TRY_USER/${'$'}PKG\" \"/data_mirror/data_ce/null/${'$'}TRY_USER/${'$'}PKG\" \"/data_mirror/data_ce/${'$'}TRY_USER/${'$'}PKG\"" else ":"}
          ${if (rule.includeDe) "copy_first_nonempty \"${'$'}TRY_TMP/de\" \"/data/user_de/${'$'}TRY_USER/${'$'}PKG\" \"/data_mirror/data_de/null/${'$'}TRY_USER/${'$'}PKG\" \"/data_mirror/data_de/${'$'}TRY_USER/${'$'}PKG\"" else ":"}
          ${if (rule.includeExternal) "copy_first_nonempty \"${'$'}TRY_TMP/external\" \"/data/media/${'$'}TRY_USER/Android/data/${'$'}PKG\" \"/storage/emulated/${'$'}TRY_USER/Android/data/${'$'}PKG\"" else ":"}
          ${if (rule.includeMedia) "copy_first_nonempty \"${'$'}TRY_TMP/media\" \"/data/media/${'$'}TRY_USER/Android/media/${'$'}PKG\" \"/storage/emulated/${'$'}TRY_USER/Android/media/${'$'}PKG\"" else ":"}
          ${if (rule.includeObb) "copy_first_nonempty \"${'$'}TRY_TMP/obb\" \"/data/media/${'$'}TRY_USER/Android/obb/${'$'}PKG\" \"/storage/emulated/${'$'}TRY_USER/Android/obb/${'$'}PKG\"" else ":"}
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
        cat > "${'$'}TMP/manifest.json" <<EOF
        {"packageName":"${packageName}","configuredSourceUser":${settings.cloneUserId},"sourceUser":"${'$'}DETECTED_USER","sourceUserState":"${'$'}DETECTED_STATE","targetUser":${settings.mainUserId},"createdAt":"${'$'}TS","includeCe":${rule.includeCe},"includeDe":${rule.includeDe},"includeExternal":${rule.includeExternal},"includeMedia":${rule.includeMedia},"includeObb":${rule.includeObb},"includeAppWebView":${rule.includeAppWebView},"excludeCache":${rule.excludeCache},"snapshotSizeKb":"${'$'}SIZE_KB","copiedParts":"${'$'}COPIED_PARTS","copiedItems":"${'$'}COPIED_ITEMS"}
        EOF
        if [ -d "${'$'}BASE/active" ]; then mv "${'$'}BASE/active" "${'$'}BASE/history/${'$'}TS" || exit 15; fi
        mv "${'$'}TMP" "${'$'}BASE/active" || exit 16
        chmod -R 700 "${'$'}BASE" >/dev/null 2>&1 || true
        echo "SNAPSHOT_ACTIVE=${'$'}BASE/active"
        echo "SNAPSHOT_SOURCE_USER=${'$'}DETECTED_USER"
    """.trimIndent()

    fun restore(packageName: String, settings: UCloneSettings, appPackage: String): String = restoreBody(
        packageName = packageName,
        settings = settings,
        appPackage = appPackage,
        rollbackName = """${'$'}TS""",
    )

    fun rollback(packageName: String, rollbackId: String, settings: UCloneSettings, appPackage: String): String =
        restoreBody(
            packageName = packageName,
            settings = settings,
            appPackage = appPackage,
            rollbackName = """rollback_${'$'}TS""",
            sourcePrefix = "${settings.rootDir}/rollback/$packageName/$rollbackId",
        )

    private fun restoreBody(
        packageName: String,
        settings: UCloneSettings,
        appPackage: String,
        rollbackName: String,
        sourcePrefix: String = "",
    ): String {
        val sourceRoot = sourcePrefix.ifBlank { "${settings.rootDir}/snapshots/$packageName/active" }
        return """
            set -u
            ROOT=${shellQuote(settings.rootDir)}
            PKG=${shellQuote(packageName)}
            APP_PKG=${shellQuote(appPackage)}
            DST_USER=${settings.mainUserId}
            TS=${'$'}(date +%Y%m%d-%H%M%S)
            ACTIVE=${shellQuote(sourceRoot)}
            ROLLBACK="${'$'}ROOT/rollback/${'$'}PKG/$rollbackName"
            [ "${'$'}PKG" != "${'$'}APP_PKG" ] || { echo "ERR_SELF_SYNC"; exit 41; }
            [ -d "${'$'}ACTIVE" ] || { echo "ERR_SNAPSHOT_MISSING:${'$'}ACTIVE" >&2; exit 51; }
            [ "${'$'}ACTIVE" != "${'$'}ROLLBACK" ] || { echo "ERR_ROLLBACK_SOURCE_CONFLICT:${'$'}ACTIVE" >&2; exit 61; }
            UID_VALUE=${'$'}(cmd package list packages -U --user "${'$'}DST_USER" | awk -v p="package:${'$'}PKG" '${'$'}1==p { sub("uid:","",${'$'}2); print ${'$'}2; exit }')
            [ -n "${'$'}UID_VALUE" ] || { echo "ERR_TARGET_UID_MISSING" >&2; exit 52; }
            mkdir -p "${'$'}ROLLBACK" "${'$'}ROOT/tmp" || exit 53
            am force-stop --user "${'$'}DST_USER" "${'$'}PKG" >/dev/null 2>&1 || true
            BACKUP_PARTS=0
            RESTORED_PARTS=0
            RESTORED_ITEMS=0
            count_items() {
              find "${'$'}1" -mindepth 1 2>/dev/null | wc -l | tr -d ' '
            }
            backup_dir() {
              SRC="${'$'}1"
              DST="${'$'}2"
              [ -d "${'$'}SRC" ] || return 0
              SRC_ITEMS=${'$'}(count_items "${'$'}SRC")
              [ "${'$'}SRC_ITEMS" -gt 0 ] || { echo "SKIP_BACKUP_EMPTY:${'$'}SRC"; return 0; }
              rm -rf "${'$'}DST"
              mkdir -p "${'$'}DST" || exit 54
              (cd "${'$'}SRC" && tar -cpf - .) | (cd "${'$'}DST" && tar -xpf -) || exit 55
              BACKUP_ITEMS=${'$'}(count_items "${'$'}DST")
              [ "${'$'}BACKUP_ITEMS" -gt 0 ] || { echo "ERR_BACKUP_EMPTY:${'$'}SRC" >&2; exit 63; }
              BACKUP_PARTS=${'$'}((BACKUP_PARTS + 1))
              echo "BACKUP:${'$'}SRC ITEMS=${'$'}BACKUP_ITEMS"
            }
            restore_part() {
              SNAP="${'$'}1"
              TARGET="${'$'}2"
              OWNER="${'$'}3"
              [ -d "${'$'}SNAP" ] || { echo "SKIP_PART:${'$'}SNAP"; return 0; }
              SNAP_ITEMS=${'$'}(count_items "${'$'}SNAP")
              [ "${'$'}SNAP_ITEMS" -gt 0 ] || { echo "ERR_EMPTY_SNAPSHOT_PART:${'$'}SNAP" >&2; exit 64; }
              TMP="${'$'}TARGET.tmp.uclone.${'$'}TS"
              rm -rf "${'$'}TMP"
              mkdir -p "${'$'}TMP" || exit 56
              (cd "${'$'}SNAP" && tar -cpf - .) | (cd "${'$'}TMP" && tar -xpf -) || exit 57
              rm -rf "${'$'}TARGET"
              mv "${'$'}TMP" "${'$'}TARGET" || exit 58
              if [ -n "${'$'}OWNER" ]; then chown -R "${'$'}OWNER:${'$'}OWNER" "${'$'}TARGET" || exit 59; fi
              restorecon -RF "${'$'}TARGET" >/dev/null 2>&1 || restorecon -R "${'$'}TARGET" >/dev/null 2>&1 || exit 60
              TARGET_ITEMS=${'$'}(count_items "${'$'}TARGET")
              [ "${'$'}TARGET_ITEMS" -gt 0 ] || { echo "ERR_RESTORE_EMPTY:${'$'}TARGET" >&2; exit 65; }
              RESTORED_PARTS=${'$'}((RESTORED_PARTS + 1))
              RESTORED_ITEMS=${'$'}((RESTORED_ITEMS + TARGET_ITEMS))
              echo "RESTORED:${'$'}TARGET ITEMS=${'$'}TARGET_ITEMS"
            }
            backup_dir "/data/user/${settings.mainUserId}/${'$'}PKG" "${'$'}ROLLBACK/ce"
            backup_dir "/data/user_de/${settings.mainUserId}/${'$'}PKG" "${'$'}ROLLBACK/de"
            restore_part "${'$'}ACTIVE/ce" "/data/user/${settings.mainUserId}/${'$'}PKG" "${'$'}UID_VALUE"
            restore_part "${'$'}ACTIVE/de" "/data/user_de/${settings.mainUserId}/${'$'}PKG" "${'$'}UID_VALUE"
            restore_part "${'$'}ACTIVE/external" "/data/media/${settings.mainUserId}/Android/data/${'$'}PKG" ""
            restore_part "${'$'}ACTIVE/media" "/data/media/${settings.mainUserId}/Android/media/${'$'}PKG" ""
            restore_part "${'$'}ACTIVE/obb" "/data/media/${settings.mainUserId}/Android/obb/${'$'}PKG" ""
            [ "${'$'}RESTORED_PARTS" -gt 0 ] || { echo "ERR_NOTHING_RESTORED:${'$'}ACTIVE" >&2; exit 62; }
            sync
            am force-stop --user "${'$'}DST_USER" "${'$'}PKG" >/dev/null 2>&1 || true
            echo "ROLLBACK=${'$'}ROLLBACK"
            echo "RESTORE_SUMMARY: restoredParts=${'$'}RESTORED_PARTS restoredItems=${'$'}RESTORED_ITEMS backupParts=${'$'}BACKUP_PARTS"
        """.trimIndent()
    }
}
