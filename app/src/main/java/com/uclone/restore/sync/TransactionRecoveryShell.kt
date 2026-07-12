package com.uclone.restore.sync

import com.uclone.restore.model.UCloneSettings
import com.uclone.restore.root.shellQuote

internal object TransactionRecoveryShell {
    fun build(transactionRequestId: String, settings: UCloneSettings): String {
        require(SAFE_ID.matches(transactionRequestId))
        return """
            set -u
            ${WorkspacePathGuard.require(settings.rootDir)}
            RECOVERY_TRANSACTION_ID=${shellQuote(transactionRequestId)}
            UCLONE_TXN_DIR="${'$'}ROOT/transactions/${'$'}RECOVERY_TRANSACTION_ID"
            TXN_JSON="${'$'}UCLONE_TXN_DIR/transaction.json"
            uclone_now_ms() {
              NOW_VALUE=${'$'}(/system/bin/date +%s%3N 2>/dev/null || true)
              case "${'$'}NOW_VALUE" in
                ''|*[!0-9]*) /system/bin/date +%s | awk '{ printf "%.0f\n", ${'$'}1 * 1000 }' ;;
                *) echo "${'$'}NOW_VALUE" ;;
              esac
            }
            uclone_stage_begin() {
              command -v uclone_active_stage >/dev/null 2>&1 && uclone_active_stage "${'$'}1"
              echo "UCLONE_STAGE_BEGIN:${'$'}1"
            }
            transaction_string() {
              sed -n "s/.*\"${'$'}2\":\"\([^\"]*\)\".*/\1/p" "${'$'}1" | head -1
            }
            transaction_scalar() {
              sed -n "s/.*\"${'$'}2\":\([^,}]*\).*/\1/p" "${'$'}1" | head -1
            }
            [ -f "${'$'}TXN_JSON" ] || { echo "ERR_TRANSACTION_MISSING:${'$'}RECOVERY_TRANSACTION_ID" >&2; exit 91; }
            UCLONE_REQUEST_ID=${'$'}(transaction_string "${'$'}TXN_JSON" requestId)
            [ "${'$'}UCLONE_REQUEST_ID" = "${'$'}RECOVERY_TRANSACTION_ID" ] || { echo "ERR_TRANSACTION_ID_MISMATCH" >&2; exit 91; }
            PKG=${'$'}(transaction_string "${'$'}TXN_JSON" packageName)
            case "${'$'}PKG" in ''|*[!A-Za-z0-9_.]*) echo "ERR_TRANSACTION_PACKAGE:${'$'}PKG" >&2; exit 91 ;; esac
            UCLONE_TXN_OPERATION=${'$'}(transaction_string "${'$'}TXN_JSON" operation)
            UCLONE_TXN_SOURCE_USER=${'$'}(transaction_scalar "${'$'}TXN_JSON" sourceUserId)
            UCLONE_TXN_TARGET_USER=${'$'}(transaction_scalar "${'$'}TXN_JSON" targetUserId)
            UCLONE_TXN_ROLLBACK=${'$'}(transaction_string "${'$'}TXN_JSON" rollbackPath)
            UCLONE_TXN_ROLLBACK_NEXT=${'$'}(transaction_string "${'$'}TXN_JSON" rollbackPathNext)
            UCLONE_TXN_STAGE=${'$'}(transaction_string "${'$'}TXN_JSON" stage)
            UCLONE_TXN_ROLLBACK_READY=${'$'}(transaction_scalar "${'$'}TXN_JSON" rollbackReady)
            UCLONE_TXN_TARGET_MUTATED=${'$'}(transaction_scalar "${'$'}TXN_JSON" targetMutated)
            UCLONE_TXN_COMMITTED=${'$'}(transaction_scalar "${'$'}TXN_JSON" committed)
            UCLONE_TXN_GATE_STATE=${'$'}(transaction_string "${'$'}TXN_JSON" gateState)
            UCLONE_TXN_MARKER_MANAGED=${'$'}(transaction_scalar "${'$'}TXN_JSON" switchMarkerManaged)
            UCLONE_TXN_MARKER_EXISTED=${'$'}(transaction_scalar "${'$'}TXN_JSON" switchMarkerExisted)
            UCLONE_TXN_MARKER_VALUE=${'$'}(transaction_string "${'$'}TXN_JSON" switchMarkerValue)
            UCLONE_TXN_STARTED_AT=${'$'}(transaction_scalar "${'$'}TXN_JSON" startedAt)
            UCLONE_TXN_SELECTED_PARTS=${'$'}(transaction_string "${'$'}TXN_JSON" selectedParts)
            UCLONE_TXN_MODIFIED_PARTS=${'$'}(transaction_string "${'$'}TXN_JSON" modifiedParts)
            UCLONE_TXN_ORIGIN_BOOT_ID=${'$'}(transaction_string "${'$'}TXN_JSON" originBootId)
            UCLONE_TXN_ORIGIN_ROOT_PID=${'$'}(transaction_scalar "${'$'}TXN_JSON" originRootPid)
            UCLONE_TXN_ORIGIN_PID_START_TICKS=${'$'}(transaction_string "${'$'}TXN_JSON" originRootPidStartTicks)
            [ -n "${'$'}UCLONE_TXN_SELECTED_PARTS" ] || UCLONE_TXN_SELECTED_PARTS=ce,de,external,media,obb,permissions
            [ -n "${'$'}UCLONE_TXN_ORIGIN_BOOT_ID" ] || UCLONE_TXN_ORIGIN_BOOT_ID=unknown
            case "${'$'}UCLONE_TXN_ORIGIN_ROOT_PID" in ''|*[!0-9]*) UCLONE_TXN_ORIGIN_ROOT_PID=0 ;; esac
            [ -n "${'$'}UCLONE_TXN_ORIGIN_PID_START_TICKS" ] || UCLONE_TXN_ORIGIN_PID_START_TICKS=unknown
            case "${'$'}UCLONE_TXN_TARGET_USER" in ''|*[!0-9]*) echo "ERR_TRANSACTION_TARGET_USER" >&2; exit 91 ;; esac
            case "${'$'}UCLONE_TXN_SELECTED_PARTS" in ''|*[!a-z_,]*) echo "ERR_TRANSACTION_SELECTED_PARTS" >&2; exit 91 ;; esac
            case "${'$'}UCLONE_TXN_MODIFIED_PARTS" in *[!a-z_,]*) echo "ERR_TRANSACTION_MODIFIED_PARTS" >&2; exit 91 ;; esac
            for UCLONE_TXN_PART in ${'$'}(printf '%s' "${'$'}UCLONE_TXN_SELECTED_PARTS,${'$'}UCLONE_TXN_MODIFIED_PARTS" | tr ',' ' '); do
              case "${'$'}UCLONE_TXN_PART" in ce|de|external|media|obb|permissions) ;; *) echo "ERR_TRANSACTION_PART:${'$'}UCLONE_TXN_PART" >&2; exit 91 ;; esac
            done
            for BOOLEAN_VALUE in "${'$'}UCLONE_TXN_ROLLBACK_READY" "${'$'}UCLONE_TXN_TARGET_MUTATED" "${'$'}UCLONE_TXN_COMMITTED" "${'$'}UCLONE_TXN_MARKER_MANAGED" "${'$'}UCLONE_TXN_MARKER_EXISTED"; do
              case "${'$'}BOOLEAN_VALUE" in true|false) ;; *) echo "ERR_TRANSACTION_BOOLEAN_STATE" >&2; exit 91 ;; esac
            done
            ${BackupIntegrityShell.functions()}
            ${PermissionStateShell.functions()}
            ${TransactionSafetyShell.functions()}
            uclone_current_package_uid() {
              CURRENT_UID_USER="${'$'}1"
              /system/bin/cmd package list packages -U --user "${'$'}CURRENT_UID_USER" 2>/dev/null |
                awk -v package="package:${'$'}PKG" '${'$'}1 == package { sub("uid:", "", ${'$'}2); print ${'$'}2; exit }'
            }
            uclone_recovery_gate_validate() {
              VALIDATE_GATE_FILE="${'$'}1"
              VALIDATE_EXPECTED_ROLE="${'$'}{2:-}"
              VALIDATE_EXPECTED_USER="${'$'}{3:-}"
              VALIDATE_EXPECTED_UID="${'$'}{4:-}"
              [ -f "${'$'}VALIDATE_GATE_FILE" ] || return 1
              VALIDATE_ROLE=${'$'}(uclone_gate_value "${'$'}VALIDATE_GATE_FILE" role)
              VALIDATE_STATE=${'$'}(uclone_gate_value "${'$'}VALIDATE_GATE_FILE" state)
              VALIDATE_USER=${'$'}(uclone_gate_value "${'$'}VALIDATE_GATE_FILE" userId)
              VALIDATE_UID=${'$'}(uclone_gate_value "${'$'}VALIDATE_GATE_FILE" uid)
              VALIDATE_PACKAGE=${'$'}(uclone_gate_value "${'$'}VALIDATE_GATE_FILE" packageName)
              case "${'$'}VALIDATE_ROLE" in source|target) ;; *) return 1 ;; esac
              case "${'$'}VALIDATE_STATE" in PREPARED|HELD|RELEASED) ;; *) return 1 ;; esac
              case "${'$'}VALIDATE_USER:${'$'}VALIDATE_UID" in *[!0-9:]*) return 1 ;; esac
              [ "${'$'}VALIDATE_PACKAGE" = "${'$'}PKG" ] || return 1
              [ -z "${'$'}VALIDATE_EXPECTED_ROLE" ] || [ "${'$'}VALIDATE_ROLE" = "${'$'}VALIDATE_EXPECTED_ROLE" ] || return 1
              [ -z "${'$'}VALIDATE_EXPECTED_USER" ] || [ "${'$'}VALIDATE_USER" = "${'$'}VALIDATE_EXPECTED_USER" ] || return 1
              [ -z "${'$'}VALIDATE_EXPECTED_UID" ] || [ "${'$'}VALIDATE_UID" = "${'$'}VALIDATE_EXPECTED_UID" ] || return 1
              VALIDATE_CURRENT_UID=${'$'}(uclone_current_package_uid "${'$'}VALIDATE_USER")
              [ -n "${'$'}VALIDATE_CURRENT_UID" ] && [ "${'$'}VALIDATE_UID" = "${'$'}VALIDATE_CURRENT_UID" ] || return 1
              return 0
            }
            uclone_recovery_gate_ensure_held() {
              ENSURE_GATE_DIR="${'$'}1"
              ENSURE_GATE_FILE="${'$'}ENSURE_GATE_DIR/gate.state"
              ENSURE_EXPECTED_USER="${'$'}2"
              ENSURE_EXPECTED_UID="${'$'}3"
              uclone_recovery_gate_validate "${'$'}ENSURE_GATE_FILE" target "${'$'}ENSURE_EXPECTED_USER" "${'$'}ENSURE_EXPECTED_UID" || return 1
              ENSURE_GATE_USER=${'$'}(uclone_gate_value "${'$'}ENSURE_GATE_FILE" userId)
              ENSURE_GATE_UID=${'$'}(uclone_gate_value "${'$'}ENSURE_GATE_FILE" uid)
              [ "${'$'}(uclone_gate_value "${'$'}ENSURE_GATE_FILE" state)" = "HELD" ] || return 1
              CURRENT_LINE=${'$'}(uclone_package_user_line "${'$'}ENSURE_GATE_USER")
              CURRENT_ENABLED=${'$'}(printf '%s\n' "${'$'}CURRENT_LINE" | sed -n 's/.* enabled=\([0-4]\).*/\1/p')
              if [ "${'$'}CURRENT_ENABLED" != "3" ]; then
                /system/bin/cmd package disable-user --user "${'$'}ENSURE_GATE_USER" "${'$'}PKG" >/dev/null 2>&1 || return 1
              fi
              /system/bin/am force-stop --user "${'$'}ENSURE_GATE_USER" "${'$'}PKG" >/dev/null 2>&1 || return 1
              ENSURE_WAIT=0
              while uclone_app_process_exists "${'$'}ENSURE_GATE_UID" && [ "${'$'}ENSURE_WAIT" -lt 40 ]; do
                /system/bin/am force-stop --user "${'$'}ENSURE_GATE_USER" "${'$'}PKG" >/dev/null 2>&1 || true
                sleep 0.05
                ENSURE_WAIT=${'$'}((ENSURE_WAIT + 1))
              done
              ! uclone_app_process_exists "${'$'}ENSURE_GATE_UID"
            }
            uclone_recovery_release_all_gates() {
              for RECOVERY_GATE_FILE in "${'$'}UCLONE_TXN_DIR"/gates/*/gate.state; do
                [ -f "${'$'}RECOVERY_GATE_FILE" ] || continue
                RECOVERY_GATE_STATE=${'$'}(uclone_gate_value "${'$'}RECOVERY_GATE_FILE" state)
                [ "${'$'}RECOVERY_GATE_STATE" != "RELEASED" ] || continue
                uclone_recovery_gate_validate "${'$'}RECOVERY_GATE_FILE" || return 1
                uclone_gate_release "${'$'}(dirname "${'$'}RECOVERY_GATE_FILE")" || return 1
              done
              return 0
            }
            uclone_validate_rollback_path() {
              CANDIDATE="${'$'}1"
              [ -d "${'$'}CANDIDATE" ] && [ ! -L "${'$'}CANDIDATE" ] || return 1
              CANDIDATE_REAL=${'$'}(readlink -f "${'$'}CANDIDATE" 2>/dev/null || true)
              [ "${'$'}CANDIDATE_REAL" = "${'$'}CANDIDATE" ] || return 1
              CANDIDATE_PARENT=${'$'}(dirname "${'$'}CANDIDATE")
              CANDIDATE_NAME=${'$'}(basename "${'$'}CANDIDATE")
              case "${'$'}CANDIDATE_PARENT" in
                "${'$'}ROOT/rollback/${'$'}PKG"|"${'$'}ROOT/clone_rollback/${'$'}PKG") return 0 ;;
                "${'$'}ROOT/tmp")
                  case "${'$'}CANDIDATE_NAME" in undo_"${'$'}PKG"_*|undo_clone_"${'$'}PKG"_*) return 0 ;; esac
                  ;;
              esac
              return 1
            }
            count_items() {
              COUNT_VALUE=${'$'}(find "${'$'}1" -mindepth 1 -print 2>/dev/null | wc -l | tr -d ' ') || return 1
              case "${'$'}COUNT_VALUE" in ''|*[!0-9]*) return 1 ;; esac
              echo "${'$'}COUNT_VALUE"
            }
            uclone_transaction_part_requires_recovery() {
              RECOVERY_PART="${'$'}1"
              if [ -n "${'$'}UCLONE_TXN_MODIFIED_PARTS" ]; then
                case ",${'$'}UCLONE_TXN_MODIFIED_PARTS," in
                  *,"${'$'}RECOVERY_PART",*) return 0 ;;
                  *) return 1 ;;
                esac
              fi
              case ",${'$'}UCLONE_TXN_SELECTED_PARTS," in
                *,"${'$'}RECOVERY_PART",*) return 0 ;;
                *) return 1 ;;
              esac
            }
            validate_target_path() {
              case "${'$'}1" in
                /data/user/"${'$'}UCLONE_TXN_TARGET_USER"/"${'$'}PKG"|/data/user_de/"${'$'}UCLONE_TXN_TARGET_USER"/"${'$'}PKG"|/data/media/"${'$'}UCLONE_TXN_TARGET_USER"/Android/data/"${'$'}PKG"|/data/media/"${'$'}UCLONE_TXN_TARGET_USER"/Android/media/"${'$'}PKG"|/data/media/"${'$'}UCLONE_TXN_TARGET_USER"/Android/obb/"${'$'}PKG") ;;
                *) return 1 ;;
              esac
            }
            clear_target_contents() {
              validate_target_path "${'$'}1" || return 1
              find "${'$'}1" -mindepth 1 -maxdepth 1 -exec rm -rf {} \; || return 1
            }
            recovery_apply_security() {
              SECURITY_TARGET="${'$'}1"
              SECURITY_OWNER="${'$'}2"
              SECURITY_MODE="${'$'}3"
              SECURITY_KIND="${'$'}4"
              case "${'$'}SECURITY_KIND" in app|media|obb) ;; *) return 1 ;; esac
              chown -hR "${'$'}SECURITY_OWNER" "${'$'}SECURITY_TARGET" || return 1
              OWNER_UID=${'$'}{SECURITY_OWNER%%:*}
              if [ "${'$'}SECURITY_KIND" = "app" ]; then
                APP_ID=${'$'}((OWNER_UID % 100000))
                OWNER_USER_ID=${'$'}((OWNER_UID / 100000))
                if [ "${'$'}APP_ID" -ge 10000 ] && [ "${'$'}APP_ID" -le 19999 ]; then
                  CACHE_GID=${'$'}((OWNER_USER_ID * 100000 + 20000 + APP_ID - 10000))
                  [ -d "${'$'}SECURITY_TARGET/cache" ] && chown -hR "${'$'}OWNER_UID:${'$'}CACHE_GID" "${'$'}SECURITY_TARGET/cache" >/dev/null 2>&1 || true
                  [ -d "${'$'}SECURITY_TARGET/code_cache" ] && chown -hR "${'$'}OWNER_UID:${'$'}CACHE_GID" "${'$'}SECURITY_TARGET/code_cache" >/dev/null 2>&1 || true
                fi
              fi
              restorecon -RF "${'$'}SECURITY_TARGET" >/dev/null 2>&1 || restorecon -R "${'$'}SECURITY_TARGET" >/dev/null 2>&1 || return 1
              chmod "${'$'}SECURITY_MODE" "${'$'}SECURITY_TARGET" || return 1
            }
            recovery_restore_part() {
              PART_NAME="${'$'}1"
              TARGET="${'$'}2"
              KIND="${'$'}3"
              validate_target_path "${'$'}TARGET" || return 1
              uclone_verify_part_metadata "${'$'}ROLLBACK" "${'$'}PART_NAME" >/dev/null || return 1
              PART_STATE=${'$'}(sed -n '1p' "${'$'}ROLLBACK/.state/${'$'}PART_NAME" | tr -d '\r')
              case "${'$'}PART_STATE" in
                absent) rm -rf "${'$'}TARGET" || return 1; return 0 ;;
                empty|data) ;;
                *) return 1 ;;
              esac
              TARGET_MODE=${'$'}(uclone_part_root_mode "${'$'}ROLLBACK" "${'$'}PART_NAME") || return 1
              mkdir -p "${'$'}TARGET" || return 1
              clear_target_contents "${'$'}TARGET" || return 1
              if [ "${'$'}PART_STATE" = "data" ]; then
                [ -d "${'$'}ROLLBACK/${'$'}PART_NAME" ] || return 1
                (cd "${'$'}ROLLBACK/${'$'}PART_NAME" && tar -cpf - .) | (cd "${'$'}TARGET" && tar -xpf -) || return 1
              fi
              case "${'$'}KIND" in
                app) TARGET_OWNER="${'$'}TARGET_UID:${'$'}TARGET_UID" ;;
                media) TARGET_OWNER="${'$'}TARGET_UID:1078" ;;
                obb) TARGET_OWNER="${'$'}TARGET_UID:1079" ;;
                *) return 1 ;;
              esac
              recovery_apply_security "${'$'}TARGET" "${'$'}TARGET_OWNER" "${'$'}TARGET_MODE" "${'$'}KIND" || return 1
            }
            uclone_restore_switch_marker() {
              [ "${'$'}UCLONE_TXN_MARKER_MANAGED" = "true" ] || return 0
              MARKER_PATH="${'$'}ROOT/switches/${'$'}PKG/active"
              MARKER_DIR=${'$'}(dirname "${'$'}MARKER_PATH")
              if [ "${'$'}UCLONE_TXN_MARKER_EXISTED" = "true" ]; then
                case "${'$'}UCLONE_TXN_MARKER_VALUE" in ''|*[!A-Za-z0-9_.-]*) return 1 ;; esac
                mkdir -p "${'$'}MARKER_DIR" || return 1
                MARKER_TMP="${'$'}MARKER_PATH.tmp.${'$'}${'$'}"
                printf '%s\n' "${'$'}UCLONE_TXN_MARKER_VALUE" > "${'$'}MARKER_TMP" || return 1
                chmod 600 "${'$'}MARKER_TMP" || return 1
                sync
                mv -f "${'$'}MARKER_TMP" "${'$'}MARKER_PATH" || return 1
              else
                rm -f "${'$'}MARKER_PATH" || return 1
              fi
            }
            uclone_stage_begin PRECHECK
            if [ "${'$'}UCLONE_TXN_COMMITTED" = "true" ]; then
              uclone_recovery_release_all_gates || { uclone_transaction_recovery_required; exit 92; }
              UCLONE_TXN_STAGE=COMMITTED
              UCLONE_TXN_GATE_STATE=RELEASED
              uclone_transaction_write || exit 92
              echo "TRANSACTION_RECOVERY_COMMITTED_GATE_RELEASED=${'$'}RECOVERY_TRANSACTION_ID"
              exit 0
            fi
            if [ "${'$'}UCLONE_TXN_TARGET_MUTATED" != "true" ]; then
              uclone_recovery_release_all_gates || { uclone_transaction_recovery_required; exit 92; }
              UCLONE_TXN_STAGE=ABORTED
              UCLONE_TXN_GATE_STATE=RELEASED
              uclone_transaction_write || exit 92
              echo "TRANSACTION_RECOVERY_ABORTED=${'$'}RECOVERY_TRANSACTION_ID"
              exit 0
            fi
            [ "${'$'}UCLONE_TXN_ROLLBACK_READY" = "true" ] || { uclone_transaction_recovery_required; exit 91; }
            ROLLBACK="${'$'}UCLONE_TXN_ROLLBACK"
            if ! uclone_validate_rollback_path "${'$'}ROLLBACK"; then
              ROLLBACK="${'$'}UCLONE_TXN_ROLLBACK_NEXT"
            fi
            uclone_validate_rollback_path "${'$'}ROLLBACK" || { uclone_transaction_recovery_required; echo "ERR_TRANSACTION_ROLLBACK_PATH" >&2; exit 91; }
            UCLONE_TXN_ROLLBACK="${'$'}ROLLBACK"
            UCLONE_TXN_ROLLBACK_NEXT=""
            TARGET_UID=${'$'}(uclone_current_package_uid "${'$'}UCLONE_TXN_TARGET_USER")
            case "${'$'}TARGET_UID" in ''|*[!0-9]*) uclone_transaction_recovery_required; exit 91 ;; esac
            ROLLBACK_MANIFEST="${'$'}ROLLBACK/manifest.json"
            uclone_require_canonical_backup_file "${'$'}ROLLBACK_MANIFEST" || {
              uclone_transaction_recovery_required
              echo "ERR_TRANSACTION_MANIFEST_MISSING_OR_UNSAFE" >&2
              exit 91
            }
            ROLLBACK_SCHEMA=${'$'}(sed -n 's/.*"schemaVersion":\([0-9][0-9]*\).*/\1/p' "${'$'}ROLLBACK_MANIFEST" | head -1)
            ROLLBACK_PACKAGE=${'$'}(sed -n 's/.*"packageName":"\([^"]*\)".*/\1/p' "${'$'}ROLLBACK_MANIFEST" | head -1)
            ROLLBACK_SIGNING_CERTIFICATE_SHA256=${'$'}(sed -n 's/.*"sourceSigningCertificateSha256":"\([0-9a-f,]*\)".*/\1/p' "${'$'}ROLLBACK_MANIFEST" | head -1)
            ROLLBACK_APK_DIGEST=${'$'}(sed -n 's/.*"sourceApkDigest":"\([0-9a-fA-F]*\)".*/\1/p' "${'$'}ROLLBACK_MANIFEST" | head -1)
            ROLLBACK_VERSION_CODE=${'$'}(sed -n 's/.*"sourceVersionCode":"\([0-9][0-9]*\)".*/\1/p' "${'$'}ROLLBACK_MANIFEST" | head -1)
            case "${'$'}ROLLBACK_SCHEMA" in 3|4) ;; *)
              uclone_transaction_recovery_required
              echo "ERR_TRANSACTION_MANIFEST_IDENTITY" >&2
              exit 91
            esac
            [ "${'$'}ROLLBACK_PACKAGE" = "${'$'}PKG" ] || {
              uclone_transaction_recovery_required
              echo "ERR_TRANSACTION_MANIFEST_IDENTITY" >&2
              exit 91
            }
            CURRENT_APK_DIGEST=${'$'}(uclone_package_code_digest "${'$'}UCLONE_TXN_TARGET_USER" "${'$'}PKG") || { uclone_transaction_recovery_required; exit 91; }
            CURRENT_VERSION_CODE=${'$'}(uclone_package_version_code "${'$'}PKG") || { uclone_transaction_recovery_required; exit 91; }
            if [ "${'$'}ROLLBACK_SCHEMA" = "4" ] && [ "${'$'}ROLLBACK_SIGNING_CERTIFICATE_SHA256" != "${'$'}UCLONE_SIGNING_CERTIFICATE_SHA256" ]; then
              uclone_transaction_recovery_required
              echo "ERR_TRANSACTION_SIGNATURE_MISMATCH" >&2
              exit 91
            fi
            [ "${'$'}ROLLBACK_APK_DIGEST" = "${'$'}CURRENT_APK_DIGEST" ] && [ "${'$'}ROLLBACK_VERSION_CODE" = "${'$'}CURRENT_VERSION_CODE" ] || {
              uclone_transaction_recovery_required
              echo "ERR_TRANSACTION_APK_MISMATCH" >&2
              exit 91
            }
            TARGET_GATE_DIR="${'$'}UCLONE_TXN_DIR/gates/target_${'$'}UCLONE_TXN_TARGET_USER"
            uclone_recovery_gate_ensure_held "${'$'}TARGET_GATE_DIR" "${'$'}UCLONE_TXN_TARGET_USER" "${'$'}TARGET_UID" || { uclone_transaction_recovery_required; echo "ERR_TRANSACTION_TARGET_GATE" >&2; exit 91; }
            uclone_transaction_stage ROLLING_BACK || exit 91
            uclone_stage_begin AUTO_ROLLBACK
            if uclone_transaction_part_requires_recovery ce; then
              recovery_restore_part ce "/data/user/${'$'}UCLONE_TXN_TARGET_USER/${'$'}PKG" app || { uclone_transaction_recovery_required; exit 91; }
            fi
            if uclone_transaction_part_requires_recovery de; then
              recovery_restore_part de "/data/user_de/${'$'}UCLONE_TXN_TARGET_USER/${'$'}PKG" app || { uclone_transaction_recovery_required; exit 91; }
            fi
            if uclone_transaction_part_requires_recovery external; then
              recovery_restore_part external "/data/media/${'$'}UCLONE_TXN_TARGET_USER/Android/data/${'$'}PKG" media || { uclone_transaction_recovery_required; exit 91; }
            fi
            if uclone_transaction_part_requires_recovery media; then
              recovery_restore_part media "/data/media/${'$'}UCLONE_TXN_TARGET_USER/Android/media/${'$'}PKG" media || { uclone_transaction_recovery_required; exit 91; }
            fi
            if uclone_transaction_part_requires_recovery obb; then
              recovery_restore_part obb "/data/media/${'$'}UCLONE_TXN_TARGET_USER/Android/obb/${'$'}PKG" obb || { uclone_transaction_recovery_required; exit 91; }
            fi
            if uclone_transaction_part_requires_recovery permissions; then
              [ -d "${'$'}ROLLBACK/permissions" ] || { uclone_transaction_recovery_required; echo "ERR_TRANSACTION_PERMISSION_ROLLBACK_MISSING" >&2; exit 91; }
              uclone_restore_permission_state "${'$'}ROLLBACK/permissions" "${'$'}UCLONE_TXN_TARGET_USER" EXACT || { uclone_transaction_recovery_required; exit 91; }
            fi
            uclone_restore_switch_marker || { uclone_transaction_recovery_required; exit 91; }
            sync
            /system/bin/am force-stop --user "${'$'}UCLONE_TXN_TARGET_USER" "${'$'}PKG" >/dev/null 2>&1 || { uclone_transaction_recovery_required; exit 91; }
            uclone_transaction_rolled_back || exit 91
            uclone_recovery_release_all_gates || { uclone_transaction_recovery_required; exit 92; }
            uclone_transaction_finish_rolled_back || exit 92
            echo "TRANSACTION_RECOVERY_ROLLED_BACK=${'$'}RECOVERY_TRANSACTION_ID rollback=${'$'}ROLLBACK"
        """.trimIndent()
    }

    private val SAFE_ID = Regex("[A-Za-z0-9_.-]{1,128}")
}
