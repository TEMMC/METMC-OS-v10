#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════
#  METMC OS — Install KDE Plasma Desktop Environment in Chroot
#
#  Run this inside the chroot Ubuntu filesystem to install
#  KDE Plasma desktop shell and all required dependencies.
#
#  Usage: chroot /path/to/rootfs /bin/bash /setup/install-plasma.sh
# ═══════════════════════════════════════════════════════════════

set -e

echo "╔══════════════════════════════════════════╗"
echo "║       NativOS — Installing KDE Plasma         ║"
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

# ── Phone apps (GNOME Calls, Chatty for SMS, Camera) ──
echo "[6/8] Installing phone applications..."
apt-get install -y --no-install-recommends \
    gnome-calls \
    chatty \
    megapixels \
    gnome-contacts \
    gnome-clocks \
    gnome-calculator \
    gnome-text-editor \
    nautilus \
    gnome-terminal \
    firefox-esr \
    2>/dev/null || {
    echo "[!] Some phone apps not available — installing alternatives..."
    apt-get install -y --no-install-recommends \
        gnome-terminal \
        firefox-esr \
        2>/dev/null || true
}

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

# ── KDE Plasma settings, desktop tweaks, Bluetooth tools ──
echo "[8/10] Installing KDE Plasma settings and desktop customization tools..."
for package in plasma-mobile-settings gnome-control-center gnome-tweaks dconf-editor; do
    if apt-cache show "$package" >/dev/null 2>&1; then
        apt-get install -y --no-install-recommends "$package" || echo "[!] Could not install optional package: $package"
    else
        echo "[!] Package not available in configured Ubuntu repositories: $package"
    fi
done
# Useful Bluetooth diagnostics only. Android remains the owner of the physical Bluetooth radio;
# do not start a competing Linux NetworkManager/BlueZ radio manager on this Android-hosted setup.
for package in bluez rfkill; do
    if apt-cache show "$package" >/dev/null 2>&1; then
        apt-get install -y --no-install-recommends "$package" || echo "[!] Could not install optional package: $package"
    fi
done

# ── Remove Flatpak to keep the Linux rootfs lean ──
echo "[9/10] Removing Flatpak and its software-center integrations..."
apt-get purge -y plasma-discover-backend-flatpak 2>/dev/null || true
# Remove Flatpak system installation data only; user files outside the rootfs are untouched.
rm -rf /var/lib/flatpak/repo /var/cache/flatpak

# ── Cleanup ──
echo "[10/10] Cleaning up..."
apt-get clean
rm -rf /var/lib/apt/lists/*

# ── Create NativOS config directory ──
mkdir -p /etc/nativOS

echo ""
echo "╔══════════════════════════════════════════╗"
echo "║    KDE Plasma install complete              ║"
echo "╚══════════════════════════════════════════╝"
echo ""
echo "Next: run install-bridge.sh to add METMC Wi-Fi, Bluetooth, display, sound, location and system-settings launchers"
