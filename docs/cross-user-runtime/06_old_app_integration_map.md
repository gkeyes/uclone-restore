# Integration Map With Existing UClone Restore

CrossUser Runtime is a sibling runtime plane, not a replacement for the existing data plane.

## Existing data plane

| Existing component | Current responsibility | CrossUser usage |
|---|---|---|
| `SyncEngine` | finite root-backed backup/restore/install/user tasks | do not use for live display sessions |
| `ShellScripts` | transactional shell data movement and user lifecycle scripts | may donate lifecycle snippets after extraction, not runtime display logic |
| `TaskCoordinator` | single active finite task admission | keep for data tasks; add separate session coordinator later |
| `TaskLogStore` | persistent task history and progress | may record open/close summary tasks later, not live session source of truth |
| `SettingsStore` | user IDs, root dir, clone unlock policy | reusable settings source |
| `PackageInspector` | installed package visibility across users | reusable package preflight |
| `RootEnvironmentChecker` | root/user/CE environment checks | reusable diagnostics input |
| `ExternalActionService` | signed external action entry | future runtime actions must be separate and negotiated |
| `launcher-module` | launcher gesture relay | may open Host Activity later; must not own root/runtime state |

## Future runtime plane

Recommended additions after device gates:

```text
CrossUserCapabilityRepository
CrossUserRuntimeClient
CrossUserSessionCoordinator
CrossUserSessionRepository
CrossUserResourceGate
CrossUserRuntimeSettings
CrossUserRuntimeAuditStore
CrossUserHostActivity
CrossUserHostViewModel
```

## Conflict model

Minimum conservative lock:

```text
active CrossUser Session -> reject data restore/push/stop-user
active data transaction -> reject CrossUser Session
recovery-required runtime session -> reject new runtime/data mutation until reconciled
```

Refined keys after POC:

```text
USER_LIFECYCLE(user10)
PACKAGE_RUNTIME(user10, packageName)
PACKAGE_DATA(user10, packageName)
DISPLAY(displayId)
GLOBAL_FRAMEWORK_HOOK
```

## External protocol boundary

Do not overload existing operations:

- `SWITCH_OR_RESTORE` means data-plane switch/restore.
- Future `OPEN_CROSS_USER_APP` must mean runtime-plane real user10 open.

Launcher flow should be:

```text
launcher user gesture
-> explicit main-app Activity
-> CrossUserHostActivity obtains Surface
-> runtime client creates a session
-> bridge launches target only after Surface/display/user gates pass
```

## Current scaffold files

- `app/src/main/java/com/uclone/restore/crossuser/domain/CrossUserModels.kt`
- `app/src/main/java/com/uclone/restore/crossuser/session/CrossUserSessionStateMachine.kt`
- `app/src/main/java/com/uclone/restore/crossuser/session/CrossUserResourceGate.kt`
- `app/src/main/java/com/uclone/restore/crossuser/client/FakeCrossUserRuntime.kt`
- `crossuser-probe-app/`
- `tools/cross-user-lab/`

These are not wired to the production UI.
