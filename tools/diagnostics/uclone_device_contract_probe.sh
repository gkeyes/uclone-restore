#!/system/bin/sh

set +e

PKG="${1:-com.xingin.xhs}"
case "$PKG" in
  ''|*[!A-Za-z0-9_.]*)
    echo "INVALID_PACKAGE=$PKG"
    exit 2
    ;;
esac

TMP="/data/local/tmp/uclone_contract_probe.$$"
case "$TMP" in
  /data/local/tmp/uclone_contract_probe.*) ;;
  *) echo "INVALID_TEMP=$TMP"; exit 2 ;;
esac

umask 077
mkdir -p "$TMP" || exit 3
cleanup() {
  case "$TMP" in
    /data/local/tmp/uclone_contract_probe.*) rm -rf "$TMP" ;;
  esac
}
trap cleanup EXIT HUP INT TERM

section() {
  printf '\n===== %s =====\n' "$1"
}

line_count() {
  wc -l < "$1" 2>/dev/null | tr -d ' '
}

normalize_appops() {
  awk '
    {
      line=$0
      sub(/^[[:space:]]*/, "", line)
      sub(/^Uid mode:[[:space:]]*/, "", line)
      if (line ~ /^[A-Z0-9_()]+:[[:space:]]*(allow|ignore|deny|default|foreground|ask)(;|[[:space:]]|$)/) {
        op=line
        sub(/:.*/, "", op)
        mode=line
        sub(/^[^:]*:[[:space:]]*/, "", mode)
        sub(/[;[:space:]].*/, "", mode)
        print op " " mode
      }
    }
  ' "$1"
}

appops_unique() {
  awk '
    NF != 2 { invalid=1; next }
    seen[$1]++ { invalid=1 }
    END { exit invalid ? 1 : 0 }
  ' "$1"
}

appops_uid_prefix_matches() {
  awk '
    FILENAME == ARGV[1] { expected[++expected_count]=$0; next }
    {
      actual_count++
      if (actual_count <= expected_count && $0 != expected[actual_count]) mismatch=1
    }
    END {
      if (actual_count < expected_count) mismatch=1
      exit mismatch ? 1 : 0
    }
  ' "$1" "$2"
}

section "PROBE"
echo "PACKAGE=$PKG"
echo "START_EPOCH=$(date +%s 2>/dev/null)"
echo "ROOT_ID=$(id 2>&1)"
echo "ANDROID_RELEASE=$(getprop ro.build.version.release 2>/dev/null)"
echo "ANDROID_SDK=$(getprop ro.build.version.sdk 2>/dev/null)"
echo "BUILD=$(getprop ro.build.fingerprint 2>/dev/null)"

section "UCLONE_PACKAGES"
for APP in com.uclone.restore com.uclone.restore.module; do
  APP_DUMP="$TMP/${APP}.dump"
  dumpsys package "$APP" > "$APP_DUMP" 2>&1
  APP_RC=$?
  echo "APP=$APP DUMPSYS_EXIT=$APP_RC"
  sed -n '/versionCode=/p;/versionName=/p' "$APP_DUMP" | sed -n '1,4p'
done

section "COMMANDS"
for COMMAND_NAME in am cmd dumpsys pm tar toybox awk sed sort stat readlink restorecon find du; do
  COMMAND_PATH=$(command -v "$COMMAND_NAME" 2>/dev/null)
  echo "COMMAND=$COMMAND_NAME PATH=${COMMAND_PATH:-NOT_FOUND}"
done

section "USERS"
echo "USER_LIST_COMMAND=cmd user list"
cmd user list 2>&1
echo "CMD_USER_LIST_EXIT=$?"
echo "USER_LIST_COMMAND=pm list users"
pm list users 2>&1
echo "PM_USER_LIST_EXIT=$?"

section "CLONE_STATE"
I=1
while [ "$I" -le 5 ]; do
  STATE_FILE="$TMP/clone_state_$I"
  am get-started-user-state 10 > "$STATE_FILE" 2>&1
  STATE_RC=$?
  STATE_BYTES=$(wc -c < "$STATE_FILE" 2>/dev/null | tr -d ' ')
  STATE_VALUE=$(sed -n '1p' "$STATE_FILE" | tr -d '\r')
  echo "STATE_SAMPLE=$I EXIT=$STATE_RC BYTES=${STATE_BYTES:-0} VALUE=$STATE_VALUE"
  I=$((I + 1))
done

