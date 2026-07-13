package com.uclone.restore.sync

internal object BackupIntegrityShell {
    fun functions(): String = """
        uclone_sha256_stream() {
          if command -v sha256sum >/dev/null 2>&1; then
            sha256sum
          elif command -v toybox >/dev/null 2>&1; then
            toybox sha256sum
          else
            return 127
          fi
        }
        uclone_require_canonical_backup_directory() {
          BACKUP_DIRECTORY="${'$'}1"
          [ -d "${'$'}BACKUP_DIRECTORY" ] && [ ! -L "${'$'}BACKUP_DIRECTORY" ] || return 1
          BACKUP_DIRECTORY_REAL=${'$'}(readlink -f "${'$'}BACKUP_DIRECTORY" 2>/dev/null || true)
          [ "${'$'}BACKUP_DIRECTORY_REAL" = "${'$'}BACKUP_DIRECTORY" ]
        }
        uclone_require_canonical_backup_file() {
          BACKUP_FILE="${'$'}1"
          [ -f "${'$'}BACKUP_FILE" ] && [ ! -L "${'$'}BACKUP_FILE" ] || return 1
          BACKUP_FILE_REAL=${'$'}(readlink -f "${'$'}BACKUP_FILE" 2>/dev/null || true)
          [ "${'$'}BACKUP_FILE_REAL" = "${'$'}BACKUP_FILE" ]
        }
        uclone_expected_signing_certificate_sha256() {
          SIGNING_CERTIFICATE_SET="${'$'}{UCLONE_EXPECTED_SIGNING_CERTIFICATE_SHA256:-}"
          case "${'$'}SIGNING_CERTIFICATE_SET" in
            ''|,*|*,|*,,*|*[!0-9a-f,]*) return 1 ;;
          esac
          SIGNING_CERTIFICATE_OLD_IFS="${'$'}IFS"
          IFS=,
          for SIGNING_CERTIFICATE_DIGEST in ${'$'}SIGNING_CERTIFICATE_SET; do
            [ "${'$'}{#SIGNING_CERTIFICATE_DIGEST}" -eq 64 ] || { IFS="${'$'}SIGNING_CERTIFICATE_OLD_IFS"; return 1; }
          done
          IFS="${'$'}SIGNING_CERTIFICATE_OLD_IFS"
          echo "${'$'}SIGNING_CERTIFICATE_SET"
        }
        UCLONE_SIGNING_CERTIFICATE_SHA256=${'$'}(uclone_expected_signing_certificate_sha256) || {
          echo "ERR_PACKAGE_SIGNING_CERTIFICATE_UNAVAILABLE" >&2
          exit 52
        }
        uclone_package_code_digest() {
          PACKAGE_DIGEST_USER="${'$'}1"
          PACKAGE_DIGEST_NAME="${'$'}2"
          PACKAGE_PATH_OUTPUT=${'$'}(/system/bin/cmd package path --user "${'$'}PACKAGE_DIGEST_USER" "${'$'}PACKAGE_DIGEST_NAME" 2>/dev/null ||
            /system/bin/pm path --user "${'$'}PACKAGE_DIGEST_USER" "${'$'}PACKAGE_DIGEST_NAME" 2>/dev/null) || return 1
          [ -n "${'$'}PACKAGE_PATH_OUTPUT" ] || return 1
          PACKAGE_CODE_DIGEST=${'$'}(
            printf '%s\n' "${'$'}PACKAGE_PATH_OUTPUT" |
              sed -n 's/^package://p' |
              LC_ALL=C sort |
              while IFS= read -r PACKAGE_APK_PATH; do
                [ -f "${'$'}PACKAGE_APK_PATH" ] || exit 1
                PACKAGE_APK_SHA=${'$'}(uclone_sha256_stream < "${'$'}PACKAGE_APK_PATH" | awk '{print ${'$'}1}') || exit 1
                PACKAGE_APK_NAME=${'$'}(basename "${'$'}PACKAGE_APK_PATH") || exit 1
                printf '%s  %s\n' "${'$'}PACKAGE_APK_SHA" "${'$'}PACKAGE_APK_NAME"
              done |
              uclone_sha256_stream |
              awk '{print ${'$'}1}'
          ) || return 1
          case "${'$'}PACKAGE_CODE_DIGEST" in ''|*[!0-9a-fA-F]*) return 1 ;; esac
          [ "${'$'}{#PACKAGE_CODE_DIGEST}" -eq 64 ] || return 1
          echo "${'$'}PACKAGE_CODE_DIGEST"
        }
        uclone_package_version_code() {
          PACKAGE_VERSION_NAME="${'$'}1"
          PACKAGE_VERSION_CODE=${'$'}(/system/bin/dumpsys package "${'$'}PACKAGE_VERSION_NAME" 2>/dev/null |
            sed -n 's/.*versionCode=\([0-9][0-9]*\).*/\1/p' | head -1)
          case "${'$'}PACKAGE_VERSION_CODE" in ''|*[!0-9]*) return 1 ;; esac
          echo "${'$'}PACKAGE_VERSION_CODE"
        }
        uclone_package_version_name() {
          PACKAGE_VERSION_NAME_PACKAGE="${'$'}1"
          PACKAGE_VERSION_NAME_VALUE=${'$'}(/system/bin/dumpsys package "${'$'}PACKAGE_VERSION_NAME_PACKAGE" 2>/dev/null |
            awk -F= '/^[[:space:]]*versionName=/ { sub(/^[^=]*=/, ""); print; exit }' | tr -d '\r')
          [ -n "${'$'}PACKAGE_VERSION_NAME_VALUE" ] && [ "${'$'}PACKAGE_VERSION_NAME_VALUE" != "null" ] || return 1
          if printf '%s' "${'$'}PACKAGE_VERSION_NAME_VALUE" | LC_ALL=C grep '[[:cntrl:]]' >/dev/null 2>&1; then
            return 1
          fi
          echo "${'$'}PACKAGE_VERSION_NAME_VALUE"
        }
        uclone_measure_part() {
          MEASURE_STATE="${'$'}1"
          MEASURE_PAYLOAD="${'$'}2"
          UCLONE_META_ITEMS=0
          UCLONE_META_BYTES=0
          UCLONE_META_DIGEST=""
          case "${'$'}MEASURE_STATE" in
            excluded|absent|empty)
              UCLONE_META_DIGEST=${'$'}(printf '%s\n' "${'$'}MEASURE_STATE" | uclone_sha256_stream | awk '{print ${'$'}1}') || return 1
              ;;
            data)
              [ -d "${'$'}MEASURE_PAYLOAD" ] || return 1
              UCLONE_META_ITEMS=${'$'}(find "${'$'}MEASURE_PAYLOAD" -xdev -mindepth 1 -print 2>/dev/null | wc -l | tr -d ' ') || return 1
              case "${'$'}UCLONE_META_ITEMS" in ''|*[!0-9]*) return 1 ;; esac
              [ "${'$'}UCLONE_META_ITEMS" -gt 0 ] || return 1
              UCLONE_META_BYTES=${'$'}(
                find "${'$'}MEASURE_PAYLOAD" -xdev -type f -print0 2>/dev/null |
                  xargs -0 -n 500 sh -c '
                    [ "${'$'}#" -eq 0 ] || stat -c "%s" "${'$'}@" || {
                      printf "%s\n" UCLONE_STAT_BATCH_FAILED
                      exit 1
                    }
                  ' sh |
                  awk '
                    ${'$'}0 == "UCLONE_STAT_BATCH_FAILED" { failed = 1; next }
                    ${'$'}0 !~ /^[0-9]+${'$'}/ { failed = 1; next }
                    { total += ${'$'}1 }
                    END {
                      if (failed) exit 1
                      printf "%.0f\n", total + 0
                    }
                  '
              ) || return 1
              case "${'$'}UCLONE_META_BYTES" in ''|*[!0-9]*) return 1 ;; esac
              UCLONE_META_DIGEST=${'$'}(
                cd "${'$'}MEASURE_PAYLOAD" &&
                LC_ALL=C find . -xdev -mindepth 1 -print0 2>/dev/null |
                  xargs -0 -n 500 sh -c '
                    [ "${'$'}#" -eq 0 ] || stat -c "%N|%F|%s|%Y|%a" "${'$'}@" || {
                      printf "%s\n" UCLONE_STAT_BATCH_FAILED
                      exit 1
                    }
                  ' sh |
                  awk '
                    ${'$'}0 == "UCLONE_STAT_BATCH_FAILED" { failed = 1; next }
                    { print }
                    END { if (failed) exit 1 }
                  ' |
                  LC_ALL=C sort |
                  uclone_sha256_stream |
                  awk '{print ${'$'}1}'
              ) || return 1
              ;;
            *) return 1 ;;
          esac
          case "${'$'}UCLONE_META_DIGEST" in ''|*[!0-9a-fA-F]*) return 1 ;; esac
          [ "${'$'}{#UCLONE_META_DIGEST}" -eq 64 ] || return 1
          return 0
        }
        uclone_write_part_metadata() {
          META_ROOT="${'$'}1"
          META_PART="${'$'}2"
          META_STATE="${'$'}3"
          META_SECURITY_SOURCE="${'$'}{4:-}"
          case "${'$'}META_PART" in ce|de|external|media|obb) ;; *) return 1 ;; esac
          uclone_require_canonical_backup_directory "${'$'}META_ROOT" || return 1
          uclone_require_canonical_backup_directory "${'$'}META_ROOT/.state" || return 1
          uclone_require_canonical_backup_file "${'$'}META_ROOT/.state/${'$'}META_PART" || return 1
          META_PAYLOAD="${'$'}META_ROOT/${'$'}META_PART"
          case "${'$'}META_STATE" in
            data) uclone_require_canonical_backup_directory "${'$'}META_PAYLOAD" || return 1 ;;
            excluded|absent|empty) [ ! -e "${'$'}META_PAYLOAD" ] && [ ! -L "${'$'}META_PAYLOAD" ] || return 1 ;;
            *) return 1 ;;
          esac
          uclone_measure_part "${'$'}META_STATE" "${'$'}META_PAYLOAD" || return 1
          META_ROOT_MODE=""
          META_ROOT_UID=""
          META_ROOT_GID=""
          META_ROOT_CONTEXT=""
          if [ -d "${'$'}META_SECURITY_SOURCE" ]; then
            META_ROOT_MODE=${'$'}(stat -c '%a' "${'$'}META_SECURITY_SOURCE" 2>/dev/null || true)
            META_ROOT_UID=${'$'}(stat -c '%u' "${'$'}META_SECURITY_SOURCE" 2>/dev/null || true)
            META_ROOT_GID=${'$'}(stat -c '%g' "${'$'}META_SECURITY_SOURCE" 2>/dev/null || true)
            META_ROOT_CONTEXT=${'$'}(ls -Zd "${'$'}META_SECURITY_SOURCE" 2>/dev/null | awk '{print ${'$'}1; exit}')
          elif [ -f "${'$'}META_SECURITY_SOURCE" ]; then
            uclone_require_canonical_backup_file "${'$'}META_SECURITY_SOURCE" || return 1
            META_ROOT_MODE=${'$'}(uclone_meta_value "${'$'}META_SECURITY_SOURCE" rootMode)
            META_ROOT_UID=${'$'}(uclone_meta_value "${'$'}META_SECURITY_SOURCE" rootUid)
            META_ROOT_GID=${'$'}(uclone_meta_value "${'$'}META_SECURITY_SOURCE" rootGid)
            META_ROOT_CONTEXT=${'$'}(uclone_meta_value "${'$'}META_SECURITY_SOURCE" rootContext)
          fi
          case "${'$'}META_ROOT_MODE" in ''|*[!0-7]*) META_ROOT_MODE="" ;; esac
          case "${'$'}META_ROOT_UID" in ''|*[!0-9]*) META_ROOT_UID="" ;; esac
          case "${'$'}META_ROOT_GID" in ''|*[!0-9]*) META_ROOT_GID="" ;; esac
          case "${'$'}META_ROOT_CONTEXT" in u:object_r:*) ;; *) META_ROOT_CONTEXT="" ;; esac
          if [ ! -e "${'$'}META_ROOT/.meta" ] && [ ! -L "${'$'}META_ROOT/.meta" ]; then
            mkdir -p "${'$'}META_ROOT/.meta" || return 1
          fi
          uclone_require_canonical_backup_directory "${'$'}META_ROOT/.meta" || return 1
          META_PATH="${'$'}META_ROOT/.meta/${'$'}META_PART"
          META_TMP="${'$'}META_PATH.tmp.${'$'}${'$'}"
          {
            echo "schema=2"
            echo "state=${'$'}META_STATE"
            echo "items=${'$'}UCLONE_META_ITEMS"
            echo "bytes=${'$'}UCLONE_META_BYTES"
            echo "digest=${'$'}UCLONE_META_DIGEST"
            echo "rootMode=${'$'}META_ROOT_MODE"
            echo "rootUid=${'$'}META_ROOT_UID"
            echo "rootGid=${'$'}META_ROOT_GID"
            echo "rootContext=${'$'}META_ROOT_CONTEXT"
          } > "${'$'}META_TMP" || return 1
          chmod 600 "${'$'}META_TMP" || return 1
          mv -f "${'$'}META_TMP" "${'$'}META_PATH" || return 1
          echo "PART_INTEGRITY:${'$'}META_PART state=${'$'}META_STATE items=${'$'}UCLONE_META_ITEMS bytes=${'$'}UCLONE_META_BYTES digest=${'$'}UCLONE_META_DIGEST"
        }
        uclone_meta_value() {
          META_FILE="${'$'}1"
          META_KEY="${'$'}2"
          awk -F= -v key="${'$'}META_KEY" '${'$'}1 == key { sub(/^[^=]*=/, ""); print; exit }' "${'$'}META_FILE" 2>/dev/null
        }
        uclone_verify_part_metadata() {
          VERIFY_ROOT="${'$'}1"
          VERIFY_PART="${'$'}2"
          case "${'$'}VERIFY_PART" in ce|de|external|media|obb) ;; *) return 1 ;; esac
          VERIFY_STATE_FILE="${'$'}VERIFY_ROOT/.state/${'$'}VERIFY_PART"
          VERIFY_META_FILE="${'$'}VERIFY_ROOT/.meta/${'$'}VERIFY_PART"
          uclone_require_canonical_backup_directory "${'$'}VERIFY_ROOT" || return 1
          uclone_require_canonical_backup_directory "${'$'}VERIFY_ROOT/.state" || return 1
          uclone_require_canonical_backup_file "${'$'}VERIFY_STATE_FILE" || return 1
          if [ ! -e "${'$'}VERIFY_ROOT/.meta" ] && [ ! -L "${'$'}VERIFY_ROOT/.meta" ]; then return 2; fi
          uclone_require_canonical_backup_directory "${'$'}VERIFY_ROOT/.meta" || return 1
          if [ ! -e "${'$'}VERIFY_META_FILE" ] && [ ! -L "${'$'}VERIFY_META_FILE" ]; then return 2; fi
          uclone_require_canonical_backup_file "${'$'}VERIFY_META_FILE" || return 1
          VERIFY_STATE=${'$'}(sed -n '1p' "${'$'}VERIFY_STATE_FILE" | tr -d '\r')
          case "${'$'}VERIFY_STATE" in
            data) uclone_require_canonical_backup_directory "${'$'}VERIFY_ROOT/${'$'}VERIFY_PART" || return 1 ;;
            excluded|absent|empty)
              [ ! -e "${'$'}VERIFY_ROOT/${'$'}VERIFY_PART" ] && [ ! -L "${'$'}VERIFY_ROOT/${'$'}VERIFY_PART" ] || return 1
              ;;
            *) return 1 ;;
          esac
          EXPECTED_SCHEMA=${'$'}(uclone_meta_value "${'$'}VERIFY_META_FILE" schema)
          EXPECTED_STATE=${'$'}(uclone_meta_value "${'$'}VERIFY_META_FILE" state)
          EXPECTED_ITEMS=${'$'}(uclone_meta_value "${'$'}VERIFY_META_FILE" items)
          EXPECTED_BYTES=${'$'}(uclone_meta_value "${'$'}VERIFY_META_FILE" bytes)
          EXPECTED_DIGEST=${'$'}(uclone_meta_value "${'$'}VERIFY_META_FILE" digest)
          case "${'$'}EXPECTED_SCHEMA" in 1|2) ;; *) return 1 ;; esac
          [ "${'$'}EXPECTED_STATE" = "${'$'}VERIFY_STATE" ] || return 1
          case "${'$'}EXPECTED_ITEMS:${'$'}EXPECTED_BYTES" in *[!0-9:]*) return 1 ;; esac
          uclone_measure_part "${'$'}VERIFY_STATE" "${'$'}VERIFY_ROOT/${'$'}VERIFY_PART" || return 1
          [ "${'$'}EXPECTED_ITEMS" = "${'$'}UCLONE_META_ITEMS" ] || return 1
          [ "${'$'}EXPECTED_BYTES" = "${'$'}UCLONE_META_BYTES" ] || return 1
          [ "${'$'}EXPECTED_DIGEST" = "${'$'}UCLONE_META_DIGEST" ] || return 1
          echo "PART_INTEGRITY_OK:${'$'}VERIFY_PART items=${'$'}UCLONE_META_ITEMS bytes=${'$'}UCLONE_META_BYTES"
          return 0
        }
        uclone_part_root_mode() {
          PART_META_ROOT="${'$'}1"
          PART_META_NAME="${'$'}2"
          PART_META_FILE="${'$'}PART_META_ROOT/.meta/${'$'}PART_META_NAME"
          [ -f "${'$'}PART_META_FILE" ] || return 1
          PART_ROOT_MODE=${'$'}(uclone_meta_value "${'$'}PART_META_FILE" rootMode)
          case "${'$'}PART_ROOT_MODE" in ''|*[!0-7]*) return 1 ;; esac
          echo "${'$'}PART_ROOT_MODE"
        }
    """.trimIndent()
}
