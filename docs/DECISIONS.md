# Decisions

## 2026-07-13: rebuild 0.3 from v0.2.0

The previous 0.3 branch changed too many coupled subsystems before device validation. The rebuild starts from tag `v0.2.0` and uses the previous branch only as a source of requirements, tests, and small independently reviewable implementations.

## Version boundary

- 0.3 contains workspace ownership, labeled MAIN/CLONE state backups, cross-user install, and forced clone-data refresh.
- 0.4 contains persistent transaction recovery and App execution gating.

## Compatibility

External protocol version 1 and existing 0.2 backup formats remain readable. New metadata is additive. No automatic destructive migration is allowed.