section "PACKAGE_AND_UID"
for USER_ID in 0 10; do
  PACKAGE_FILE="$TMP/packages_$USER_ID"
  PACKAGE_UID_FILE="$TMP/packages_uid_$USER_ID"
  cmd package list packages --user "$USER_ID" "$PKG" > "$PACKAGE_FILE" 2>&1
  PACKAGE_RC=$?
  PACKAGE_LINE=$(grep -F -x "package:$PKG" "$PACKAGE_FILE" | sed -n '1p')
  echo "USER=$USER_ID PACKAGE_EXIT=$PACKAGE_RC EXACT=${PACKAGE_LINE:-NOT_FOUND}"

  cmd package list packages -U --user "$USER_ID" "$PKG" > "$PACKAGE_UID_FILE" 2>&1
  PACKAGE_UID_RC=$?
  PACKAGE_UID_LINE=$(awk -v p="package:$PKG" '$1 == p { print; exit }' "$PACKAGE_UID_FILE")
  echo "USER=$USER_ID PACKAGE_UID_EXIT=$PACKAGE_UID_RC UID_LINE=${PACKAGE_UID_LINE:-NOT_FOUND}"
  PACKAGE_UID=$(printf '%s\n' "$PACKAGE_UID_LINE" | sed -n 's/.* uid:\([0-9][0-9]*\).*/\1/p')
  printf '%s\n' "$PACKAGE_UID" > "$TMP/package_uid_value_$USER_ID"
done

section "FILTERED_MISSING_PACKAGE"
MISSING_PACKAGE="com.uclone.contract.probe.missingp$$"
for USER_ID in 0 10; do
  MISSING_FILE="$TMP/missing_$USER_ID"
  cmd package list packages --user "$USER_ID" "$MISSING_PACKAGE" > "$MISSING_FILE" 2>&1
  MISSING_RC=$?
  MISSING_BYTES=$(wc -c < "$MISSING_FILE" 2>/dev/null | tr -d ' ')
  MISSING_VALUE=$(sed -n '1,3p' "$MISSING_FILE" | tr '\n' '|')
  echo "USER=$USER_ID EXIT=$MISSING_RC BYTES=${MISSING_BYTES:-0} VALUE=${MISSING_VALUE:-EMPTY}"
done

section "DUMPSYS_PERMISSION_CAPTURE"
PACKAGE_DUMP="$TMP/package.dump"
dumpsys package "$PKG" > "$PACKAGE_DUMP" 2> "$TMP/package.err"
DUMPSYS_RC=$?
echo "DUMPSYS_EXIT=$DUMPSYS_RC RAW_LINES=$(line_count "$PACKAGE_DUMP") STDERR_LINES=$(line_count "$TMP/package.err")"
sed -n '1,5p' "$TMP/package.err"

for USER_ID in 0 10; do
  awk -v user="$USER_ID" '
    $0 ~ "^[[:space:]]*User " user ":" { in_user=1; found_user=1; next }
    $0 ~ "^[[:space:]]*User [0-9]+:" { in_user=0 }
    in_user && $0 ~ "^[[:space:]]*runtime permissions:" { found_runtime=1 }
    END { exit(found_user && found_runtime ? 0 : 1) }
  ' "$PACKAGE_DUMP"
  BLOCK_RC=$?

  RUNTIME_FILE="$TMP/runtime_$USER_ID"
  awk -v user="$USER_ID" '
    $0 ~ "^[[:space:]]*User " user ":" { in_user=1; in_runtime=0; next }
    $0 ~ "^[[:space:]]*User [0-9]+:" { in_user=0; in_runtime=0 }
    in_user && $0 ~ "^[[:space:]]*runtime permissions:" { in_runtime=1; next }
    in_runtime && substr($0, 1, 8) != "        " { in_runtime=0 }
    in_runtime && $0 ~ "^[[:space:]]*[A-Za-z][A-Za-z0-9_.]*:" {
      name=$1; sub(":", "", name); if ($0 ~ "granted=true") print name
    }
  ' "$PACKAGE_DUMP" | sort -u > "$RUNTIME_FILE"
  PARSE_RC=$?
  echo "USER=$USER_ID RUNTIME_BLOCK_EXIT=$BLOCK_RC PARSE_EXIT=$PARSE_RC GRANTS=$(line_count "$RUNTIME_FILE")"
  sed -n '1,10p' "$RUNTIME_FILE"
done

