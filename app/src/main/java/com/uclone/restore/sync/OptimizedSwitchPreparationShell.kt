package com.uclone.restore.sync

import com.uclone.restore.model.AppRule
import com.uclone.restore.model.UCloneSettings
import com.uclone.restore.root.shellQuote

internal object OptimizedSwitchPreparationShell {
    fun safeReturn(rule: AppRule, settings: UCloneSettings, rollbackId: String): String = """
        UCLONE_SWITCH_MODE=SAFE
        UCLONE_EXPECTED_MAIN_RETURN=${shellQuote(rollbackId)}
        SAFE_CHECKPOINT_CREATED=0
        SAFE_CHECKPOINT_COMPLETE=0
        SAFE_PREPARATION_COMPLETE=0
        safe_prepare_on_exit() {
          SAFE_PREP_EXIT=${'$'}?
          trap - EXIT
          if [ "${'$'}SAFE_CHECKPOINT_CREATED" = "1" ] && [ "${'$'}SAFE_CHECKPOINT_COMPLETE" != "1" ]; then
            case "${'$'}ROLLBACK" in
              "${'$'}ROOT"/rollback/"${'$'}PKG"/switch_checkpoint_*) rm -rf "${'$'}ROLLBACK" 2>/dev/null || true ;;
            esac
          fi
          if [ "${'$'}SAFE_PREP_EXIT" -ne 0 ] &&
             [ "${'$'}SAFE_PREPARATION_COMPLETE" != "1" ] &&
             [ "${'$'}{CLONE_TARGET_MUTATED:-0}" = "1" ]; then
            echo "RECOVERY_REQUIRED:mode=SAFE target=user10 reason=partial_sync checkpoint=${'$'}ROLLBACK" >&2
            SAFE_PREP_EXIT=91
          fi
          if command -v cleanup_on_exit >/dev/null 2>&1; then
            cleanup_on_exit
          elif command -v cleanup_switch_temp >/dev/null 2>&1; then
            cleanup_switch_temp
          fi
          exit "${'$'}SAFE_PREP_EXIT"
        }
        create_safe_checkpoint_dir() {
          if [ -e "${'$'}ROLLBACK" ] || [ -L "${'$'}ROLLBACK" ]; then
            echo "ERR_ROLLBACK_ID_COLLISION:${'$'}ROLLBACK" >&2
            return 54
          fi
          mkdir -p "${'$'}ROLLBACK" || return 54
          SAFE_CHECKPOINT_CREATED=1
          mkdir "${'$'}ROLLBACK/.state" || return 54
        }
        ${precheck(rule, settings)}
        trap safe_prepare_on_exit EXIT
        ${commonFunctions(rule)}
        UCLONE_ESTIMATED_KB=0
        ${if (rule.includeCe) "uclone_add_dir_kb \"/data/user/${'$'}MAIN_USER/${'$'}PKG\"" else ":"}
        ${if (rule.includeDe) "uclone_add_dir_kb \"/data/user_de/${'$'}MAIN_USER/${'$'}PKG\"" else ":"}
        ${if (rule.includeExternal) "uclone_add_dir_kb \"/data/media/${'$'}MAIN_USER/Android/data/${'$'}PKG\"" else ":"}
        ${if (rule.includeMedia) "uclone_add_dir_kb \"/data/media/${'$'}MAIN_USER/Android/media/${'$'}PKG\"" else ":"}
        ${if (rule.includeObb) "uclone_add_dir_kb \"/data/media/${'$'}MAIN_USER/Android/obb/${'$'}PKG\"" else ":"}
        uclone_require_space "${'$'}UCLONE_ESTIMATED_KB" "safe_switch_checkpoint"
        force_stop_switch_users || exit 76
        create_safe_checkpoint_dir || exit ${'$'}?
        uclone_copy_pass_begin clone_checkpoint
        ${if (rule.includeCe) "backup_switch_part \"/data/user/${'$'}MAIN_USER/${'$'}PKG\" \"${'$'}ROLLBACK/ce\" ce || exit 55" else "mark_switch_part_unselected ce || exit 55"}
        ${if (rule.includeDe) "backup_switch_part \"/data/user_de/${'$'}MAIN_USER/${'$'}PKG\" \"${'$'}ROLLBACK/de\" de || exit 55" else "mark_switch_part_unselected de || exit 55"}
        ${if (rule.includeExternal) "backup_switch_part \"/data/media/${'$'}MAIN_USER/Android/data/${'$'}PKG\" \"${'$'}ROLLBACK/external\" external || exit 55" else "mark_switch_part_unselected external || exit 55"}
        ${if (rule.includeMedia) "backup_switch_part \"/data/media/${'$'}MAIN_USER/Android/media/${'$'}PKG\" \"${'$'}ROLLBACK/media\" media || exit 55" else "mark_switch_part_unselected media || exit 55"}
        ${if (rule.includeObb) "backup_switch_part \"/data/media/${'$'}MAIN_USER/Android/obb/${'$'}PKG\" \"${'$'}ROLLBACK/obb\" obb || exit 55" else "mark_switch_part_unselected obb || exit 55"}
        ${if (rule.includePermissions) "uclone_capture_permission_state \"${'$'}ROLLBACK/permissions\" \"${'$'}MAIN_USER\" || echo \"WARN_PERMISSION_CAPTURE_SKIPPED:${'$'}MAIN_USER\"" else ":"}
        CHECKPOINT_SIZE_KB=${'$'}(du -sk "${'$'}ROLLBACK" 2>/dev/null | awk '{print ${'$'}1}')
        printf '%s\n' "{\"packageName\":\"${'$'}PKG\",\"rollbackId\":\"${'$'}ROLLBACK_ID\",\"createdAt\":\"${'$'}TS\",\"reason\":\"安全返回主数据前保存当前分数据\",\"targetUser\":\"${'$'}MAIN_USER\",\"stateKind\":\"CLONE\",\"backupKind\":\"transaction_undo\",\"sizeKb\":\"${'$'}CHECKPOINT_SIZE_KB\"}" > "${'$'}ROLLBACK/manifest.json" || exit 53
        chmod 600 "${'$'}ROLLBACK/manifest.json" "${'$'}ROLLBACK/.state/"* >/dev/null 2>&1 || true
        sync
        ROLLBACK_FINALIZED=1
        uclone_copy_pass_end clone_checkpoint
        uclone_valid_state_backup "${'$'}ROLLBACK" CLONE || {
          echo "ERR_SWITCH_CHECKPOINT_INVALID:${'$'}ROLLBACK" >&2
          exit 53
        }
        SAFE_CHECKPOINT_COMPLETE=1
        CLONE_TARGET_MUTATED=0
        uclone_copy_pass_begin checkpoint_to_user10
        ${workspaceRestoreCalls(rule)}
        uclone_copy_pass_end checkpoint_to_user10
        [ "${'$'}SWITCH_PUSHED_PARTS" -gt 0 ] || {
          echo "ERR_NOTHING_PUSHED:${'$'}ROLLBACK" >&2
          exit 62
        }
        ${if (rule.includePermissions) "uclone_restore_permission_state \"${'$'}ROLLBACK/permissions\" \"${'$'}CLONE_USER\"" else ":"}
        force_stop_switch_users || exit 76
        sync
        SAFE_PREPARATION_COMPLETE=1
        echo "SAFE_CLONE_CHECKPOINT_SYNCED:path=${'$'}ROLLBACK parts=${'$'}SWITCH_PUSHED_PARTS items=${'$'}SWITCH_PUSHED_ITEMS"
    """.trimIndent()

