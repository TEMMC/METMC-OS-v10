package com.nativOS.runtime

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages Linux rootfs downloads, extraction, and lifecycle.
 * Ported from DroidDesk's RootfsManager, adapted for NativOS.
 */
class RootfsManager(private val context: Context) {

    companion object {
        private const val TAG = "NativOS.RootfsManager"
        private const val BUFFER_SIZE = 8192
        private const val BUNDLED_ROOTFS_ASSET = "rootfs/nativos-rootfs-arm64.tgz"

        val DISTRO_URLS = mapOf(
            "ubuntu" to "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-arm64.tar.gz",
            "alpine" to "https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/aarch64/alpine-minirootfs-3.20.0-aarch64.tar.gz"
        )
        val DISTRO_NAMES = mapOf(
            "ubuntu" to "Ubuntu 24.04 Base",
            "alpine" to "Alpine Linux 3.20"
        )
        const val DEFAULT_DISTRO = "ubuntu"
    }

    private val baseDir: File get() = context.filesDir
    private val rootfsDir: File get() = File(baseDir, "rootfs")
    private val downloadDir: File get() = File(baseDir, "downloads")
    private val configFile: File get() = File(baseDir, "distro.conf")
    private val setupCompleteFile: File get() = File(baseDir, "SETUP_COMPLETE")

    fun getInstalledDistro(): String =
        if (configFile.exists()) configFile.readText().trim() else ""

    fun getRootfsPath(): String = rootfsDir.absolutePath

    fun isRootfsReady(): Boolean =
        rootfsDir.exists() && File(rootfsDir, "bin").exists() &&
        File(rootfsDir, "usr").exists() && File(rootfsDir, "etc").exists()

    fun isSetupComplete(): Boolean = setupCompleteFile.exists()

    fun isFlatpakInstalled(): Boolean = File(rootfsDir, "usr/bin/flatpak").exists()

