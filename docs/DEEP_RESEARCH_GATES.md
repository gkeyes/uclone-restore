# UClone Restore Deep Research Gates

This project is not a generic file copy tool. It is a cross-user Android/HyperOS app-data restore tool that runs only in main user `0`, reads clone user `10` through root, captures clone-state snapshots, and restores those snapshots back to user `0`.

The next engineering milestone is stability on real rooted HyperOS devices. UI polish is secondary until the gates below pass.

## Priority

| Priority | Track | Why It Blocks |
| --- | --- | --- |
| P0 | user10 unlock and CE availability | Determines whether clone login state can be read reliably. |
| P0 | restore consistency audit against Neo Backup | Determines why Neo Backup can restore some apps more reliably than UClone. |
| P1 | permissions, AppOps, and special access migration | Determines whether restored apps behave like the original app state. |
| P1 | switch and restore state machine | Prevents wrong labels, stale restore targets, and accidental overwrite. |

Engineering order:

1. Prove the P0 gates on device.
2. Harden full restore semantics.
3. Add permission/AppOps fidelity.
4. Centralize product state calculation.

Do not expand bidirectional sync, scheduled jobs, or UI-only features before the P0 gates are addressed.

## Track 1: user10 Unlock And CE Availability

### Research Goal

Confirm on target HyperOS/Android devices whether a root app running in user `0` can:

- Start user `10`.
- Detect user `10` CE availability.
- Unlock user `10` CE without switching UI.
- Or must require the user to switch to clone UI, unlock manually, then return to user `0`.

### Required Device Probes

Run all probes through `su -c` from user `0`:

```sh
am start-user 10
am get-started-user-state 10
am unlock-user 10
am get-started-user-state 10
```

States must be normalized into:

```kotlin
sealed class User10CeState {
    data object Unavailable : User10CeState()
    data object StartedLocked : User10CeState()
    data object RunningUnlocked : User10CeState()
    data object NotStarted : User10CeState()
    data class Unknown(val raw: String) : User10CeState()
}
```

### Engineering Gate

- CE snapshot capture is allowed only when user `10` is `RUNNING_UNLOCKED`.
- If user `10` is locked, CE paths must not be copied and the task must fail before writing a success marker.
- `am unlock-user 10` must be treated as best-effort only. It cannot be assumed to unlock CE when the clone user has a PIN, pattern, or password.
- UI must provide:
  - `启动分身`
  - `切换到分身解锁`
  - `我已解锁，重新检测`

### Acceptance Evidence

For at least one test app, collect logs showing:

- `am get-started-user-state 10` before start.
- State observed by polling after non-blocking `am start-user 10`. Do not use `-w`: HyperOS may already reach `RUNNING_LOCKED` while the waiting command remains blocked until its internal timeout.
- State after `am unlock-user 10`.
- Whether `/data/user/10/<pkg>` is readable.
- Whether `/data/user_de/10/<pkg>` is readable.

Outcomes:

- `PASS_USER10_CE_UNLOCKED`: state is `RUNNING_UNLOCKED` and CE data is readable.
- `FAIL_CE_LOCKED`: state is locked and CE capture was correctly blocked.
- `WARN_UNLOCK_USER_BEST_EFFORT`: `am unlock-user` did not unlock CE, but the UI correctly routed to manual unlock.

## Track 2: Neo Backup-Level Permissions And AppOps Migration

### Research Goal

Determine which system states Neo Backup preserves and which UClone must migrate explicitly:

- Runtime permissions.
- AppOps package modes and uid modes.
- Notification permission.
- Storage/media/scoped-storage related AppOps.
- Battery optimization.
- Overlay, exact alarm, install unknown apps, usage access, accessibility, notification listener, device admin, default apps, and OEM background policies.

### Snapshot Model

```kotlin
data class PermissionSnapshot(
    val packageName: String,
    val userId: Int,
    val runtimePermissions: List<RuntimePermissionState>,
    val appOps: List<AppOpState>,
    val specialAccessHints: List<SpecialAccessHint>
)
```

