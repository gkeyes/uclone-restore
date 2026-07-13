# UClone Restore Development Rules

## Source of truth

Use this order when requirements disagree:

1. The user's newest explicit instruction.
2. `需求.md` for shipped product behavior.
3. Current production code and `.github/workflows/android-ci.yml`.
4. Documents under `docs/`.
5. Historical reports and old 0.3 branches as investigation leads only.

## Change discipline

- Treat this repository as a data-safety-critical Android Root project.
- One behavior change per reviewable slice. Do not combine a bug fix with UI redesign, dependency upgrades, or broad refactoring.
- Trace UI/module request through `ExternalActionService`, `TaskCoordinator`, `SyncEngine`, generated POSIX shell, and final marker before editing.
- Search all callers before changing a public model, operation, persistent field, or shell marker.
- New restrictions require a user requirement or reproducible device evidence. Do not turn theoretical risks into hard runtime failures.
- Do not add user settings for internal command compatibility, timeouts, retries, or ROM workarounds.
- Preserve the proven 0.2 copy, switch, push, restore, and clone-lifecycle behavior unless a failing test or device log proves it wrong.
- Never use an older persistent backup as the exact undo image for a new target mutation.
- Permission/AppOps capture failure must never revoke permissions, reset AppOps, or invalidate otherwise valid file data.
- Do not copy production code wholesale from the abandoned 0.3 implementation. Port one independently testable behavior at a time.

## Required workflow

1. Record the baseline commit and reproduce or specify the behavior contract.
2. For Android, Root, PackageManager, AppOps, user-lifecycle, or Shell behavior, capture the exact command, output, exit code, Android/ROM version, and user state on the target device before changing production logic. AOSP documentation and source are supporting evidence, not a substitute for the target ROM result.
3. Map direct and indirect callers and failure paths.
4. Add a regression test that would fail before the fix for subtle shell, lifecycle, transaction, or protocol behavior. The fixture must be derived from captured device output when the boundary is ROM-dependent.
5. Implement the smallest coherent change. Do not add an unobserved fallback, restriction, retry, timeout, parser shape, or success assumption.
6. Run targeted tests, all App/module unit tests, lint, and the GitHub Actions release build.
7. Review the final diff for unrelated changes and unverified platform assumptions.
8. Use the exact GitHub fixed-signed artifact for device tests. Record the installed version before testing. A green build or local shell simulation is not device acceptance.

## Device evidence gate

- Static analysis proves code shape; executable host tests prove POSIX behavior; neither proves HyperOS command or transaction behavior.
- Every changed Root/device boundary requires a non-destructive contract probe on the target phone before implementation when feasible.
- Every changed data operation requires a post-build phone test using the exact GitHub artifact, including the final notification, history record, request ID, marker, filesystem state, and actual App state.
- A device failure becomes a permanent fixture or regression test before the fix is accepted.
- If wireless debugging is unavailable, prepare one finite terminal script for the user and consume its complete log. Do not replace missing evidence with a guessed result.
- Until the applicable phone test passes, report the change as `CODE_READY_DEVICE_UNVERIFIED`, never as deployable, stable, or fixed.

## Authorization boundaries

Do not compile, commit, push, install, restart Launcher/device, publish, delete releases, or delete phone/workspace data without explicit authorization in the current request.

## Completion criteria

- GitHub Actions passes the Linux/JDK 17/Gradle 8.13/Android 36 gate.
- Changed shell behavior is exercised, not only checked as source text.
- Device-mutating behavior has a documented device test result or is explicitly marked unverified.
- Notifications, history, request ID, final marker, filesystem state, and user-visible result agree.
- The stable `v0.2.0` release remains available until the rebuild passes its full release checklist.
