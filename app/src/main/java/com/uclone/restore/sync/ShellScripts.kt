package com.uclone.restore.sync

import com.uclone.restore.model.AppRule
import com.uclone.restore.model.UCloneSettings
import com.uclone.restore.root.shellQuote

object ShellScripts {
    private val rollbackIdPattern = Regex("[A-Za-z0-9_.-]+")
    private enum class RestoreSourceKind {
        ACTIVE,
        ROLLBACK,
        SWITCH_TEMP
    }

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
        CAPTURE_REQUIRE_CE=${if (rule.includeCe) "1" else "0"}
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
          case "${'$'}STATE" in
            *RUNNING_UNLOCKED*) ;;
            *)
              if [ "${'$'}CAPTURE_REQUIRE_CE" = "1" ]; then
                echo "ERR_USER_NOT_UNLOCKED:${'$'}TRY_USER:${'$'}STATE" >&2
                rm -rf "${'$'}TRY_TMP"
                return 1
              fi
              echo "WARN_USER_NOT_UNLOCKED:${'$'}TRY_USER:${'$'}STATE"
              ;;
          esac
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
          if [ "${'$'}CAPTURE_REQUIRE_CE" = "1" ] && [ ! -d "${'$'}TRY_TMP/ce" ]; then
            echo "ERR_CAPTURE_CE_MISSING:${'$'}TRY_USER" >&2
            rm -rf "${'$'}TRY_TMP"
            return 1
          fi
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
        printf '%s\n' "{\"packageName\":\"${packageName}\",\"configuredSourceUser\":${settings.cloneUserId},\"sourceUser\":\"${'$'}DETECTED_USER\",\"sourceUserState\":\"${'$'}DETECTED_STATE\",\"targetUser\":${settings.mainUserId},\"createdAt\":\"${'$'}TS\",\"includeCe\":${rule.includeCe},\"includeDe\":${rule.includeDe},\"includeExternal\":${rule.includeExternal},\"includeMedia\":${rule.includeMedia},\"includeObb\":${rule.includeObb},\"includePermissions\":${rule.includePermissions},\"includeAppWebView\":${rule.includeAppWebView},\"excludeCache\":${rule.excludeCache},\"snapshotSizeKb\":\"${'$'}SIZE_KB\",\"copiedParts\":\"${'$'}COPIED_PARTS\",\"copiedItems\":\"${'$'}COPIED_ITEMS\"}" > "${'$'}TMP/manifest.json" || exit 18
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
        rollbackReason = "恢复到主系统前生成",
    )

    fun switchFromCloneLatest(
        packageName: String,
        rule: AppRule,
        settings: UCloneSettings,
        appPackage: String,
    ): String = restoreBody(
        packageName = packageName,
        settings = settings,
        appPackage = appPackage,
        rollbackName = """${'$'}TS""",
        rollbackReason = "切换到分身态前生成",
        sourceKind = RestoreSourceKind.SWITCH_TEMP,
        prepareSourceScript = switchTempSourceScript(rule, settings),
        writeSwitchMarker = true,
    )

    fun probeCloneCe(settings: UCloneSettings): String = """
        set -u
        CLONE_USER=${settings.cloneUserId}
        echo "PROBE_CLONE_USER=${'$'}CLONE_USER"
        echo "ROOT_ID=${'$'}(id 2>&1)"
        state_of_clone() {
          am get-started-user-state "${'$'}CLONE_USER" 2>&1 || true
        }
        probe_base_path() {
          LABEL="${'$'}1"
          PATH_VALUE="${'$'}2"
          if [ -d "${'$'}PATH_VALUE" ]; then
            ITEMS=${'$'}(find "${'$'}PATH_VALUE" -mindepth 1 -maxdepth 1 2>/dev/null | wc -l | tr -d ' ')
            echo "${'$'}LABEL=READABLE items=${'$'}ITEMS path=${'$'}PATH_VALUE"
          else
            echo "${'$'}LABEL=MISSING path=${'$'}PATH_VALUE"
          fi
        }
        STATE=${'$'}(state_of_clone)
        echo "STATE=${'$'}STATE"
        probe_base_path "CE_BASE" "/data/user/${'$'}CLONE_USER"
        probe_base_path "DE_BASE" "/data/user_de/${'$'}CLONE_USER"
        case "${'$'}STATE" in
          *RUNNING_UNLOCKED*)
            echo "USER10_CE_STATE=RUNNING_UNLOCKED"
            echo "USER10_CE_READY=1"
            ;;
          *RUNNING_LOCKED*|RUNNING)
            echo "USER10_CE_STATE=STARTED_LOCKED"
            echo "ERR_USER10_CE_LOCKED:${'$'}STATE" >&2
            exit 80
            ;;
          *"User is not started"*|*"not started"*|*SHUTDOWN*|*STOPPING*)
            echo "USER10_CE_STATE=NOT_STARTED"
            echo "ERR_USER10_NOT_STARTED:${'$'}STATE" >&2
            exit 81
            ;;
          *)
            echo "USER10_CE_STATE=UNKNOWN"
            echo "ERR_USER10_STATE_UNKNOWN:${'$'}STATE" >&2
            exit 82
            ;;
        esac
    """.trimIndent()

    fun unlockCloneWithCredential(settings: UCloneSettings): String {
        val credential = settings.cloneUnlockCredential
        val inputText = androidInputTextToken(credential)
        return """
        set -u
        CLONE_USER=${settings.cloneUserId}
        MAIN_USER=${settings.mainUserId}
        CREDENTIAL=${shellQuote(credential)}
        INPUT_TEXT=${shellQuote(inputText)}
        echo "UNLOCK_CLONE_USER=${'$'}CLONE_USER"
        echo "ROOT_ID=${'$'}(id 2>&1)"
        echo "CREDENTIAL_CONFIGURED=${if (credential.isBlank()) "0" else "1"}"
        echo "CREDENTIAL_LENGTH=${credential.length}"
        state_of_clone() {
          am get-started-user-state "${'$'}CLONE_USER" 2>&1 || true
        }
        current_user() {
          am get-current-user 2>&1 || true
        }
        print_keyguard_summary() {
          LABEL="${'$'}1"
          FOCUS=${'$'}(dumpsys window 2>/dev/null | grep -m 1 'mCurrentFocus' | sed 's/[[:space:]][[:space:]]*/ /g' || true)
          KEYGUARD=${'$'}(dumpsys window 2>/dev/null | grep -m 1 -E 'mShowingLockscreen|isStatusBarKeyguard|mDreamingLockscreen' | sed 's/[[:space:]][[:space:]]*/ /g' || true)
          echo "${'$'}LABEL"_FOCUS="${'$'}FOCUS"
          echo "${'$'}LABEL"_KEYGUARD="${'$'}KEYGUARD"
        }
        wait_for_current_user() {
          WANT_USER="${'$'}1"
          i=0
          while [ "${'$'}i" -lt 10 ]; do
            NOW_USER=${'$'}(current_user)
            [ "${'$'}NOW_USER" = "${'$'}WANT_USER" ] && return 0
            sleep 1
            i=${'$'}((i + 1))
          done
          return 1
        }
        check_unlocked_or_continue() {
          LABEL="${'$'}1"
          CHECK_STATE=${'$'}(state_of_clone)
          echo "${'$'}LABEL=${'$'}CHECK_STATE"
          case "${'$'}CHECK_STATE" in
            *RUNNING_UNLOCKED*)
              echo "USER10_CE_STATE=RUNNING_UNLOCKED"
              echo "USER10_CE_READY=1"
              echo "UNLOCK_SUCCESS_STAGE=${'$'}LABEL"
              return 0
              ;;
          esac
          return 1
        }
        prepare_keyguard_input() {
          echo "WAKE_AND_DISMISS_BEGIN"
          input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || input keyevent 224 >/dev/null 2>&1 || true
          input keyevent 82 >/dev/null 2>&1 || true
          wm dismiss-keyguard >/dev/null 2>&1 || true
          input swipe "${'$'}X" "${'$'}Y_START" "${'$'}X" "${'$'}Y_END" 350 >/dev/null 2>&1 || true
          input tap "${'$'}X" "${'$'}Y_FIELD" >/dev/null 2>&1 || true
          sleep 1
        }
        send_digit_keyevents() {
          case "${'$'}CREDENTIAL" in
            ''|*[!0-9]*)
              echo "DIGIT_KEYEVENTS_SKIPPED=non_numeric"
              return 1
              ;;
          esac
          echo "DIGIT_KEYEVENTS_BEGIN"
          i=1
          while [ "${'$'}i" -le ${credential.length} ]; do
            DIGIT=${'$'}(printf '%s' "${'$'}CREDENTIAL" | cut -c "${'$'}i")
            input keyevent "KEYCODE_${'$'}DIGIT" >/dev/null 2>&1 || echo "WARN_DIGIT_KEYEVENT_FAILED:index=${'$'}i"
            i=${'$'}((i + 1))
          done
          input keyevent 66 >/dev/null 2>&1 || true
          echo "DIGIT_KEYEVENTS_SENT=${credential.length}"
          return 0
        }
        send_pin_pad_taps() {
          case "${'$'}CREDENTIAL" in
            ''|*[!0-9]*)
              echo "PIN_PAD_TAPS_SKIPPED=non_numeric"
              return 1
              ;;
          esac
          echo "PIN_PAD_TAPS_BEGIN"
          echo "PIN_PAD_GRID=cols_25_50_75_rows_56_66_76_86"
          i=1
          while [ "${'$'}i" -le ${credential.length} ]; do
            DIGIT=${'$'}(printf '%s' "${'$'}CREDENTIAL" | cut -c "${'$'}i")
            case "${'$'}DIGIT" in
              1) TAP_X=${'$'}((WIDTH * 25 / 100)); TAP_Y=${'$'}((HEIGHT * 56 / 100)) ;;
              2) TAP_X=${'$'}((WIDTH * 50 / 100)); TAP_Y=${'$'}((HEIGHT * 56 / 100)) ;;
              3) TAP_X=${'$'}((WIDTH * 75 / 100)); TAP_Y=${'$'}((HEIGHT * 56 / 100)) ;;
              4) TAP_X=${'$'}((WIDTH * 25 / 100)); TAP_Y=${'$'}((HEIGHT * 66 / 100)) ;;
              5) TAP_X=${'$'}((WIDTH * 50 / 100)); TAP_Y=${'$'}((HEIGHT * 66 / 100)) ;;
              6) TAP_X=${'$'}((WIDTH * 75 / 100)); TAP_Y=${'$'}((HEIGHT * 66 / 100)) ;;
              7) TAP_X=${'$'}((WIDTH * 25 / 100)); TAP_Y=${'$'}((HEIGHT * 76 / 100)) ;;
              8) TAP_X=${'$'}((WIDTH * 50 / 100)); TAP_Y=${'$'}((HEIGHT * 76 / 100)) ;;
              9) TAP_X=${'$'}((WIDTH * 75 / 100)); TAP_Y=${'$'}((HEIGHT * 76 / 100)) ;;
              0) TAP_X=${'$'}((WIDTH * 50 / 100)); TAP_Y=${'$'}((HEIGHT * 86 / 100)) ;;
              *) echo "WARN_PIN_PAD_BAD_DIGIT:index=${'$'}i"; return 1 ;;
            esac
            input tap "${'$'}TAP_X" "${'$'}TAP_Y" >/dev/null 2>&1 || echo "WARN_PIN_PAD_TAP_FAILED:index=${'$'}i"
            sleep 0.15
            i=${'$'}((i + 1))
          done
          input keyevent 66 >/dev/null 2>&1 || true
          echo "PIN_PAD_TAPS_SENT=${credential.length}"
          return 0
        }
        STATE_BEFORE=${'$'}(state_of_clone)
        echo "STATE_BEFORE=${'$'}STATE_BEFORE"
        CURRENT_BEFORE=${'$'}(current_user)
        echo "CURRENT_USER_BEFORE=${'$'}CURRENT_BEFORE"
        if [ -z "${'$'}CREDENTIAL" ]; then
          echo "ERR_CREDENTIAL_EMPTY" >&2
          exit 83
        fi
        case "${'$'}STATE_BEFORE" in
          *RUNNING_UNLOCKED*)
            echo "USER10_CE_STATE=RUNNING_UNLOCKED"
            echo "USER10_CE_READY=1"
            echo "UNLOCK_SUCCESS_STAGE=STATE_BEFORE"
            exit 0
            ;;
        esac
        echo "START_USER_BEGIN"
        START_OUTPUT=${'$'}(am start-user -w "${'$'}CLONE_USER" 2>&1 || true)
        echo "START_USER_OUTPUT=${'$'}START_OUTPUT"
        STATE_AFTER_START=${'$'}(state_of_clone)
        echo "STATE_AFTER_START=${'$'}STATE_AFTER_START"
        VERIFY_EXIT=0
        VERIFY_OUTPUT=${'$'}(cmd lock_settings verify --old "${'$'}CREDENTIAL" --user "${'$'}CLONE_USER" 2>&1) || VERIFY_EXIT=${'$'}?
        echo "LOCK_SETTINGS_VERIFY_EXIT=${'$'}VERIFY_EXIT"
        case "${'$'}VERIFY_OUTPUT" in
          *"Lock credential verified successfully"*) VERIFY_RESULT="SUCCESS" ;;
          *"Profile uses unified challenge"*) VERIFY_RESULT="UNIFIED_CHALLENGE_UNSUPPORTED" ;;
          *"Request throttled"*) VERIFY_RESULT="THROTTLED" ;;
          *"didn't match"*) VERIFY_RESULT="BAD_CREDENTIAL" ;;
          *"Unknown command"*|*"Unknown option"*|*"Can't find service"*) VERIFY_RESULT="UNSUPPORTED" ;;
          "") VERIFY_RESULT="EMPTY_OUTPUT" ;;
          *) VERIFY_RESULT="OTHER_OUTPUT_LEN_${'$'}(printf '%s' "${'$'}VERIFY_OUTPUT" | wc -c | tr -d ' ')" ;;
        esac
        echo "LOCK_SETTINGS_VERIFY_RESULT=${'$'}VERIFY_RESULT"
        check_unlocked_or_continue "STATE_AFTER_VERIFY" && exit 0
        echo "SWITCH_USER_BEGIN"
        SWITCH_OUTPUT=${'$'}(am switch-user "${'$'}CLONE_USER" 2>&1 || true)
        echo "SWITCH_USER_OUTPUT=${'$'}SWITCH_OUTPUT"
        wait_for_current_user "${'$'}CLONE_USER" || echo "WARN_CURRENT_USER_NOT_CLONE:${'$'}(current_user)"
        SIZE=${'$'}(wm size 2>/dev/null | sed -n 's/.*: \([0-9][0-9]*\)x\([0-9][0-9]*\).*/\1 \2/p' | head -1)
        if [ -n "${'$'}SIZE" ]; then
          WIDTH=${'$'}(printf '%s' "${'$'}SIZE" | awk '{print ${'$'}1}')
          HEIGHT=${'$'}(printf '%s' "${'$'}SIZE" | awk '{print ${'$'}2}')
        else
          WIDTH=1080
          HEIGHT=2400
        fi
        X=${'$'}((WIDTH / 2))
        Y_START=${'$'}((HEIGHT * 4 / 5))
        Y_END=${'$'}((HEIGHT / 3))
        Y_FIELD=${'$'}((HEIGHT * 3 / 5))
        echo "INPUT_SCREEN=${'$'}WIDTH x ${'$'}HEIGHT"
        print_keyguard_summary "BEFORE_INPUT"
        prepare_keyguard_input
        print_keyguard_summary "AFTER_PREPARE"
        if send_digit_keyevents; then
          sleep 3
          check_unlocked_or_continue "STATE_AFTER_DIGIT_KEYEVENTS" && {
            echo "RETURN_MAIN_AFTER_DIGITS_BEGIN"
            RETURN_DIGIT_OUTPUT=${'$'}(am switch-user "${'$'}MAIN_USER" 2>&1 || true)
            echo "RETURN_MAIN_AFTER_DIGITS_OUTPUT=${'$'}RETURN_DIGIT_OUTPUT"
            exit 0
          }
        fi
        prepare_keyguard_input
        if send_pin_pad_taps; then
          sleep 3
          check_unlocked_or_continue "STATE_AFTER_PIN_PAD_TAPS" && {
            echo "RETURN_MAIN_AFTER_PIN_PAD_BEGIN"
            RETURN_PIN_PAD_OUTPUT=${'$'}(am switch-user "${'$'}MAIN_USER" 2>&1 || true)
            echo "RETURN_MAIN_AFTER_PIN_PAD_OUTPUT=${'$'}RETURN_PIN_PAD_OUTPUT"
            exit 0
          }
        fi
        prepare_keyguard_input
        input text "${'$'}INPUT_TEXT" >/dev/null 2>&1 || echo "WARN_INPUT_TEXT_FAILED"
        echo "INPUT_TEXT_SENT_LENGTH=${credential.length}"
        input keyevent 66 >/dev/null 2>&1 || input keyevent 160 >/dev/null 2>&1 || true
        sleep 3
        STATE_AFTER_INPUT=${'$'}(state_of_clone)
        echo "STATE_AFTER_INPUT=${'$'}STATE_AFTER_INPUT"
        CURRENT_AFTER_INPUT=${'$'}(current_user)
        echo "CURRENT_USER_AFTER_INPUT=${'$'}CURRENT_AFTER_INPUT"
        echo "RETURN_MAIN_BEGIN"
        RETURN_OUTPUT=${'$'}(am switch-user "${'$'}MAIN_USER" 2>&1 || true)
        echo "RETURN_MAIN_OUTPUT=${'$'}RETURN_OUTPUT"
        STATE_AFTER_RETURN=${'$'}(state_of_clone)
        echo "STATE_AFTER_RETURN=${'$'}STATE_AFTER_RETURN"
        case "${'$'}STATE_AFTER_INPUT ${'$'}STATE_AFTER_RETURN" in
          *RUNNING_UNLOCKED*)
            echo "USER10_CE_STATE=RUNNING_UNLOCKED"
            echo "USER10_CE_READY=1"
            ;;
          *)
            echo "USER10_CE_STATE=LOCKED_AFTER_CREDENTIAL_ATTEMPT"
            echo "ERR_USER10_CREDENTIAL_UNLOCK_FAILED:${'$'}STATE_AFTER_RETURN" >&2
            exit 84
            ;;
        esac
    """.trimIndent()
    }

    fun auditRestoreConsistency(packageName: String, settings: UCloneSettings, appPackage: String): String = """
        set -u
        ROOT=${shellQuote(settings.rootDir)}
        PKG=${shellQuote(packageName)}
        APP_PKG=${shellQuote(appPackage)}
        TARGET_USER=${settings.mainUserId}
        CLONE_USER=${settings.cloneUserId}
        TS=${'$'}(date +%Y%m%d-%H%M%S)
        OUT="${'$'}ROOT/audit/${'$'}PKG/${'$'}TS"
        mkdir -p "${'$'}OUT" || exit 10
        echo "AUDIT_DIR=${'$'}OUT"
        echo "AUDIT_PACKAGE=${'$'}PKG"
        echo "AUDIT_TARGET_USER=${'$'}TARGET_USER"
        echo "AUDIT_CLONE_USER=${'$'}CLONE_USER"
        run_capture() {
          NAME="${'$'}1"
          shift
          {
            echo "COMMAND:${'$'}*"
            "${'$'}@" 2>&1
            echo "EXIT:${'$'}?"
          } > "${'$'}OUT/${'$'}NAME"
        }
        capture_tree() {
          NAME="${'$'}1"
          PATH_VALUE="${'$'}2"
          {
            echo "PATH:${'$'}PATH_VALUE"
            if [ -d "${'$'}PATH_VALUE" ]; then
              find "${'$'}PATH_VALUE" -maxdepth 4 2>/dev/null | while IFS= read -r ITEM; do
                ls -ldnZ "${'$'}ITEM" 2>/dev/null || ls -ldn "${'$'}ITEM" 2>/dev/null || true
              done
            else
              echo "MISSING:${'$'}PATH_VALUE"
            fi
          } > "${'$'}OUT/${'$'}NAME"
        }
        capture_lz() {
          NAME="${'$'}1"
          PATH_VALUE="${'$'}2"
          {
            echo "PATH:${'$'}PATH_VALUE"
            if [ -d "${'$'}PATH_VALUE" ]; then
              ls -lanZ "${'$'}PATH_VALUE" 2>/dev/null || ls -lan "${'$'}PATH_VALUE" 2>/dev/null || true
            else
              echo "MISSING:${'$'}PATH_VALUE"
            fi
          } > "${'$'}OUT/${'$'}NAME"
        }
        printf '%s\n' "{\"packageName\":\"${'$'}PKG\",\"appPackage\":\"${'$'}APP_PKG\",\"targetUser\":\"${'$'}TARGET_USER\",\"cloneUser\":\"${'$'}CLONE_USER\",\"createdAt\":\"${'$'}TS\"}" > "${'$'}OUT/manifest.json"
        id > "${'$'}OUT/root_id.txt" 2>&1 || true
        run_capture "uid.txt" sh -c "cmd package list packages -U --user ${'$'}TARGET_USER | grep -F \"package:${'$'}PKG\" || true"
        run_capture "user_state.txt" sh -c "am get-started-user-state ${'$'}TARGET_USER; am get-started-user-state ${'$'}CLONE_USER"
        run_capture "package_dump.txt" sh -c "dumpsys package \"${'$'}PKG\" || true"
        run_capture "cmd_package_dump.txt" sh -c "cmd package dump \"${'$'}PKG\" || true"
        run_capture "appops_pkg.txt" sh -c "cmd appops get --user ${'$'}TARGET_USER \"${'$'}PKG\" || true"
        run_capture "appops_uid.txt" sh -c "cmd appops get --uid \"${'$'}PKG\" || true"
        capture_tree "file_tree_ce.txt" "/data/user/${'$'}TARGET_USER/${'$'}PKG"
        capture_tree "file_tree_de.txt" "/data/user_de/${'$'}TARGET_USER/${'$'}PKG"
        capture_tree "file_tree_external.txt" "/data/media/${'$'}TARGET_USER/Android/data/${'$'}PKG"
        capture_tree "file_tree_media.txt" "/data/media/${'$'}TARGET_USER/Android/media/${'$'}PKG"
        capture_tree "file_tree_obb.txt" "/data/media/${'$'}TARGET_USER/Android/obb/${'$'}PKG"
        capture_lz "ls_lZ_ce.txt" "/data/user/${'$'}TARGET_USER/${'$'}PKG"
        capture_lz "ls_lZ_de.txt" "/data/user_de/${'$'}TARGET_USER/${'$'}PKG"
        if [ -f "${'$'}ROOT/snapshots/${'$'}PKG/active/manifest.json" ]; then
          cp "${'$'}ROOT/snapshots/${'$'}PKG/active/manifest.json" "${'$'}OUT/active_manifest.json" 2>/dev/null || true
        else
          echo "MISSING:${'$'}ROOT/snapshots/${'$'}PKG/active/manifest.json" > "${'$'}OUT/active_manifest.json"
        fi
        PACKAGE_PRESENT=0
        cmd package list packages --user "${'$'}TARGET_USER" 2>/dev/null | grep -qx "package:${'$'}PKG" && PACKAGE_PRESENT=1 || true
        CE_PRESENT=0
        [ -d "/data/user/${'$'}TARGET_USER/${'$'}PKG" ] && CE_PRESENT=1 || true
        DE_PRESENT=0
        [ -d "/data/user_de/${'$'}TARGET_USER/${'$'}PKG" ] && DE_PRESENT=1 || true
        CLONE_STATE=${'$'}(am get-started-user-state "${'$'}CLONE_USER" 2>&1 || true)
        STATUS="PASS_COLLECTION"
        [ "${'$'}PACKAGE_PRESENT" = "1" ] || STATUS="FAIL_PACKAGE_MISSING"
        {
          echo "# UClone Restore Audit"
          echo
          echo "- status: ${'$'}STATUS"
          echo "- package: ${'$'}PKG"
          echo "- targetUser: ${'$'}TARGET_USER"
          echo "- cloneUser: ${'$'}CLONE_USER"
          echo "- cloneState: ${'$'}CLONE_STATE"
          echo "- cePresent: ${'$'}CE_PRESENT"
          echo "- dePresent: ${'$'}DE_PRESENT"
          echo "- auditDir: ${'$'}OUT"
          echo
          echo "restorecon: not run in this read-only audit"
          echo "delete: not run in this audit"
        } > "${'$'}OUT/summary.md"
        echo "AUDIT_STATUS=${'$'}STATUS"
        echo "AUDIT_SUMMARY=${'$'}OUT/summary.md"
    """.trimIndent()

    fun rollback(
        packageName: String,
        rollbackId: String,
        settings: UCloneSettings,
        appPackage: String,
        clearSwitchMarker: Boolean = false,
    ): String {
        requireSafeRollbackId(rollbackId)
        return restoreBody(
            packageName = packageName,
            settings = settings,
            appPackage = appPackage,
            rollbackName = """rollback_${'$'}TS""",
            rollbackReason = if (clearSwitchMarker) "还原主系统态前生成" else "恢复主系统备份前生成",
            sourcePrefix = "${settings.rootDir}/rollback/$packageName/$rollbackId",
            sourceRollbackId = rollbackId,
            clearSwitchMarker = clearSwitchMarker,
        )
    }

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

    fun deleteRestoreBackup(
        packageName: String,
        rollbackId: String,
        settings: UCloneSettings,
        appPackage: String,
    ): String {
        requireSafeRollbackId(rollbackId)
        return """
        set -u
        ROOT=${shellQuote(settings.rootDir)}
        PKG=${shellQuote(packageName)}
        ROLLBACK_ID=${shellQuote(rollbackId)}
        APP_PKG=${shellQuote(appPackage)}
        ROLLBACK_PARENT="${'$'}ROOT/rollback/${'$'}PKG"
        TARGET="${'$'}ROOT/rollback/${'$'}PKG/${'$'}ROLLBACK_ID"
        SWITCH_MARKER="${'$'}ROOT/switches/${'$'}PKG/active"
        [ "${'$'}PKG" != "${'$'}APP_PKG" ] || { echo "ERR_SELF_SYNC"; exit 41; }
        [ -n "${'$'}ROOT" ] && [ "${'$'}ROOT" != "/" ] || { echo "ERR_BAD_ROOT:${'$'}ROOT" >&2; exit 71; }
        validate_rollback_id() {
          CHECK_ROLLBACK_ID="${'$'}1"
          [ -n "${'$'}CHECK_ROLLBACK_ID" ] || { echo "ERR_BAD_ROLLBACK_ID:${'$'}CHECK_ROLLBACK_ID" >&2; exit 73; }
          [ "${'$'}CHECK_ROLLBACK_ID" != "." ] && [ "${'$'}CHECK_ROLLBACK_ID" != ".." ] || { echo "ERR_BAD_ROLLBACK_ID:${'$'}CHECK_ROLLBACK_ID" >&2; exit 73; }
          case "${'$'}CHECK_ROLLBACK_ID" in
            *[!A-Za-z0-9_.-]*) echo "ERR_BAD_ROLLBACK_ID:${'$'}CHECK_ROLLBACK_ID" >&2; exit 73 ;;
          esac
        }
        validate_rollback_id "${'$'}ROLLBACK_ID"
        case "${'$'}TARGET" in
          "${'$'}ROOT"/rollback/"${'$'}PKG"/"${'$'}ROLLBACK_ID") ;;
          *) echo "ERR_BAD_ROLLBACK_TARGET:${'$'}TARGET" >&2; exit 72 ;;
        esac
        case "${'$'}ROLLBACK_PARENT" in
          "${'$'}ROOT"/rollback/"${'$'}PKG") ;;
          *) echo "ERR_BAD_ROLLBACK_PARENT:${'$'}ROLLBACK_PARENT" >&2; exit 72 ;;
        esac
        [ -d "${'$'}TARGET" ] || { echo "ERR_ROLLBACK_MISSING:${'$'}TARGET" >&2; exit 74; }
        SIZE_KB=${'$'}(du -sk "${'$'}ROLLBACK_PARENT" 2>/dev/null | awk '{print ${'$'}1}')
        ITEMS=${'$'}(find "${'$'}ROLLBACK_PARENT" -mindepth 1 2>/dev/null | wc -l | tr -d ' ')
        EXPECTED_CHILD_PREFIX="${'$'}ROOT/rollback/${'$'}PKG/"
        DELETED_COUNT=${'$'}(find "${'$'}ROLLBACK_PARENT" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | wc -l | tr -d ' ')
        find "${'$'}ROLLBACK_PARENT" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | while IFS= read -r OLD; do
          case "${'$'}OLD" in
            "${'$'}EXPECTED_CHILD_PREFIX"*) rm -rf "${'$'}OLD" || exit 75 ;;
            *) echo "ERR_BAD_ROLLBACK_CHILD:${'$'}OLD" >&2; exit 75 ;;
          esac
        done || exit 75
        if [ -f "${'$'}SWITCH_MARKER" ]; then
          case "${'$'}SWITCH_MARKER" in
            "${'$'}ROOT"/switches/"${'$'}PKG"/active) rm -f "${'$'}SWITCH_MARKER" || exit 76 ;;
            *) echo "ERR_BAD_SWITCH_MARKER:${'$'}SWITCH_MARKER" >&2; exit 76 ;;
          esac
          echo "SWITCH_MARKER_CLEARED=${'$'}SWITCH_MARKER"
        fi
        echo "DELETED_RESTORE_BACKUPS=${'$'}ROLLBACK_PARENT COUNT=${'$'}DELETED_COUNT SIZE_KB=${'$'}SIZE_KB ITEMS=${'$'}ITEMS"
    """.trimIndent()
    }

    private fun switchTempSourceScript(rule: AppRule, settings: UCloneSettings): String = """
        SWITCH_TEMP="${'$'}ACTIVE"
        case "${'$'}SWITCH_TEMP" in
          "${'$'}ROOT"/tmp/switch_"${'$'}PKG"_"${'$'}TS") ;;
          *) echo "ERR_BAD_SWITCH_TEMP:${'$'}SWITCH_TEMP" >&2; exit 72 ;;
        esac
        cleanup_switch_temp() {
          [ -n "${'$'}{SWITCH_TEMP:-}" ] || return 0
          case "${'$'}SWITCH_TEMP" in
            "${'$'}ROOT"/tmp/switch_"${'$'}PKG"_"${'$'}TS") rm -rf "${'$'}SWITCH_TEMP" "${'$'}SWITCH_TEMP".try_* 2>/dev/null || true ;;
          esac
        }
        trap cleanup_switch_temp EXIT
        mkdir -p "${'$'}ROOT/tmp" || exit 10
        rm -rf "${'$'}SWITCH_TEMP" "${'$'}SWITCH_TEMP".try_*
        SWITCH_REQUIRE_CE=${if (rule.includeCe) "1" else "0"}
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
        add_candidate_user "${settings.cloneUserId}"
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
          TRY_TMP="${'$'}SWITCH_TEMP.try_${'$'}TRY_USER"
          rm -rf "${'$'}TRY_TMP"
          mkdir -p "${'$'}TRY_TMP" || exit 11
          STATE=${'$'}(am get-started-user-state "${'$'}TRY_USER" 2>/dev/null || true)
          echo "PROBE_USER=${'$'}TRY_USER STATE=${'$'}STATE"
          case "${'$'}STATE" in
            *RUNNING_UNLOCKED*) ;;
            *)
              echo "ERR_USER_NOT_UNLOCKED:${'$'}TRY_USER:${'$'}STATE" >&2
              rm -rf "${'$'}TRY_TMP"
              return 1
              ;;
          esac
          if cmd package list packages --user "${'$'}TRY_USER" 2>/dev/null | grep -qx "package:${'$'}PKG"; then
            echo "PACKAGE_LISTED:${'$'}TRY_USER"
          else
            echo "ERR_PACKAGE_NOT_LISTED:${'$'}TRY_USER" >&2
            rm -rf "${'$'}TRY_TMP"
            return 1
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
          if [ "${'$'}SWITCH_REQUIRE_CE" = "1" ] && [ ! -d "${'$'}TRY_TMP/ce" ]; then
            echo "ERR_SWITCH_CE_MISSING:${'$'}TRY_USER" >&2
            rm -rf "${'$'}TRY_TMP"
            return 1
          fi
          if [ "${'$'}COPIED_PARTS" -gt 0 ]; then
            rm -rf "${'$'}SWITCH_TEMP"
            mv "${'$'}TRY_TMP" "${'$'}SWITCH_TEMP" || exit 14
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
        echo "SWITCH_SOURCE_READY=${'$'}SWITCH_TEMP"
        echo "SWITCH_SOURCE_USER=${'$'}DETECTED_USER"
    """.trimIndent()

    private fun restoreBody(
        packageName: String,
        settings: UCloneSettings,
        appPackage: String,
        rollbackName: String,
        rollbackReason: String,
        sourcePrefix: String = "",
        sourceRollbackId: String? = null,
        sourceKind: RestoreSourceKind = if (sourceRollbackId == null) RestoreSourceKind.ACTIVE else RestoreSourceKind.ROLLBACK,
        prepareSourceScript: String = ":",
        writeSwitchMarker: Boolean = false,
        clearSwitchMarker: Boolean = false,
    ): String {
        val sourceRoot = sourcePrefix.ifBlank { "${settings.rootDir}/snapshots/$packageName/active" }
        val activeAssignment = when (sourceKind) {
            RestoreSourceKind.SWITCH_TEMP -> "ACTIVE=\"${'$'}ROOT/tmp/switch_${'$'}{PKG}_${'$'}TS\""
            else -> "ACTIVE=${shellQuote(sourceRoot)}"
        }
        val sourceKindToken = when (sourceKind) {
            RestoreSourceKind.ACTIVE -> "active"
            RestoreSourceKind.ROLLBACK -> "rollback"
            RestoreSourceKind.SWITCH_TEMP -> "switch_temp"
        }
        sourceRollbackId?.let(::requireSafeRollbackId)
        return """
            set -u
            ROOT=${shellQuote(settings.rootDir)}
            PKG=${shellQuote(packageName)}
            APP_PKG=${shellQuote(appPackage)}
            DST_USER=${settings.mainUserId}
            TS=${'$'}(date +%Y%m%d-%H%M%S)
            $activeAssignment
            SOURCE_KIND=${shellQuote(sourceKindToken)}
            SOURCE_ROLLBACK_ID=${shellQuote(sourceRollbackId.orEmpty())}
            ROLLBACK_ID="$rollbackName"
            ROLLBACK="${'$'}ROOT/rollback/${'$'}PKG/$rollbackName"
            [ "${'$'}PKG" != "${'$'}APP_PKG" ] || { echo "ERR_SELF_SYNC"; exit 41; }
            [ -n "${'$'}ROOT" ] && [ "${'$'}ROOT" != "/" ] || { echo "ERR_BAD_ROOT:${'$'}ROOT" >&2; exit 71; }
            validate_rollback_id() {
              CHECK_ROLLBACK_ID="${'$'}1"
              [ -n "${'$'}CHECK_ROLLBACK_ID" ] || { echo "ERR_BAD_ROLLBACK_ID:${'$'}CHECK_ROLLBACK_ID" >&2; exit 73; }
              [ "${'$'}CHECK_ROLLBACK_ID" != "." ] && [ "${'$'}CHECK_ROLLBACK_ID" != ".." ] || { echo "ERR_BAD_ROLLBACK_ID:${'$'}CHECK_ROLLBACK_ID" >&2; exit 73; }
              case "${'$'}CHECK_ROLLBACK_ID" in
                *[!A-Za-z0-9_.-]*) echo "ERR_BAD_ROLLBACK_ID:${'$'}CHECK_ROLLBACK_ID" >&2; exit 73 ;;
              esac
            }
            if [ -n "${'$'}SOURCE_ROLLBACK_ID" ]; then
              validate_rollback_id "${'$'}SOURCE_ROLLBACK_ID"
              EXPECTED_ACTIVE="${'$'}ROOT/rollback/${'$'}PKG/${'$'}SOURCE_ROLLBACK_ID"
            elif [ "${'$'}SOURCE_KIND" = "switch_temp" ]; then
              EXPECTED_ACTIVE="${'$'}ROOT/tmp/switch_${'$'}{PKG}_${'$'}TS"
            else
              EXPECTED_ACTIVE="${'$'}ROOT/snapshots/${'$'}PKG/active"
            fi
            $prepareSourceScript
            [ "${'$'}ACTIVE" = "${'$'}EXPECTED_ACTIVE" ] || { echo "ERR_BAD_RESTORE_SOURCE:${'$'}ACTIVE" >&2; exit 72; }
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
            prune_old_rollbacks() {
              ROLLBACK_PARENT="${'$'}ROOT/rollback/${'$'}PKG"
              SWITCH_MARKER_FOR_PRUNE="${'$'}ROOT/switches/${'$'}PKG/active"
              SWITCH_ID_FOR_PRUNE=""
              if [ -f "${'$'}SWITCH_MARKER_FOR_PRUNE" ]; then
                SWITCH_ID_FOR_PRUNE=${'$'}(sed -n '1p' "${'$'}SWITCH_MARKER_FOR_PRUNE" | tr -d '\r')
              fi
              [ -d "${'$'}ROLLBACK_PARENT" ] || return 0
              EXPECTED_CHILD_PREFIX="${'$'}ROOT/rollback/${'$'}PKG/"
              find "${'$'}ROLLBACK_PARENT" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | while IFS= read -r OLD; do
                OLD_ID=${'$'}(basename "${'$'}OLD")
                [ "${'$'}OLD_ID" = "${'$'}ROLLBACK_ID" ] && continue
                case "${'$'}OLD" in
                  "${'$'}EXPECTED_CHILD_PREFIX"*)
                    rm -rf "${'$'}OLD" && echo "PRUNED_ROLLBACK=${'$'}OLD" || echo "WARN_PRUNE_ROLLBACK_FAILED:${'$'}OLD"
                    if [ -n "${'$'}SWITCH_ID_FOR_PRUNE" ] && [ "${'$'}OLD_ID" = "${'$'}SWITCH_ID_FOR_PRUNE" ]; then
                      case "${'$'}SWITCH_MARKER_FOR_PRUNE" in
                        "${'$'}ROOT"/switches/"${'$'}PKG"/active) rm -f "${'$'}SWITCH_MARKER_FOR_PRUNE" && echo "SWITCH_MARKER_CLEARED=${'$'}SWITCH_MARKER_FOR_PRUNE" || exit 70 ;;
                        *) echo "ERR_BAD_SWITCH_MARKER:${'$'}SWITCH_MARKER_FOR_PRUNE" >&2; exit 70 ;;
                      esac
                    fi
                    ;;
                  *)
                    echo "WARN_SKIP_BAD_ROLLBACK_PATH:${'$'}OLD"
                    ;;
                esac
              done || exit 70
              if [ -f "${'$'}SWITCH_MARKER_FOR_PRUNE" ]; then
                SWITCH_ID_AFTER_PRUNE=${'$'}(sed -n '1p' "${'$'}SWITCH_MARKER_FOR_PRUNE" | tr -d '\r')
                if [ "${'$'}SWITCH_ID_AFTER_PRUNE" != "${'$'}ROLLBACK_ID" ] || [ ! -d "${'$'}ROLLBACK_PARENT/${'$'}SWITCH_ID_AFTER_PRUNE" ]; then
                  case "${'$'}SWITCH_MARKER_FOR_PRUNE" in
                    "${'$'}ROOT"/switches/"${'$'}PKG"/active) rm -f "${'$'}SWITCH_MARKER_FOR_PRUNE" && echo "SWITCH_MARKER_CLEARED=${'$'}SWITCH_MARKER_FOR_PRUNE" || exit 70 ;;
                    *) echo "ERR_BAD_SWITCH_MARKER:${'$'}SWITCH_MARKER_FOR_PRUNE" >&2; exit 70 ;;
                  esac
                fi
              fi
            }
            backup_dir "/data/user/${settings.mainUserId}/${'$'}PKG" "${'$'}ROLLBACK/ce"
            backup_dir "/data/user_de/${settings.mainUserId}/${'$'}PKG" "${'$'}ROLLBACK/de"
            ${if (settings.includePermissions) "backup_permission_state \"${'$'}ROLLBACK/permissions\"" else ":"}
            ROLLBACK_SIZE_KB=${'$'}(du -sk "${'$'}ROLLBACK" 2>/dev/null | awk '{print ${'$'}1}')
            printf '%s\n' "{\"packageName\":\"${'$'}PKG\",\"rollbackId\":\"${'$'}ROLLBACK_ID\",\"createdAt\":\"${'$'}TS\",\"reason\":\"$rollbackReason\",\"sizeKb\":\"${'$'}ROLLBACK_SIZE_KB\"}" > "${'$'}ROLLBACK/manifest.json" || exit 53
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
            prune_old_rollbacks
            sync
            force_stop_package_users
            echo "ROLLBACK=${'$'}ROLLBACK"
            echo "RESTORE_SUMMARY: restoredParts=${'$'}RESTORED_PARTS restoredItems=${'$'}RESTORED_ITEMS backupParts=${'$'}BACKUP_PARTS"
        """.trimIndent()
    }

    private fun requireSafeRollbackId(rollbackId: String) {
        require(rollbackId.isNotBlank() && rollbackId != "." && rollbackId != ".." && rollbackIdPattern.matches(rollbackId)) {
            "Unsafe rollback id: $rollbackId"
        }
    }

    private fun androidInputTextToken(value: String): String =
        value.replace(" ", "%s")
}
