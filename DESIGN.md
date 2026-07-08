# UClone Restore Design System

## 1. Atmosphere & Identity

UClone Restore should feel like a precise iOS utility for a risky root workflow: calm, readable, and explicit about state. The signature is an iOS-style grouped control surface: pale gray page background, white rounded groups, compact rows, blue actions, and status chips that explain what is safe before the user touches restore.

## 2. Color

### Palette

| Role | Token | Light | Usage |
|------|-------|-------|-------|
| Surface/page | iosPage | #F5F5F7 | Main app background |
| Surface/group | iosGroup | #FFFFFF | Grouped list cards and panels |
| Surface/raised | iosRaised | #FAFAFC | Search fields and secondary fills |
| Text/primary | iosText | #1D1D1F | Titles and primary row text |
| Text/secondary | iosSecondary | #6E6E73 | Labels, package names, helper text |
| Text/tertiary | iosTertiary | #8E8E93 | Metadata and muted hints |
| Border/subtle | iosSeparator | #D2D2D7 | Dividers and field outlines |
| Accent/primary | iosBlue | #007AFF | Primary actions, selected tab, focus |
| Accent/pressed | iosBluePressed | #0066CC | Pressed primary action |
| Status/success | iosGreen | #34C759 | Root OK, completed |
| Status/warning | iosOrange | #FF9500 | Risk and caution |
| Status/error | iosRed | #FF3B30 | Failed and destructive states |

### Rules

- Blue is reserved for actions, selection, and links.
- White groups sit on the pale gray page; separators are used inside groups instead of heavy card shadows.
- Status colors appear only in compact chips, status text, or progress rows.

## 3. Typography

### Scale

| Level | Size | Weight | Line Height | Tracking | Usage |
|-------|------|--------|-------------|----------|-------|
| Large title | 28sp | 700 | 1.14 | 0 | Screen titles |
| Title | 20sp | 600 | 1.20 | 0 | Group headings and important app labels |
| Body | 16sp | 400 | 1.38 | 0 | Row values and action labels |
| Body emphasis | 16sp | 600 | 1.38 | 0 | Key values and selected labels |
| Subhead | 14sp | 400 | 1.43 | 0 | Package names and secondary row text |
| Caption | 12sp | 500 | 1.33 | 0 | Section captions, metadata, chips |
| Mono | 12sp | 400 | 1.35 | 0 | Logs and filesystem paths |

### Font Stack

- Primary: Android system sans, matching the platform while using Apple-like scale and spacing.
- Mono: platform monospace for logs and paths.

### Rules

- Keep labels sentence-like and practical. No marketing copy.
- Long package names and paths may wrap in dedicated path/log areas; compact rows ellipsize.

## 4. Spacing & Layout

### Base Unit

All spacing derives from 4dp.

| Token | Value | Usage |
|-------|-------|-------|
| space1 | 4dp | Icon/text micro gap |
| space2 | 8dp | Row internal gap |
| space3 | 12dp | Compact group padding |
| space4 | 16dp | Screen edge, row padding |
| space5 | 20dp | Group vertical rhythm |
| space6 | 24dp | Major screen spacing |

### Grid

- Mobile-first single column.
- Screen edge padding: 16dp.
- Groups stack with 8-12dp gaps.
- Primary actions use full-width rows on small screens.
- Tool pages use 12dp horizontal page padding and 10dp vertical page padding.

### Rules

- Use grouped lists for settings, diagnostics, history, and app detail.
- Avoid nested cards; a group can contain rows, buttons, chips, or logs.

## 5. Components

### ScreenHeader
- **Structure**: large title, optional caption.
- **Spacing**: 16dp horizontal, 8dp internal gap.
- **States**: static only.
- **Accessibility**: title is the first meaningful text on the screen.

### IOSGroup
- **Structure**: rounded white container with vertical content.
- **Variants**: normal, compact.
- **Spacing**: 16dp padding, 12dp internal gap.
- **States**: default only; contents own interaction states.
- **Accessibility**: group title is visible when it adds context.

### InfoRow
- **Structure**: left label, right value.
- **Variants**: normal, status-colored value.
- **Spacing**: 8dp vertical, 12dp horizontal gap.
- **States**: default only.
- **Accessibility**: value remains text, not icon-only.

### IOSActionButton
- **Structure**: rounded capsule, optional icon, label.
- **Variants**: primary blue, secondary raised, danger red text.
- **Spacing**: 10dp vertical, 14dp horizontal.
- **States**: default, pressed, disabled.
- **Accessibility**: minimum 44dp touch target.

### IOSCompactButton
- **Structure**: compact capsule with a short verb.
- **Variants**: primary blue, secondary raised, disabled.
- **Spacing**: 7dp vertical, 12dp horizontal.
- **States**: default, pressed, disabled.
- **Accessibility**: use only for repeated row actions with very short labels.

### StatusPill
- **Structure**: compact rounded capsule with dot and label.
- **Variants**: success, warning, error, neutral.
- **Spacing**: 6dp vertical, 10dp horizontal.
- **States**: default only.
- **Accessibility**: color is always paired with text.

### AppListRow
- **Structure**: app icon, app label/package, install/snapshot metadata, chevron.
- **Spacing**: 10-12dp row padding, 10dp icon gap.
- **States**: default, pressed.
- **Accessibility**: row text includes app name and status.

### FavoriteQuickRow
- **Structure**: app icon, app name/package/size, right-aligned compact `切换` and `还原` actions.
- **Spacing**: 10dp vertical row padding, 10dp icon and action gaps.
- **States**: default, pressed, disabled restore when no switch rollback marker exists.
- **Accessibility**: destructive state changes still require confirmation.

### CollapsedSearch
- **Structure**: search icon button in the App header, expanding into a full-width input only when requested.
- **States**: collapsed, expanded, clear-and-collapse.
- **Accessibility**: icon has a text content description.

### LogPanel
- **Structure**: monospace text inside raised rounded surface.
- **Variants**: normal, empty.
- **Spacing**: 12dp padding.
- **States**: default only.
- **Accessibility**: selectable text is preferred for paths/logs.

## 6. Motion & Interaction

### Timing

| Type | Duration | Easing | Usage |
|------|----------|--------|-------|
| Press | 100ms | ease-out | Button and row pressed feedback |
| Standard | 200ms | ease-in-out | Navigation and state color changes |

### Rules

- Motion is limited to tactile press and Material navigation defaults.
- Do not animate root operations or logs; reliability is more important than spectacle.

## 7. Depth & Surface

### Strategy

Use tonal-shift plus subtle separators. The page is pale gray, groups are white, raised controls are off-white, and depth is communicated mostly by shape and contrast. Shadows stay minimal.

| Level | Treatment | Usage |
|-------|-----------|-------|
| Level 0 | #F5F5F7 | Page background |
| Level 1 | #FFFFFF, 18dp radius | Group panels |
| Level 2 | #FAFAFC, 14dp radius, subtle separator | Search, logs, secondary controls |