    fun dangerousReturn(rule: AppRule, settings: UCloneSettings, rollbackId: String): String = """
        UCLONE_SWITCH_MODE=DANGEROUS_FAST
        UCLONE_EXPECTED_MAIN_RETURN=${shellQuote(rollbackId)}
        DANGER_META="${'$'}ROOT/tmp/dangerous_switch_${'$'}{PKG}_${'$'}RUN_ID"
        DANGER_META_CREATED=0
        DANGEROUS_PREPARATION_COMPLETE=0
        cleanup_switch_temp() {
          if [ "${'$'}{DANGER_META_CREATED:-0}" = "1" ]; then
            case "${'$'}{DANGER_META:-}" in
              "${'$'}ROOT"/tmp/dangerous_switch_"${'$'}PKG"_"${'$'}RUN_ID") rm -rf "${'$'}DANGER_META" 2>/dev/null || true ;;
            esac
          fi
        }
        dangerous_prepare_on_exit() {
          DANGEROUS_PREP_EXIT=${'$'}?
          trap - EXIT
          if [ "${'$'}DANGEROUS_PREP_EXIT" -ne 0 ] &&
             [ "${'$'}DANGEROUS_PREPARATION_COMPLETE" != "1" ] &&
             [ "${'$'}{CLONE_TARGET_MUTATED:-0}" = "1" ]; then
            echo "RECOVERY_REQUIRED:mode=DANGEROUS_FAST target=user10 reason=partial_sync" >&2
            DANGEROUS_PREP_EXIT=91
          fi
          if command -v cleanup_on_exit >/dev/null 2>&1; then
            cleanup_on_exit
          elif command -v cleanup_switch_temp >/dev/null 2>&1; then
            cleanup_switch_temp
          fi
          exit "${'$'}DANGEROUS_PREP_EXIT"
        }
        ${precheck(rule, settings)}
        trap dangerous_prepare_on_exit EXIT
        if [ -e "${'$'}DANGER_META" ] || [ -L "${'$'}DANGER_META" ]; then
          echo "ERR_DANGEROUS_TEMP_COLLISION:${'$'}DANGER_META" >&2
          exit 53
        fi
        mkdir -p "${'$'}DANGER_META" || exit 53
        DANGER_META_CREATED=1
        ${commonFunctions(rule)}
        force_stop_switch_users || exit 76
        ${if (rule.includePermissions) "uclone_capture_permission_state \"${'$'}DANGER_META/permissions\" \"${'$'}MAIN_USER\" || echo \"WARN_PERMISSION_CAPTURE_SKIPPED:${'$'}MAIN_USER\"" else ":"}
        CLONE_TARGET_MUTATED=0
        uclone_copy_pass_begin live_user0_to_user10
        ${liveRestoreCalls(rule)}
        uclone_copy_pass_end live_user0_to_user10
        [ "${'$'}SWITCH_PUSHED_PARTS" -gt 0 ] || {
          echo "ERR_NOTHING_PUSHED:user=${'$'}MAIN_USER" >&2
          exit 62
        }
        ${if (rule.includePermissions) "uclone_restore_permission_state \"${'$'}DANGER_META/permissions\" \"${'$'}CLONE_USER\"" else ":"}
        force_stop_switch_users || exit 76
        sync
        DANGEROUS_PREPARATION_COMPLETE=1
        echo "DANGEROUS_CLONE_SYNCED:parts=${'$'}SWITCH_PUSHED_PARTS items=${'$'}SWITCH_PUSHED_ITEMS rollback=none"
    """.trimIndent()