Runtime permission collection candidates:

```sh
dumpsys package <pkg>
cmd package dump <pkg>
```

AppOps collection candidates:

```sh
cmd appops get --user 10 <pkg>
cmd appops get --uid <pkg>
dumpsys appops
```

Restore candidates:

```sh
pm grant --user 0 <pkg> <permission>
cmd appops set --user 0 <pkg> <OP> <MODE>
cmd appops reset --user 0 <pkg>
```

### Engineering Gate

- Runtime permissions should prefer `pm grant --user`.
- AppOps restore must distinguish package mode, uid mode, and default mode. Do not blindly replay every line as `cmd appops set --user 0 <pkg> <OP> allow`.
- Direct edits to `runtime-permissions.xml` are not allowed unless separately proven on device and followed by PackageManager state refresh validation.
- Special access states must be classified:
  - Shell-restorable.
  - Best-effort restorable.
  - Manual-required.
- Restore logs must include explicit outcomes:
  - `GRANT_OK`
  - `WARN_GRANT_FAILED`
  - `APPOPS_OK`
  - `WARN_APPOPS_FAILED`
  - `SPECIAL_ACCESS_MANUAL_REQUIRED`

### Acceptance Evidence

For one package restored by both Neo Backup and UClone, collect before/after:

- `dumpsys package <pkg>`
- `cmd package dump <pkg>`
- `cmd appops get --user 0 <pkg>`
- `cmd appops get --uid <pkg>`

Outcomes:

- `PASS_PERMISSION_MATCH`: runtime grant states match the intended snapshot.
- `WARN_PERMISSION_DIFF`: non-critical permission flags differ and are logged.
- `WARN_APPOPS_DIFF`: AppOps modes differ and are logged with op/mode/user.
- `SPECIAL_ACCESS_MANUAL_REQUIRED`: non-shell-restorable access is shown to the user.

## Track 3: Switch And Restore State Machine

### Research Goal

Define one product state machine so UI does not infer action labels from scattered local conditions.

Definitions:

- `activeSnapshot`: clone golden snapshot used for default restore.
  - `/data/adb/uclone/snapshots/<pkg>/active`
- `historySnapshot`: older clone snapshots not used by default.
  - `/data/adb/uclone/snapshots/<pkg>/history/<timestamp>`
- `rollbackBackup`: passive user0 backup created before restore, switch, or rollback.
  - `/data/adb/uclone/rollback/<pkg>/<timestamp>`
- `switchMarker`: state that a switch is active and can be restored.
  - `/data/adb/uclone/switches/<pkg>/active`
- `pendingUnlockMarker`: optional marker for a user10 manual unlock workflow.
  - `/data/adb/uclone/markers/pending_unlock_<pkg>.json`

Example pending marker:

```json
{
  "packageName": "com.example.app",
  "pendingAction": "CAPTURE_SNAPSHOT_FROM_USER10",
  "createdAt": 1783560000000,
  "sourceUser": 10,
  "targetUser": 0,
  "requireUser10Unlocked": true
}
```

### Required Resolver

```kotlin
class AppActionStateResolver {
    fun resolve(
        appEntry: AppEntry,
        activeSnapshot: SnapshotRecord?,
        latestTask: TaskRecord?,
        user10State: User10CeState,
        packageVersionState: PackageVersionState
    ): AppActionState
}
```

UI must consume `AppActionState`; it must not directly combine task status, marker status, user state, and snapshot existence.

### State Rules

| Condition | Main Action |
| --- | --- |
| No active snapshot | `建立分身快照` |
| Active snapshot exists and target app exists | `恢复到主系统` |
| user10 CE locked and capture is needed | `切换到分身解锁` |
| user0 package missing | `主系统未安装，无法恢复` |
| user10 package missing | `分身未安装，无法建立快照` |
| Task running | `查看执行进度` |
| Last restore failed | `查看失败原因 / 回滚` |
| App version mismatch | `版本不一致，确认后恢复` |
| Snapshot manifest missing | `快照损坏，不允许恢复` |

