# External Action Protocol

UClone Restore exposes a protected service for a future launcher/Xposed/LSPosed module.

The module should only inject launcher long-press menu items and forward the target package name to UClone. It must not run root copy/restore logic itself.

Important process boundary:

- Hook code that runs inside `com.miui.home` or `com.android.launcher3` is running as the launcher process.
- That hook code must not directly call UClone's signature-protected service.
- Hook code must not read the module's private `DataStore`, `SharedPreferences`, or Room database directly.
- The module provider must create the `PendingIntent`; the launcher only sends that token and never constructs the UClone intent itself.

## Permission

UClone declares:

```text
com.uclone.restore.permission.CONTROL
```

The permission is `signature` level. The module must be signed with the same release key and request this permission.

The `PendingIntent` is owned by the module APK. Sending it lets Android perform the operation with the creator's identity, so UClone's signature permission is checked against the same-signed module rather than the launcher UID.

UClone also has an in-app setting:

```text
设置 -> 模块控制 -> 允许模块控制
```

Default is off.

## Launcher Hook Call Path

Do not do this from code running in the launcher process:

```kotlin
context.startForegroundService(ucloneIntent)
```

Use this flow instead:

```text
Launcher Hook
  -> ModuleRelayProvider.queryMenuState(packageName, componentName, targetUserHandle)
  -> provider validates caller/config
  -> provider returns whether to show the menu, the label, and a module-owned PendingIntent
  -> hook invokes PendingIntent.send()
  -> Android directly starts UClone ExternalActionService as a foreground service
  -> ExternalActionService validates the request and starts the task
```

The current implementation deliberately uses `PendingIntent.getForegroundService(...)` with an explicit UClone service component:

```text
Launcher Hook
  -> ModuleRelayProvider.call(...)
  -> provider returns a PendingIntent created by the module process
  -> hook invokes pendingIntent.send()
  -> UClone ExternalActionService starts directly
```

The token is immutable, one-shot, and has a request-specific data URI. This prevents reuse after the action and prevents an old launcher menu token from silently inheriting new extras after an APK update.

Do not use a no-display Activity trampoline for the launcher hook path. Android 14+ requires sender-side background-activity-launch opt-in, and an asynchronously blocked Activity can make `PendingIntent.send()` return without ever entering relay code. A foreground-service PendingIntent sent from the visible launcher is the supported user-action path.

`ModuleRelayActivity` and `ModuleRelayService` remain non-exported legacy components for compatibility with already-issued tokens; the provider no longer issues new tokens for them.

All hook-side logic must be wrapped in `try/catch`. Missing classes, fields, methods, or menu containers should skip injection and write `HookEventLog`; they must never crash the launcher process.

## Module Relay Components

The module relay provider must be exported because it is called by the launcher process:

```xml
<provider
    android:name=".relay.ModuleRelayProvider"
    android:authorities="com.uclone.restore.module.relay"
    android:exported="true" />
```

Do not protect this provider with UClone's signature permission, because the caller is the launcher. The provider must perform its own caller check:

```text
Binder.getCallingUid()
PackageManager.getPackagesForUid(callingUid)
allowed launcher package list, for example com.miui.home
```

The legacy module relay activity and service must not be exported:

```xml
<activity
    android:name=".relay.ModuleRelayActivity"
    android:exported="false"
    android:noHistory="true"
    android:theme="@android:style/Theme.NoDisplay" />
```

```xml
<service
    android:name=".relay.ModuleRelayService"
    android:exported="false" />
```

The launcher hook can only trigger UClone through a `PendingIntent` created by the module provider. The PendingIntent creator remains the module APK UID for permission purposes.

## UClone Components

UClone exposes a protected no-display activity for explicit compatibility flows and the foreground service used by the launcher module:

```text
com.uclone.restore/.external.ExternalActionActivity
com.uclone.restore/.external.ExternalActionService
```

Both components must be `exported=true` and protected by:

```text
com.uclone.restore.permission.CONTROL
```

The permission must be `signature`; the module APK must declare `uses-permission` and be signed with the same key as UClone.

Action:

```text
com.uclone.restore.action.EXECUTE
```

Required extras:

```text
com.uclone.restore.extra.PROTOCOL_VERSION = 1
com.uclone.restore.extra.OPERATION = <operation>
com.uclone.restore.extra.PACKAGE_NAME = <target package>
com.uclone.restore.extra.REQUEST_ID = <non-empty uuid>
```

Optional extras:

```text
com.uclone.restore.extra.SOURCE = module
```

## Query Interface

The launcher module must not decide whether an app is currently in main state or clone state by reading files, switch markers, or UClone private storage.

There are two query layers.

Hook to module provider:

```text
ModuleRelayProvider.queryMenuState(packageName, componentName, targetUserHandle)
```

Recommended provider output:

```text
SHOW_MENU
MENU_LABEL
REQUEST_ID
PENDING_INTENT
REJECT_REASON
```

UClone should also expose a read-only query interface before the module shows UClone-derived dynamic labels:

```text
com.uclone.restore.action.QUERY
```

Recommended operation:

```text
QUERY_ACTION_STATE
```

Required input:

```text
com.uclone.restore.extra.PROTOCOL_VERSION = 1
com.uclone.restore.extra.PACKAGE_NAME = <target package>
```

Recommended output fields:

```text
PACKAGE_NAME
PRIMARY_ACTION
PRIMARY_LABEL
BUSY
ALREADY_RUNNING
HAS_SWITCH_MARKER
HAS_ACTIVE_SNAPSHOT
NEED_USER10_UNLOCK
RISK_LEVEL
ERROR_CODE
MESSAGE
```

The query result is only for display. UClone must re-check every guard rail when an execute request arrives.

