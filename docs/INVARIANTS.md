# Invariants

- No target App data is modified before source validation and rollback preparation succeed.
- A persistent MAIN/CLONE backup is not the current transaction undo.
- Switch, restore, and push remain distinct one-way operations.
- App state changes only after the corresponding data operation commits.
- Permission/AppOps capture failure cannot revoke or reset target state.
- Workspace payload owner is `root:root`; restored App data owner is the target App.
- User lifecycle completion is determined from observed user state, not only the `am` client exit.
- Every external request has one request ID and reaches a terminal status.
- New platform restrictions require device evidence and cannot be exposed as speculative user settings.
