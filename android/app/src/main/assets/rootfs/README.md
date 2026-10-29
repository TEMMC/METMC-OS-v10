# MT OS V10 — bundled Ubuntu Noble root filesystem

Every APK build bundles the prepared ARM64 Ubuntu Noble root filesystem as the uncompressed
`nativos-rootfs-arm64.tar` asset plus its matching `nativos-rootfs-arm64.tar.sha256` sidecar.
Gradle verifies the SHA-256 before packaging; GitHub Actions also tests archive listing and extraction
with Toybox `tar`, then repeats those checks against the exact archive extracted from the built APK.

The production workflow creates this archive after provisioning Ubuntu 24.04 Noble and overlaying
the known-good MT-Phosh and MT-Phoc ARM64 artifacts. Normal device boot uses this bundled archive
offline and does not download a distribution or install packages.

For manual packaging, use a clean, fully provisioned ARM64 Ubuntu root filesystem that already
contains `usr/bin/apt-get`, `usr/bin/phosh-session`, `usr/libexec/phosh`, and `usr/local/bin/phoc`:

```sh
scripts/build-rootfs-asset.sh /path/to/prepared-ubuntu-rootfs \
  android/app/src/main/assets/rootfs/nativos-rootfs-arm64.tar
```

The script creates the matching `.sha256` sidecar. It requires GNU tar (`gtar` on macOS),
normalizes ownership, ordering, timestamps, and archive metadata, and records installed package
versions in `/usr/share/metmc/rootfs-packages.txt`. Set `SOURCE_DATE_EPOCH` for reproducible output.

Do not package a user's live root filesystem without reviewing it for accounts, tokens, history,
and personal data. Internal paths and filenames beginning with `nativos` remain for runtime
compatibility; they are implementation identifiers, not the user-facing product name.
