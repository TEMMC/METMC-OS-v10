# METMC OS V10 — Alpine + Labwc touch desktop design

## Target device and goals

Target: Samsung Galaxy A04e (SM-A042F), Android 14, MediaTek MT6765, PowerVR Rogue GE8320/Doma, roughly 2 GB RAM. The design prioritizes genuine overlapping windows and multitasking rather than a heavyweight desktop shell.

## Desktop stack

- Alpine Linux ARM64, with the metmc unprivileged desktop account.
- Labwc as a lightweight stacking Wayland compositor; Xwayland for legacy X11 applications.
- Labwc nested in METMC's existing Android-hosted X11 display using the wlroots X11 backend. Android remains the physical display owner.
- Waybar for a touch-sized taskbar, running-window list, clock, network/audio/battery status and tray.
- Fuzzel app launcher, Foot terminal and Thunar file manager.
- Four workspaces, server-side window decorations, keyboard shortcuts and 44px-or-larger panel targets. Neon green-on-black styling extends to Labwc window decorations, Waybar, Fuzzel, GTK applications, Foot terminal and the shell prompt.
- Dark METMC theme with green accents, no blur or animations, and Pixman software rendering as a compatibility baseline for the PowerVR device.

## Touch and multitasking behavior

- Large taskbar/menu targets and readable status text.
- Tapping an app in the task list activates its window; overlapping windows can be moved, resized, minimized, maximized and restored.
- App launcher from the taskbar or Super+D; terminal from Super+Enter; window switching from Alt+Tab.
- Four workspaces reduce clutter while preserving simultaneous apps.
- Keyboard, text fields, clipboard, orientation/resize, Android Back handling and actual touch event delivery must be validated with the existing X11 input bridge. The bridge may translate taps to pointer events rather than exposing true multitouch gestures.

## Android hardware integration to carry forward

- Wi-Fi status/connectivity and Android Wi-Fi panel; Bluetooth status/discovery/settings.
- Audio volume/mute and supported routing; brightness, screen timeout, sleep/wake, battery percentage and charging state.
- Display sizing/scaling, rotation/resume handling and Android/Linux clipboard synchronization.
- Optional camera, microphone, location, sensors, flashlight, haptics and notifications, each behind feature-specific Android permissions.
- Android app launch shortcuts. Android apps remain Android activities; independently movable Android windows require supported Samsung/Android freeform or secondary-display behavior and are not guaranteed by Labwc.
- Calls/SMS only when explicitly enabled and the required Android permissions are granted; do not request them during initial desktop startup.
- Shared Android storage, rootfs backup/restore, diagnostics export, CPU/RAM/battery/storage monitor, themes/icons and explicit Linux package management.
- In-app APK self-update checks/downloads only when requested by the user. No update checks, downloads or package installs during boot or first boot.

## Kernel access and security boundary

The chroot shares Android's live kernel; it does not boot another kernel and cannot add missing vendor drivers. Bind-mounting /dev, /proc and /sys provides visibility but does not automatically authorize every operation. Access remains limited by kernel configuration, SELinux, device-node permissions, Android app UID/capabilities, Samsung drivers and firmware.

Keep SELinux Enforcing by default. Do not blanket-disable SELinux or expose unrestricted Android-host root to desktop applications. The privileged Android bridge should validate requests and expose narrow, auditable operations. Use device-node allowlists where possible and diagnose specific AVC denials before considering temporary diagnostics. Linux sudo administration applies to the chroot and must not be described as unrestricted Android-host root access.

## Offline and resource rules

- Build and package the complete Alpine ARM64 rootfs and desktop components in CI; APK startup must never download rootfs files, run apk update or install packages.
- Package installation and upgrades remain user-initiated and separate from boot.
- Avoid Flatpak, heavy desktop shells, background indexing, compositor effects and developer packages in the default image.
- Include a package manifest and checksums; preserve the previous rootfs until a replacement validates.
- Preserve the metmc home during safe migrations; keep direct root password login locked and use explicit sudo for Linux administration.
- The release is not complete until the rootfs builder, Kotlin startup/readiness checks, Alpine package management, bridge installation, Xwayland and CI tests are migrated and the result is tested on the A04e.

## Status

These files define the intended Labwc touch layout and design. They do not yet switch the active APK from its current Debian/Xfce runtime to Alpine/Labwc; that requires migrating rootfs provisioning and the Android runtime code, then passing CI and device testing.
