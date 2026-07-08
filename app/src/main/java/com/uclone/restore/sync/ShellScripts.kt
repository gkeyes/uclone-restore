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
        SRC_USER=${settings.cloneUserId}
        TS=${'$'}(date +%Y%m%d-%H%M%S)
        BASE="${'$'}ROOT/snapshots/${'$'}PKG"
        TMP="${'$'}ROOT/tmp/capture_${'$'}{PKG}_${'$'}TS"
        [ "${'$'}PKG" != "${'$'}APP_PKG" ] || { echo "ERR_SELF_SYNC"; exit 41; }
        mkdir -p "${'$'}ROOT/snapshots" "${'$'}ROOT/rollback" "${'$'}ROOT/logs" "${'$'}ROOT/tmp" "${'$'}ROOT/config" "${'$'}BASE/history" || exit 10
        STATE=${'$'}(am get-started-user-state "${'$'}SRC_USER" 2>/dev/null || true)
        echo "USER_STATE=${'$'}STATE"
        case "${'$'}STATE" in *RUNNING_UNLOCKED*) ;; *) echo "ERR_USER_LOCKED:${'$'}STATE" >&2; exit 42;; esac
        cmd package list packages --user "${'$'}SRC_USER" | grep -qx "package:${'$'}PKG" || { echo "ERR_SOURCE_APP_MISSING"; exit 43; }
        rm -rf "${'$'}TMP"
        mkdir -p "${'$'}TMP" || exit 11
        am force-stop --user "${'$'}SRC_USER" "${'$'}PKG" >/dev/null 2>&1 || true
        copy_dir_stream() {
          SRC="${'$'}1"
          DST="${'$'}2"
          [ -d "${'$'}SRC" ] || { echo "SKIP_MISSING:${'$'}SRC"; return 0; }
          mkdir -p "${'$'}DST.tmp" || exit 12
          (cd "${'$'}SRC" && tar -cpf - .) | (cd "${'$'}DST.tmp" && tar -xpf -) || exit 13
          rm -rf "${'$'}DST"
          mv "${'$'}DST.tmp" "${'$'}DST" || exit 14
          ${if (rule.excludeCache) "rm -rf \"${'$'}DST/cache\" \"${'$'}DST/code_cache\" 2>/dev/null || true" else ":"}
          echo "COPIED:${'$'}SRC"
        }
        ${if (rule.includeCe) "copy_dir_stream \"/data/user/${settings.cloneUserId}/${'$'}PKG\" \"${'$'}TMP/ce\"" else ":"}
        ${if (rule.includeDe) "copy_dir_stream \"/data/user_de/${settings.cloneUserId}/${'$'}PKG\" \"${'$'}TMP/de\"" else ":"}
        ${if (rule.includeExternal) "copy_dir_stream \"/data/media/${settings.cloneUserId}/Android/data/${'$'}PKG\" \"${'$'}TMP/external\"" else ":"}
        ${if (rule.includeMedia) "copy_dir_stream \"/data/media/${settings.cloneUserId}/Android/media/${'$'}PKG\" \"${'$'}TMP/media\"" else ":"}
        ${if (rule.includeObb) "copy_dir_stream \"/data/media/${settings.cloneUserId}/Android/obb/${'$'}PKG\" \"${'$'}TMP/obb\"" else ":"}
        SIZE_KB=${'$'}(du -sk "${'$'}TMP" 2>/dev/null | awk '{print ${'$'}1}')
        cat > "${'$'}TMP/manifest.json" <<EOF
        {"packageName":"${packageName}","sourceUser":${settings.cloneUserId},"targetUser":${settings.mainUserId},"createdAt":"${'$'}TS","includeCe":${rule.includeCe},"includeDe":${rule.includeDe},"includeExternal":${rule.includeExternal},"includeMedia":${rule.includeMedia},"includeObb":${rule.includeObb},"includeAppWebView":${rule.includeAppWebView},"excludeCache":${rule.excludeCache},"snapshotSizeKb":"${'$'}SIZE_KB"}
        EOF
        if [ -d "${'$'}BASE/active" ]; then mv "${'$'}BASE/active" "${'$'}BASE/history/${'$'}TS" || exit 15; fi
        mv "${'$'}TMP" "${'$'}BASE/active" || exit 16
        chmod -R 700 "${'$'}BASE" >/dev/null 2>&1 || true
        echo "SNAPSHOT_ACTIVE=${'$'}BASE/active"
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
            backup_dir() {
              SRC="${'$'}1"
              DST="${'$'}2"
              [ -d "${'$'}SRC" ] || return 0
              mkdir -p "${'$'}DST" || exit 54
              (cd "${'$'}SRC" && tar -cpf - .) | (cd "${'$'}DST" && tar -xpf -) || exit 55
              echo "BACKUP:${'$'}SRC"
            }
            restore_part() {
              SNAP="${'$'}1"
              TARGET="${'$'}2"
              OWNER="${'$'}3"
              [ -d "${'$'}SNAP" ] || { echo "SKIP_PART:${'$'}SNAP"; return 0; }
              TMP="${'$'}TARGET.tmp.uclone.${'$'}TS"
              rm -rf "${'$'}TMP"
              mkdir -p "${'$'}TMP" || exit 56
              (cd "${'$'}SNAP" && tar -cpf - .) | (cd "${'$'}TMP" && tar -xpf -) || exit 57
              rm -rf "${'$'}TARGET"
              mv "${'$'}TMP" "${'$'}TARGET" || exit 58
              if [ -n "${'$'}OWNER" ]; then chown -R "${'$'}OWNER:${'$'}OWNER" "${'$'}TARGET" || exit 59; fi
              restorecon -RF "${'$'}TARGET" >/dev/null 2>&1 || restorecon -R "${'$'}TARGET" >/dev/null 2>&1 || exit 60
              echo "RESTORED:${'$'}TARGET"
            }
            backup_dir "/data/user/${settings.mainUserId}/${'$'}PKG" "${'$'}ROLLBACK/ce"
            backup_dir "/data/user_de/${settings.mainUserId}/${'$'}PKG" "${'$'}ROLLBACK/de"
            restore_part "${'$'}ACTIVE/ce" "/data/user/${settings.mainUserId}/${'$'}PKG" "${'$'}UID_VALUE"
            restore_part "${'$'}ACTIVE/de" "/data/user_de/${settings.mainUserId}/${'$'}PKG" "${'$'}UID_VALUE"
            restore_part "${'$'}ACTIVE/external" "/data/media/${settings.mainUserId}/Android/data/${'$'}PKG" ""
            restore_part "${'$'}ACTIVE/media" "/data/media/${settings.mainUserId}/Android/media/${'$'}PKG" ""
            restore_part "${'$'}ACTIVE/obb" "/data/media/${settings.mainUserId}/Android/obb/${'$'}PKG" ""
            am force-stop --user "${'$'}DST_USER" "${'$'}PKG" >/dev/null 2>&1 || true
            echo "ROLLBACK=${'$'}ROLLBACK"
        """.trimIndent()
    }
}
