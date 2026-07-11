# UClone Restore

Android root tool for moving app data between the Xiaomi/HyperOS main user and clone user.

Current release line: `0.3.0`

- Main app: `0.3.0`
- Launcher module: `0.3.0`

On Android 15 and newer, background runtime for `dataSync` foreground services is time-limited. Starting with `0.3.0`, explicit user actions coming from the launcher module or launcher shortcuts use the declared `specialUse` foreground-service type, so a cold UClone process is not rejected before its progress notification appears after the `dataSync` budget has been exhausted. Tasks submitted from the visible main app continue to use `dataSync`. Both the main app and launcher module must be upgraded from `0.2.0` for this fix to take effect.
- Tested target: rooted Xiaomi/HyperOS multi-user environment, usually `user0` + `user10`

## What It Does

UClone Restore is built for one practical workflow: keep two Android users on the same device and move an app's usable state between them.

Supported directions in the current release:

- Pull clone data from `user10` and restore it to main user `user0`.
- Push main user `user0` data to clone user `user10`.
- Create active snapshots and restore from the latest available snapshot.
- Create rollback backups before destructive restore or switch operations.
- Restore clone rollback data when a clone-side push needs to be undone.
- Optionally copy runtime permissions and AppOps where Android allows shell-level restoration.
- Install the same existing APK for the other Android user, optionally followed by permission or data migration.

The app is not a cloud sync tool. It works locally on the rooted device and stores data under `/data/adb/uclone`.

## Architecture

Two APKs are published:

- `app-release.apk`: the main UClone Restore app.
- `launcher-module-release.apk`: an LSPosed module that adds a UClone entry to supported launcher long-press menus.

The module is only an entry point. Real root operations, backup, restore, rollback, task logging, and notifications are handled by the main UClone Restore app.

Recommended module scope:

- Enable the module for `com.miui.home` only.
- Do not add target apps to the LSPosed scope. Target apps are selected inside the module settings page.

## Requirements

- Android device with root, tested with KernelSU/KernelSU Next style root.
- Xiaomi/HyperOS clone user available as `user10`.
- UClone Restore installed in main user `user0`.
- Main app and launcher module installed from the same fixed-signed release.
- For launcher module control:
  - Enable "allow module control" in UClone Restore settings.
  - Enable the LSPosed module for `com.miui.home`.
  - Reboot or restart the launcher after module activation.

## User10 Unlock Behavior

CE data under `/data/user/10/<pkg>` is only reliable after the clone user is `RUNNING_UNLOCKED`.

UClone can attempt a silent unlock flow when configured:

1. Start clone user when needed.
2. Verify the configured PIN/password with `cmd lock_settings verify --old ... --user 10`.
3. Wait until `am get-started-user-state 10` reports `RUNNING_UNLOCKED`.
4. Run the requested data operation.
5. Stop clone user after the task when that setting is enabled.

The credential is encrypted at rest with an Android Keystore AES-GCM key and is sent to the root shell over standard input, not embedded in `su -c` arguments or logs.

If the clone user cannot be unlocked, CE snapshot or restore operations are blocked rather than silently using incomplete data.

## Storage Layout

All paths are on the Android device.

Default root directory:

```text
/data/adb/uclone
```

Important subdirectories:

```text
/data/adb/uclone/snapshots/<pkg>/active
/data/adb/uclone/snapshots/<pkg>/history/<timestamp>
/data/adb/uclone/rollback/<pkg>/<timestamp>
/data/adb/uclone/clone_rollback/<pkg>/latest
/data/adb/uclone/logs
/data/adb/uclone/tmp
```

Snapshots are not stored in `/sdcard/Documents` by default because they contain private app login-state data.

## Data Scope

Default included data:

- CE app data: `/data/user/<user>/<pkg>`
- DE app data: `/data/user_de/<user>/<pkg>`
- External app data: `/data/media/<user>/Android/data/<pkg>`

Optional data:

- `/data/media/<user>/Android/media/<pkg>`
- `/data/media/<user>/Android/obb/<pkg>`
- Runtime permissions and AppOps

Default exclusions:

- `cache`
- `code_cache`

Always excluded:

- `/data/misc/keystore`

Apps that depend on Android Keystore-backed secrets may still require a new login even when file data restores correctly.

## Main Workflows

### Switch To Clone State

Copies the latest clone-side app state to main user.

High-level flow:

1. Ensure clone user is unlocked when CE data is required.
2. Capture clone-side data into a temporary operation source.
3. Back up current main-user app data as a passive rollback.
4. Restore clone data to main user.
5. Fix UID/GID ownership and SELinux context.
6. Record a switch marker so the next action can restore the previous main state.

### Restore Main State

Uses the switch marker rollback to restore the main user's previous state.

### Push Main To Clone

Copies main-user app data into clone user.

High-level flow:

1. Ensure clone user is unlocked when CE data is required.
2. Back up current clone-user app data as clone rollback.
3. Restore main-user data into clone user.
4. Fix UID/GID ownership and SELinux context.

### Manual Backups

Manual active snapshots and passive rollback backups are shown separately in the app's data pages. Passive rollback backups are created automatically before restore/switch/push operations.

## Launcher Module

The launcher module adds a UClone action to supported launcher long-press menus.

Design constraints:

- Hook layer never performs root operations.
- Hook layer queries `ModuleRelayProvider` for menu state.
- Click actions use a module-owned foreground-service `PendingIntent` returned by `ModuleRelayProvider`.
- The `PendingIntent` starts UClone's `ExternalActionService` directly; legacy relay components are not used for new tokens.
- UClone's external service is protected by the signature permission `com.uclone.restore.permission.CONTROL`.

This keeps Launcher, module, and UClone responsibilities separated.

## Install

Install both APKs from the same release:

```bash
adb install -r app-release.apk
adb install -r launcher-module-release.apk
```

If moving from an old debug-signed build to the fixed-signed release, Android may require a one-time uninstall because debug and release signatures differ.

## Build

Local debug build:

```bash
gradle --no-daemon :app:assembleDebug
```

Release builds are produced by GitHub Actions on every push to `main`.

Release signing uses repository secrets:

- `RELEASE_KEYSTORE_BASE64`
- `RELEASE_STORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`

The CI uploads:

- `uclone-restore-release-apk`
- `uclone-launcher-module-release-apk`

## Safety Notes

- Every core command runs through `su -c`.
- Root command stdout, stderr, and exit code are logged.
- Restore operations back up the target user's current data before overwriting it.
- Delete/reset operations should be treated as destructive.
- Do not manually delete Android data directories unless you have a verified rollback path.

## Known Limits

- HyperOS and Android multi-user behavior varies by device and ROM.
- Permission/AppOps restoration is best-effort; some special access states require manual system settings.
- Android Keystore-backed app secrets cannot be cloned by file copy.
- The launcher long-press hook currently targets supported MIUI/HyperOS launcher internals and may need adjustment after launcher updates.

## Figma MVP Draft

https://www.figma.com/design/bVBjSk3xsciEOkTSXbHHNV
