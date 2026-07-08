# UClone Restore

Android Root tool for restoring app login state from a Xiaomi/HyperOS clone user to the main user.

First version scope:

- Runs only in main system `user0`.
- Reads clone system `user10` data through root.
- Captures a clone "golden snapshot" under `/data/adb/uclone/snapshots/<pkg>/active`.
- Restores the active snapshot to main system `user0`.
- Backs up current `user0` data before every restore under `/data/adb/uclone/rollback/<pkg>/<timestamp>`.
- Repairs ownership with the target app UID and runs `restorecon`.
- Does not depend on Neo Backup or root inside the clone user.

Storage location:

- Snapshots are stored on the Android device, not on the Mac.
- Default root data directory: `/data/adb/uclone`.
- Per-app active snapshot: `/data/adb/uclone/snapshots/<pkg>/active`.
- Public storage such as `/sdcard/Documents` is not the default because snapshots contain private login-state data.

Default data range:

- CE data: `/data/user/<user>/<pkg>`
- DE data: `/data/user_de/<user>/<pkg>`
- External, media, and OBB are opt-in.
- `cache` and `code_cache` are excluded by default.
- `/data/misc/keystore` is never copied.

Build:

```bash
gradle --no-daemon :app:assembleDebug
```

GitHub Actions compiles the fixed-signed release APK on every push to `main` and uploads `uclone-restore-release-apk`.
Release signing is driven by repository secrets:

- `RELEASE_KEYSTORE_BASE64`
- `RELEASE_STORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`

Installability note: fixed-signed releases can cover-install later fixed-signed releases. Moving from an older debug-signed APK to the fixed-signed release requires a one-time uninstall because Android treats them as different signing identities.

Figma MVP draft:

- https://www.figma.com/design/bVBjSk3xsciEOkTSXbHHNV
