# CrossUser Runtime Device Validation Plan

This plan converts the task prompt into executable gates. Do not mark a gate passed without target-device evidence.

## L0: read-only baseline

Command:

```bash
tools/cross-user-lab/collect_l0.sh -s "$SERIAL" -o reports/cross-user-runtime
```

Allowed:

- `getprop`;
- `id`, `su -c id`, SELinux context;
- `pm list users`;
- `am get-current-user`;
- `dumpsys activity/user/display/window/input/package`;
- `cmd ... help`;
- logcat and dmesg tail.

Forbidden:

- install/uninstall;
- start/stop/switch/unlock users;
- create/remove displays;
- change overlays/properties;
- reboot;
- write device data.

Success criterion:

- report directory exists;
- `manifest.json`, `device.json`, `commands.log`, `dumpsys/`, `logs/`, and `checksums.sha256` are present;
- serial is hashed, not stored raw;
- current user, fingerprint, SDK, and SELinux mode are recorded.

## L1: user0 Host VirtualDisplay

Prerequisite:

- L0 complete.
- Probe App may be installed only after explicit device-test authorization.

Test:

1. Host creates private VirtualDisplay.
2. Host creates public own-content VirtualDisplay.
3. user0 Probe renders first frame on each display.
4. Destroy Host Surface and display.

Success criterion:

- first frame observed;
- no display leak in `dumpsys display`;
- no AVC denial related to display creation.

## L2: same-user different UID placement

Test:

1. user0 Host owns public display.
2. Launch user0 Probe onto that display as a different UID from UClone.
3. Deliver basic touch and Back if a candidate input route exists.

Success criterion:

- task/window appears on selected displayId;
- target UID remains Probe's user0 UID;
- cleanup removes task/display.

## L3: user10 visible assignment without target app launch

Test:

1. Keep foreground/current user at user0.
2. Create candidate display.
3. Attempt visible-background start/assignment for user10.
4. Do not launch production apps.

Success criterion:

- `am get-current-user` remains `0`;
- user10 becomes running and visible on target display if supported;
- cleanup reverses assignment;
- SELinux remains Enforcing.

## L4: user10 Probe Direct Surface

Test:

1. Install Probe App to user0 and user10.
2. Write distinct CE/DE markers in both users.
3. Launch user10 Probe onto target display.
4. Capture Probe identity evidence.

Success criterion:

- `UserHandle`/UID-derived user equals `10`;
- `dataDir` and device-protected dataDir point to user10;
- user10 marker is visible and user0 marker is not substituted;
- user0 same-package data hash is unchanged;
- first frame is direct Surface, not copied user0 data.

## L5: interaction and lifecycle

Test matrix:

- single touch, multi-touch, long press, swipe;
- Back;
- text input and password field;
- Surface destroy/recreate;
- rotation;
- Host background/foreground;
- target crash and process restart;
- permission dialog;
- DocumentsUI;
- WebView;
- secure page expected black/blocked behavior.

Success criterion:

- input is display-scoped;
- no default display hijack;
- `FLAG_SECURE` is respected;
- stale sessions clean up deterministically.

## L6: failure injection

Test:

- kill Host;
- kill bridge;
- force-stop target;
- stop user10;
- remove display;
- duplicate session;
- sleep/wake;
- reboot with stale journal only if explicitly authorized.

Success criterion:

- cleanup path is idempotent;
- no leaked UClone-created display/task;
- user10 is not stopped if it was not started by the session;
- recovery artifacts identify any uncertain state.

## L7: experimental app integration

Only after L4-L6 pass:

- expose experimental settings entry;
- keep feature flag default false;
- Probe App only unless production package testing is explicitly authorized.

## Go/No-Go

Go requires G0-G7 evidence from the task prompt.

No-Go if any route requires:

- SELinux Permissive;
- broad framework permission bypass;
- hardcoded Binder transaction code in product code;
- copying or bind-mounting real user10 data;
- insecure logging of input/PIN/app frames;
- non-deterministic cleanup.
