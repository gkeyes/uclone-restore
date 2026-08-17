# CrossUser Runtime Threat Model

This feature would create a privileged path that can show and operate another Android user profile's real app instance. The default implementation stance is fail-closed.

| Threat | Risk | Control |
|---|---|---|
| Unauthorized app calls the bridge | Opens or controls user10 apps | Signature and UID checks, explicit binding, random session token, deny unknown callers |
| Caller supplies dangerous userId | Controls another Android user | Server-side fixed `mainUserId`/`cloneUserId`, reject negative/current/all users |
| Caller supplies system package | Controls settings/SystemUI/critical packages | Package denylist, allowlist initially only Probe App |
| Generic root RPC | Privilege escalation | Typed API only, no `exec(String)` |
| Input injected to default display | Host/user0 hijack | Bridge overwrites displayId from session registry, rejects unknown target |
| Surface hijack or stale Surface | App content leak | Binder death, surface generation, host visibility gate, single-session POC |
| Data restore races with runtime | App data corruption | `CrossUserResourceGate` on user/package/data/runtime keys |
| user10 originally running gets stopped | User state disruption | user lifecycle receipts; stop only if session started it |
| Secure content bypass | DRM/privacy violation | Non-secure display by default; respect `FLAG_SECURE` |
| Logs leak PIN/input/app content | Sensitive data exposure | No input text/frame logging; serial hash only; no screenshots for real apps by default |
| Hook bootloop | Device recovery risk | No hook unless separate ADR, fingerprint gates, disable/rescue path |
| Stale display/task after crash | Persistent resource leak | Session journal, watchdog, idempotent cleanup, boot reconciliation |
| Launcher module overreach | Root/control outside main app | Launcher remains user-gesture relay only |

## Audit requirements

Every real-device run must record:

- exact repo commit and dirty state;
- device serial hash, build fingerprint, SDK, SELinux mode;
- command log with redaction;
- dumpsys display/window/activity/input;
- AVC/logcat evidence;
- Probe identity JSON/screens only for probe data;
- cleanup result and remaining display/task/process state.

## Release gate

This feature cannot be a release candidate until:

- G0-G7 from the task prompt are complete;
- unauthorized bridge access tests pass;
- `FLAG_SECURE` remains respected;
- no SELinux Permissive or broad framework bypass is required;
- old data-plane regression suite remains green with the feature flag off.
