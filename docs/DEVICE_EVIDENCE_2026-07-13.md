# Target Device Command Evidence - 2026-07-13

This is a sanitized record of commands run by the user on the current target phone. User names and other personal labels are omitted. It is evidence for the command contracts below, not evidence that a GitHub APK has passed a data operation.

## Device

```text
ANDROID_RELEASE=16
ANDROID_SDK=36
BUILD=Xiaomi/popsicle/popsicle:16/BP2A.250605.031.A3/OS3.0.315.2.WPBCNXM:user/release-keys
ROOT=uid=0(root) gid=0(root) groups=0(root) context=u:r:ksu:s0
```

## User discovery

```text
$ cmd user list
cmd: Failure calling service user: Failed transaction (2147483646)
EXIT=2

$ pm list users
Users:
        UserInfo{0:<redacted>:4c13} running
        UserInfo{10:security space:413} running
EXIT=0
```

Production implication: use `pm list users`; do not substitute `cmd user list` on this target.

## Clone state

Five consecutive calls returned the same result:

```text
$ am get-started-user-state 10
RUNNING_UNLOCKED
EXIT=0
```

## Filtered package queries

```text
$ cmd package list packages -U --user 0 com.xingin.xhs
package:com.xingin.xhs uid:10332
EXIT=0

$ cmd package list packages -U --user 10 com.xingin.xhs
package:com.xingin.xhs uid:1010332
EXIT=0

$ cmd package list packages --user 10 com.uclone.contract.probe.missing
<empty output>
EXIT=0
```

Production implication: package presence is an exact `package:<name>` record. Exit code zero alone does not mean present.

## AppOps scope

The package query returned a UID-scope prefix followed by package-scope entries. The numeric UID query returned only the UID-scope prefix.

Representative user0 evidence:

```text
$ cmd appops get --user 0 com.xingin.xhs
Uid mode: COARSE_LOCATION: ignore
FINE_LOCATION: ignore
...
READ_OXYGEN_SATURATION: ignore
FINE_LOCATION: allow; time=...
GPS: allow; time=...
...
EXIT=0

$ cmd appops get --user 0 10332
Uid mode: COARSE_LOCATION: ignore
FINE_LOCATION: ignore
...
READ_OXYGEN_SATURATION: ignore
EXIT=0
```

The same contract was observed for user10 with UID `1010332`.

Production implication: preserve UID-scope AppOps and migrate only a package tail that is positively identified by comparing the two outputs. If the scope cannot be proven, skip AppOps migration with a warning and continue valid file-data work.

## Tar owner behavior

```text
SOURCE_OWNER=10332:10332 CREATE_EXIT=0
WORKSPACE_XOPF_EXIT=0 OWNER=0:0 EXPECTED=0:0
APP_XPF_EXIT=0 OWNER=10332:10332 EXPECTED=10332:10332
```

Production implication: workspace extraction uses `tar -xopf`; restoration into real App storage keeps the App-target restoration path.

## Not yet proven

- `cmd package install-existing --user` success and visibility timing on this target.
- Cache, `code_cache`, external-data, and OBB owner/GID/mode/context replacement rules.
- `find <dir> -mindepth 1 -print -quit` exits successfully and returns exactly one entry on this target.
- `readlink -f` followed by `du -sk` reports a non-zero size for the real directory behind a task-created live-source symlink.
- Any rebuilt 0.3 GitHub artifact performing push, switch, restore, or launcher cold start.

These gaps must remain device-unverified. They do not authorize fallback commands, fixed retries, or metadata changes.