### Engineering Gate

- Task log cleanup must not alter action state.
- Deleting an active snapshot must immediately remove restore availability.
- Deleting a passive rollback must not remove an active clone snapshot.
- App reinstall or UID change must invalidate stale ownership assumptions.
- `从分身最新恢复`, `更新分身快照`, and `恢复到主系统` must remain separate operations and must not be named "sync".

### Acceptance Evidence

Unit tests must cover:

- No snapshot.
- Active snapshot present.
- user10 locked.
- package missing in user0.
- package missing in user10.
- task running.
- restore failed with rollback available.
- snapshot manifest missing.
- app version mismatch.
- switch marker present.

## Track 4: Real Device Restore Consistency Audit

### Research Goal

Explain, with evidence, why Neo Backup restores some app states more reliably than UClone.

For the same package:

- A: backup/restore with Neo Backup.
- B: snapshot/restore with UClone.

Compare the same device after each restore.

### Audit Script

Create:

```sh
audit_restore_consistency.sh <pkg> <userId>
```

Output:

```text
/data/adb/uclone/audit/<pkg>/<timestamp>/
├── file_tree_ce.txt
├── file_tree_de.txt
├── file_tree_external.txt
├── ls_lZ_ce.txt
├── package_dump.txt
├── appops_pkg.txt
├── appops_uid.txt
├── uid.txt
├── user_state.txt
├── manifest.json
└── summary.md
```

### Required Audit Dimensions

File trees:

```sh
find /data/user/0/<pkg> -maxdepth 3 -printf '%M %u %g %s %p\n'
find /data/user_de/0/<pkg> -maxdepth 3 -printf '%M %u %g %s %p\n'
find /data/media/0/Android/data/<pkg> -maxdepth 3 -printf '%M %u %g %s %p\n'
```

Ownership:

```sh
cmd package list packages -U --user 0 | grep <pkg>
ls -ln /data/user/0/<pkg>
```

SELinux:

```sh
ls -lZ /data/user/0/<pkg>
restorecon -RF /data/user/0/<pkg>
ls -lZ /data/user/0/<pkg>
```

Package and AppOps:

```sh
dumpsys package <pkg>
cmd package dump <pkg>
cmd appops get --user 0 <pkg>
cmd appops get --uid <pkg>
dumpsys appops
```

Process state:

- Whether source app was force-stopped before backup.
- Whether target app was force-stopped before restore.
- Whether target app was force-stopped after restore.

Directory restore policy:

- Whether target directory is deleted first or merged.
- Whether `no_backup` is preserved.
- Whether `cache` and `code_cache` are excluded.
- Whether `app_webview` is preserved.

### Engineering Gate

Audit summary must classify findings:

- `PASS`
- `WARN_FILE_DIFF`
- `WARN_PERMISSION_DIFF`
- `WARN_APPOPS_DIFF`
- `FAIL_UID_OWNER`
- `FAIL_SELINUX`
- `FAIL_CE_LOCKED`
- `FAIL_PACKAGE_MISSING`

No restore workflow should be called stable until the audit explains differences between Neo Backup and UClone for at least one low-risk test app.

## Global Non-Negotiables

- All root commands go through `su -c`.
- Every root command logs stdout, stderr, and exit code.
- Do not depend on root inside user10.
- Do not copy `/data/misc/keystore`.
- Do not create large single archive files as the normal snapshot format.
- Always back up user0 before overwriting user0 data.
- After restore, chown restored files to the user0 app UID and run `restorecon -RF`.
- If user10 is not `RUNNING_UNLOCKED`, CE capture is blocked.
- Deleting files on a rooted device requires explicit user approval.

## Deliverables Before Next Feature Expansion

- `User10CeState` parser and gate.
- Device probe logs for user10 state transitions.
- Permission/AppOps snapshot data model and replay logs.
- `AppActionStateResolver` with unit tests.
- `audit_restore_consistency.sh`.
- One Neo Backup vs UClone audit bundle for a low-risk app.
- A short `summary.md` explaining every WARN/FAIL in the audit.