    private fun precheck(rule: AppRule, settings: UCloneSettings): String = """
        MAIN_USER=${settings.mainUserId}
        CLONE_USER=${settings.cloneUserId}
        FORCE_CURRENT_STATE=${'$'}(uclone_current_main_state)
        [ "${'$'}FORCE_CURRENT_STATE" = "CLONE" ] || {
          echo "ERR_SWITCH_RETURN_STATE:expected=CLONE actual=${'$'}FORCE_CURRENT_STATE" >&2
          exit 88
        }
        FORCE_ACTUAL_MAIN_RETURN=${'$'}(uclone_read_main_return_id 2>/dev/null || true)
        [ "${'$'}FORCE_ACTUAL_MAIN_RETURN" = "${'$'}UCLONE_EXPECTED_MAIN_RETURN" ] || {
          echo "ERR_SWITCH_RETURN_POINT:expected=${'$'}UCLONE_EXPECTED_MAIN_RETURN actual=${'$'}FORCE_ACTUAL_MAIN_RETURN" >&2
          exit 88
        }
        uclone_valid_state_backup "${'$'}ROOT/rollback/${'$'}PKG/${'$'}UCLONE_EXPECTED_MAIN_RETURN" MAIN || {
          echo "ERR_MAIN_RETURN_INVALID:${'$'}UCLONE_EXPECTED_MAIN_RETURN" >&2
          exit 53
        }
        MAIN_RETURN_DIR="${'$'}ROOT/rollback/${'$'}PKG/${'$'}UCLONE_EXPECTED_MAIN_RETURN"
        require_main_return_part() {
          RMRP_NAME="${'$'}1"
          RMRP_STATE=${'$'}(sed -n '1p' "${'$'}MAIN_RETURN_DIR/.state/${'$'}RMRP_NAME" 2>/dev/null | tr -d '\r')
          case "${'$'}RMRP_STATE" in
            data|empty|absent) ;;
            *) echo "ERR_MAIN_RETURN_PART_UNAVAILABLE:${'$'}RMRP_NAME:${'$'}RMRP_STATE" >&2; exit 53 ;;
          esac
        }
        ${if (rule.includeCe) "require_main_return_part ce" else ":"}
        ${if (rule.includeDe) "require_main_return_part de" else ":"}
        ${if (rule.includeExternal) "require_main_return_part external" else ":"}
        ${if (rule.includeMedia) "require_main_return_part media" else ":"}
        ${if (rule.includeObb) "require_main_return_part obb" else ":"}
        ${ShellScripts.ensureCloneCeReadyScript(settings, settings.includeCe, settings.autoUnlockClone, settings.stopCloneAfterTask)}
        cmd package list packages --user "${'$'}MAIN_USER" 2>/dev/null | grep -qx "package:${'$'}PKG" || {
          echo "ERR_PACKAGE_NOT_LISTED_SOURCE:${'$'}MAIN_USER" >&2
          exit 42
        }
        cmd package list packages --user "${'$'}CLONE_USER" 2>/dev/null | grep -qx "package:${'$'}PKG" || {
          echo "ERR_PACKAGE_NOT_LISTED_TARGET:${'$'}CLONE_USER" >&2
          exit 43
        }
        MAIN_UID=${'$'}(cmd package list packages -U --user "${'$'}MAIN_USER" "${'$'}PKG" 2>/dev/null | awk -v p="package:${'$'}PKG" '${'$'}1==p { sub("uid:","",${'$'}2); print ${'$'}2; exit }')
        CLONE_UID=${'$'}(cmd package list packages -U --user "${'$'}CLONE_USER" "${'$'}PKG" 2>/dev/null | awk -v p="package:${'$'}PKG" '${'$'}1==p { sub("uid:","",${'$'}2); print ${'$'}2; exit }')
        [ -n "${'$'}MAIN_UID" ] || { echo "ERR_SOURCE_UID_MISSING:${'$'}MAIN_USER" >&2; exit 52; }
        [ -n "${'$'}CLONE_UID" ] || { echo "ERR_TARGET_UID_MISSING:${'$'}CLONE_USER" >&2; exit 52; }
        echo "SWITCH_RETURN_PRECHECK:mode=${'$'}UCLONE_SWITCH_MODE mainUser=${'$'}MAIN_USER cloneUser=${'$'}CLONE_USER mainReturn=${'$'}FORCE_ACTUAL_MAIN_RETURN"
    """.trimIndent()

