# Release Checklist

## Source gate

- [ ] Diff contains one coherent vertical slice and no unrelated cleanup.
- [ ] Added restrictions cite a requirement or device log.
- [ ] Existing protocol and persisted-data compatibility were reviewed.
- [ ] `git diff --check` passes.

## GitHub gate

- [ ] Debug and Release unit tests pass for App and module.
- [ ] App and module lint pass.
- [ ] Fixed-signed Release APKs build on Ubuntu, JDK 17, Gradle 8.13, Android 36.
- [ ] App and module signatures match.
- [ ] Artifact package names, versions, and SHA-256 values are recorded.

## Device gate

- [ ] Install or overwrite-install succeeds with the GitHub artifact.
- [ ] Installed App and module version codes match the tested GitHub run; an older locally installed build is not accepted as evidence.
- [ ] Relevant cold/warm and user10 states pass.
- [ ] Notification, history, request ID, marker, filesystem result, and actual App behavior agree.
- [ ] At least one non-destructive failure/rejection path is observed.
- [ ] Data owner/GID/context and rollback behavior are verified for changed copy code.
- [ ] Every ROM-dependent command assumption introduced by this change has a recorded target-device probe.

Do not replace the stable `v0.2.0` release until every applicable item is complete.
