# 0.4.0-rc.1 Preview Implementation Record

Date: 2026-08-17

Status: `CODE_READY_DEVICE_UNVERIFIED` until the fixed-signed GitHub artifact passes the applicable phone gates.

## Scope implemented

- `crossuser-api`: versioned Parcelable/AIDL protocol with typed probe, session, Surface replacement, touch, Back, query, close, and stale cleanup operations.
- `crossuser-runtime`: Host-owned R1 display client, non-daemon libsu RootService, command adapter, input adapter, session journal, validation, and tests.
- `crossuser-probe-app`: isolated identity/CE/DE marker and touch/Back evidence app.
- Main App: default-off experimental setting, non-exported Host Activity, and shared resource admission with the existing data task coordinator.
- CI: 0.4 branch gates and one commit-pinned bundle containing three same-certificate Release APKs, `SHA256SUMS.txt`, and a build manifest.

## Hard boundaries

- main user is fixed to user0 and target user to user10 for this preview;
- only `com.gkeyes.uclone.crossuserprobe/.MainActivity` is accepted by the bridge;
- `USER_CURRENT`, `USER_ALL`, arbitrary package/component, generic shell, and hardcoded Binder transaction codes are absent;
- no system_server Hook, trusted-display R2, encoded fallback, production package, launcher entry, reboot test, or SELinux Permissive path;
- Host verifies the ROM capability report before creating a session;
- the root adapter repeats current-user, package-install, visibility, unlock, and foreground-user invariants;
- session cleanup stops user10 only when the session receipt proves it started user10 and user10 is not the foreground user.

## Target-device L0 fact record

Evidence bundle is kept locally under `reports/cross-user-runtime/` and excluded from Git because it contains raw device diagnostics.

Observed on 2026-08-17:

| Field | FACT |
|---|---|
| Product | Xiaomi 2509FPN0BC (`popsicle`) |
| Build | Android 17 / SDK 37 / OS4.0.0.20.XPBCNXM |
| Fingerprint | `Xiaomi/popsicle/popsicle:17/CP2A.260605.016/OS4.0.0.20.XPBCNXM:user/release-keys` |
| Security patch | 2026-08-01 |
| Root | uid 0, `u:r:ksu:s0` |
| SELinux | Enforcing |
| Users | user0 and user10 both `RUNNING_UNLOCKED` |
| Foreground during first L0 | user10 |
| Foreground during second L0 | user0 |
| Visible background users | false |
| Visible background users on default display | false |
| Displays eligible for background-user start | none |
| user0 / user10 visible during second L0 | true / false |

The second L0 run satisfies the user0 foreground precondition, but still records an R1 L3 blocker because the effective and real visible-background-user values are both false and the eligible display list is empty. No install, user start/stop/switch, display creation, Hook, property/overlay change, or reboot was performed by either L0 run.

## Status vocabulary

- `FACT`: rows above and repository/CI artifacts.
- `INFERENCE`: the ROM will likely reject user10 visible assignment because its effective capability is false.
- `UNKNOWN`: whether a minimal ROM-specific compatibility path can safely enable visible assignment; R2/input/first-frame behavior on this build.
- `BLOCKED`: L3/L4 until an approved route can pass the visible-user gate without broad framework bypass; returning to user0 alone is now confirmed insufficient.