    /** Copy the production rootfs bundled in the signed APK into the staging area. */
    fun stageBundledRootfs(onProgress: (progress: Double, status: String) -> Unit): Boolean {
        val assetName = BUNDLED_ROOTFS_ASSET.substringAfterLast('/')
        val available = runCatching {
            context.assets.list("rootfs")?.contains(assetName) == true
        }.getOrDefault(false)
        if (!available) return false

        return try {
            downloadDir.mkdirs()
            val target = File(downloadDir, "$DEFAULT_DISTRO-rootfs.tar.gz")
            val temporary = File(downloadDir, "$DEFAULT_DISTRO-rootfs.tar.gz.part")
            onProgress(0.0, "Preparing bundled Linux system...")
            var copied = 0L
            var lastReportedPercent = -1
            val expected = runCatching {
                context.assets.openFd(BUNDLED_ROOTFS_ASSET).use { it.length }
            }.getOrDefault(-1L)
            context.assets.open(BUNDLED_ROOTFS_ASSET).use { input ->
                FileOutputStream(temporary, false).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copied += count
                        if (expected > 0) {
                            val percent = ((copied * 100L) / expected).toInt().coerceIn(0, 100)
                            if (percent != lastReportedPercent) {
                                lastReportedPercent = percent
                                onProgress(percent / 100.0, "Preparing bundled Linux system...")
                            }
                        }
                    }
                    output.fd.sync()
                }
            }
            if (copied == 0L) throw IllegalStateException("Bundled rootfs is empty")
            if (target.exists() && !target.delete()) {
                throw IllegalStateException("Could not replace staged rootfs")
            }
            if (!temporary.renameTo(target)) {
                throw IllegalStateException("Could not finalize staged rootfs")
            }
            configFile.writeText(DEFAULT_DISTRO)
            onProgress(1.0, "Bundled Linux system ready")
            Log.i(TAG, "Staged bundled rootfs (${copied / (1024 * 1024)} MiB)")
            true
        } catch (error: Throwable) {
            File(downloadDir, "$DEFAULT_DISTRO-rootfs.tar.gz.part").delete()
            Log.e(TAG, "Could not stage bundled rootfs", error)
            false
        }
    }

    /** Ensure the lightweight Xfce X11 desktop runtime exists. */
    fun ensureXfceRuntime(chrootManager: ChrootManager): Boolean {
        val session = File(rootfsDir, "usr/bin/startxfce4")
        val panel = File(rootfsDir, "usr/bin/xfce4-panel")
        val wm = File(rootfsDir, "usr/bin/xfwm4")
        if (session.exists() && panel.exists() && wm.exists()) return true

        Log.w(TAG, "Xfce runtime missing; repairing installation")
        val result = chrootManager.execChroot(
            """
                dpkg --configure -a || true
                apt-get update || exit 1
                TMPDIR=/tmp DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC \
                    apt-get install -y --no-install-recommends \
                    xfce4-session xfce4-panel xfwm4 xfce4-settings \
                    xfce4-terminal thunar xfce4-whiskermenu-plugin \
                    xfce4-pulseaudio-plugin adwaita-icon-theme \
                    dbus-x11 x11-xserver-utils policykit-1 || exit 1
                test -x /usr/bin/startxfce4
                test -x /usr/bin/xfce4-panel
                test -x /usr/bin/xfwm4
            """.trimIndent()
        )
        if (result == 0 && session.exists() && panel.exists() && wm.exists()) {
            Log.i(TAG, "Xfce runtime is ready")
            return true
        }
        Log.e(TAG, "Could not repair Xfce runtime (exit $result)")
        return false
    }

    /** Keep a small terminal and essential diagnostics/networking tools available. */
    fun ensureEssentialTools(chrootManager: ChrootManager): Boolean {
        val terminal = File(rootfsDir, "usr/bin/xfce4-terminal")
        val result = chrootManager.execChroot(
            """
                dpkg --configure -a || true
                apt-get update || exit 1
                TMPDIR=/tmp DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC \
                    apt-get install -y --no-install-recommends \
                    xfce4-terminal thunar nano less htop file procps psmisc \
                    ca-certificates curl wget git openssh-client \
                    iproute2 iputils-ping net-tools dnsutils traceroute \
                    netcat-openbsd nmap whois || exit 1
                test -x /usr/bin/xfce4-terminal
            """.trimIndent()
        )
        if (result == 0 && terminal.exists()) {
            Log.i(TAG, "Essential desktop and networking tools are ready")
            return true
        }
        Log.w(TAG, "Could not provision all essential tools (exit $result)")
        return false
    }

    /** Apply a compact dark hacker-style Xfce layout for touch use. */
    fun configureXfceTouchLayout(chrootManager: ChrootManager): Boolean {
        val result = chrootManager.execChroot(
            """
                set -e
                id metmc >/dev/null 2>&1 || exit 1
                home="$(getent passwd metmc | cut -d: -f6)"
                test -n "$home" || exit 1
                install -d -o metmc -g metmc "$home/.config/xfce4/xfconf/xfce-perchannel-xml" "$home/.config/gtk-3.0"
                cat > "$home/.config/gtk-3.0/settings.ini" <<'EOF'
[Settings]
gtk-theme-name=Adwaita-dark
gtk-icon-theme-name=Adwaita
gtk-font-name=Noto Sans 11
gtk-cursor-theme-name=Adwaita
gtk-toolbar-style=GTK_TOOLBAR_BOTH_HORIZ
gtk-button-images=1
gtk-menu-images=1
EOF
                cat > "$home/.config/xfce4/xfconf/xfce-perchannel-xml/xsettings.xml" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<channel name="xsettings" version="1.0">
  <property name="Net" type="empty">
    <property name="ThemeName" type="string" value="Adwaita-dark"/>
    <property name="IconThemeName" type="string" value="Adwaita"/>
    <property name="DoubleClickTime" type="int" value="400"/>
    <property name="DoubleClickDistance" type="int" value="8"/>
  </property>
  <property name="Gtk" type="empty">
    <property name="FontName" type="string" value="Noto Sans 11"/>
    <property name="CursorThemeName" type="string" value="Adwaita"/>
  </property>
</channel>
EOF
                cat > "$home/.config/xfce4/xfconf/xfce-perchannel-xml/xfwm4.xml" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<channel name="xfwm4" version="1.0">
  <property name="general" type="empty">
    <property name="theme" type="string" value="Adwaita-dark"/>
    <property name="title_font" type="string" value="Noto Sans Bold 11"/>
    <property name="button_layout" type="string" value="O|HMC"/>
    <property name="wrap_windows" type="bool" value="false"/>
    <property name="use_compositing" type="bool" value="false"/>
    <property name="workspace_count" type="int" value="1"/>
  </property>
</channel>
EOF
                cat > "$home/.config/xfce4/xfconf/xfce-perchannel-xml/xfce4-panel.xml" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<channel name="xfce4-panel" version="1.0">
  <property name="panels" type="array">
    <value type="int" value="1"/>
    <property name="panel-1" type="empty">
      <property name="position" type="string" value="p=6;x=0;y=0"/>
      <property name="length" type="uint" value="100"/>
      <property name="position-locked" type="bool" value="true"/>
      <property name="size" type="uint" value="52"/>
      <property name="icon-size" type="uint" value="28"/>
      <property name="plugin-ids" type="array">
        <value type="int" value="1"/>
        <value type="int" value="2"/>
        <value type="int" value="3"/>
        <value type="int" value="4"/>
      </property>
    </property>
  </property>
  <property name="plugins" type="empty">
    <property name="plugin-1" type="string" value="applicationsmenu"/>
    <property name="plugin-2" type="string" value="tasklist"/>
    <property name="plugin-3" type="string" value="separator"/>
    <property name="plugin-4" type="string" value="clock"/>
  </property>
</channel>
EOF
                chown -R metmc:metmc "$home/.config"
                # Seed a useful touch-sized terminal profile and shell prompt.
                install -d -o metmc -g metmc "$home/.config/xfce4/terminal"
                cat > "$home/.config/xfce4/terminal/terminalrc" <<'EOF'
[Configuration]
FontName=Monospace 11
MiscAlwaysShowTabs=TRUE
MiscMenubarDefault=FALSE
MiscToolbarDefault=FALSE
ColorForeground=#00ff88
ColorBackground=#101418
ColorPalette=#101418;#ff5555;#50fa7b;#f1fa8c;#8be9fd;#bd93f9;#8be9fd;#f8f8f2;#6272a4;#ff6e6e;#69ff94;#ffffa5;#d6acff;#ff92df;#a4ffff;#ffffff
EOF
                chown -R metmc:metmc "$home/.config/xfce4"
                if ! grep -q 'METMC OS' "$home/.bashrc" 2>/dev/null; then
                    cat >> "$home/.bashrc" <<'EOF'

# METMC OS hacker-style prompt
if [ -n "$PS1" ]; then
  PS1='\[\e[1;32m\]metmc@METMC-OS\[\e[0m\]:\[\e[1;34m\]\w\[\e[0m\]$ '
fi
EOF
                    chown metmc:metmc "$home/.bashrc"
                fi
            """.trimIndent()
        )
        if (result == 0) Log.i(TAG, "Xfce touch layout configured")
        else Log.w(TAG, "Xfce layout setup failed (exit $result)")
        return result == 0
    }

    /** Add or repair the signed per-user Flathub remote used by GNOME Software. */
    fun ensureFlathub(chrootManager: ChrootManager): Boolean {
        if (!isFlatpakInstalled()) return false
        val result = chrootManager.execChroot(
            """
                mkdir -p /root/.gnupg
                chmod 0700 /root/.gnupg
                if flatpak --user remotes --columns=name 2>/dev/null | grep -Fxq flathub &&
                   test -s /root/.local/share/flatpak/repo/flathub.trustedkeys.gpg; then
                    exit 0
                fi
                flatpak --user remote-delete --force flathub 2>/dev/null || true
                timeout 60s flatpak --user remote-add flathub https://dl.flathub.org/repo/flathub.flatpakrepo
            """.trimIndent()
        )
        if (result == 0) {
            Log.i(TAG, "Flathub remote ready")
        } else {
            Log.w(TAG, "Could not configure Flathub (exit $result)")
        }
        return result == 0
    }

    fun downloadRootfs(
        distro: String = DEFAULT_DISTRO,
        onProgress: (progress: Double, status: String) -> Unit
    ) {
        try {
            val url = DISTRO_URLS[distro] ?: throw IllegalArgumentException("Unknown distro: $distro")
            val distroName = DISTRO_NAMES[distro] ?: distro
            downloadDir.mkdirs()
            val targetFile = File(downloadDir, "${distro}-rootfs.tar.gz")

            onProgress(0.0, "Connecting to download server...")
            Log.i(TAG, "Downloading $distroName from $url")

            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 30000
            connection.readTimeout = 30000
            connection.instanceFollowRedirects = true

            var downloadedBytes = 0L
            if (targetFile.exists()) {
                downloadedBytes = targetFile.length()
                connection.setRequestProperty("Range", "bytes=$downloadedBytes-")
            }
            connection.requestMethod = "GET"

            if (connection.responseCode == 416) {
                onProgress(1.0, "$distroName already downloaded")
                configFile.writeText(distro)
                return
            }
            if (connection.responseCode == 200 && downloadedBytes > 0) {
                targetFile.delete()
                downloadedBytes = 0L
            }

            val totalBytes = connection.contentLengthLong
            val expectedTotal = downloadedBytes + totalBytes
            if (totalBytes == 0L) {
                onProgress(1.0, "$distroName downloaded")
                configFile.writeText(distro)
                return
            }

            val buffer = ByteArray(BUFFER_SIZE)
            connection.inputStream.use { input ->
                FileOutputStream(targetFile, downloadedBytes > 0).use { output ->
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        if (expectedTotal > 0) {
                            val progress = downloadedBytes.toDouble() / expectedTotal
                            val dlMB = downloadedBytes / (1024 * 1024)
                            val totMB = expectedTotal / (1024 * 1024)
                            onProgress(progress, "Downloading: ${dlMB}MB / ${totMB}MB")
                        }
                    }
                }
            }
            connection.disconnect()
            configFile.writeText(distro)
            onProgress(1.0, "$distroName downloaded successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Download failed: ${e.message}", e)
            onProgress(-1.0, "Download failed: ${e.message}")
        }
    }

    fun extractRootfs(onProgress: (progress: Double, status: String) -> Unit) {
        try {
            val distro = getInstalledDistro().ifEmpty { DEFAULT_DISTRO }
            val tarball = File(downloadDir, "${distro}-rootfs.tar.gz")
            if (!tarball.exists()) {
                onProgress(-1.0, "Rootfs tarball not found.")
                return
            }
            if (rootfsDir.exists()) rootfsDir.deleteRecursively()
            rootfsDir.mkdirs()

            onProgress(0.1, "Extracting Linux filesystem...")
            Log.i(TAG, "Extracting rootfs from ${tarball.absolutePath}")

            val process = ProcessBuilder("tar", "zxf", tarball.absolutePath, "-C", rootfsDir.absolutePath)
                .redirectErrorStream(true).start()
            val reader = process.inputStream.bufferedReader()
            var line: String?
            var lineCount = 0
            var lastLine = ""
            while (reader.readLine().also { line = it } != null) {
                lastLine = line!!
                lineCount++
                if (lineCount % 500 == 0) onProgress(0.1 + (lineCount % 5000) / 10000.0, "Extracting files...")
            }
            val exitCode = process.waitFor()
            val binDir = File(rootfsDir, "bin")
            if (exitCode != 0 && (!binDir.exists() || binDir.list()?.isEmpty() == true))
                throw RuntimeException("tar failed (code $exitCode): $lastLine")

            onProgress(0.7, "Configuring Linux environment...")
            configureRootfs()
            setupCompleteFile.writeText("done")
            tarball.delete()
            onProgress(1.0, "Linux filesystem ready")
        } catch (e: Exception) {
            Log.e(TAG, "Extraction failed: ${e.message}", e)
            onProgress(-1.0, "Extraction failed: ${e.message}")
        }
    }

    private fun configureRootfs() {
        File(rootfsDir, "etc/resolv.conf").apply {
            parentFile?.mkdirs()
            writeText("nameserver 8.8.8.8\nnameserver 8.8.4.4\nnameserver 1.1.1.1\n")
        }
        File(rootfsDir, "etc/apt/apt.conf.d").mkdirs()
        File(rootfsDir, "etc/apt/apt.conf.d/99-disable-sandbox").writeText("APT::Sandbox::User \"root\";\n")
        File(rootfsDir, "etc/hostname").writeText("nativOS\n")
        File(rootfsDir, "etc/hosts").apply {
            parentFile?.mkdirs()
            writeText("127.0.0.1 localhost\n127.0.0.1 nativOS\n::1 localhost\n")
        }
        File(rootfsDir, "etc/profile.d/nativOS.sh").apply {
            parentFile?.mkdirs()
            writeText("#!/bin/bash\nexport DISPLAY=:0\nexport XDG_RUNTIME_DIR=/tmp/runtime-root\nmkdir -p /tmp/runtime-root 2>/dev/null\n")
        }
        File(rootfsDir, "etc/sudoers.d/nativOS").apply {
            parentFile?.mkdirs()
            writeText("Defaults !requiretty\nroot ALL=(ALL) NOPASSWD: ALL\n")
        }
        listOf("tmp", "tmp/runtime-root", "run", "var/run", "dev/shm").forEach { File(rootfsDir, it).mkdirs() }
    }

    fun installKdePlasma(chrootManager: ChrootManager, onProgress: (Double, String) -> Unit) {
        try {
            onProgress(0.0, "Clearing package locks...")
            try { chrootManager.execChroot("rm -f /var/lib/apt/lists/lock /var/cache/apt/archives/lock /var/lib/dpkg/lock*; dpkg --configure -a 2>/dev/null || true") } catch (_: Exception) {}

            onProgress(0.05, "Updating package lists...")
            chrootManager.execChroot("apt-get update -y")

            onProgress(0.10, "Installing D-Bus and desktop services...")
            chrootManager.execChroot("TMPDIR=/tmp DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends dbus dbus-x11 policykit-1 packagekit")

            onProgress(0.20, "Installing KDE Plasma desktop...")
            chrootManager.execChroot("TMPDIR=/tmp DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends plasma-desktop plasma-workspace kwin-x11 konsole dolphin systemsettings xwayland x11-xserver-utils")

            onProgress(0.35, "Installing touch-friendly and desktop dependencies...")
            chrootManager.execChroot("TMPDIR=/tmp DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends adwaita-icon-theme fonts-noto-core fonts-noto-color-emoji")

            onProgress(0.45, "Installing compatibility tools...")
            chrootManager.execChroot("TMPDIR=/tmp DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends gcc libxcb1-dev libxcb-dri3-dev libc6-dev libvulkan1 vulkan-tools wget curl")

            onProgress(0.55, "Building X11 socket compatibility hook...")
            chrootManager.execChroot("""
                bash -c "cat > /tmp/socket_hook.c << 'EOF'
#define _GNU_SOURCE
#include <sys/socket.h>
#include <sys/un.h>
#include <dlfcn.h>
#include <string.h>
#include <stdio.h>
#include <stddef.h>
int connect(int sockfd, const struct sockaddr *addr, socklen_t addrlen) {
    int (*real_connect)(int, const struct sockaddr *, socklen_t) = dlsym(RTLD_NEXT, \"connect\");
    if (addr && addr->sa_family == AF_UNIX) {
        struct sockaddr_un *un = (struct sockaddr_un *)addr;
        if (strstr(un->sun_path, \".X11-unix/X\")) {
            struct sockaddr_un abstract_addr;
            memset(&abstract_addr, 0, sizeof(abstract_addr));
            abstract_addr.sun_family = AF_UNIX;
            const char *tmpdir = getenv(\"TMPDIR\");
            if (!tmpdir) tmpdir = \"/tmp\";
            snprintf(abstract_addr.sun_path + 1, sizeof(abstract_addr.sun_path) - 1, \"%s/.X11-unix/X0\", tmpdir);
            socklen_t abs_len = offsetof(struct sockaddr_un, sun_path) + 1 + strlen(abstract_addr.sun_path + 1);
            return real_connect(sockfd, (struct sockaddr *)&abstract_addr, abs_len);
        }
    }
    return real_connect(sockfd, addr, addrlen);
}
EOF
gcc -shared -fPIC -o /usr/local/lib/libsocket_hook.so /tmp/socket_hook.c -ldl"
            """.trimIndent())

            onProgress(0.65, "Building kernel compatibility hooks...")
            chrootManager.execChroot("""
                bash -c "cat > /tmp/nodri3.c << 'EOF'
#include <xcb/xcb.h>
#include <dlfcn.h>
extern xcb_extension_t xcb_dri3_id;
const xcb_query_extension_reply_t *xcb_get_extension_data(xcb_connection_t *c, xcb_extension_t *ext) {
    const xcb_query_extension_reply_t *(*real_fn)(xcb_connection_t*, xcb_extension_t*) = dlsym(RTLD_NEXT, \"xcb_get_extension_data\");
    if (ext == &xcb_dri3_id) {
        const xcb_query_extension_reply_t *reply = real_fn(c, ext);
        if (reply && reply->present) ((xcb_query_extension_reply_t *)reply)->present = 0;
        return reply;
    }
    return real_fn(c, ext);
}
EOF
gcc -shared -fPIC -o /usr/local/lib/libnodri3.so /tmp/nodri3.c -lxcb -ldl -lxcb-dri3"
            """.trimIndent())
            chrootManager.execChroot("""
                bash -c "cat > /tmp/close_range_compat.c << 'EOF'
#define _GNU_SOURCE
#include <errno.h>
int close_range(unsigned int first, unsigned int last, int flags) {
    (void) first; (void) last; (void) flags; errno = ENOSYS; return -1;
}
EOF
gcc -shared -fPIC -o /usr/local/lib/libnativos-close-range.so /tmp/close_range_compat.c"
            """.trimIndent())

            onProgress(0.75, "Installing built-in apps and software center...")
            chrootManager.execChroot("TMPDIR=/tmp DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends gnome-calculator gnome-clocks gnome-software flatpak 2>/dev/null || true")

            onProgress(0.85, "Configuring Flathub...")
            ensureFlathub(chrootManager)

            onProgress(0.95, "Building icon and desktop caches...")
            chrootManager.execChroot("""
                bash -c "update-desktop-database /usr/share/applications 2>/dev/null || true; gtk-update-icon-cache -f -t /usr/share/icons/hicolor 2>/dev/null || true; glib-compile-schemas /usr/share/glib-2.0/schemas 2>/dev/null || true"
            """.trimIndent())

            onProgress(1.0, "KDE Plasma installed!")
        } catch (e: Exception) {
            Log.e(TAG, "KDE Plasma install failed: ${e.message}", e)
            onProgress(-1.0, "KDE Plasma install failed: ${e.message}")
        }
    }

    private fun calculateDirSize(dir: File): Long =
        if (!dir.exists()) 0 else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
