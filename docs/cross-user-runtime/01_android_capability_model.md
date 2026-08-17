# CrossUser Runtime Capability Model

Status labels:

- `SUPPORTED`: verified on the target device.
- `UNSUPPORTED`: verified unavailable on the target device.
- `BLOCKED_BY_PERMISSION`: Binder/framework permission denied.
- `BLOCKED_BY_SELINUX`: SELinux denial or domain restriction observed.
- `UNKNOWN`: not verified on the target device yet.

No status in this document is a product claim until backed by an evidence bundle under `reports/cross-user-runtime/`.

## Required capability fields

The scaffolded Kotlin model uses these capability keys:

| Capability | Meaning | Current status |
|---|---|---|
| `activities_on_secondary_displays` | Framework permits activities on secondary displays | `UNKNOWN` |
| `visible_background_users_resource` | Effective framework resource allows visible background users | `UNKNOWN` |
| `visible_background_users_runtime` | Runtime state can make user10 visible on target display | `UNKNOWN` |
| `start_user_visible_api_present` | Target ROM exposes a callable visible-user API path | `UNKNOWN` |
| `start_user_visible_api_callable` | Privileged bridge can call that API | `UNKNOWN` |
| `host_public_display_creatable` | user0 Host can create public/own-content VirtualDisplay | `UNKNOWN` |
| `host_public_display_accepts_other_uid` | user0 Probe from another UID can launch onto Host display | `UNKNOWN` |
| `trusted_display_creatable` | privileged bridge can create a trusted display | `UNKNOWN` |
| `launch_as_user_on_display` | user10 Activity can launch on selected displayId | `UNKNOWN` |
| `target_task_observed_on_display` | dumpsys confirms target task/window on selected display | `UNKNOWN` |
| `virtual_touch_device` | Virtual input can be bound to selected display | `UNKNOWN` |
| `display_targeted_input_injection` | touch/key injection can target selected display only | `UNKNOWN` |
| `back_injection` | Back can be delivered to selected user10 task | `UNKNOWN` |
| `multi_touch_injection` | pointer IDs and multi-touch survive mapping/injection | `UNKNOWN` |
| `user_running` | target user10 is running during session | `UNKNOWN` |
| `user_unlocked` | target user10 CE storage is unlocked when needed | `UNKNOWN` |
| `direct_surface_supported` | direct Surface rendering works without encoding | `UNKNOWN` |
| `encoded_fallback_supported` | scrcpy-like fallback is technically viable | `UNKNOWN` |
| `secure_content_policy` | `FLAG_SECURE` is respected by runtime path | `UNKNOWN` |
| `requires_framework_hook` | only visible-user gate requires a hook | `UNKNOWN` |

## FACT

- Android multi-user UIDs encode the user in the UID range; the Probe app exposes `uid`, `appId`, and `userId` using `PER_USER_RANGE = 100000`.
- The current UClone production root path is shell-based, not Binder-based.
- The current external protocol is version `1` and has no cross-user runtime session operations.
- Existing UClone data-plane tasks can mutate user0/user10 app data and stop/start users; runtime sessions need an independent conflict gate.

## INFERENCE

- A Binder bridge is required before production because `Surface`, callback death, and high-rate input are not appropriate over shell/stdout.
- Host-owned public VirtualDisplay should be tested before trusted privileged display because it keeps Surface ownership in the user0 Host Activity and reduces privileged surface handling.
- If user10 visible-background assignment is disabled by framework resources or OEM gates, normal root may not be sufficient.

## UNKNOWN

- Whether the target HyperOS build enables or retains visible-background-user APIs.
- Whether a user-created public VirtualDisplay is trusted enough for user10 assignment.
- Whether root UID can call the needed ActivityTaskManager/UserManager/DisplayManager paths under SELinux Enforcing.
- Whether target input injection can be display-scoped without a system_server hook.
- IME behavior on secondary displays for user10.
- Whether SystemUI permission dialogs and DocumentsUI stay on the same user/display.

## BLOCKED

- No target device serial was provided in this run, so L0 device evidence was not collected.
- L1-L4 cannot be claimed without installing the Probe App and executing the device matrix.
