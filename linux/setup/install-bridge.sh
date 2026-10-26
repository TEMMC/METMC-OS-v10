#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════
#  NativOS — Install Bridge Client in Chroot
#
#  Installs the Python bridge daemon and systemd service
#  that connects Linux apps to Android hardware APIs.
# ═══════════════════════════════════════════════════════════════

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BRIDGE_DIR="$SCRIPT_DIR/../bridge-client"
CONFIG_DIR="$SCRIPT_DIR/../config"

echo "╔══════════════════════════════════════════╗"
echo "║    NativOS — Installing Bridge Client    ║"
echo "╚══════════════════════════════════════════╝"
echo ""

# ── Install Python dependencies ──
echo "[1/4] Installing Python 3..."
export DEBIAN_FRONTEND=noninteractive
apt-get install -y --no-install-recommends python3 python3-pip python3-dbus wl-clipboard \
  mesa-utils libegl1 libgl1 libgles2 libgbm1 libdrm2 2>/dev/null || true

# ── Copy bridge daemon ──
echo "[2/4] Installing bridge daemon..."
cp "$BRIDGE_DIR/bridge_transport.py" /usr/local/bin/bridge_transport.py
chmod +x /usr/local/bin/bridge_transport.py
cp "$BRIDGE_DIR/nativOS-bridge.py" /usr/local/bin/nativOS-bridge
chmod +x /usr/local/bin/nativOS-bridge
cp "$BRIDGE_DIR/mt-bridge.py" /usr/local/bin/mt-bridge
chmod +x /usr/local/bin/mt-bridge
cp "$SCRIPT_DIR/mt-gpu-status" /usr/local/bin/mt-gpu-status
chmod +x /usr/local/bin/mt-gpu-status

# Install Phosh-accessible Wi-Fi controls backed by Android Wi-Fi APIs.
cp "$BRIDGE_DIR/metmc-wifi.py" /usr/local/bin/metmc-wifi
chmod +x /usr/local/bin/metmc-wifi
cp "$BRIDGE_DIR/metmc-settings.py" /usr/local/bin/metmc-settings
chmod +x /usr/local/bin/metmc-settings

# ── Configure safe GPU defaults and diagnostics ──
mkdir -p /etc/profile.d
cp "$SCRIPT_DIR/../config/mt-gpu.sh" /etc/profile.d/mt-gpu.sh
chmod 0644 /etc/profile.d/mt-gpu.sh

# ── Copy phoc config ──
echo "[3/4] Installing Phoc configuration and MT GPU helper..."
mkdir -p /etc/nativOS
cp "$CONFIG_DIR/phoc.ini" /etc/nativOS/phoc.ini
mkdir -p /usr/share/applications
cp "$CONFIG_DIR/metmc-wifi.desktop" /usr/share/applications/metmc-wifi.desktop
for launcher in metmc-bluetooth metmc-settings metmc-display metmc-sound metmc-location; do
    cp "$CONFIG_DIR/$launcher.desktop" "/usr/share/applications/$launcher.desktop"
done

# ── Install systemd service ──
echo "[4/4] Installing systemd service..."
cp "$CONFIG_DIR/nativOS-bridge.service" /etc/systemd/system/
systemctl daemon-reload 2>/dev/null || true
systemctl enable nativOS-bridge.service 2>/dev/null || true

# ── Create bridge socket directory ──
mkdir -p /run/nativOS

echo ""
echo "✅ Bridge client installed!"
echo "   Daemon: /usr/local/bin/nativOS-bridge"
echo "   CLI: /usr/local/bin/mt-bridge"
echo "   GPU diagnostics: /usr/local/bin/mt-gpu-status"
echo "   Service: nativOS-bridge.service"
echo "   Socket: /run/nativOS/bridge.sock (bind-mounted from Android)"
echo "   Wi-Fi: launch METMC Wi-Fi from Phosh or run metmc-wifi status/open
   Hardware: launch Bluetooth, Display, Sound, Location, or METMC Device Settings from Phosh"