section "APPOPS_CAPTURE"
for USER_ID in 0 10; do
  APPOPS_RAW="$TMP/appops_$USER_ID.raw"
  APPOPS_ERR="$TMP/appops_$USER_ID.err"
  APPOPS_PARSED="$TMP/appops_$USER_ID.parsed"
  cmd appops get --user "$USER_ID" "$PKG" > "$APPOPS_RAW" 2> "$APPOPS_ERR"
  APPOPS_RC=$?
  awk '
    /^[A-Z0-9_()]+: (allow|ignore|deny|default|foreground|ask)/ {
      op=$1; sub(":", "", op); mode=$2; sub(";", "", mode); print op " " mode
    }
  ' "$APPOPS_RAW" | sort -u > "$APPOPS_PARSED"
  APPOPS_PARSE_RC=$?
  echo "USER=$USER_ID APPOPS_EXIT=$APPOPS_RC RAW_LINES=$(line_count "$APPOPS_RAW") STDERR_LINES=$(line_count "$APPOPS_ERR") PARSE_EXIT=$APPOPS_PARSE_RC PARSED_LINES=$(line_count "$APPOPS_PARSED")"
  sed -n '1,5p' "$APPOPS_ERR"
  sed -n '1,12p' "$APPOPS_PARSED"

  PACKAGE_UID=$(sed -n '1p' "$TMP/package_uid_value_$USER_ID")
  if [ -n "$PACKAGE_UID" ]; then
    APPOPS_UID_RAW="$TMP/appops_uid_$USER_ID.raw"
    APPOPS_ALL_NORMALIZED="$TMP/appops_all_$USER_ID.normalized"
    APPOPS_UID_NORMALIZED="$TMP/appops_uid_$USER_ID.normalized"
    APPOPS_PACKAGE_NORMALIZED="$TMP/appops_package_$USER_ID.normalized"
    cmd appops get --user "$USER_ID" "$PACKAGE_UID" > "$APPOPS_UID_RAW" 2>&1
    APPOPS_UID_RC=$?
    echo "USER=$USER_ID UID=$PACKAGE_UID UID_APPOPS_EXIT=$APPOPS_UID_RC UID_APPOPS_LINES=$(line_count "$APPOPS_UID_RAW")"
    sed -n '1,12p' "$APPOPS_UID_RAW"
    normalize_appops "$APPOPS_RAW" > "$APPOPS_ALL_NORMALIZED"
    APPOPS_ALL_NORMALIZE_RC=$?
    normalize_appops "$APPOPS_UID_RAW" > "$APPOPS_UID_NORMALIZED"
    APPOPS_UID_NORMALIZE_RC=$?
    APPOPS_UID_COUNT=$(line_count "$APPOPS_UID_NORMALIZED")
    appops_uid_prefix_matches "$APPOPS_UID_NORMALIZED" "$APPOPS_ALL_NORMALIZED"
    APPOPS_PREFIX_RC=$?
    awk -v skip="$APPOPS_UID_COUNT" 'NR > skip { print }' "$APPOPS_ALL_NORMALIZED" > "$APPOPS_PACKAGE_NORMALIZED"
    APPOPS_TAIL_RC=$?
    appops_unique "$APPOPS_UID_NORMALIZED"
    APPOPS_UID_UNIQUE_RC=$?
    appops_unique "$APPOPS_PACKAGE_NORMALIZED"
    APPOPS_PACKAGE_UNIQUE_RC=$?
    echo "USER=$USER_ID APPOPS_CONTRACT allNormalize=$APPOPS_ALL_NORMALIZE_RC uidNormalize=$APPOPS_UID_NORMALIZE_RC prefix=$APPOPS_PREFIX_RC tail=$APPOPS_TAIL_RC uidUnique=$APPOPS_UID_UNIQUE_RC packageUnique=$APPOPS_PACKAGE_UNIQUE_RC uidEntries=$APPOPS_UID_COUNT packageEntries=$(line_count "$APPOPS_PACKAGE_NORMALIZED")"
    sed -n '1,12p' "$APPOPS_PACKAGE_NORMALIZED"
  else
    echo "USER=$USER_ID UID_APPOPS=SKIPPED_NO_UID"
  fi
done

section "APP_DATA_METADATA"
for USER_ID in 0 10; do
  for PATH_VALUE in \
    "/data/user/$USER_ID/$PKG" \
    "/data/user/$USER_ID/$PKG/cache" \
    "/data/user/$USER_ID/$PKG/code_cache" \
    "/data/user_de/$USER_ID/$PKG" \
    "/data/media/$USER_ID/Android/data/$PKG" \
    "/data/media/$USER_ID/Android/media/$PKG" \
    "/data/media/$USER_ID/Android/obb/$PKG"
  do
    if [ -e "$PATH_VALUE" ] || [ -L "$PATH_VALUE" ]; then
      printf 'USER=%s PATH=%s ' "$USER_ID" "$PATH_VALUE"
      stat -c 'OWNER=%u:%g MODE=%a' "$PATH_VALUE" 2>&1
      ls -Zd "$PATH_VALUE" 2>&1 | sed 's/^/CONTEXT=/'
    else
      echo "USER=$USER_ID PATH=$PATH_VALUE STATE=MISSING"
    fi
  done
done

