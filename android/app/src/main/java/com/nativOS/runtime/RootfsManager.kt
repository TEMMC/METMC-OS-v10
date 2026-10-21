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
            "debian" to "Debian 13 Trixie (bundled)",
            "ubuntu" to "Ubuntu 24.04 Base",
            "alpine" to "Alpine Linux 3.20"
        )
        const val DEFAULT_DISTRO = "debian"
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

    /** The APK's default system is Debian 13 Trixie; old Ubuntu installs need migration. */
    fun isBundledDistroReady(): Boolean = isRootfsReady() && isDebianTrixieRootfs()

    private fun isDebianTrixieRootfs(): Boolean {
        val release = File(rootfsDir, "etc/os-release")
        if (!release.exists()) return false
        val contents = runCatching { release.readText() }.getOrDefault("")
        return Regex("(?m)^ID=debian$").containsMatchIn(contents) &&
            Regex("(?m)^VERSION_CODENAME=trixie$").containsMatchIn(contents)
    }

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

    /** Verify the window manager, taskbar, terminal, and essential bundled utilities. */
    fun ensureXfceRuntime(chrootManager: ChrootManager): Boolean {
        val required = listOf(
            "usr/bin/startxfce4",
            "usr/bin/xfwm4",
            "usr/bin/xfce4-panel",
            "usr/bin/xfce4-terminal",
            "usr/bin/thunar",
            "usr/bin/curl",
            "usr/bin/ip",
            "usr/bin/ping"
        )
        val missing = required.filterNot { File(rootfsDir, it).exists() }
        if (missing.isEmpty()) {
            Log.i(TAG, "Xfce windowed desktop and essential tools are present in the bundled rootfs")
            return configureXfceTouchLayout(chrootManager)
        }
        Log.w(TAG, "Offline Xfce rootfs is missing required files: ${missing.joinToString()}")
        return false
    }

    /** Apply a compact dark hacker-style Xfce layout for touch use. */
    /** Apply a compact dark hacker-style Xfce layout with touch-sized panel controls. */
    fun configureXfceTouchLayout(chrootManager: ChrootManager): Boolean {
        val result = chrootManager.execChroot(
            """
                set -e
                id metmc >/dev/null 2>&1 || exit 1
                install -d -o metmc -g metmc /home/metmc/.config/xfce4/xfconf/xfce-perchannel-xml /home/metmc/.config/gtk-3.0 /home/metmc/.config/xfce4/terminal
                cat > /home/metmc/.config/gtk-3.0/settings.ini <<'EOF'
[Settings]
gtk-theme-name=Adwaita-dark
gtk-icon-theme-name=Adwaita
gtk-font-name=Noto Sans 11
gtk-cursor-theme-name=Adwaita
gtk-toolbar-style=GTK_TOOLBAR_BOTH_HORIZ
gtk-button-images=1
gtk-menu-images=1
EOF
                cat > /home/metmc/.config/xfce4/xfconf/xfce-perchannel-xml/xsettings.xml <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<channel name="xsettings" version="1.0">
  <property name="Net" type="empty">
    <property name="ThemeName" type="string" value="Adwaita-dark"/>
    <property name="IconThemeName" type="string" value="Adwaita"/>
  </property>
  <property name="Gtk" type="empty">
    <property name="FontName" type="string" value="Noto Sans 11"/>
    <property name="CursorThemeName" type="string" value="Adwaita"/>
  </property>
</channel>
EOF
                cat > /home/metmc/.config/xfce4/xfconf/xfce-perchannel-xml/xfwm4.xml <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<channel name="xfwm4" version="1.0">
  <property name="general" type="empty">
    <property name="theme" type="string" value="Adwaita-dark"/>
    <property name="title_font" type="string" value="Noto Sans Bold 11"/>
    <property name="button_layout" type="string" value="O|HMC"/>
    <property name="use_compositing" type="bool" value="false"/>
    <property name="workspace_count" type="int" value="1"/>
  </property>
</channel>
EOF
                cat > /home/metmc/.config/xfce4/xfconf/xfce-perchannel-xml/xfce4-panel.xml <<'EOF'
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
                cat > /home/metmc/.config/xfce4/terminal/terminalrc <<'EOF'
[Configuration]
FontName=Monospace 11
MiscAlwaysShowTabs=TRUE
MiscMenubarDefault=FALSE
MiscToolbarDefault=FALSE
ColorForeground=#00ff88
ColorBackground=#101418
EOF
                if ! grep -q 'METMC OS' /home/metmc/.bashrc 2>/dev/null; then
                    cat >> /home/metmc/.bashrc <<'EOF'

# METMC OS hacker-style prompt
if [ -n "${'$'}PS1" ]; then
  PS1='\[\e[1;32m\]metmc@METMC-OS\[\e[0m\]:\[\e[1;34m\]\w\[\e[0m\]${'$'} '
fi
EOF
                fi
                chown -R metmc:metmc /home/metmc/.config /home/metmc/.bashrc
            """.trimIndent()
        )
        if (result == 0) Log.i(TAG, "Xfce touch layout configured")
        else Log.w(TAG, "Xfce layout setup failed (exit ${'$'}result)")
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
        if (distro == "debian") {
            if (!stageBundledRootfs(onProgress)) {
                onProgress(-1.0, "Bundled Debian Trixie rootfs is missing from this APK")
            }
            return
        }
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
        var previousRootfs: File? = null
        try {
            val distro = getInstalledDistro().ifEmpty { DEFAULT_DISTRO }
            val tarball = File(downloadDir, "${distro}-rootfs.tar.gz")
            if (!tarball.exists()) {
                onProgress(-1.0, "Rootfs tarball not found.")
                return
            }

            // Keep the previous installation until the new rootfs validates.
            // This allows an upgrade from Ubuntu to Debian without immediately
            // destroying the old system if extraction or validation fails.
            if (rootfsDir.exists()) {
                val backup = File(baseDir, "rootfs-previous-${System.currentTimeMillis()}")
                if (!rootfsDir.renameTo(backup)) {
                    throw IllegalStateException("Could not safely move the previous Linux rootfs")
                }
                previousRootfs = backup
            }
            if (!rootfsDir.mkdirs() && !rootfsDir.isDirectory) {
                throw IllegalStateException("Could not create the new Linux rootfs directory")
            }

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
            if (exitCode != 0) throw RuntimeException("tar failed (code $exitCode): $lastLine")

            onProgress(0.7, "Configuring Linux environment...")
            configureRootfs()
            if (distro == "debian" && !isDebianTrixieRootfs()) {
                throw IllegalStateException("Bundled rootfs is not Debian 13 Trixie")
            }
            setupCompleteFile.writeText("done")

            // Preserve the user's Linux home directory when replacing an older distro.
            previousRootfs?.let { backup ->
                val oldHome = File(backup, "home/metmc")
                if (oldHome.exists()) {
                    val newHome = File(rootfsDir, "home/metmc")
                    if (newHome.exists() && !newHome.deleteRecursively()) {
                        throw IllegalStateException("Could not prepare the new metmc home directory")
                    }
                    newHome.parentFile?.mkdirs()
                    if (!oldHome.renameTo(newHome)) {
                        oldHome.copyRecursively(newHome, overwrite = true)
                        if (!oldHome.deleteRecursively()) {
                            Log.w(TAG, "Old metmc home remains in the previous-rootfs backup")
                        }
                    }
                }
            }

            previousRootfs?.let { backup ->
                if (backup.exists() && !backup.deleteRecursively()) {
                    Log.w(TAG, "Could not remove previous rootfs backup: ${backup.absolutePath}")
                }
            }
            tarball.delete()
            onProgress(1.0, "Linux filesystem ready")
        } catch (e: Exception) {
            previousRootfs?.let { backup ->
                if (backup.exists()) {
                    rootfsDir.deleteRecursively()
                    if (!backup.renameTo(rootfsDir)) {
                        Log.e(TAG, "Failed to restore previous rootfs from ${backup.absolutePath}")
                    }
                }
            }
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
        File(rootfsDir, "etc/hostname").writeText("metmc-os\n")
        File(rootfsDir, "etc/hosts").apply {
            parentFile?.mkdirs()
            writeText("127.0.0.1 localhost\n127.0.1.1 metmc-os\n::1 localhost\n")
        }
        File(rootfsDir, "etc/profile.d/nativOS.sh").delete()
        File(rootfsDir, "etc/profile.d/metmc-os.sh").apply {
            parentFile?.mkdirs()
            writeText("#!/bin/bash\nexport DISPLAY=:0\n")
        }
        File(rootfsDir, "etc/sudoers.d/nativOS").delete()
        File(rootfsDir, "etc/sudoers.d/metmc").apply {
            parentFile?.mkdirs()
            writeText("Defaults !requiretty\nmetmc ALL=(ALL:ALL) NOPASSWD: ALL\n")
        }
        listOf("tmp", "tmp/runtime-root", "tmp/runtime-metmc", "run", "var/run", "dev/shm").forEach { File(rootfsDir, it).mkdirs() }
    }

    fun installXfceDesktop(chrootManager: ChrootManager, onProgress: (Double, String) -> Unit) {
        try {
            onProgress(0.0, "Repairing package database...")
            chrootManager.execChroot("rm -f /var/lib/apt/lists/lock /var/cache/apt/archives/lock /var/lib/dpkg/lock*; dpkg --configure -a 2>/dev/null || true")
            onProgress(0.05, "Updating package lists...")
            if (chrootManager.execChroot("apt-get update") != 0) throw IllegalStateException("Could not update package lists")
            onProgress(0.18, "Installing lightweight Xfce desktop...")
            val desktopResult = chrootManager.execChroot("TMPDIR=/tmp DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends xfce4-session xfce4-panel xfwm4 xfce4-settings xfce4-terminal thunar xfce4-whiskermenu-plugin xfce4-pulseaudio-plugin dbus dbus-x11 policykit-1 x11-xserver-utils adwaita-icon-theme fonts-noto-core fonts-noto-color-emoji")
            if (desktopResult != 0) throw IllegalStateException("Xfce package installation failed")
            onProgress(0.45, "Installing compact hacker and networking tools...")
            val toolsResult = chrootManager.execChroot("TMPDIR=/tmp DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends nano less htop file procps psmisc ca-certificates curl wget git openssh-client iproute2 iputils-ping net-tools dnsutils traceroute netcat-openbsd nmap whois")
            if (toolsResult != 0) Log.w(TAG, "Some optional diagnostics packages could not be installed")
            onProgress(0.72, "Applying touch-friendly dark theme and taskbar...")
            configureXfceTouchLayout(chrootManager)
            onProgress(0.88, "Preparing non-root desktop user...")
            chrootManager.ensureDefaultLinuxUser()
            onProgress(1.0, "METMC OS Xfce desktop ready")
            Log.i(TAG, "Xfce desktop provisioning complete")
        } catch (error: Throwable) {
            Log.e(TAG, "Xfce desktop installation failed", error)
            onProgress(-1.0, error.message ?: "Xfce installation failed")
        }
    }

    private fun calculateDirSize(dir: File): Long =
        if (!dir.exists()) 0 else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
