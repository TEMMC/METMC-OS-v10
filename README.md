# METMC OS version 10

**METMC OS v10** is an independent project maintained by **Tinotenda Enock Mapfumo (Dr TEMMC)** under the GitHub account [TEMMC](https://github.com/TEMMC).

It is based on the open-source [NativOS project](https://github.com/orailnoor/NativOS) and aims to provide a touch-first Linux desktop environment on rooted ARM64 Android phones.

## Project identity

- **Project name:** METMC OS version 10
- **Maintainer:** Tinotenda Enock Mapfumo (Dr TEMMC)
- **Source repository:** https://github.com/TEMMC/METMC-OS-v10
- **Upstream project:** https://github.com/orailnoor/NativOS
- **Android application ID:** `com.temmc.metmcosv10`
- **Current development version:** 10.0.0-alpha

## Goals

- Establish an independently branded and reproducible build.
- Investigate touch responsiveness and frame pacing on the Samsung Galaxy A04e (SM-A042F / MediaTek MT6765).
- Make changes in small, testable commits and publish reproducible Android builds.
- Keep upstream copyright notices, licenses, and third-party attribution intact.
- Document device compatibility honestly; fixes are not claimed until tested.

## Current status

The NativOS source has been imported and the Android app identity, display name, project metadata, and About screen have been updated for METMC OS v10. **A new APK has not yet been built or tested.** No A04e-specific performance fix is claimed yet.

This project runs a Linux desktop within an Android-rooted environment. It is **not** a native Linux ROM and does not replace the Android kernel.

## Build requirements

Use the Android project under `android/` and follow the upstream build instructions and [production readiness checklist](PRODUCTION_READINESS.md). The build uses JDK 17 and Android SDK 35. The required prepared ARM64 root filesystem archive and matching SHA-256 file must be present under `android/app/src/main/assets/rootfs/`; the Gradle build intentionally fails if they are missing or do not match.

Do not commit private signing keys, user root filesystems, tokens, or personal data.

## Upstream and acknowledgements

METMC OS v10 is an independent derivative project, not an official upstream release. The original NativOS project and its author/contributors retain their original credit and copyright. The app also contains Termux:X11-derived code and other components with separate licenses. See [NOTICE.md](NOTICE.md), [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md), and [LICENSE](LICENSE).

---
Maintained by **Tinotenda Enock Mapfumo (Dr TEMMC)**.
