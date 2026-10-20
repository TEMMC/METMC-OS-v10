#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════
#  METMC OS — Install KDE Plasma Desktop Environment in Chroot
#
#  Run this inside the chroot Ubuntu filesystem to install
#  KDE Plasma desktop shell and all required dependencies.
#
#  Usage: chroot /path/to/rootfs /bin/bash /setup/install-phosh.sh
# ═══════════════════════════════════════════════════════════════

set -e

echo "╔══════════════════════════════════════════╗"
echo "║       METMC OS — Installing KDE Plasma        ║"
echo "╚══════════════════════════════════════════╝"
echo ""

export DEBIAN_FRONTEND=noninteractive
export TZ=Etc/UTC

# ── Update package index ──
echo "[1/8] Updating package lists..."
apt-get update -y -q

# ── Core X11/Wayland dependencies ──
echo "[2/8] Installing display server dependencies..."
apt-get install -y --no-install-recommends \
    xwayland \
    weston \
    x11-utils \
    x11-xserver-utils \
    dbus-x11 \
    upower \
    ca-certificates \
    locales \
    wget \
    curl

# ── Install KDE Plasma for the embedded Android X11 display ──
echo "[3/8] Installing KDE Plasma and KWin X11..."
apt-get install -y --no-install-recommends \
    plasma-desktop plasma-workspace kwin-x11 konsole dolphin \
    systemsettings plasma-nm xwayland x11-xserver-utils \
    dbus-x11 policykit-1 || exit 1

# ── Optional on-screen keyboard ──
echo "[4/8] Installing an on-screen keyboard..."
apt-get install -y --no-install-recommends maliit-keyboard 2>/dev/null || \
    echo "[!] Optional on-screen keyboard package unavailable"

# ── KDE utilities (optional; do not fail the desktop install if unavailable) ──
echo "[5/8] Installing optional KDE utilities..."
for package in kcalc ark okular plasma-systemmonitor; do
    if apt-cache show "$package" >/dev/null 2>&1; then
        apt-get install -y --no-install-recommends "$package" || echo "[!] Could not install optional package: $package"
    fi
done

# ── Audio (PipeWire) ──
echo "[7/8] Installing audio stack..."
apt-get install -y --no-install-recommends \
    pipewire \
    pipewire-pulse \
    wireplumber \
    2>/dev/null || \
    apt-get install -y --no-install-recommends pulseaudio 2>/dev/null || true

# ── Feedback daemon (haptics, LEDs) ──
apt-get install -y --no-install-recommends \
    feedbackd \
    2>/dev/null || echo "[!] feedbackd not available"

# ── Location service ──
apt-get install -y --no-install-recommends \
    geoclue-2.0 \
    2>/dev/null || echo "[!] geoclue not available"

# ── Sensors ──
apt-get install -y --no-install-recommends \
    iio-sensor-proxy \
    2>/dev/null || echo "[!] iio-sensor-proxy not available"

# ── Optional KDE settings and Bluetooth diagnostics ──
echo "[8/10] Installing optional KDE settings and hardware diagnostics..."
for package in plasma-systemmonitor plasma-discover bluez rfkill; do
    if apt-cache show "$package" >/dev/null 2>&1; then
        apt-get install -y --no-install-recommends "$package" || echo "[!] Could not install optional package: $package"
    fi
done

# ── Cleanup ──
echo "[10/10] Cleaning up..."
apt-get clean
rm -rf /var/lib/apt/lists/*

# ── Create METMC configuration directory ──
mkdir -p /etc/metmc

echo ""
echo "╔══════════════════════════════════════════╗"
echo "║    KDE Plasma install complete              ║"
echo "╚══════════════════════════════════════════╝"
echo ""
echo "Next: run install-bridge.sh to add METMC Wi-Fi, Bluetooth, display, sound, location and system-settings launchers"
