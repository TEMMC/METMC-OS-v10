# MT OS rootfs protection policy

## Ownership boundary

The Android APK and the Linux rootfs have separate lifecycles. The rootfs at
`filesDir/rootfs` is persistent user data. Installing or upgrading the APK must
not delete, replace, re-extract, migrate, or overlay files onto that directory.

An APK upgrade may update Android application code and app-owned assets. It
must not run the rootfs installer against an existing installation.

## Startup behavior

1. If a supported rootfs exists, validate it read-only and start its installed
   desktop. Do not stage the APK-bundled archive.
2. If the rootfs path exists but is incomplete or unsupported, stop with a
   diagnostic. Do not treat it as a fresh installation and do not overwrite it.
3. Only when the rootfs path does not exist may first-install extraction run.
4. Validate the extracted distribution and essential directories before making
   it the active rootfs.
5. Validate MT-Phosh/MT-Phoc independently from Labwc. A Phosh installation
   must not be rejected because optional Labwc packages are absent.

## First-install extraction

The archive is extracted under `rootfs-installing`, not the live rootfs path.
Configuration and structural validation run against this staging directory.
Only a successful validation permits a same-filesystem rename to `rootfs`.
On failure, only the staging directory is discarded; the archive remains
available for another first-install attempt.

The installer refuses to run if `rootfs` already exists, even if that
directory is incomplete. This is deliberate: automatic repair must never
silently destroy potentially recoverable user data.

## Explicit rootfs upgrades

A future rootfs upgrade feature must be a separate, user-confirmed operation.
It must:
- stop the Linux session and unmount all rootfs bind mounts;
- check free storage and create a metadata-preserving backup;
- stage the new rootfs in a separate directory;
- validate its distribution, architecture, MT desktop, bridge, and configuration;
- preserve user home/configuration using a deliberate migration plan;
- switch only after validation and retain a rollback copy until boot validation
  succeeds.

No existing APK update path is authorized to perform these steps.

## Limits

Android normally preserves an app's private files during an in-place APK
upgrade. Android does not guarantee preservation if the user clears app data,
uninstalls the app, or the operating system removes its private data. This
protection layer cannot prevent those Android-level actions. Users should keep
an external backup for important Linux data.

## Required regression checks

- APK upgrade with an existing Ubuntu rootfs: no rootfs extraction or migration.
- APK upgrade with an existing Alpine rootfs: no rootfs extraction or migration.
- Existing incomplete rootfs: startup fails safely without replacing it.
- Fresh install: extraction occurs only in `rootfs-installing`.
- Failed extraction or validation: no active rootfs is promoted; archive remains.
- Valid MT-Phosh/MT-Phoc rootfs without Labwc: Phosh runtime validation succeeds.
- Valid Labwc rootfs: Labwc validation and touch-layout seeding remain available.
