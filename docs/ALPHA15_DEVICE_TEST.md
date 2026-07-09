# Alpha 15 Device Test

Focus: stacked glass-card launcher icon with direct manifest PNG entry.

## Launcher icon

1. Install the APK over the previous version.
2. On the package installer confirmation screen, verify the app icon already shows the stacked glass-card icon.
3. If the launcher keeps the old icon after install, restart the launcher or reboot once.
4. Verify the UClone icon:
   - icon uses three stacked liquid-glass cards;
   - no letters, numbers, arrows, or black top bars;
   - card edges are not clipped by round or rounded-square launcher masks;
   - small desktop icon remains readable and does not look like a white bordered screenshot.

## Notes

The manifest points directly to `@drawable/ic_launcher_card` for both `android:icon` and `android:roundIcon`. The adaptive icon resources remain in the project, but the package installer should no longer need to resolve the adaptive XML path.
