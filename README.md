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

GitHub Actions prepares a Ubuntu 24.04 ARM64 root filesystem with pinned MT-Phosh and MT-Phoc runtime artifacts, Xwayland, the MT Android bridge, a non-root `metmc` user, `sudo`, and essential networking tools. The prepared root filesystem is packaged as a plain TAR asset and bundled inside the APK. First launch copies and extracts that asset locally; boot must never download a rootfs or install packages.

## Windowed desktop and multitasking

METMC's intended desktop is **Labwc + Wayland + Xwayland**, nested into the Android-hosted X11 display. Labwc provides stacking windows; Waybar provides the running-window taskbar; Fuzzel launches apps; Foot is the terminal; and Thunar is the file manager. The neon green-on-black METMC Hacker theme applies to window decorations, taskbar, launcher, GTK defaults and terminal. Large taskbar targets and four workspaces are intended to make multitasking usable on a phone. The nested compositor and touch/window behavior still require CI and on-device validation.

Android apps remain Android activities. METMC can publish their launchers to the Linux app menu and request bounded/freeform launches, but movable Android app windows depend on Samsung's Android WindowManager and firmware support; Labwc cannot directly turn an Android activity into a Linux window. Linux multi-window support and Android freeform windows are separate features.

The chroot shares the Android kernel and does not install a separate Linux kernel. The app binds `/dev`, `/proc`, and `/sys` into the rootfs where permitted. Wi-Fi, Bluetooth, audio, camera, location, brightness, battery and other Android-owned hardware features use the Android bridge where implemented and authorized. Actual access remains subject to Android permissions, SELinux, Samsung vendor drivers, and supported APIs; it is not unrestricted hardware access. SELinux remains Enforcing by default.

## Touch design and compact tools

The initial desktop configuration lives under `linux/config/skel/` and `linux/config/labwc/theme/METMC-Hacker/`. The default image includes a dark theme, neon terminal prompt, taskbar, app launcher, multi-window shortcuts, and small utilities such as `nano`, `less`, `htop`, `curl`, `wget`, `git`, `ssh`, `ip`, `ping`, `dig`, `traceroute`, `nc`, `nmap`, `whois`, `jq`, `python3`, `tmux`, `rsync`, `zip`, and `unzip`. The goal is a compressed, bundled image rather than package installation during first boot.

## Current status

Ubuntu with MT-Phosh/MT-Phoc is the only supported Linux runtime. The bundled rootfs asset, checksum, packaging script, and APK extraction format must stay aligned.

## Build requirements

Use the Android project under `android/` and follow the upstream build instructions and [production readiness checklist](PRODUCTION_READINESS.md). The build uses JDK 17 and Android SDK 35. The required prepared ARM64 root filesystem archive and matching SHA-256 file must be present under `android/app/src/main/assets/rootfs/`; the Gradle build intentionally fails if they are missing or do not match.

Do not commit private signing keys, user root filesystems, tokens, or personal data.

## Upstream and acknowledgements

METMC OS v10 is an independent derivative project, not an official upstream release. The original NativOS project and its author/contributors retain their original credit and copyright. The app also contains Termux:X11-derived code and other components with separate licenses. See [NOTICE.md](NOTICE.md), [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md), and [LICENSE](LICENSE).

---
Maintained by **Tinotenda Enock Mapfumo (Dr TEMMC)**.


Release publishing is performed by GitHub Actions after the APK build and signature verification pass.


## MT desktop source integration

The repository now pins the MT desktop sources in `integrations/mt-desktop-sources.yml`:
- **MT-Phosh:** `TEMMC/MT-phosh-`, branch `main`.
- **MT-Phoc:** `TEMMC/MT-phoc`, branch `mt-desktop-abc-experimental`.
- **MT Android–Linux bridge:** bridge protocol/service in the Android app plus the Linux clients under `linux/bridge-client/`.

The **Integrate MT Desktop Sources** GitHub Actions workflow checks out those exact source branches, validates the source trees and bridge files, and publishes a checksum-protected source bundle. The Android APK build also bundles the MT bridge CLI and shared socket transport into the Linux rootfs.

**Runtime status:** the APK workflow prepares an Ubuntu ARM64 rootfs and overlays pinned MT-Phosh and MT-Phoc install artifacts. CI success and on-device Galaxy A04e validation are still required before claiming the runtime is verified.
