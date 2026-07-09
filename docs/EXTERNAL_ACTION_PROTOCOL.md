# External Action Protocol

UClone Restore exposes a protected service for the future launcher/Xposed/LSPosed module.

The module should only inject launcher long-press menu items and forward the target package name to UClone. It must not run root copy/restore logic itself.

## Permission

UClone declares:

```text
com.uclone.restore.permission.CONTROL
```

The permission is `signature` level. The module must be signed with the same release key and request this permission.

UClone also has an in-app setting:

```text
设置 -> 模块控制 -> 允许模块控制
```

Default is off.

## Component

Start this service explicitly:

```text
com.uclone.restore/.external.ExternalActionService
```

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
```

The status broadcast is also sent with `com.uclone.restore.permission.CONTROL`, so only same-signature receivers should receive it.

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

## Example

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
