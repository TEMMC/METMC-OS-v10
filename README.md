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

## 🔐 Default Linux root password

> **DEFAULT ROOT PASSWORD: `metmc`**

The Linux chroot sets the **root** account password to **`metmc`**. This is the Linux password inside METMC OS, not your Android lock-screen password. Change it after setup if you need stronger local security. The Android-side root bridge manages the chroot independently of this password.

**Terminal troubleshooting:** the previous global Bash startup redirect to `su - metmc` could replace the interactive shell and leave a blank terminal if the handoff failed. The app now removes that legacy redirect instead of injecting it into `/etc/bash.bashrc`. This makes the shell-handoff regression a stronger explanation for the black terminal than the root password itself; the password change alone is not evidence that the terminal is fixed.

## Goals

- Establish an independently branded and reproducible build.
- Investigate touch responsiveness and frame pacing on the Samsung Galaxy A04e (SM-A042F / MediaTek MT6765).
- Make changes in small, testable commits and publish reproducible Android builds.
- Keep upstream copyright notices, licenses, and third-party attribution intact.
- Document device compatibility honestly; fixes are not claimed until tested.

## Offline startup guarantee

The Android boot path must never download a root filesystem, run `apt-get update`, install Linux packages, or contact Flathub/update servers. The GitHub Actions build downloads the upstream rootfs, provisions Xfce and essential tools inside the ARM64 rootfs on the runner, validates the required binaries, and embeds the prepared archive in the APK; first launch only copies and extracts that bundled asset. The bundled rootfs must already contain Xfce, the essential tools, and `sudo`. If the asset or required desktop files are missing, the app must show an actionable error and stop rather than attempting network recovery. Manual package management from the Linux desktop remains user-initiated.

## Current status

The NativOS source has been imported and rebranded as METMC OS v10. The app includes Android hardware controls, CPU/RAM status, and an opt-in system diagnostics export. v10.7.0 uses lightweight Xfce/Xfwm4 on the embedded X11 display, with a dark green hacker-style theme, a touch-sized panel/taskbar, and Xfce Terminal. It retains the guarded Linux package manager with quick-access desktop controls, a live system monitor, shared-folder access, update-center links, Linux configuration backup and rootfs validation. The app is labelled METMC OS V10. Selecting METMC OS V10 as Android's default Home app lets Android open it after boot. Package upgrades affect only the Linux rootfs. Includes compact network diagnostics (iproute2, ping, DNS tools, traceroute, netcat, nmap, whois, curl, wget and SSH client). Desktop performance and touch behavior still need on-device testing.

This project runs a Linux desktop within an Android-rooted environment. It is **not** a native Linux ROM and does not replace the Android kernel.

## Android apps in the Xfce desktop

METMC OS publishes installed Android launchers in the Linux application menu. Selecting one asks Android to launch that app as a separate Android task with a preferred window size, and startup tries to enable Android's `enable_freeform_support` and `force_resizable_activities` settings. **This is best-effort only**: Android must support freeform windows in its framework/SystemUI for the app to appear as a movable, resizable floating window.

Android apps are still rendered and managed by Android's WindowManager, not by Xfce's `xfwm4`. Xfce cannot directly adopt an existing Android activity surface as an X11 window. If this Samsung firmware ignores freeform mode, Android apps may take over the screen; the launch-bounds request cannot force a floating window by itself. METMC logs the freeform feature/settings probe for diagnostics. No package downloads are performed to enable this feature.

## Build requirements

Use the Android project under `android/` and follow the upstream build instructions and [production readiness checklist](PRODUCTION_READINESS.md). The build uses JDK 17 and Android SDK 35. The required prepared ARM64 root filesystem archive and matching SHA-256 file must be present under `android/app/src/main/assets/rootfs/`; the Gradle build intentionally fails if they are missing or do not match.

Do not commit private signing keys, user root filesystems, tokens, or personal data.

## Upstream and acknowledgements

METMC OS v10 is an independent derivative project, not an official upstream release. The original NativOS project and its author/contributors retain their original credit and copyright. The app also contains Termux:X11-derived code and other components with separate licenses. See [NOTICE.md](NOTICE.md), [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md), and [LICENSE](LICENSE).

---
Maintained by **Tinotenda Enock Mapfumo (Dr TEMMC)**.
