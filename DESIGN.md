# UClone Restore Design System (v2)

UClone Restore and the UClone launcher module follow the shared cross-app design system v2
(`DESIGN_SYSTEM.md` in the multi-app workspace). The values below are hard requirements; code
implements them in:

| Module | Tokens | Components / motion |
|---|---|---|
| `app` (Compose + Material 3) | `ui/Theme.kt` (`UCloneSpacing`, `UCloneRadius`, `UCloneAlpha`, colors, type) | `ui/DesignSystem.kt` (`UCloneMotion`, haptics, press feedback, stagger, collapsing title, `AnimatedCount`, `EmptyState`, `SheetHost` + `UCloneAlertSheet`, `ToneChip`), `ui/Components.kt`, `ui/GlassComponents.kt` |
| `launcher-module` (Android Views) | `settings/ModuleTheme.kt` (`ModulePalette`, `ModuleDimens`, `ModuleType`, `ModuleMotion`, `ModuleHaptics`) | builders in `settings/ModuleSettingsActivity.kt` |

The product character is unchanged: a calm, explicit utility for a risky root workflow. State is
always shown in text; color only supports it.

## 1. Tokens

### Spacing (4dp grid)

| Token | dp | Usage |
|---|---|---|
| xxs | 4 | icon/text micro gap |
| xs | 8 | row internal gap |
| sm | 12 | gap between cards, elements inside a group |
| md | 16 | page gutter, card padding |
| lg | 24 | section gap, sheet padding |
| xl | 32 | large separation |

List rows are at least 56dp tall; every touch target is at least 48dp.

### Radii

| Token | dp | Usage |
|---|---|---|
| card | 24 | grouped cards, tool panels |
| row / button | 16 | rows, primary/secondary buttons |
| control | 14 | text fields, segmented controls |
| small | 10 | row icon backgrounds, small chips |
| chip | full | status chips |
| sheet | 28 | top corners of bottom sheets |

### Type (all numbers use tabular figures, `tnum`)

| Role | Spec | Compose style |
|---|---|---|
| largeTitle | 30 / Bold → collapses to 17 / SemiBold | `headlineMedium`, `CollapsedTitleStyle` |
| title | 20 / SemiBold | `titleLarge` |
| headline (card title) | 17 / SemiBold | `titleMedium` |
| body | 16 / Regular | `bodyLarge` |
| subhead (secondary description) | 14 / Regular | `bodyMedium` |
| sectionHeader | 13 / Medium, secondary color, above the card, aligned with card text (gutter + 16) | `labelMedium` |
| caption | 12 / Regular | `bodySmall` |

Text alpha on `onSurface`: primary 0.92, secondary 0.62, tertiary 0.42, disabled 0.38.

### Color

- Accent: Android 12+ dynamic primary; otherwise `#3478F6` (light) / `#5A93FF` (dark).
- Surfaces:

| | light | dark |
|---|---|---|
| page | `#F5F6F8` | `#000000` |
| card | `#FFFFFF` | `#16171A` |
| raised (sheet, pressed card) | `#FFFFFF` | `#212226` |
| hairline | black 8% | white 10% |
| scrim | black 32% | black 50% |

- Semantic tones (container / content):

| Tone | light | dark |
|---|---|---|
| success | `#E6F4EA` / `#1B6B34` | `#17351F` / `#8FD9A8` |
| warning | `#FDF1DC` / `#8A5300` | `#3A2B10` / `#F5C36B` |
| danger | `#FCE8E8` / `#B3261E` | `#3B1A1A` / `#F4A7A3` |
| info | `#E5EEFD` / `#1A57C9` | `#172947` / `#A9C5FA` |
| neutral | `#EEF0F3` / `#4A4F57` | `#26282D` / `#C4C8CF` |

- No heavy shadows. Cards have no shadow; sheets use the raised tone plus a top hairline.
- The floating tab bar keeps the existing optical glass (it samples the content behind it); its
  selection lens moves on the spatial spring.

### Motion

| Token | Compose | View equivalent |
|---|---|---|
| spatial | spring(0.86, 380) | PathInterpolator(0.2,0,0,1) 360ms |
| effects | spring(1.0, 600) | 200ms |
| pressIn | 0.965 (full-width rows 0.985), spring(1.0, 1500) | 100ms |
| pressRelease | spring(0.62, 420) | overshoot 380ms |
| page | shared X axis, 30dp + fade, spring(1.0, 380); reversed on back | same |
| sheet enter | from bottom spring(0.9, 380); scrim fade 220ms | — |
| sheet / dialog exit | 220ms PathInterpolator(0.3,0,1,1) down + fade | — |
| stagger | first 8 blocks, 26ms apart, +16dp rise + fade; once per screen per process | same |
| value change | digits roll up when increasing, down when decreasing | same |
| expand / collapse | expandVertically + fade; chevron rotates 180° | height animation |

With the system animator scale at 0 every animation snaps to its end state; no logic waits for an
animation to finish (sheets keep their content composed only for display during the exit).

### Haptics

Switches: toggleOn / toggleOff. Tabs, filters, segments: segmentTick. Primary and destructive
confirmations: confirm. Cancel in a confirmation sheet: reject. Older APIs fall back to
CLOCK_TICK / CONFIRM / LONG_PRESS.

## 2. Components

1. **Screen scaffold** — edge-to-edge. Top-level screens show a large title (`TopLevelHeader`) that
   scrolls away; the shell then fades in a 17sp compact title bar with a hairline. Secondary
   screens (App detail, backup detail, diagnostics) show their large title in content and the
   floating back bar fades in the 17sp title on scroll. The module collapses its 30sp header into a
   17sp compact bar.
2. **SectionHeader + GroupCard** — section header outside, above the card. No full-width dividers
   inside cards; only inset hairlines that start at the row text.
3. **Row** — 36dp tinted icon (radius 10) + title/subtitle + trailing value/chevron/switch. The
   whole row is pressable.
4. **StatusChip / Banner** — semantic tone; tone changes cross-fade, labels fade through.
5. **Buttons** — primary (filled accent, 52dp in sheets, radius 16), secondary (accent 12% tonal),
   destructive (danger tone), text. All have press feedback; primary and destructive confirm with
   haptics.
6. **EmptyState** — 64dp tonal circle icon + headline + one subhead line + optional action.
7. **BottomSheet** — every confirmation, picker and form uses `UCloneAlertSheet` inside
   `SheetHost`: top corners 28dp, 36×4dp handle, drag-down / scrim tap / predictive back dismiss,
   spring entrance, 220ms accelerating exit.
8. **SearchField** — radius 14, raised neutral fill, 1.5dp accent outline fades in on focus.
9. **Segmented / Tabs** — the selected indicator slides on the spatial curve.

## 3. Rules kept from v1

- Labels stay practical; no marketing copy. Long package names and paths ellipsize in rows and
  scroll horizontally in dedicated path text.
- Destructive or data-overwriting actions always go through a confirmation sheet whose text states
  the source and target user.
- Do not animate root operations or logs themselves; motion only responds to user actions and state
  changes.
