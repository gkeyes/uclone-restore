# UClone CrossUser Runtime Lab

This folder contains read-only lab tooling for CrossUser Runtime L0 evidence.

Current scope:

- requires an explicit ADB serial;
- uses `adb -s <serial>` for every device command;
- collects repository, property, user, display, input, framework, logcat and AVC-adjacent evidence;
- does not install apps, start or stop users, create displays, change overlays, reboot, or write device data;
- writes the raw command outputs plus `manifest.json`, `device.json`, `summary.md`, and `checksums.sha256`.

Example:

```bash
tools/cross-user-lab/collect_l0.sh -s "$SERIAL" -o reports/cross-user-runtime
```

Before moving to L1 or higher, review `commands.log`, `dumpsys/`, `logs/`, and
the exact target ROM fingerprint in `manifest.json`.
