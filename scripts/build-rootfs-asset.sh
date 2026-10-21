#!/bin/sh
set -eu

if [ "$#" -ne 2 ]; then
    echo "usage: $0 PREPARED_ALPINE_ROOTFS OUTPUT_TAR" >&2
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

for executable in bin/bash usr/bin/labwc usr/bin/waybar usr/bin/fuzzel usr/bin/foot usr/bin/thunar sbin/apk; do
    test -x "$rootfs/$executable" || { echo "Missing Alpine runtime component: $executable" >&2; exit 1; }
done

staging=$(mktemp -d "${TMPDIR:-/tmp}/metmc-alpine-rootfs.XXXXXX")
trap 'rm -rf "$staging"' EXIT HUP INT TERM
cp -a "$rootfs"/. "$staging"/
rm -f "$staging/usr/bin/qemu-aarch64-static"
rm -rf "$staging/tmp"/* "$staging/run"/* "$staging/var/cache/apk"/*
rm -f "$staging/etc/machine-id" "$staging/var/lib/dbus/machine-id"
mkdir -p "$staging/tmp" "$staging/run" "$staging/var/tmp" "$staging/usr/share/metmc"
chmod 1777 "$staging/tmp" "$staging/var/tmp"

# Save an exact package inventory from Alpine's installed package database.
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

cat > "$staging/usr/share/metmc/rootfs-build.txt" <<EOF
schema=2
distribution=alpine
release=3.22
architecture=aarch64
source_date_epoch=$source_date_epoch
EOF

mkdir -p "$(dirname "$output")"
archive="$output"
"$tar_bin" --sort=name --mtime="@$source_date_epoch" --owner=0 --group=0 --numeric-owner --xattrs --acls --pax-option=delete=atime,delete=ctime --exclude='./dev/*' --exclude='./proc/*' --exclude='./sys/*' --exclude='./rootfs.tar' -C "$staging" -cf "$archive" .
checksum=$(sha256sum "$output" | awk '{print $1}')
printf '%s  %s\n' "$checksum" "$(basename "$output")" > "$output.sha256"
echo "Created uncompressed Alpine rootfs: $output"
echo "Created checksum: $output.sha256"
