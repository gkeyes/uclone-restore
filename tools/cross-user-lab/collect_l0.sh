#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
DEFAULT_OUT_ROOT="$SCRIPT_DIR/reports"
ADB_BIN="${ADB:-adb}"
SERIAL="${UCLONE_DEVICE_SERIAL:-}"
OUT_ROOT="$DEFAULT_OUT_ROOT"
MAIN_USER="${UCLONE_MAIN_USER:-0}"
TARGET_USER="${UCLONE_CLONE_USER:-10}"
PROBE_PACKAGE="${UCLONE_PROBE_PACKAGE:-com.gkeyes.uclone.crossuserprobe}"

usage() {
  cat <<'USAGE'
Usage:
  collect_l0.sh -s SERIAL [-o OUT_DIR]
  collect_l0.sh --self-test

L0 is read-only. It records repo/device/root/display/user/framework clues but does
not install apps, start/stop users, create displays, change overlays, or write to
the device.
USAGE
}

json_escape() {
  local value="$1"
  value="${value//\\/\\\\}"
  value="${value//\"/\\\"}"
  value="${value//$'\n'/\\n}"
  printf '%s' "$value"
}

sha256_text() {
  local value="$1"
  if command -v shasum >/dev/null 2>&1; then
    printf '%s' "$value" | shasum -a 256 | awk '{print $1}'
  elif command -v sha256sum >/dev/null 2>&1; then
    printf '%s' "$value" | sha256sum | awk '{print $1}'
  else
    printf 'sha256-unavailable'
  fi
}

write_json_object() {
  local path="$1"
  shift
  {
    printf '{\n'
    local first=1
    while [ "$#" -gt 0 ]; do
      local key="$1"
      local value="$2"
      shift 2
      if [ "$first" -eq 0 ]; then
        printf ',\n'
      fi
      first=0
      printf '  "%s": "%s"' "$(json_escape "$key")" "$(json_escape "$value")"
    done
    printf '\n}\n'
  } > "$path"
}

redact_serial() {
  sed "s/${SERIAL//\//\\/}/<serial>/g"
}

run_host() {
  local out="$1"
  shift
  local log_line
  log_line="$(printf '%q ' "$@")"
  printf 'HOST %s\n' "$log_line" | redact_serial >> "$COMMANDS_LOG"
  {
    set +e
    "$@" 2>&1
    local status="$?"
    set -e
    printf '\nEXIT=%s\n' "$status"
  } | redact_serial > "$out"
}

run_adb() {
  local out="$1"
  shift
  local log_line
  log_line="$(printf '%q ' "$ADB_BIN" -s "$SERIAL" "$@")"
  printf 'ADB %s\n' "$log_line" | redact_serial >> "$COMMANDS_LOG"
  {
    set +e
    "$ADB_BIN" -s "$SERIAL" "$@" 2>&1
    local status="$?"
    set -e
    printf '\nEXIT=%s\n' "$status"
  } | redact_serial > "$out"
}

run_adb_shell() {
  local out="$1"
  shift
  run_adb "$out" shell "$@"
}

capture_prop() {
  local name="$1"
  "$ADB_BIN" -s "$SERIAL" shell getprop "$name" 2>/dev/null | tr -d '\r' | tail -n 1 || true
}

self_test() {
  local escaped
  escaped="$(json_escape $'a"b\\c\nd')"
  [ "$escaped" = 'a\"b\\c\nd' ] || {
    printf 'json_escape failed: %s\n' "$escaped" >&2
    exit 1
  }
  local hash
  hash="$(sha256_text "serial")"
  [ -n "$hash" ] || {
    printf 'sha256_text returned empty\n' >&2
    exit 1
  }
  printf 'SELF_TEST_OK\n'
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --self-test)
      self_test
      exit 0
      ;;
    -s|--serial)
      SERIAL="${2:-}"
      shift 2
      ;;
    -o|--out)
      OUT_ROOT="${2:-}"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      usage >&2
      exit 64
      ;;
  esac
done

if [ -z "$SERIAL" ]; then
  printf 'ERR_SERIAL_REQUIRED: pass -s SERIAL or set UCLONE_DEVICE_SERIAL\n' >&2
  exit 64
fi

STARTED_AT="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
SERIAL_HASH="$(sha256_text "$SERIAL")"
REPORT_DIR="$OUT_ROOT/${STARTED_AT//:/-}-${SERIAL_HASH:0:12}"
mkdir -p "$REPORT_DIR/dumpsys" "$REPORT_DIR/probe" "$REPORT_DIR/logs"
COMMANDS_LOG="$REPORT_DIR/commands.log"
: > "$COMMANDS_LOG"

run_host "$REPORT_DIR/repo.txt" git -C "$REPO_ROOT" rev-parse HEAD
run_host "$REPORT_DIR/worktree.txt" git -C "$REPO_ROOT" status --short --branch

