# Architecture

## Production request path

```text
Compose UI / Launcher module
-> ExternalActionService
-> TaskCoordinator
-> ExternalActionDispatcher
-> SyncEngine
-> generated POSIX shell
-> RootShellExecutor
-> Android user and workspace data
```

The Service owns Android lifecycle and notifications. The coordinator owns single-task admission. The dispatcher maps protocol operations to domain calls. `SyncEngine` records task progress and results. Shell remains the Root execution backend.

## Rebuild rule

The 0.2 implementation is the behavioral baseline. New 0.3 capabilities are vertical slices that extend this path without replacing it. A slice is complete only when its dispatcher mapping, engine call, shell behavior, task result, UI state, and device evidence agree.
