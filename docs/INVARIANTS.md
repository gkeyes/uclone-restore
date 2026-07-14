# Invariants

- No target App data is modified before source validation and rollback preparation succeed.
- The fixed MAIN return point is not the current transaction undo.
- Every mutation uses a newly created collision-free undo until data and state-marker commit; only after commit may that temporary undo be promoted or pruned.
- Only the normal MAIN-to-CLONE switch may initialize a missing fixed MAIN return point. Generic restore and rollback operations never initialize or replace it.
- Manual fixed-MAIN replacement requires the explicit confirmed-MAIN marker written by a successful MAIN restore; legacy marker absence is not sufficient proof.
- Normal CLONE switching always reads current user10 data and never selects a legacy persistent CLONE backup.
- An existing valid MAIN return point changes only through the explicit update action, or through `REFRESH_ON_MAIN_EXIT` while user0 is explicitly confirmed as MAIN. A legacy inferred MAIN state keeps the existing return point and emits a warning.
- Returning from CLONE resolves one of four plans: `SYNC_SAFE`, `SYNC_FAST`, `DISCARD_SAFE`, or `DISCARD_FAST`. Discard plans never read or modify user10.
- UI, desktop shortcut, and module requests execute the same persisted switch-policy snapshot accepted by `ExternalActionService`.
- Switch, restore, and push remain distinct one-way operations.
- App state changes only after the corresponding data operation commits.
- Permission/AppOps capture failure cannot revoke or reset target state.
- Workspace payload owner is `root:root`; restored App data owner is the target App.
- User lifecycle completion is determined from observed user state, not only the `am` client exit.
- Every external request has one request ID and reaches a terminal status.
- New platform restrictions require device evidence and cannot be exposed as speculative user settings.
