# METMC OS version 10

**METMC OS v10** is an independent project maintained by **Tinotenda Enock Mapfumo (Dr TEMMC)** under the GitHub account [TEMMC](https://github.com/TEMMC).

It is based on the open-source [NativOS project](https://github.com/orailnoor/NativOS) and aims to provide a touch-first Linux desktop environment on rooted ARM64 Android phones.

## Project identity

- **Project name:** METMC OS version 10
- **Maintainer:** Tinotenda Enock Mapfumo (Dr TEMMC)
- **Source repository:** https://github.com/TEMMC/METMC-OS-v10
- **Upstream project:** https://github.com/orailnoor/NativOS
- **Android application ID:** `com.temmc.metmcosv10`
- **Current development version:** 10.7.0

## Linux user and privilege model

- The desktop and interactive terminal run as the unprivileged **`metmc`** Linux user.
- Administrative commands must be invoked explicitly with **`sudo`**. The Linux root account is locked for direct password login; no default root password is configured.
- Android's rooted bridge remains responsible for mounting and managing the private chroot. This is separate from the Linux desktop user's privileges.
- Flatpak and its stored runtimes are removed from the bundled rootfs. Linux applications are managed through the normal package manager when the user explicitly requests it.

## Kernel and hardware access

METMC runs in a chroot and shares the **Android device's existing kernel**; it does not boot or install a separate Linux kernel. The app bind-mounts `/dev`, `/proc`, and `/sys` into the Linux rootfs and exposes the Android graphics device nodes needed by the embedded display. SELinux remains **Enforcing by default**; actual access is limited by the Android kernel, SELinux policy, device-node permissions, and drivers present in Samsung firmware. We will inspect specific AVC denials before considering temporary permissive-mode diagnostics. This does **not** mean arbitrary kernel-module loading, flashing, or unrestricted control of every hardware device is available from the chroot.

### Android hardware bridge

The Android app is the hardware owner; Linux requests supported operations through the app's local bridge rather than trying to replace Samsung's vendor services. The bridge currently has handlers for Wi-Fi status and Android Wi-Fi controls, Bluetooth state/discovery, audio volume and microphone, camera, location, sensors, flashlight, haptics, battery, brightness, clipboard, notifications, and Android-app launching. The Settings screen requests the Android runtime permissions needed by these features, including nearby Wi-Fi and Bluetooth permissions on Android 13/12+, plus optional call/SMS permissions. Every request remains subject to the user's grant and Android's API restrictions.

Wi-Fi connectivity is normally shared from Android into Debian. Android 10+ restricts ordinary apps from silently toggling Wi-Fi, so the bridge opens Android's own Wi-Fi panel where needed; Bluetooth and other restricted controls may similarly require Android confirmation. A declared permission is not a guarantee of direct hardware access. External USB devices, monitor mode, hotspot creation, modem control, NFC, biometrics, system-wide notification reading, and other privileged operations require additional device-specific implementation or Android special access, and are not claimed as fully working yet.

## Goals

- Establish an independently branded and reproducible build.
- Investigate touch responsiveness and frame pacing on the Samsung Galaxy A04e (SM-A042F / MediaTek MT6765).
- Make changes in small, testable commits and publish reproducible Android builds.
- Keep upstream copyright notices, licenses, and third-party attribution intact.
- Document device compatibility honestly; fixes are not claimed until tested.

## Offline startup guarantee

The Android boot path must never download a root filesystem, run `apt-get update`, install Linux packages, or contact update servers. GitHub Actions creates a fresh Debian 13 Trixie ARM64 rootfs with a minimal **Xfce + Xfwm4** desktop, a touch-sized panel/taskbar, Xfce Terminal, Thunar, the unprivileged `metmc` user, `sudo`, and essential tools. The prepared rootfs is embedded in the APK; first launch only copies and extracts the bundled asset. If the asset or required desktop files are missing, the app must show an actionable error and stop rather than attempting network recovery. Package management remains user-initiated.

## Windowed desktop and multitasking

METMC uses the Android-hosted X11 display directly and starts Xfce as the `metmc` Linux user. **Xfwm4 is the Linux window manager**: Linux GUI applications can have independent decorated windows that can be moved, resized, minimized, maximized, and switched from the Xfce panel/taskbar. The touch layout is configured with larger panel controls and a dark theme while omitting a second desktop stack to keep the image lean.

Android apps remain Android activities. METMC can publish their launchers to the Linux app menu and request bounded/freeform launches, but movable Android app windows depend on Samsung's Android WindowManager and firmware support; Xfce cannot directly turn an Android activity into an X11 window. Linux multi-window support and Android freeform windows are therefore separate features and must be tested separately on the A04e.

The chroot shares the Android kernel and does not install a separate Linux kernel. The app binds `/dev`, `/proc`, and `/sys` into the rootfs where permitted. Wi-Fi, Bluetooth, audio, camera, location, brightness, battery and other Android-owned hardware features use the Android bridge where implemented and authorized. Actual access remains subject to Android permissions, SELinux, Samsung vendor drivers, and supported APIs; it is not unrestricted hardware access. SELinux remains Enforcing by default.

## Planned Alpine + Labwc touch desktop

A touch-first Labwc design has been added under [docs/ALPINE_LABWC_TOUCH_DESIGN.md](docs/ALPINE_LABWC_TOUCH_DESIGN.md), with initial compositor, Waybar, launcher and autostart configuration in linux/config/labwc, linux/config/waybar and linux/config/fuzzel.ini. It targets a 64px taskbar, large touch targets, window task switching, four workspaces, Xwayland compatibility and the dark METMC theme.

**Important:** those are design assets only at this stage. The current APK workflow and Android startup/readiness code still build and launch Debian/Xfce. The actual Alpine/Labwc migration must replace rootfs provisioning, the desktop launcher, runtime checks and package-management paths before this design can become the active desktop. Do not install a build expecting Labwc until that migration and CI validation are complete.

## Current status

The Xfce windowed-desktop branch is an experimental development build. CI must pass and the APK must be tested on the Galaxy A04e before a release is considered verified. Touch input, keyboard behavior, window decorations, panel usability, Android freeform windows, hardware bridging, GPU behavior, RAM use, and boot time remain device-test items. No claim of full compatibility is made until those checks pass.

## Build requirements

Use the Android project under `android/` and follow the upstream build instructions and [production readiness checklist](PRODUCTION_READINESS.md). The build uses JDK 17 and Android SDK 35. The required prepared ARM64 root filesystem archive and matching SHA-256 file must be present under `android/app/src/main/assets/rootfs/`; the Gradle build intentionally fails if they are missing or do not match.

Do not commit private signing keys, user root filesystems, tokens, or personal data.

## Upstream and acknowledgements

METMC OS v10 is an independent derivative project, not an official upstream release. The original NativOS project and its author/contributors retain their original credit and copyright. The app also contains Termux:X11-derived code and other components with separate licenses. See [NOTICE.md](NOTICE.md), [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md), and [LICENSE](LICENSE).

---
Maintained by **Tinotenda Enock Mapfumo (Dr TEMMC)**.


Release publishing is performed by GitHub Actions after the APK build and signature verification pass.
