# ADR: CrossUser Runtime Scaffolding

Date: 2026-08-17

Status: accepted for the Probe-only 0.4.0-rc.1 preview.

## Context

UClone Restore currently has a mature data plane for backup, restore, push, rollback, install, and clone-user lifecycle operations. The requested CrossUser Runtime is a separate runtime/display plane: keep user0 foreground, run the target package as user10, render that task into a user0 Host Surface, and deliver input to that user10 display.

The repository currently uses `su` subprocesses for privileged finite tasks. That path is unsuitable for a long-lived interactive session because it cannot own Binder `Surface` references, Binder death, typed callbacks, or high-rate touch packets reliably.

## Decision

The scaffold phase added:

- pure Kotlin domain/API scaffolding under `com.uclone.restore.crossuser`;
- a deterministic session state machine;
- a conservative resource gate;
- a fake runtime backend that never claims target-device support;
- an isolated `crossuser-probe-app` module;
- read-only L0 lab tooling and documentation.

The 0.4.0-rc.1 implementation adds a default-disabled Host UI, a typed non-daemon libsu RootService, and a shared in-process resource gate. Launcher module operations and data-plane copy/restore semantics remain unchanged.

## Candidate bridge decision

The executable preview adopts R1 first:

1. user0 Host-created `PUBLIC | OWN_CONTENT_ONLY` VirtualDisplay;
2. non-daemon `libsu` RootService for visible-user assignment, fixed Probe launch, display-scoped touch/Back, and cleanup;
3. `app_process` bridge only if later evidence isolates the RootService domain as the blocker;
4. encoded fallback only after Direct Surface route evidence says it is needed;
5. system_server hook only after a separate ADR proves the exact missing framework gate.

## API boundary

The runtime API must remain typed and narrow. It must not expose:

- generic shell execution;
- arbitrary Binder method invocation;
- arbitrary file paths;
- caller-supplied user aliases like `USER_CURRENT` or `USER_ALL`;
- display/input targeting outside the active session.

## State ownership

`CrossUserSessionCoordinator` should be separate from `TaskCoordinator`.

The resource gate should block at least these conflicts:

- active user10 app runtime session vs same-package user10 data restore/push;
- active runtime session vs stop-user for user10;
- active data transaction vs opening a runtime session;
- stale/recovery-required runtime session vs new session creation.

## Consequences

Positive:

- Existing backup/restore semantics are insulated.
- Future bridge work has a stable state model and testable fake backend.
- The Probe App can prove userId/uid/data markers without touching real apps.

Negative:

- This does not prove target HyperOS supports the feature.
- Adding a new Gradle module increases build surface.
- The actual Binder bridge and Host Activity remain future work.

## Current No-Go boundaries

- No system_server hook.
- No SELinux Permissive.
- No production package testing.
- No copying/bind-mounting user10 data as proof of runtime.
- No hardcoded Binder transaction codes.