    private fun commonFunctions(rule: AppRule): String = """
        SWITCH_PUSHED_PARTS=0
        SWITCH_PUSHED_ITEMS=0
        count_items() {
          [ -d "${'$'}1" ] || { echo 0; return 0; }
          (cd "${'$'}1" && find . -mindepth 1 2>/dev/null | wc -l | tr -d ' ')
        }
        force_stop_switch_users() {
          am force-stop --user "${'$'}MAIN_USER" "${'$'}PKG" >/dev/null 2>&1 || return 1
          am force-stop --user "${'$'}CLONE_USER" "${'$'}PKG" >/dev/null 2>&1 || return 1
          echo "FORCE_STOP_USERS:${'$'}MAIN_USER ${'$'}CLONE_USER"
        }
        backup_switch_part() {
          BSP_SRC="${'$'}1"
          BSP_DST="${'$'}2"
          BSP_NAME="${'$'}3"
          if [ ! -d "${'$'}BSP_SRC" ]; then
            printf '%s\n' absent > "${'$'}ROLLBACK/.state/${'$'}BSP_NAME" || return 1
            return 0
          fi
          BSP_ITEMS=${'$'}(count_items "${'$'}BSP_SRC")
          if [ "${'$'}BSP_ITEMS" -le 0 ]; then
            printf '%s\n' empty > "${'$'}ROLLBACK/.state/${'$'}BSP_NAME" || return 1
            return 0
          fi
          mkdir -p "${'$'}BSP_DST" || return 1
          (cd "${'$'}BSP_SRC" && tar -cpf - .) | (cd "${'$'}BSP_DST" && tar -xopf -) || return 1
          BSP_WRITTEN=${'$'}(count_items "${'$'}BSP_DST")
          [ "${'$'}BSP_WRITTEN" -gt 0 ] || return 1
          printf '%s\n' data > "${'$'}ROLLBACK/.state/${'$'}BSP_NAME" || return 1
          BSP_SIZE_KB=${'$'}(du -sk "${'$'}BSP_DST" 2>/dev/null | awk '{print ${'$'}1}')
          UCLONE_SCANNED_FILES=${'$'}((UCLONE_SCANNED_FILES + BSP_ITEMS))
          UCLONE_COPIED_FILES=${'$'}((UCLONE_COPIED_FILES + BSP_WRITTEN))
          uclone_add_written_kb "${'$'}BSP_SIZE_KB"
          uclone_record_temp_path "${'$'}BSP_DST"
          echo "SWITCH_CHECKPOINT_PART:${'$'}BSP_NAME items=${'$'}BSP_WRITTEN"
        }
        mark_switch_part_unselected() {
          MSU_NAME="${'$'}1"
          printf '%s\n' unselected > "${'$'}ROLLBACK/.state/${'$'}MSU_NAME" || return 1
          echo "SWITCH_CHECKPOINT_PART:${'$'}MSU_NAME state=unselected"
        }
        validate_clone_target() {
          case "${'$'}1" in
            /data/user/${'$'}CLONE_USER/${'$'}PKG|/data/user_de/${'$'}CLONE_USER/${'$'}PKG|/data/media/${'$'}CLONE_USER/Android/data/${'$'}PKG|/data/media/${'$'}CLONE_USER/Android/media/${'$'}PKG|/data/media/${'$'}CLONE_USER/Android/obb/${'$'}PKG) return 0 ;;
            *) echo "ERR_UNSAFE_TARGET:${'$'}1" >&2; return 1 ;;
          esac
        }
        read_clone_context() {
          ls -Zd "${'$'}1" 2>/dev/null | awk '{print ${'$'}1; exit}'
        }
        apply_clone_security() {
          ACS_TARGET="${'$'}1"
          ACS_KIND="${'$'}2"
          ACS_CONTEXT="${'$'}3"
          case "${'$'}ACS_KIND" in
            app) ACS_OWNER="${'$'}CLONE_UID:${'$'}CLONE_UID" ;;
            media) ACS_OWNER="${'$'}CLONE_UID:1078" ;;
            *) return 1 ;;
          esac
          chown -hR "${'$'}ACS_OWNER" "${'$'}ACS_TARGET" || return 1
          if [ "${'$'}ACS_KIND" = app ]; then
            ACS_APP_ID=${'$'}((CLONE_UID % 100000))
            ACS_CACHE_GID=${'$'}((20000 + ACS_APP_ID))
            [ ! -d "${'$'}ACS_TARGET/cache" ] || chown -hR "${'$'}CLONE_UID:${'$'}ACS_CACHE_GID" "${'$'}ACS_TARGET/cache" >/dev/null 2>&1 || true
            [ ! -d "${'$'}ACS_TARGET/code_cache" ] || chown -hR "${'$'}CLONE_UID:${'$'}ACS_CACHE_GID" "${'$'}ACS_TARGET/code_cache" >/dev/null 2>&1 || true
          fi
          if [ -n "${'$'}ACS_CONTEXT" ]; then
            chcon -R -h "${'$'}ACS_CONTEXT" "${'$'}ACS_TARGET" >/dev/null 2>&1 || restorecon -RF "${'$'}ACS_TARGET" >/dev/null 2>&1 || restorecon -R "${'$'}ACS_TARGET" >/dev/null 2>&1 || return 1
          else
            restorecon -RF "${'$'}ACS_TARGET" >/dev/null 2>&1 || restorecon -R "${'$'}ACS_TARGET" >/dev/null 2>&1 || return 1
          fi
        }
        stream_to_clone() {
          STC_SRC="${'$'}1"
          STC_TARGET="${'$'}2"
          STC_KIND="${'$'}3"
          STC_NAME="${'$'}4"
          [ -d "${'$'}STC_SRC" ] || return 2
          STC_ITEMS=${'$'}(count_items "${'$'}STC_SRC")
          [ "${'$'}STC_ITEMS" -gt 0 ] || return 2
          validate_clone_target "${'$'}STC_TARGET" || return 1
          mkdir -p "${'$'}STC_TARGET" || return 1
          STC_CONTEXT=${'$'}(read_clone_context "${'$'}STC_TARGET")
          case "${'$'}STC_CONTEXT" in u:object_r:*) ;; *) STC_CONTEXT="" ;; esac
          CLONE_TARGET_MUTATED=1
          find "${'$'}STC_TARGET" -mindepth 1 -maxdepth 1 -exec rm -rf {} \; || return 1
          (cd "${'$'}STC_SRC" && tar -cpf - .) | (cd "${'$'}STC_TARGET" && tar -xpf -) || return 1
          ${if (rule.excludeCache) """
          case "${'$'}STC_NAME" in ce|de) rm -rf "${'$'}STC_TARGET/cache" "${'$'}STC_TARGET/code_cache" 2>/dev/null || true ;; esac
          """.trimIndent() else ":"}
          apply_clone_security "${'$'}STC_TARGET" "${'$'}STC_KIND" "${'$'}STC_CONTEXT" || return 1
          STC_WRITTEN=${'$'}(count_items "${'$'}STC_TARGET")
          [ "${'$'}STC_WRITTEN" -gt 0 ] || return 1
          STC_SIZE_KB=${'$'}(du -sk "${'$'}STC_TARGET" 2>/dev/null | awk '{print ${'$'}1}')
          SWITCH_PUSHED_PARTS=${'$'}((SWITCH_PUSHED_PARTS + 1))
          SWITCH_PUSHED_ITEMS=${'$'}((SWITCH_PUSHED_ITEMS + STC_WRITTEN))
          UCLONE_SCANNED_FILES=${'$'}((UCLONE_SCANNED_FILES + STC_ITEMS))
          UCLONE_COPIED_FILES=${'$'}((UCLONE_COPIED_FILES + STC_WRITTEN))
          uclone_add_written_kb "${'$'}STC_SIZE_KB"
          echo "SWITCH_PUSHED_PART:${'$'}STC_NAME items=${'$'}STC_WRITTEN target=${'$'}STC_TARGET"
        }
        apply_clone_part_state() {
          ACPS_STATE="${'$'}1"
          ACPS_TARGET="${'$'}2"
          ACPS_KIND="${'$'}3"
          ACPS_NAME="${'$'}4"
          validate_clone_target "${'$'}ACPS_TARGET" || return 1
          case "${'$'}ACPS_STATE" in
            absent)
              CLONE_TARGET_MUTATED=1
              rm -rf "${'$'}ACPS_TARGET" || return 1
              SWITCH_PUSHED_PARTS=${'$'}((SWITCH_PUSHED_PARTS + 1))
              echo "SWITCH_PUSHED_STATE:${'$'}ACPS_NAME state=absent target=${'$'}ACPS_TARGET"
              ;;
            empty)
              mkdir -p "${'$'}ACPS_TARGET" || return 1
              ACPS_CONTEXT=${'$'}(read_clone_context "${'$'}ACPS_TARGET")
              case "${'$'}ACPS_CONTEXT" in u:object_r:*) ;; *) ACPS_CONTEXT="" ;; esac
              CLONE_TARGET_MUTATED=1
              find "${'$'}ACPS_TARGET" -mindepth 1 -maxdepth 1 -exec rm -rf {} \; || return 1
              apply_clone_security "${'$'}ACPS_TARGET" "${'$'}ACPS_KIND" "${'$'}ACPS_CONTEXT" || return 1
              SWITCH_PUSHED_PARTS=${'$'}((SWITCH_PUSHED_PARTS + 1))
              echo "SWITCH_PUSHED_STATE:${'$'}ACPS_NAME state=empty target=${'$'}ACPS_TARGET"
              ;;
            *) return 1 ;;
          esac
        }
        sync_workspace_part_to_clone() {
          SWPC_NAME="${'$'}1"
          SWPC_TARGET="${'$'}2"
          SWPC_KIND="${'$'}3"
          SWPC_STATE_FILE="${'$'}ROLLBACK/.state/${'$'}SWPC_NAME"
          [ -f "${'$'}SWPC_STATE_FILE" ] && [ ! -L "${'$'}SWPC_STATE_FILE" ] || return 1
          SWPC_STATE=${'$'}(sed -n '1p' "${'$'}SWPC_STATE_FILE" | tr -d '\r')
          case "${'$'}SWPC_STATE" in
            data) stream_to_clone "${'$'}ROLLBACK/${'$'}SWPC_NAME" "${'$'}SWPC_TARGET" "${'$'}SWPC_KIND" "${'$'}SWPC_NAME" ;;
            empty|absent) apply_clone_part_state "${'$'}SWPC_STATE" "${'$'}SWPC_TARGET" "${'$'}SWPC_KIND" "${'$'}SWPC_NAME" ;;
            *) echo "ERR_SWITCH_SOURCE_STATE:${'$'}SWPC_NAME:${'$'}SWPC_STATE" >&2; return 1 ;;
          esac
        }
        sync_live_part_to_clone() {
          SLPC_TARGET="${'$'}1"
          SLPC_KIND="${'$'}2"
          SLPC_NAME="${'$'}3"
          shift 3
          for SLPC_SRC in "${'$'}@"; do
            [ -d "${'$'}SLPC_SRC" ] || continue
            SLPC_ITEMS=${'$'}(count_items "${'$'}SLPC_SRC")
            if [ "${'$'}SLPC_ITEMS" -gt 0 ]; then
              stream_to_clone "${'$'}SLPC_SRC" "${'$'}SLPC_TARGET" "${'$'}SLPC_KIND" "${'$'}SLPC_NAME"
              return ${'$'}?
            fi
            apply_clone_part_state empty "${'$'}SLPC_TARGET" "${'$'}SLPC_KIND" "${'$'}SLPC_NAME"
            return ${'$'}?
          done
          apply_clone_part_state absent "${'$'}SLPC_TARGET" "${'$'}SLPC_KIND" "${'$'}SLPC_NAME"
        }
        handle_optional_push() {
          HOP_EXIT="${'$'}1"
          HOP_NAME="${'$'}2"
          case "${'$'}HOP_EXIT" in
            0) return 0 ;;
            2) echo "SKIP_MISSING_SELECTED_PART:${'$'}HOP_NAME"; return 0 ;;
            *) echo "ERR_SWITCH_SYNC_PART:${'$'}HOP_NAME" >&2; exit 91 ;;
          esac
        }
    """.trimIndent()

    private fun workspaceRestoreCalls(rule: AppRule): String = buildList {
        if (rule.includeCe) add(optionalWorkspaceCall("ce", "/data/user/${'$'}CLONE_USER/${'$'}PKG", "app"))
        if (rule.includeDe) add(optionalWorkspaceCall("de", "/data/user_de/${'$'}CLONE_USER/${'$'}PKG", "app"))
        if (rule.includeExternal) add(optionalWorkspaceCall("external", "/data/media/${'$'}CLONE_USER/Android/data/${'$'}PKG", "media"))
        if (rule.includeMedia) add(optionalWorkspaceCall("media", "/data/media/${'$'}CLONE_USER/Android/media/${'$'}PKG", "media"))
        if (rule.includeObb) add(optionalWorkspaceCall("obb", "/data/media/${'$'}CLONE_USER/Android/obb/${'$'}PKG", "media"))
    }.joinToString("\n").ifBlank { "echo \"ERR_NO_DATA_PART_SELECTED\" >&2; exit 62" }

    private fun liveRestoreCalls(rule: AppRule): String = buildList {
        if (rule.includeCe) add(optionalLiveCall(
            "ce",
            "/data/user/${'$'}CLONE_USER/${'$'}PKG",
            "app",
            "\"/data/user/${'$'}MAIN_USER/${'$'}PKG\" \"/data_mirror/data_ce/null/${'$'}MAIN_USER/${'$'}PKG\" \"/data_mirror/data_ce/${'$'}MAIN_USER/${'$'}PKG\"",
        ))
        if (rule.includeDe) add(optionalLiveCall(
            "de",
            "/data/user_de/${'$'}CLONE_USER/${'$'}PKG",
            "app",
            "\"/data/user_de/${'$'}MAIN_USER/${'$'}PKG\" \"/data_mirror/data_de/null/${'$'}MAIN_USER/${'$'}PKG\" \"/data_mirror/data_de/${'$'}MAIN_USER/${'$'}PKG\"",
        ))
        if (rule.includeExternal) add(optionalLiveCall(
            "external",
            "/data/media/${'$'}CLONE_USER/Android/data/${'$'}PKG",
            "media",
            "\"/data/media/${'$'}MAIN_USER/Android/data/${'$'}PKG\" \"/storage/emulated/${'$'}MAIN_USER/Android/data/${'$'}PKG\"",
        ))
        if (rule.includeMedia) add(optionalLiveCall(
            "media",
            "/data/media/${'$'}CLONE_USER/Android/media/${'$'}PKG",
            "media",
            "\"/data/media/${'$'}MAIN_USER/Android/media/${'$'}PKG\" \"/storage/emulated/${'$'}MAIN_USER/Android/media/${'$'}PKG\"",
        ))
        if (rule.includeObb) add(optionalLiveCall(
            "obb",
            "/data/media/${'$'}CLONE_USER/Android/obb/${'$'}PKG",
            "media",
            "\"/data/media/${'$'}MAIN_USER/Android/obb/${'$'}PKG\" \"/storage/emulated/${'$'}MAIN_USER/Android/obb/${'$'}PKG\"",
        ))
    }.joinToString("\n").ifBlank { "echo \"ERR_NO_DATA_PART_SELECTED\" >&2; exit 62" }

    private fun optionalWorkspaceCall(name: String, target: String, kind: String): String = """
        sync_workspace_part_to_clone "$name" "$target" "$kind"
        handle_optional_push "${'$'}?" "$name"
    """.trimIndent()

    private fun optionalLiveCall(name: String, target: String, kind: String, candidates: String): String = """
        sync_live_part_to_clone "$target" "$kind" "$name" $candidates
        handle_optional_push "${'$'}?" "$name"
    """.trimIndent()
}
