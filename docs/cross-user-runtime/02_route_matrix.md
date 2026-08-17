# CrossUser Runtime Route Matrix

The goal is a real user10 app process, real user10 data, rendered inside a user0 UClone Host Activity. Copying or bind-mounting user10 data into user0 is explicitly out of scope for this runtime plane.

| Route | Description | Data/user identity | Complexity | Current decision | Required evidence |
|---|---|---:|---:|---|---|
| R0 | Normal switch to user10 | Real user10, wrong UX | Low | Baseline only | `am switch-user` behavior, existing UClone user controls |
| R1 | user0 Host-owned `PUBLIC | OWN_CONTENT_ONLY` VirtualDisplay + privileged user10 visible launch | Target design | Medium-high | First POC candidate | L1-L4: display, visible user assignment, launch, first frame, input |
| R2 | Privileged bridge creates trusted/public VirtualDisplay and launches user10 | Target design | High | Second candidate if R1 display trust fails | Same as R1 plus bridge display ownership and cleanup evidence |
| R3 | WM Shell TaskView/TaskOrganizer embedding | Target design if privileges exist | Very high | Research only | Framework availability, system signature/privilege requirements |
| R4 | scrcpy-like app_process server + encoding + user0 decode | Real user10, encoded UX | High | Fallback candidate | Codec latency, input, secure-content behavior, visible-user requirements |
| R5 | minimal system_server hook for visible-user/display gate | Target design with hook risk | Extreme | Last resort only | Hook ADR, fingerprint gate, rescue plan, SELinux Enforcing evidence |
| R6 | Copy/restore user10 data into user0 app | Not real user10 process | Existing | Existing data-plane fallback, not this feature | Existing UClone tests |
| R7 | Bind mount user10 data under user0 | Not accepted, high risk | Extreme | No-Go | None; forbidden for this runtime goal |

## Decision order

1. Establish L0 evidence without device mutation.
2. Run L1/L2 on user0 only to prove display creation, placement, cleanup, and input basics.
3. Try R1 for user10 visible assignment and Probe launch.
4. If R1 fails specifically on display trust, try R2.
5. If direct surface succeeds but input is the only blocker, test display-targeted input alternatives before considering hooks.
6. If only visible-background-user framework gates fail, and all other paths are proven, write a separate Hook ADR before any hook code.
7. If any route requires SELinux Permissive, broad permission bypass, hardcoded Binder transaction codes, or data copying/binding, mark No-Go for productization.

## Current route status

| Route | Status | Reason |
|---|---|---|
| R1 | `UNKNOWN` | Needs target L1-L4 evidence. |
| R2 | `UNKNOWN` | Must not begin until R1 failure mode is known. |
| R4 | `UNKNOWN` | Not needed unless Direct Surface is blocked or unstable. |
| R5 | `BLOCKED` | `UCLONE_ALLOW_SYSTEM_SERVER_HOOK=false` and no Hook ADR/evidence. |