## Operations

```text
SWITCH_OR_RESTORE
SWITCH_TO_CLONE
RESTORE_MAIN
BACKUP_DEFAULT
RESTORE_LATEST_BACKUP
PUSH_MAIN_TO_CLONE
RESTORE_LATEST_CLONE_ROLLBACK
```

Meanings:

- `SWITCH_OR_RESTORE`: UClone parses the marker value as explicit MAIN, valid CLONE return point, or UNKNOWN; it never decides direction from file existence alone.
- `SWITCH_TO_CLONE`: force switch current main app data to latest clone state.
- `RESTORE_MAIN`: restore using the current switch rollback marker.
- `BACKUP_DEFAULT`: create the default active backup using current UClone settings.
- `RESTORE_LATEST_BACKUP`: restore the active backup to the main system.
- `PUSH_MAIN_TO_CLONE`: push current main system app data to clone system.
- `RESTORE_LATEST_CLONE_ROLLBACK`: restore `clone_rollback/<pkg>/latest` back to clone system.

## Status Broadcast

UClone broadcasts status updates with:

```text
com.uclone.restore.action.STATUS
```

Extras:

```text
com.uclone.restore.extra.PROTOCOL_VERSION
com.uclone.restore.extra.OPERATION
com.uclone.restore.extra.PACKAGE_NAME
com.uclone.restore.extra.REQUEST_ID
com.uclone.restore.extra.STATUS
com.uclone.restore.extra.ERROR_CODE
com.uclone.restore.extra.MESSAGE
com.uclone.restore.extra.TASK_TYPE
```

Statuses:

```text
ACCEPTED
SUCCESS
FAILED
REJECTED
BUSY
NEED_CONFIRMATION
NEED_USER_ACTION
```

The status broadcast is also sent with `com.uclone.restore.permission.CONTROL`, so only same-signature receivers should receive it.

UClone should send the status broadcast explicitly to the module package:

```kotlin
Intent("com.uclone.restore.action.STATUS")
    .setPackage(modulePackageName)
```

Do not rely on a global implicit broadcast for module callbacks.

`ACCEPTED` only means UClone accepted the request into its execution path. `ALREADY_RUNNING` means the same `REQUEST_ID` is already active and was not submitted twice. `BUSY` means a different request owns the single execution slot. UClone does not queue either case. The module should wait for a later terminal status with the same `REQUEST_ID` after `ACCEPTED`.

## UClone-Side Guard Rails

UClone rejects the request when:

- module control is disabled;
- target package is UClone itself;
- target package is a system or updated-system app;
- target package is not visible/installed for the current user;
- another operation from either the module or the main App is already running;
- a module request is outside the published switch/backup/restore/push operation allowlist;
- the operation is unsupported or missing required extras.

User lifecycle controls, deletion, workspace reset, and diagnostics are internal main-App operations. `STOP_CLONE_USER` is an explicit user command from the Home screen; it is separate from automatic post-task cleanup. Automatic cleanup remains guarded by `CLONE_STARTED_BY_TASK=1` and never stops a user10 session that was already running before the task.

The module can still have its own per-app settings page. Those settings should only decide whether to show hook menu entries for each App. UClone remains the authority for whether the operation is allowed.

All main-App and module mutations share one `TaskCoordinator`, one persistent `TaskRepository`, and the same foreground `ExternalActionService`. The main App does not execute backup or restore work from a ViewModel coroutine. This keeps request deduplication, progress, history, notifications, and final status in one path even when the UI process was previously closed.

`SOURCE` is only diagnostic metadata. It must never be treated as authentication because any caller can forge an extra.

High-risk operations such as `PUSH_MAIN_TO_CLONE` should not be exposed by the launcher module by default. If exposed later, UClone should require an explicit confirmation path rather than allowing silent destructive overwrite from the long-press menu.

First MVP menu scope:

```text
SWITCH_OR_RESTORE only
```

The module can keep protocol placeholders for backup, restore, and push operations, but it should not expose them from the launcher long-press menu until UClone has a confirmation UI and a query-driven state model.

## Example

This example is valid from the module APK process, not from hook code running inside the launcher:

```kotlin
val intent = Intent("com.uclone.restore.action.EXECUTE")
    .setClassName("com.uclone.restore", "com.uclone.restore.external.ExternalActionService")
    .putExtra("com.uclone.restore.extra.PROTOCOL_VERSION", 1)
    .putExtra("com.uclone.restore.extra.OPERATION", "SWITCH_OR_RESTORE")
    .putExtra("com.uclone.restore.extra.PACKAGE_NAME", targetPackageName)
    .putExtra("com.uclone.restore.extra.REQUEST_ID", UUID.randomUUID().toString())
    .putExtra("com.uclone.restore.extra.SOURCE", "module")

// ModuleRelayProvider wraps this explicit intent with
// PendingIntent.getForegroundService(..., FLAG_ONE_SHOT | FLAG_IMMUTABLE).
```

Launcher hook code should call the module relay instead:

```kotlin
val result = context.contentResolver.call(
    Uri.parse("content://com.uclone.restore.module.relay"),
    "queryMenuState",
    null,
    Bundle().apply {
        putString("operation", "SWITCH_OR_RESTORE")
        putString("packageName", targetPackageName)
        putString("componentName", targetComponentName)
        putParcelable("targetUserHandle", targetUserHandle)
        putString("requestId", UUID.randomUUID().toString())
    },
)

result?.getParcelable<PendingIntent>("pendingIntent")?.send()
```

The returned PendingIntent targets `ExternalActionService` directly. It must be created by the module provider, remain immutable and one-shot, and carry a unique request ID in both its extras and data URI.
