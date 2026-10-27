# MT OS — bundled Ubuntu Linux root filesystem

Every APK build requires `nativos-rootfs-arm64.tar` and its matching `nativos-rootfs-arm64.tar.sha256` in this directory. Gradle verifies the archive checksum before packaging.

Build the archive from a prepared Ubuntu ARM64 root filesystem that already contains the MT-Phosh and MT-Phoc runtime, Python 3, Xwayland, the MT bridge dependencies, essential networking tools, sudo, icons, and required compatibility libraries:

```sh
scripts/build-rootfs-asset.sh /path/to/prepared-ubuntu-rootfs \
  android/app/src/main/assets/rootfs/nativos-rootfs-arm64.tar
```

The packaging script requires GNU tar and normalizes ownership, ordering, timestamps, and metadata for reproducible builds. It records installed Debian package versions in `/usr/share/metmc/rootfs-packages.txt`. Set `SOURCE_DATE_EPOCH` when producing a reproducible release.

The archive is a plain TAR, not gzip-compressed: Android extracts it with `tar xf`. The archive must contain the root filesystem contents at its top level (for example, `etc/os-release`), not an extra enclosing directory. Do not package a user's live root filesystem without reviewing it for accounts, tokens, history, and personal data.

Internal paths and filenames beginning with `nativos` are retained for runtime compatibility; they are implementation identifiers, not the user-facing product name.
