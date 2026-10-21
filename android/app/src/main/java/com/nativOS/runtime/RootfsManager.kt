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
            "alpine" to "https://dl-cdn.alpinelinux.org/alpine/v3.22/releases/aarch64/alpine-minirootfs-3.22.0-aarch64.tar.gz",
            "ubuntu" to "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-arm64.tar.gz"
        )
        val DISTRO_NAMES = mapOf(
            "alpine" to "Alpine Linux 3.22 (bundled)",
            "ubuntu" to "Ubuntu 24.04 Base"
        )
        const val DEFAULT_DISTRO = "alpine"
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

    /** The APK bundles Alpine ARM64; startup never bootstraps Linux over the network. */
    fun isBundledDistroReady(): Boolean = isRootfsReady() && isAlpineRootfs()

    private fun isAlpineRootfs(): Boolean {
        val release = File(rootfsDir, "etc/os-release")
        if (!release.exists()) return false
        val contents = runCatching { release.readText() }.getOrDefault("")
        return Regex("(?m)^ID=alpine$").containsMatchIn(contents) &&
            Regex("(?m)^VERSION_ID=3\\.22(?:\\.|$)").containsMatchIn(contents)
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

    /** Verify the bundled Labwc desktop and compact basic-tool set without networking. */
    fun ensureLabwcRuntime(chrootManager: ChrootManager): Boolean {
        val required = listOf(
            "usr/bin/labwc", "usr/bin/waybar", "usr/bin/fuzzel",
            "usr/bin/foot", "usr/bin/thunar", "sbin/apk",
            "usr/bin/curl", "sbin/ip", "usr/bin/htop",
            "usr/local/bin/nativOS-bridge", "usr/local/bin/metmc-settings", "usr/local/bin/metmc-wifi"
        )
        val missing = required.filterNot { File(rootfsDir, it).exists() }
        if (missing.isNotEmpty()) {
            Log.w(TAG, "Offline Alpine/Labwc rootfs is missing: ${missing.joinToString()}")
            return false
        }
        Log.i(TAG, "Alpine Labwc desktop and compact tools are present")
        return configureLabwcTouchLayout(chrootManager)
    }

    /** Seed the neon hacker theme and touch-first desktop config without downloads. */
    fun configureLabwcTouchLayout(chrootManager: ChrootManager): Boolean {
        val result = chrootManager.execChroot(
            """
                set -e
                id metmc >/dev/null 2>&1 || exit 1
                mkdir -p /home/metmc/.config/labwc /home/metmc/.config/waybar /home/metmc/.config/fuzzel /home/metmc/.config/foot /home/metmc/.config/gtk-3.0
                for item in labwc/rc.xml labwc/autostart labwc/menu.xml waybar/config.jsonc waybar/style.css fuzzel/fuzzel.ini foot/foot.ini gtk-3.0/settings.ini; do
                    source="/etc/skel/.config/${'$'}item"
                    target="/home/metmc/.config/${'$'}item"
                    if [ ! -e "${'$'}target" ] && [ -f "${'$'}source" ]; then cp "${'$'}source" "${'$'}target"; fi
                done
                mkdir -p /home/metmc/.local/share/themes/METMC-Hacker/openbox-3
                if [ -f /usr/share/themes/METMC-Hacker/openbox-3/themerc ]; then
                    cp /usr/share/themes/METMC-Hacker/openbox-3/themerc /home/metmc/.local/share/themes/METMC-Hacker/openbox-3/themerc
                fi
                chown -R metmc:metmc /home/metmc/.config /home/metmc/.local
            """.trimIndent()
        )
        if (result == 0) Log.i(TAG, "Labwc hacker theme configured")
        else Log.w(TAG, "Labwc theme setup failed (exit $result)")
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
        if (distro == "alpine") {
            if (!stageBundledRootfs(onProgress)) {
                onProgress(-1.0, "Bundled Alpine rootfs is missing from this APK")
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
            // This allows a Linux distribution migration without immediately
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
            if (distro == "alpine" && !isAlpineRootfs()) {
                throw IllegalStateException("Bundled rootfs is not Alpine Linux 3.22")
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

    /** User-initiated repair/install path; never called during boot. */
    fun installLabwcDesktop(chrootManager: ChrootManager, onProgress: (Double, String) -> Unit) {
        try {
            onProgress(0.0, "Refreshing Alpine package index (user requested)...")
            if (chrootManager.execChroot("apk update") != 0) throw IllegalStateException("Could not refresh Alpine package index")
            onProgress(0.25, "Installing Labwc desktop packages...")
            val desktopResult = chrootManager.execChroot(
                "apk add bash sudo util-linux-login dbus dbus-x11 labwc waybar fuzzel foot thunar xwayland swaybg wl-clipboard font-noto font-noto-emoji adwaita-icon-theme"
            )
            if (desktopResult != 0) throw IllegalStateException("Labwc package installation failed")
            onProgress(0.55, "Installing compact hacker and networking tools...")
            val toolsResult = chrootManager.execChroot(
                "apk add nano less htop file procps psmisc ca-certificates curl wget git openssh-client iproute2 iputils bind-tools traceroute netcat-openbsd nmap whois jq python3 tmux rsync tar gzip unzip zip tree util-linux util-linux-login coreutils findutils grep sed gawk"
            )
            if (toolsResult != 0) Log.w(TAG, "Some optional diagnostics packages could not be installed")
            onProgress(0.78, "Applying hacker theme and touch taskbar...")
            configureLabwcTouchLayout(chrootManager)
            onProgress(0.9, "Preparing non-root desktop user...")
            chrootManager.ensureDefaultLinuxUser()
            onProgress(1.0, "METMC OS Alpine Labwc desktop ready")
        } catch (error: Throwable) {
            Log.e(TAG, "Labwc desktop installation failed", error)
            onProgress(-1.0, error.message ?: "Labwc installation failed")
        }
    }

    private fun calculateDirSize(dir: File): Long =
        if (!dir.exists()) 0 else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