run_adb "$REPORT_DIR/adb-state.txt" get-state
run_adb_shell "$REPORT_DIR/command-availability.txt" 'for c in am cmd dumpsys getprop id logcat pm settings su; do printf "%s=" "$c"; command -v "$c" || true; done'
run_adb_shell "$REPORT_DIR/device-id.txt" id
run_adb_shell "$REPORT_DIR/root-id.txt" 'su -c id'
run_adb_shell "$REPORT_DIR/root-selinux-context.txt" 'su -c "cat /proc/self/attr/current"'
run_adb_shell "$REPORT_DIR/selinux.txt" getenforce
run_adb_shell "$REPORT_DIR/getprop.txt" getprop
run_adb_shell "$REPORT_DIR/users.txt" pm list users
run_adb_shell "$REPORT_DIR/current-user.txt" am get-current-user

run_adb_shell "$REPORT_DIR/dumpsys/activity-users.txt" dumpsys activity users
run_adb_shell "$REPORT_DIR/dumpsys/user.txt" dumpsys user
run_adb_shell "$REPORT_DIR/dumpsys/display.txt" dumpsys display
run_adb_shell "$REPORT_DIR/dumpsys/window-displays.txt" dumpsys window displays
run_adb_shell "$REPORT_DIR/dumpsys/input.txt" dumpsys input
run_adb_shell "$REPORT_DIR/dumpsys/package-android.txt" dumpsys package android

run_adb_shell "$REPORT_DIR/cmd-activity-help.txt" cmd activity help
run_adb_shell "$REPORT_DIR/cmd-display-help.txt" cmd display help
run_adb_shell "$REPORT_DIR/cmd-user-help.txt" cmd user help
run_adb_shell "$REPORT_DIR/cmd-overlay-help.txt" cmd overlay help
run_adb_shell "$REPORT_DIR/cmd-lock-settings-help.txt" cmd lock_settings help

run_adb_shell "$REPORT_DIR/logs/dmesg-tail.txt" 'su -c "dmesg | tail -n 500"'
run_adb "$REPORT_DIR/logs/logcat.txt" logcat -d -b all

FINGERPRINT="$(capture_prop ro.build.fingerprint)"
SDK_INT="$(capture_prop ro.build.version.sdk)"
RELEASE="$(capture_prop ro.build.version.release)"
SECURITY_PATCH="$(capture_prop ro.build.version.security_patch)"
PRODUCT_DEVICE="$(capture_prop ro.product.device)"
PRODUCT_MODEL="$(capture_prop ro.product.model)"
MIUI_VERSION="$(capture_prop ro.miui.ui.version.name)"
SELINUX="$(sed -n '1p' "$REPORT_DIR/selinux.txt" | tr -d '\r')"
CURRENT_USER="$(sed -n '1p' "$REPORT_DIR/current-user.txt" | tr -d '\r')"

write_json_object "$REPORT_DIR/device.json" \
  schemaVersion 1 \
  buildFingerprint "$FINGERPRINT" \
  sdkInt "$SDK_INT" \
  release "$RELEASE" \
  securityPatch "$SECURITY_PATCH" \
  productDevice "$PRODUCT_DEVICE" \
  productModel "$PRODUCT_MODEL" \
  miuiVersion "$MIUI_VERSION"

write_json_object "$REPORT_DIR/manifest.json" \
  schemaVersion 1 \
  startedAt "$STARTED_AT" \
  finishedAt "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" \
  mode L0_READ_ONLY \
  repoCommit "$(git -C "$REPO_ROOT" rev-parse HEAD)" \
  dirtyWorktree "$(git -C "$REPO_ROOT" status --short | wc -l | tr -d ' ')" \
  deviceSerialHash "$SERIAL_HASH" \
  buildFingerprint "$FINGERPRINT" \
  sdkInt "$SDK_INT" \
  selinuxMode "$SELINUX" \
  currentUser "$CURRENT_USER" \
  mainUser "$MAIN_USER" \
  targetUser "$TARGET_USER" \
  probePackage "$PROBE_PACKAGE" \
  result COLLECTED_L0_ONLY

cat > "$REPORT_DIR/summary.md" <<SUMMARY
# CrossUser Runtime L0 Summary

- Mode: L0_READ_ONLY
- Started: $STARTED_AT
- Repo: $(git -C "$REPO_ROOT" rev-parse HEAD)
- Dirty worktree files: $(git -C "$REPO_ROOT" status --short | wc -l | tr -d ' ')
- Serial hash: $SERIAL_HASH
- Build fingerprint: $FINGERPRINT
- SDK: $SDK_INT
- Release: $RELEASE
- Security patch: $SECURITY_PATCH
- SELinux: $SELINUX
- Current user: $CURRENT_USER
- Main user: $MAIN_USER
- Target user: $TARGET_USER
- Probe package: $PROBE_PACKAGE

No device mutation was requested by this script. Review commands.log and dumpsys
artifacts before moving beyond L0.
SUMMARY

(
  cd "$REPORT_DIR"
  find . -type f -print | sort | while IFS= read -r file; do
    shasum -a 256 "$file"
  done > checksums.sha256
)

printf '%s\n' "$REPORT_DIR"
