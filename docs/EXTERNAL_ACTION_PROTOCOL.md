# External Action Protocol

UClone Restore exposes a protected service for a future launcher/Xposed/LSPosed module.

The module should only inject launcher long-press menu items and forward the target package name to UClone. It must not run root copy/restore logic itself.

Important process boundary:

- Hook code that runs inside `com.miui.home` or `com.android.launcher3` is running as the launcher process.
- That hook code must not directly call UClone's signature-protected service.
- Hook code must not read the module's private `DataStore`, `SharedPreferences`, or Room database directly.
- A module-owned relay component must receive the hook request first, then call UClone from the module APK identity.

## Permission

UClone declares:

```text
com.uclone.restore.permission.CONTROL
```

The permission is `signature` level. The module must be signed with the same release key and request this permission.

This is only sufficient when the caller is the module APK process. It is not sufficient for code injected into the launcher process, because Android permission checks see the launcher UID as the caller.

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
  -> provider returns whether to show the menu, the label, and a PendingIntent
  -> hook invokes PendingIntent.send()
  -> ModuleRelayService runs in the module APK process
  -> ModuleRelayService calls UClone ExternalActionService
```

The first version must not return a PendingIntent that targets UClone directly. The PendingIntent should target the module relay service:

```text
Launcher Hook
  -> ModuleRelayProvider.call(...)
  -> provider returns a PendingIntent created by the module process
  -> hook invokes pendingIntent.send()
  -> ModuleRelayService receives the request
  -> ModuleRelayService starts UClone ExternalActionService
```

If Android foreground-service launch restrictions block this path on a target ROM, add a UClone no-display trampoline activity behind the module service:

```text
PendingIntent -> ModuleRelayService -> UCloneExternalActionActivity(NoDisplay) -> ExternalActionService -> finish()
```

The trampoline activity must do no business logic; it should only start the foreground service and finish.

All hook-side logic must be wrapped in `try/catch`. Missing classes, fields, methods, or menu containers should skip injection and write `HookEventLog`; they must never crash the launcher process.

## Module Relay Components

The module relay provider must be exported because it is called by the launcher process:

```xml
<provider
    android:name=".relay.ModuleRelayProvider"
    android:authorities="com.uclone.module.relay"
    android:exported="true" />
```

Do not protect this provider with UClone's signature permission, because the caller is the launcher. The provider must perform its own caller check:

```text
Binder.getCallingUid()
PackageManager.getPackagesForUid(callingUid)
allowed launcher package list, for example com.miui.home
```

The module relay service should not be exported:

```xml
<service
    android:name=".relay.ModuleRelayService"
    android:exported="false" />
```

The launcher hook can only trigger this service through a `PendingIntent` created by the module provider. The service then calls UClone from the module APK UID.

## UClone Component

UClone exposes this service:

```text
com.uclone.restore/.external.ExternalActionService
```

The service must be `exported=true` and protected by:

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
```

Optional extras:

```text
com.uclone.restore.extra.REQUEST_ID = <uuid>
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

- `SWITCH_OR_RESTORE`: if a switch marker exists, restore main state; otherwise switch to clone state.
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

`ACCEPTED` only means UClone accepted the request into its execution path. The module should wait for a later `SUCCESS`, `FAILED`, `REJECTED`, `BUSY`, `NEED_CONFIRMATION`, or `NEED_USER_ACTION` status with the same `REQUEST_ID`.

## UClone-Side Guard Rails

UClone rejects the request when:

- module control is disabled;
- target package is UClone itself;
- target package is a system or updated-system app;
- target package is not visible/installed for the current user;
- another external operation is already running;
- any in-app UClone operation is already running;
- the operation is unsupported or missing required extras.

The module can still have its own per-app settings page. Those settings should only decide whether to show hook menu entries for each App. UClone remains the authority for whether the operation is allowed.

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

if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
    context.startForegroundService(intent)
} else {
    context.startService(intent)
}
```

Launcher hook code should call the module relay instead:

```kotlin
val result = context.contentResolver.call(
    Uri.parse("content://com.uclone.module.relay"),
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

The returned PendingIntent should target `ModuleRelayService`, not UClone directly.
