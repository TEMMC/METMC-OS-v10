# MT OS V10 — external Ubuntu Noble root filesystem

The Android APK is intentionally built separately from the Linux root filesystem.
This directory does not contain a rootfs archive. The standalone ARM64 rootfs is
published as a separate release asset and is extracted by the user with Termux.

Expected app-private destination:

`/data/user/0/com.temmc.metmcosv10/files/rootfs`

The rootfs must be Ubuntu 24.04 Noble ARM64 and include the prebuilt MT-Phosh,
MT-Phoc, and MT-Bridge runtime components. Preserve the original NativOS Phoc
display configuration at `/etc/nativOS/phoc.ini` and
`/home/metmc/.config/phoc.ini`.

Before launching the app, verify the downloaded rootfs archive against its
published SHA-256 checksum. Extract it as root into the destination above.
The app validates the existing filesystem and mounts it; it does not download,
replace, or re-extract the rootfs during boot.

This is an Android chroot root filesystem, not a flashable ROM or a separate
Linux kernel.
