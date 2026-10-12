# METMC OS version 10

**METMC OS v10** is the TEMMC-maintained development fork of [NativOS](https://github.com/orailnoor/NativOS), an experimental touch-first Linux desktop for rooted ARM64 Android phones.

## Project goals

- Keep the upstream NativOS source and its licensing notices intact.
- Investigate touch responsiveness and frame pacing on the Samsung Galaxy A04e (SM-A042F / MediaTek MT6765).
- Make changes in small, testable commits and publish reproducible Android builds.
- Document device compatibility honestly; performance fixes are not claimed until tested.

## Important status

This repository is being bootstrapped from upstream. The first priority is importing the source tree and establishing a reliable build workflow. No A04e-specific performance fix is claimed yet.

NativOS runs a Linux desktop in an Android-rooted environment; it is **not** a native Linux ROM and does not replace the Android kernel.

## Upstream and credits

- Original project: https://github.com/orailnoor/NativOS
- Original author: orailnoor
- Upstream releases: https://github.com/orailnoor/NativOS/releases

This fork is independently maintained by **TEMMC**. It is not an official upstream release. Preserve upstream copyright notices, third-party notices, and license files when redistributing modified builds.

## Planned work

1. Import and verify the complete upstream source.
2. Inspect the embedded X11 / Phosh rendering path and input handling.
3. Collect comparable frame-time, memory, and touch-latency measurements on the A04e.
4. Make targeted changes only where logs or measurements identify a cause.
5. Build and test a debug APK before publishing any release.

## Build

Follow the upstream build instructions in the source README and `PRODUCTION_READINESS.md`. Upstream documents JDK 17, Android SDK 35/build tools, and a prepared ARM64 rootfs asset. The large rootfs archive and private release-signing keys should not be committed to Git.

## License

NativOS is distributed under GPL-3.0, with bundled components retaining their own licenses. See the upstream `LICENSE`, `NOTICE.md`, and `THIRD_PARTY_NOTICES.md` files imported into this repository.