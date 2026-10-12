# METMC OS version 10 — production readiness

This checklist is inherited from the NativOS upstream baseline. Checkmarks describe upstream-reported implementation or tests and **do not mean that TEMMC has independently verified them on the Galaxy A04e**. Verify each item on METMC OS v10 before declaring it complete for a release.

## Upstream-reported baseline

- [x] Bundled X11 server; no external Termux:X11 dependency
- [x] Bundled Linux rootfs boots without downloading the desktop
- [x] Root permission can be granted and retried without restarting the app
- [x] Upstream reports Phosh rendering on Galaxy S23, Poco F1, Moto G71, and Pixel 6a
- [x] Upstream reports GPU and software-rendering paths for its tested devices
- [x] Upstream reports display cutout and Android navigation inset handling

## METMC OS v10 release work

1. **Identity and build**
   - [x] Set app display name to METMC OS version 10
   - [x] Set application ID to `com.temmc.metmcosv10`
   - [x] Add maintainer attribution and project source to the About screen
   - [ ] Produce a clean APK build from this repository
   - [ ] Install and launch the new APK on the Samsung Galaxy A04e (SM-A042F)
   - [ ] Confirm the app can be upgraded and that its package identity is correct

2. **Touch responsiveness and rendering**
   - [ ] Record a baseline for touch latency and frame pacing on the Galaxy A04e
   - [ ] Inspect X11 input event delivery and rendering frame times
   - [ ] Apply only targeted changes supported by measurements
   - [ ] Re-test taps, scrolling, swipes, rotation, and long sessions

3. **Reproducible bundled rootfs**
   - [x] Require a prepared rootfs archive and matching SHA-256 checksum during builds
   - [ ] Pin source repositories and package versions used to provision the rootfs
   - [ ] Produce a complete licensing and source manifest for redistributed components

4. **Runtime recovery and diagnostics**
   - [ ] Recover from X11, Phoc, or Phosh crashes without leaving a black screen
   - [ ] Detect boot timeouts and show actionable errors
   - [ ] Add a privacy-reviewed diagnostic export
   - [ ] Verify notifications, audio, calling, suspend, and resume

5. **Root and security hardening**
   - [ ] Review every privileged command and filesystem target
   - [ ] Audit Android bridge access and exported components
   - [ ] Test system-app removal safeguards and recovery guidance
   - [ ] Test supported root managers

6. **Release candidate**
   - [ ] Automate reproducible CI builds
   - [ ] Audit logs for sensitive information
   - [ ] Complete accessibility, localization, battery, and thermal testing
   - [ ] Test install, upgrade, and uninstall on the declared support devices
   - [ ] Publish requirements, limitations, licenses, and recovery documentation

## Release gate

METMC OS v10 is ready for release only when the relevant checks have been completed and verified on the devices explicitly listed as supported. Do not describe an upstream-reported test as a TEMMC device test unless it has actually been repeated.
