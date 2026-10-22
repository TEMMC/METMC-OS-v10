# METMC OS V10 — Cyber Terminal desktop design

## Visual direction

The METMC desktop uses a professional dark navy surface, restrained teal highlights, readable high-contrast text, rounded controls, and large touch targets. The aim is a technical workstation aesthetic rather than a wall of neon.

## Included in this branch

- GTK 3 and GTK 4 user CSS overrides for Linux applications.
- Dark GTK defaults and Adwaita icon/cursor fallbacks, avoiding a new heavy theme dependency.
- Waybar panel styling and configuration with app launcher, task list, clock, network status, audio/display bridge actions, battery, and tray.
- Fuzzel application launcher colors and spacing.
- Foot terminal palette and readable monospace configuration.
- Original SVG wallpaper asset at `linux/config/wallpapers/metmc-cyber-grid.svg`; vector format keeps the file small and scales cleanly.
- Existing Labwc configuration continues to provide stacking windows and keyboard window controls.

## Open-source components and attribution

This theme configures existing open-source components rather than copying proprietary assets: GTK, Adwaita, Waybar, Fuzzel, Foot, and Labwc. The Phosh project is GPL-3.0-or-later and is a separate mobile shell paired with Phoc; it is not automatically activated by these CSS files. See the upstream Phosh documentation: https://world.pages.gitlab.gnome.org/Phosh/phosh/gettingstarted.html

## Important implementation boundary

The current bundled rootfs build workflow prepares Alpine 3.22 and validates Labwc, Waybar, Fuzzel, Foot, and Thunar. Therefore these commits improve the current bundled Linux desktop's visual design; they do **not** switch the runtime to Phosh, and they do not by themselves create a new Phosh shell, native Android window integration, or guarantee that Android apps can become Linux windows.

## Next integration and validation work

1. Confirm CI copies `linux/config/skel` to `/etc/skel` before the `metmc` home is initialized, so the updated user config actually lands in the user's home.
2. Install the wallpaper under `/usr/share/backgrounds/metmc/` and wire it to the selected background client only if that client is present in the bundled image; no network fetches at boot.
3. Add a dedicated touch-friendly app-menu panel/launcher and visible workspace/window controls, checking which Waybar modules are compiled into the Alpine package.
4. Test Waybar JSON, CSS parsing, GTK3/GTK4 appearance, terminal legibility, portrait layout, tap target size, and memory usage in CI and on the Galaxy A04e.
5. Keep Phosh as a separate experimental target until the rootfs distribution and package availability are reconciled. Do not switch the default session without a verified boot and a fallback.

## Offline-startup requirement

Theme assets must be bundled in the APK/rootfs during CI. Runtime startup must not download wallpapers, themes, packages, fonts, or updates.
