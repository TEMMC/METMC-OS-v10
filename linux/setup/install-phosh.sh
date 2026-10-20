#!/usr/bin/env bash
# METMC OS — lightweight Xfce desktop, touch layout, and networking tools.
# Run inside the Ubuntu rootfs/chroot as root.
set -euo pipefail

export DEBIAN_FRONTEND=noninteractive
export TZ=Etc/UTC

echo "[1/5] Updating package lists..."
apt-get update

echo "[2/5] Installing lightweight Xfce and touch-friendly panel..."
apt-get install -y --no-install-recommends \
  xfce4-session xfce4-panel xfwm4 xfce4-settings xfce4-terminal thunar \
  xfce4-whiskermenu-plugin xfce4-pulseaudio-plugin \
  dbus dbus-x11 policykit-1 x11-xserver-utils \
  adwaita-icon-theme fonts-noto-core fonts-noto-color-emoji

echo "[3/5] Installing essential shell and networking tools..."
apt-get install -y --no-install-recommends \
  nano less htop file procps psmisc ca-certificates curl wget git \
  openssh-client iproute2 iputils-ping net-tools dnsutils traceroute \
  netcat-openbsd nmap whois

echo "[4/5] Applying dark hacker theme and low-memory window settings..."
install -d /etc/metmc
cat > /etc/metmc/xfce-theme.ini <<'EOF'
Theme=Adwaita-dark
Accent=#00ff88
TerminalBackground=#101418
TerminalForeground=#00ff88
PanelSize=52
PanelIconSize=28
WindowCompositing=false
EOF

if id metmc >/dev/null 2>&1; then
  install -d -o metmc -g metmc \
    /home/metmc/.config/gtk-3.0 \
    /home/metmc/.config/xfce4/xfconf/xfce-perchannel-xml \
    /home/metmc/.config/xfce4/terminal
  cat > /home/metmc/.config/gtk-3.0/settings.ini <<'EOF'
[Settings]
gtk-theme-name=Adwaita-dark
gtk-icon-theme-name=Adwaita
gtk-font-name=Noto Sans 11
gtk-cursor-theme-name=Adwaita
EOF
  cat > /home/metmc/.config/xfce4/terminal/terminalrc <<'EOF'
[Configuration]
FontName=Monospace 11
MiscAlwaysShowTabs=TRUE
MiscMenubarDefault=FALSE
MiscToolbarDefault=FALSE
ColorForeground=#00ff88
ColorBackground=#101418
EOF
  chown -R metmc:metmc /home/metmc/.config
fi

echo "[5/5] Cleaning apt cache..."
apt-get clean
rm -rf /var/lib/apt/lists/*
echo "METMC OS Xfce desktop and networking tools installed."
echo "Desktop: floating windows + Xfwm4; panel/tasklist sized for touch."
echo "Network tools: ip, ping, ss, netstat, dig, traceroute, nc, nmap, whois, curl, wget, ssh."