section "TAR_OWNER_SEMANTICS"
mkdir -p "$TMP/tar_source" "$TMP/tar_workspace" "$TMP/tar_app"
printf '%s\n' probe > "$TMP/tar_source/file"
chown 10332:10332 "$TMP/tar_source/file"
SOURCE_OWNER=$(stat -c '%u:%g' "$TMP/tar_source/file" 2>&1)
(cd "$TMP/tar_source" && tar -cpf "$TMP/probe.tar" .)
TAR_CREATE_RC=$?
(cd "$TMP/tar_workspace" && tar -xopf "$TMP/probe.tar")
TAR_WORKSPACE_RC=$?
(cd "$TMP/tar_app" && tar -xpf "$TMP/probe.tar")
TAR_APP_RC=$?
WORKSPACE_OWNER=$(stat -c '%u:%g' "$TMP/tar_workspace/file" 2>&1)
APP_OWNER=$(stat -c '%u:%g' "$TMP/tar_app/file" 2>&1)
echo "SOURCE_OWNER=$SOURCE_OWNER CREATE_EXIT=$TAR_CREATE_RC"
echo "WORKSPACE_XOPF_EXIT=$TAR_WORKSPACE_RC OWNER=$WORKSPACE_OWNER EXPECTED=0:0"
echo "APP_XPF_EXIT=$TAR_APP_RC OWNER=$APP_OWNER EXPECTED=10332:10332"

section "FIND_QUIT_SEMANTICS"
mkdir -p "$TMP/find_quit"
printf '%s\n' first > "$TMP/find_quit/first"
printf '%s\n' second > "$TMP/find_quit/second"
FIND_QUIT_OUTPUT=$(find "$TMP/find_quit" -mindepth 1 -print -quit 2>&1)
FIND_QUIT_RC=$?
FIND_QUIT_LINES=$(printf '%s\n' "$FIND_QUIT_OUTPUT" | awk 'NF { count++ } END { print count + 0 }')
echo "FIND_QUIT_EXIT=$FIND_QUIT_RC LINES=$FIND_QUIT_LINES EXPECTED_LINES=1"
printf '%s\n' "$FIND_QUIT_OUTPUT" | sed -n '1,3p'

section "LIVE_SOURCE_SIZE_SEMANTICS"
mkdir -p "$TMP/live_source_real"
dd if=/dev/zero of="$TMP/live_source_real/payload" bs=1024 count=4 >/dev/null 2>&1
ln -s "$TMP/live_source_real" "$TMP/live_source_link"
LIVE_SOURCE_REAL=$(readlink -f "$TMP/live_source_link" 2>/dev/null)
LIVE_SOURCE_READLINK_RC=$?
LIVE_SOURCE_DU_OUTPUT=""
LIVE_SOURCE_DU_RC=1
if [ "$LIVE_SOURCE_READLINK_RC" -eq 0 ] && [ -n "$LIVE_SOURCE_REAL" ]; then
  LIVE_SOURCE_DU_OUTPUT=$(du -sk "$LIVE_SOURCE_REAL" 2>/dev/null)
  LIVE_SOURCE_DU_RC=$?
fi
LIVE_SOURCE_KB=$(printf '%s\n' "$LIVE_SOURCE_DU_OUTPUT" | awk 'NR == 1 { print $1 } END { if (NR != 1) exit 1 }')
LIVE_SOURCE_PARSE_RC=$?
case "$LIVE_SOURCE_KB" in ''|0|*[!0-9]*) LIVE_SOURCE_SIZE_VALID=0 ;; *) LIVE_SOURCE_SIZE_VALID=1 ;; esac
echo "LIVE_SOURCE_READLINK_EXIT=$LIVE_SOURCE_READLINK_RC LIVE_SOURCE_DU_EXIT=$LIVE_SOURCE_DU_RC LIVE_SOURCE_PARSE_EXIT=$LIVE_SOURCE_PARSE_RC SIZE_KB=${LIVE_SOURCE_KB:-0} SIZE_VALID=$LIVE_SOURCE_SIZE_VALID"

section "WORKSPACE_TOP_LEVEL"
if [ -e /data/adb/uclone ] || [ -L /data/adb/uclone ]; then
  echo "WORKSPACE_REAL=$(readlink -f /data/adb/uclone 2>&1)"
  ls -ldZ /data/adb/uclone 2>&1
  for NAME in snapshots rollback clone_rollback switches tmp logs audit config locks; do
    [ -e "/data/adb/uclone/$NAME" ] || [ -L "/data/adb/uclone/$NAME" ] || continue
    ls -ldZ "/data/adb/uclone/$NAME" 2>&1
  done
else
  echo "WORKSPACE=NOT_FOUND"
fi

section "PROBE_DONE"
echo "END_EPOCH=$(date +%s 2>/dev/null)"
echo "RESULT=COMPLETE"
