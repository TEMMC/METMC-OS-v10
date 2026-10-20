# METMC OS v10 — bundled Linux root filesystem

Every APK build requires `nativos-rootfs-arm64.tgz` and its matching `nativos-rootfs-arm64.tgz.sha256` in this directory. Gradle fails before packaging if either file is absent or the checksum does not match.

Generate the archive from a clean, fully provisioned ARM64 root filesystem:

```sh
scripts/build-rootfs-asset.sh /path/to/prepared-rootfs \
  android/app/src/main/assets/rootfs/nativos-rootfs-arm64.tgz
```

The prepared image must already contain Xfce/Xfwm4, Xfce Terminal, Thunar, essential networking tools, sudo, icons, and the compatibility libraries expected by the METMC OS runtime. Do not package a user's live root filesystem without reviewing it for accounts, tokens, history, and personal data.

The packaging script requires GNU tar (`gtar` on macOS), normalizes ownership, ordering, timestamps, and gzip metadata, and records installed package versions in `/usr/share/nativos/rootfs-packages.txt`. Set `SOURCE_DATE_EPOCH` when producing a reproducible release.

Internal paths and filenames beginning with `nativos` are retained for runtime compatibility; they are implementation identifiers, not the user-facing product name.
