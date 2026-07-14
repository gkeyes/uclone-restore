# Regression Matrix

| Area | Required scenarios |
|---|---|
| Process state | UClone cold, warm, and removed from recents |
| Clone user | not started, locked, and `RUNNING_UNLOCKED` when relevant |
| Data size | small test App and large-file/small-file-dense App |
| Operation | capture, switch, restore MAIN, push, restore clone rollback |
| Permission capture | valid result, valid empty result, command failure |
| Device command contract | `pm list users` success; missing package returns exit 0 plus empty output; package AppOps is UID prefix plus package tail; numeric UID query is UID-only |
| Security metadata | Before changing cache or OBB owner logic, record user0/user10 owner, GID, mode, and SELinux context from the target phone; missing paths are not positive evidence |
| Shell | source failure, target failure, pipe failure, empty path, 1/500/501 files |
| Repetition | double click, end-of-task resubmission, two different packages |
| Result | notification, history, request ID, marker, owner/context, actual App state |
| Upgrade | signed overwrite installation from stable 0.2 |

Each new vertical slice must identify the cells it changes and record both GitHub Action evidence and target-phone evidence in its change report. Host tests may preserve a captured device contract but cannot satisfy the device column.

Restore-path performance changes must also complete `docs/RESTORE_PERFORMANCE_ACCEPTANCE.md`; a faster run cannot override any failed correctness cell in this matrix.
