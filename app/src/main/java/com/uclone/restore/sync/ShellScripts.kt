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
          ${if (rule.includePermissions) "capture_permission_state \"${'$'}TRY_TMP/permissions\" \"${'$'}TRY_USER\"" else ":"}
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
        {"packageName":"${packageName}","configuredSourceUser":${settings.cloneUserId},"sourceUser":"${'$'}DETECTED_USER","sourceUserState":"${'$'}DETECTED_STATE","targetUser":${settings.mainUserId},"createdAt":"${'$'}TS","includeCe":${rule.includeCe},"includeDe":${rule.includeDe},"includeExternal":${rule.includeExternal},"includeMedia":${rule.includeMedia},"includeObb":${rule.includeObb},"includePermissions":${rule.includePermissions},"includeAppWebView":${rule.includeAppWebView},"excludeCache":${rule.excludeCache},"snapshotSizeKb":"${'$'}SIZE_KB","copiedParts":"${'$'}COPIED_PARTS","copiedItems":"${'$'}COPIED_ITEMS"}
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

    fun restoreForSwitch(packageName: String, settings: UCloneSettings, appPackage: String): String = restoreBody(
        packageName = packageName,
        settings = settings,
        appPackage = appPackage,
        rollbackName = """${'$'}TS""",
        writeSwitchMarker = true,
    )

    fun rollback(
        packageName: String,
        rollbackId: String,
        settings: UCloneSettings,
        appPackage: String,
        clearSwitchMarker: Boolean = false,
    ): String =
        restoreBody(
            packageName = packageName,
            settings = settings,
            appPackage = appPackage,
            rollbackName = """rollback_${'$'}TS""",
            sourcePrefix = "${settings.rootDir}/rollback/$packageName/$rollbackId",
            clearSwitchMarker = clearSwitchMarker,
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

    private fun restoreBody(
        packageName: String,
        settings: UCloneSettings,
        appPackage: String,
        rollbackName: String,
        sourcePrefix: String = "",
        writeSwitchMarker: Boolean = false,
        clearSwitchMarker: Boolean = false,
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
            ROLLBACK_ID="$rollbackName"
            ROLLBACK="${'$'}ROOT/rollback/${'$'}PKG/$rollbackName"
            [ "${'$'}PKG" != "${'$'}APP_PKG" ] || { echo "ERR_SELF_SYNC"; exit 41; }
            [ -d "${'$'}ACTIVE" ] || { echo "ERR_SNAPSHOT_MISSING:${'$'}ACTIVE" >&2; exit 51; }
            [ "${'$'}ACTIVE" != "${'$'}ROLLBACK" ] || { echo "ERR_ROLLBACK_SOURCE_CONFLICT:${'$'}ACTIVE" >&2; exit 61; }
            UID_VALUE=${'$'}(cmd package list packages -U --user "${'$'}DST_USER" | awk -v p="package:${'$'}PKG" '${'$'}1==p { sub("uid:","",${'$'}2); print ${'$'}2; exit }')
            [ -n "${'$'}UID_VALUE" ] || { echo "ERR_TARGET_UID_MISSING" >&2; exit 52; }
            mkdir -p "${'$'}ROLLBACK" "${'$'}ROOT/tmp" || exit 53
            force_stop_package_users() {
              STOPPED_USERS=""
              for U in ${'$'}(pm list users 2>/dev/null | sed -n 's/.*UserInfo{\([0-9][0-9]*\):.*/\1/p'); do
                [ -n "${'$'}U" ] || continue
                am force-stop --user "${'$'}U" "${'$'}PKG" >/dev/null 2>&1 || true
                STOPPED_USERS="${'$'}STOPPED_USERS ${'$'}U"
              done
              case " ${'$'}STOPPED_USERS " in
                *" ${'$'}DST_USER "*) ;;
                *) am force-stop --user "${'$'}DST_USER" "${'$'}PKG" >/dev/null 2>&1 || true ;;
              esac
              echo "FORCE_STOP_USERS:${'$'}STOPPED_USERS"
            }
            force_stop_package_users
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
            backup_permission_state() {
              PERM_DST="${'$'}1"
              mkdir -p "${'$'}PERM_DST" || exit 54
              dumpsys package "${'$'}PKG" 2>/dev/null | awk -v user="User ${settings.mainUserId}:" '
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
              cmd appops get --user "${settings.mainUserId}" "${'$'}PKG" 2>/dev/null | awk '
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
                /data/user/${settings.mainUserId}/${'$'}PKG|/data/user_de/${settings.mainUserId}/${'$'}PKG|/data/media/${settings.mainUserId}/Android/data/${'$'}PKG|/data/media/${settings.mainUserId}/Android/media/${'$'}PKG|/data/media/${settings.mainUserId}/Android/obb/${'$'}PKG) ;;
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
            apply_target_security() {
              SEC_TARGET="${'$'}1"
              SEC_OWNER="${'$'}2"
              SEC_CONTEXT="${'$'}3"
              if [ -n "${'$'}SEC_OWNER" ]; then
                chown -R "${'$'}SEC_OWNER" "${'$'}SEC_TARGET" || exit 59
                OWNER_UID=${'$'}(echo "${'$'}SEC_OWNER" | cut -d: -f1)
                case "${'$'}OWNER_UID" in
                  ''|*[!0-9]*) ;;
                  *)
                    APP_ID=${'$'}((OWNER_UID % 100000))
                    CACHE_GID=${'$'}((20000 + APP_ID))
                    [ -d "${'$'}SEC_TARGET/cache" ] && chown -R "${'$'}OWNER_UID:${'$'}CACHE_GID" "${'$'}SEC_TARGET/cache" >/dev/null 2>&1 || true
                    [ -d "${'$'}SEC_TARGET/code_cache" ] && chown -R "${'$'}OWNER_UID:${'$'}CACHE_GID" "${'$'}SEC_TARGET/code_cache" >/dev/null 2>&1 || true
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
              [ -d "${'$'}SNAP" ] || { echo "SKIP_PART:${'$'}SNAP"; return 0; }
              validate_target_path "${'$'}TARGET"
              SNAP_ITEMS=${'$'}(count_items "${'$'}SNAP")
              [ "${'$'}SNAP_ITEMS" -gt 0 ] || { echo "ERR_EMPTY_SNAPSHOT_PART:${'$'}SNAP" >&2; exit 64; }
              mkdir -p "${'$'}TARGET" || exit 56
              TARGET_CONTEXT=${'$'}(read_target_context "${'$'}TARGET")
              case "${'$'}TARGET_CONTEXT" in u:object_r:*) ;; *) TARGET_CONTEXT="" ;; esac
              if [ -z "${'$'}TARGET_CONTEXT" ]; then
                restorecon -RF "${'$'}TARGET" >/dev/null 2>&1 || restorecon -R "${'$'}TARGET" >/dev/null 2>&1 || true
                TARGET_CONTEXT=${'$'}(read_target_context "${'$'}TARGET")
                case "${'$'}TARGET_CONTEXT" in u:object_r:*) ;; *) TARGET_CONTEXT="" ;; esac
              fi
              if [ -n "${'$'}OWNER_UID" ]; then
                TARGET_OWNER="${'$'}OWNER_UID:${'$'}OWNER_UID"
              else
                TARGET_OWNER=${'$'}(stat -c '%u:%g' "${'$'}TARGET" 2>/dev/null || true)
                case "${'$'}TARGET_OWNER" in *:*) ;; *) TARGET_OWNER="" ;; esac
              fi
              TMP_INDEX=${'$'}((RESTORED_PARTS + 1))
              TMP="${'$'}ROOT/tmp/restore_${'$'}{PKG}_${'$'}{TS}_${'$'}TMP_INDEX"
              rm -rf "${'$'}TMP"
              mkdir -p "${'$'}TMP" || exit 56
              (cd "${'$'}SNAP" && tar -cpf - .) | (cd "${'$'}TMP" && tar -xpf -) || exit 57
              TMP_ITEMS=${'$'}(count_items "${'$'}TMP")
              [ "${'$'}TMP_ITEMS" -gt 0 ] || { echo "ERR_EXTRACT_EMPTY:${'$'}SNAP" >&2; exit 69; }
              clear_target_contents "${'$'}TARGET"
              (cd "${'$'}TMP" && tar -cpf - .) | (cd "${'$'}TARGET" && tar -xpf -) || exit 58
              rm -rf "${'$'}TMP"
              apply_target_security "${'$'}TARGET" "${'$'}TARGET_OWNER" "${'$'}TARGET_CONTEXT"
              TARGET_ITEMS=${'$'}(count_items "${'$'}TARGET")
              [ "${'$'}TARGET_ITEMS" -gt 0 ] || { echo "ERR_RESTORE_EMPTY:${'$'}TARGET" >&2; exit 65; }
              RESTORED_PARTS=${'$'}((RESTORED_PARTS + 1))
              RESTORED_ITEMS=${'$'}((RESTORED_ITEMS + TARGET_ITEMS))
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
                dumpsys package "${'$'}PKG" 2>/dev/null | awk -v user="User ${settings.mainUserId}:" '
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
                cmd appops reset --user "${'$'}DST_USER" "${'$'}PKG" >/dev/null 2>&1 || true
                while read -r OP MODE EXTRA; do
                  [ -n "${'$'}OP" ] || continue
                  [ -z "${'$'}EXTRA" ] || continue
                  case "${'$'}MODE" in allow|ignore|deny|default|foreground|ask) ;; *) continue ;; esac
                  cmd appops set --user "${'$'}DST_USER" "${'$'}PKG" "${'$'}OP" "${'$'}MODE" >/dev/null 2>&1 || echo "WARN_APPOPS_FAILED:${'$'}OP:${'$'}MODE"
                  APPOPS_COUNT=${'$'}((APPOPS_COUNT + 1))
                done < "${'$'}APPOPS_FILE"
                cmd appops write-settings >/dev/null 2>&1 || true
              fi
              echo "RESTORED_PERMISSIONS:grants=${'$'}GRANT_COUNT appops=${'$'}APPOPS_COUNT"
            }
            backup_dir "/data/user/${settings.mainUserId}/${'$'}PKG" "${'$'}ROLLBACK/ce"
            backup_dir "/data/user_de/${settings.mainUserId}/${'$'}PKG" "${'$'}ROLLBACK/de"
            ${if (settings.includePermissions) "backup_permission_state \"${'$'}ROLLBACK/permissions\"" else ":"}
            restore_part "${'$'}ACTIVE/ce" "/data/user/${settings.mainUserId}/${'$'}PKG" "${'$'}UID_VALUE"
            restore_part "${'$'}ACTIVE/de" "/data/user_de/${settings.mainUserId}/${'$'}PKG" "${'$'}UID_VALUE"
            restore_part "${'$'}ACTIVE/external" "/data/media/${settings.mainUserId}/Android/data/${'$'}PKG" ""
            restore_part "${'$'}ACTIVE/media" "/data/media/${settings.mainUserId}/Android/media/${'$'}PKG" ""
            restore_part "${'$'}ACTIVE/obb" "/data/media/${settings.mainUserId}/Android/obb/${'$'}PKG" ""
            ${if (settings.includePermissions) "restore_permission_state \"${'$'}ACTIVE/permissions\"" else ":"}
            [ "${'$'}RESTORED_PARTS" -gt 0 ] || { echo "ERR_NOTHING_RESTORED:${'$'}ACTIVE" >&2; exit 62; }
            ${if (writeSwitchMarker) """
            SWITCH_DIR="${'$'}ROOT/switches/${'$'}PKG"
            mkdir -p "${'$'}SWITCH_DIR" || exit 70
            printf '%s\n' "${'$'}ROLLBACK_ID" > "${'$'}SWITCH_DIR/active" || exit 70
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
            force_stop_package_users
            echo "ROLLBACK=${'$'}ROLLBACK"
            echo "RESTORE_SUMMARY: restoredParts=${'$'}RESTORED_PARTS restoredItems=${'$'}RESTORED_ITEMS backupParts=${'$'}BACKUP_PARTS"
        """.trimIndent()
    }
}
