#!/bin/sh
set -eu

if [ "$#" -ne 2 ]; then
    echo "usage: $0 PREPARED_ROOTFS OUTPUT_TAR" >&2
    exit 2
fi
rootfs=$1
output=$2
source_date_epoch=${SOURCE_DATE_EPOCH:-1704067200}

if [ -n "${TAR_BIN:-}" ]; then tar_bin=$TAR_BIN
elif command -v gtar >/dev/null 2>&1; then tar_bin=gtar
else tar_bin=tar
fi
if ! "$tar_bin" --version 2>/dev/null | grep -q 'GNU tar'; then
    echo "GNU tar is required for a reproducible rootfs." >&2
    exit 2
fi

if [ -x "$rootfs/sbin/apk" ]; then
    distro=alpine
    release=3.22
    for executable in bin/bash usr/bin/labwc usr/bin/waybar usr/bin/fuzzel usr/bin/foot usr/bin/thunar sbin/apk; do
        test -x "$rootfs/$executable" || { echo "Missing Alpine runtime component: $executable" >&2; exit 1; }
    done
else
    distro=ubuntu
    release=noble
    for executable in bin/bash usr/bin/apt-get usr/bin/phosh-session usr/bin/phoc usr/libexec/phosh; do
        test -x "$rootfs/$executable" || { echo "Missing Ubuntu MT desktop component: $executable" >&2; exit 1; }
    done
fi

staging=$(mktemp -d "${TMPDIR:-/tmp}/mt-rootfs.XXXXXX")
trap 'rm -rf "$staging"' EXIT HUP INT TERM
cp -a "$rootfs"/. "$staging"/
rm -f "$staging/usr/bin/qemu-aarch64-static"
rm -rf "$staging/tmp"/* "$staging/run"/* "$staging/var/cache/apt/archives"/* "$staging/var/lib/apt/lists"/* "$staging/var/cache/apk"/*
rm -f "$staging/etc/machine-id" "$staging/var/lib/dbus/machine-id"
mkdir -p "$staging/tmp" "$staging/run" "$staging/var/tmp" "$staging/usr/share/metmc"
chmod 1777 "$staging/tmp" "$staging/var/tmp"

if [ "$distro" = alpine ]; then
    awk -F: '
      /^P:/ { package=substr($0,3) }
      /^V:/ { version=substr($0,3) }
      /^A:/ { arch=substr($0,3) }
      /^$/ {
        if (package != "" && version != "") print package "=" version " [" arch "]"
        package=""; version=""; arch=""
      }
      END { if (package != "" && version != "") print package "=" version " [" arch "]" }
    ' "$staging/lib/apk/db/installed" | LC_ALL=C sort > "$staging/usr/share/metmc/rootfs-packages.txt"
else
    awk '
      /^Package: / { package=$2 }
      /^Version: / { version=$2 }
      /^Architecture: / { arch=$2 }
      /^Status: install ok installed/ { installed=1 }
      /^$/ {
        if (installed && package != "" && version != "") print package "=" version " [" arch "]"
        package=""; version=""; arch=""; installed=0
      }
      END { if (installed && package != "" && version != "") print package "=" version " [" arch "]" }
    ' "$staging/var/lib/dpkg/status" | LC_ALL=C sort > "$staging/usr/share/metmc/rootfs-packages.txt"
fi

cat > "$staging/usr/share/metmc/rootfs-build.txt" <<EOF
schema=3
distribution=$distro
release=$release
architecture=aarch64
desktop=mt-phosh-phoc
source_date_epoch=$source_date_epoch
EOF

mkdir -p "$(dirname "$output")"
"$tar_bin" --sort=name --mtime="@$source_date_epoch" --owner=0 --group=0 --numeric-owner --xattrs --acls --pax-option=delete=atime,delete=ctime --exclude='./dev/*' --exclude='./proc/*' --exclude='./sys/*' --exclude='./rootfs.tar' -C "$staging" -cf "$output" .
checksum=$(sha256sum "$output" | awk '{print $1}')
printf '%s  %s\n' "$checksum" "$(basename "$output")" > "$output.sha256"
echo "Created reproducible $distro $release ARM64 rootfs: $output"
echo "Created checksum: $output.sha256"
