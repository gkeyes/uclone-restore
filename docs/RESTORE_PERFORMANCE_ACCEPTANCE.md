# Restore Performance Acceptance

This document defines the repeatable acceptance gate for restore-path performance changes. Correct data, rollback, owner, context, marker, notification, and history results take precedence over speed.

## Artifacts and device

- Compare two fixed-signed GitHub artifacts: the accepted baseline commit and the candidate commit.
- Record GitHub run URL, commit, App/module version codes, APK SHA-256 values, device build, Android version, Root implementation, battery state, and free workspace storage.
- Use the same phone, App package, selected data parts, source backup, target state, and UClone settings for both artifacts.
- Do not use a local unsigned or differently signed APK as performance evidence.

## Scenarios

Run all applicable scenarios:

1. Restore an active snapshot to user0.
2. Restore a MAIN passive backup to user0.
3. Restore the latest clone rollback to user10.
4. Perform a SAFE MAIN-to-CLONE switch.
5. Perform the configured SAFE CLONE-to-MAIN return.

The first three scenarios validate the direct managed-source optimization. The last two are non-regression controls for the existing switch copy-pass contracts.

## Procedure

1. Verify the source and target filesystem state before each run.
2. Perform one warm-up run that is not included in the result.
3. Perform three measured runs per artifact and scenario.
4. Preserve each full task log and record its request ID, final task status, total duration, marker, rollback path, and observed App data.
5. Summarize each log with:

   ```sh
   tools/diagnostics/uclone_restore_perf_report.sh /path/to/task.log
   ```

6. Compare medians, not the fastest individual run. Do not mix logs from different data sets or device states.

## Performance thresholds

- Managed-source restore must emit no `tmp/prepared_*` payload and `source_materialize` must be metadata-only.
- For a source larger than 1 GiB or 10,000 entries, median total duration for each managed-source restore must improve by at least 10 percent, or the candidate is not accepted as a performance release.
- Median `target_downtime_ms` must not regress by more than 5 percent in any scenario.
- SAFE switch and SAFE return total duration must not regress by more than 5 percent; their expected copy-pass count must remain unchanged.
- Candidate `peak_temporary_bytes` must exclude the removed prepared source copy. Transaction undo space remains expected and must not be hidden from metrics.
- Space preflight must require at least the larger of the selected source-part sum and current target size. Only strict per-part `.size_kb` hints from completed managed backups may skip a scan. A legacy or invalid hint may perform one fallback size scan for that part; avoiding that scan is never allowed to weaken the space gate.
- A performance result is invalid if either artifact changes selected parts, cache policy, permission policy, source data, rollback policy, or user lifecycle behavior.

## Correctness gate

Every measured run must also satisfy:

- final exit code and task status agree;
- source backup remains unchanged;
- managed source root and selected top-level parts are not symbolic links; task-created live-source links resolve to the expected real App directories;
- fresh transaction undo exists before target mutation;
- restored file data is usable by the target App;
- UID/GID and SELinux context match the accepted device contract;
- MAIN/CLONE/UNKNOWN marker is correct;
- notification, history, and request ID identify the same task;
- no new warning, automatic rollback, orphaned lock, or workspace residue appears.

Any correctness failure rejects the candidate regardless of measured speed.

## Result record

For each scenario record baseline and candidate medians for:

| Metric | Baseline | Candidate | Change |
|---|---:|---:|---:|
| Total duration |  |  |  |
| Source validation |  |  |  |
| Source materialization |  |  |  |
| Transaction undo copy |  |  |  |
| Restore copy |  |  |  |
| Owner fix |  |  |  |
| SELinux fix |  |  |  |
| Post-copy verification |  |  |  |
| Durability barrier |  |  |  |
| Target downtime |  |  |  |
| Peak temporary bytes |  |  |  |

Until GitHub and target-phone gates both pass, report the change as `CODE_READY_DEVICE_UNVERIFIED`.
