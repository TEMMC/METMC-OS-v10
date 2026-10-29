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
        private const val BUNDLED_ROOTFS_ASSET = "rootfs/nativos-rootfs-arm64.tar"

        val DISTRO_URLS = mapOf(
            "alpine" to "https://dl-cdn.alpinelinux.org/alpine/v3.22/releases/aarch64/alpine-minirootfs-3.22.0-aarch64.tar.gz",
            "ubuntu" to "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-arm64.tar.gz"
        )
        val DISTRO_NAMES = mapOf(
            "alpine" to "Alpine Linux 3.22 (bundled)",
            "ubuntu" to "Ubuntu 24.04 Base"
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

    /** The APK bundles Ubuntu ARM64; startup never bootstraps Linux over the network. */
    fun isBundledDistroReady(): Boolean = isRootfsReady() && isUbuntuRootfs()

    private fun isUbuntuRootfs(): Boolean {
        val release = File(rootfsDir, "etc/os-release")
        if (!release.exists()) return false
        val contents = runCatching { release.readText() }.getOrDefault("")
        return Regex("(?m)^ID=ubuntu$").containsMatchIn(contents) &&
            Regex("(?m)^VERSION_CODENAME=noble$").containsMatchIn(contents)
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
            val target = File(downloadDir, "$DEFAULT_DISTRO-rootfs.tar")
            val temporary = File(downloadDir, "$DEFAULT_DISTRO-rootfs.tar.part")
            File(downloadDir, "$DEFAULT_DISTRO-rootfs.tar.gz").delete()
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
            File(downloadDir, "$DEFAULT_DISTRO-rootfs.tar.part").delete()
            Log.e(TAG, "Could not stage bundled rootfs", error)
            false
        }
    }

    /** Validate the selected desktop without requiring packages from another distro. */
    fun ensureDesktopRuntime(chrootManager: ChrootManager): Boolean {
        val phosh = File(rootfsDir, "usr/bin/phosh").exists() || File(rootfsDir, "usr/libexec/phosh").exists()
        val phoc = File(rootfsDir, "usr/local/bin/phoc").exists() || File(rootfsDir, "usr/bin/phoc").exists()
        val bridgeRequired = listOf("usr/local/bin/mt-bridge", "usr/local/bin/metmc-settings", "usr/local/bin/metmc-wifi")
        val missingBridge = bridgeRequired.filterNot { File(rootfsDir, it).exists() }
        if (phosh && phoc) {
            if (missingBridge.isNotEmpty()) {
                Log.w(TAG, "MT desktop bridge components missing: ${missingBridge.joinToString()}")
                return false
            }
            Log.i(TAG, "MT-Phosh/MT-Phoc runtime and bridge are present")
            return true
        }
        return ensureLabwcRuntime(chrootManager)
    }

    /** Verify the Labwc fallback and compact tools without Alpine-specific checks. */
    fun ensureLabwcRuntime(chrootManager: ChrootManager): Boolean {
        val required = listOf(
            "usr/bin/labwc", "usr/bin/waybar", "usr/bin/fuzzel",
            "usr/bin/foot", "usr/bin/thunar", "usr/bin/curl", "usr/bin/htop",
            "usr/local/bin/nativOS-bridge", "usr/local/bin/metmc-settings", "usr/local/bin/metmc-wifi"
        )
        val missing = required.filterNot { File(rootfsDir, it).exists() }
        if (missing.isNotEmpty()) {
            Log.w(TAG, "Offline Labwc rootfs is missing: ${missing.joinToString()}")
            return false
        }
        Log.i(TAG, "Labwc fallback and compact tools are present")
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
        if (distro == DEFAULT_DISTRO && runCatching { context.assets.list("rootfs")?.contains(BUNDLED_ROOTFS_ASSET.substringAfterLast('/')) == true }.getOrDefault(false)) {
            if (!stageBundledRootfs(onProgress)) onProgress(-1.0, "Bundled Ubuntu rootfs is missing or could not be copied")
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
        var stagingRootfs: File? = null
        try {
            val distro = getInstalledDistro().ifEmpty { DEFAULT_DISTRO }
            val plainTar = File(downloadDir, "${distro}-rootfs.tar")
            val gzipTar = File(downloadDir, "${distro}-rootfs.tar.gz")
            val tarball = when {
                plainTar.isFile && plainTar.length() > 0L -> plainTar
                gzipTar.isFile && gzipTar.length() > 0L -> gzipTar
                else -> throw IllegalStateException(
                    "Rootfs archive not found for $distro (expected ${plainTar.name} or ${gzipTar.name})"
                )
            }

            // Validate the archive before moving a working installation out of the way.
            // The APK asset is a plain tar; downloaded .tar.gz archives are gzip streams.
            val isGzip = tarball.inputStream().use { input ->
                val first = input.read()
                val second = input.read()
                first == 0x1f && second == 0x8b
            }
            if (tarball.name.endsWith(".gz") && !isGzip) {
                throw IllegalStateException("Rootfs archive is named .tar.gz but is not gzip-compressed")
            }
            if (tarball.name.endsWith(".tar") && isGzip) {
                throw IllegalStateException("Rootfs archive is gzip-compressed but named .tar; refusing ambiguous extraction")
            }

            // Extract into a fresh staging directory. Never erase the current rootfs
            // until the archive has fully extracted and passed distro validation.
            stagingRootfs = File(baseDir, "rootfs-staging-${System.currentTimeMillis()}")
            if (!stagingRootfs.mkdirs()) {
                throw IllegalStateException("Could not create rootfs staging directory")
            }

            onProgress(0.1, "Extracting Linux filesystem...")
            Log.i(TAG, "Extracting rootfs archive=${tarball.absolutePath}, gzip=$isGzip, staging=${stagingRootfs.absolutePath}")

            // Follow NativOS's tar extraction approach, with -C before the archive
            // so Android's Toybox tar and GNU tar both apply the destination first.
            val tarArgs = if (isGzip) "xzf" else "xf"
            val process = ProcessBuilder(
                "tar", "-C", stagingRootfs.absolutePath, tarArgs, tarball.absolutePath
            ).redirectErrorStream(true).start()

            val output = StringBuilder()
            val reader = process.inputStream.bufferedReader()
            var lineCount = 0
            while (true) {
                val line = reader.readLine() ?: break
                lineCount++
                // Keep diagnostics useful without allowing a very noisy archive to
                // consume unbounded memory or flood logcat.
                if (output.length < 16_000) output.append(line).append('\n')
                if (lineCount % 1000 == 0) {
                    onProgress(0.1 + (lineCount % 10000) / 100000.0, "Extracting files...")
                }
            }
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                val details = output.toString().trim().takeLast(4000).ifBlank { "tar produced no diagnostic output" }
                throw IllegalStateException("tar extraction failed (exit=$exitCode, lines=$lineCount): $details")
            }

            // Validate the staged tree before swapping it into the live location.
            val stagedOsRelease = File(stagingRootfs, "etc/os-release")
            if (!File(stagingRootfs, "bin").exists() ||
                !File(stagingRootfs, "usr").isDirectory ||
                !File(stagingRootfs, "etc").isDirectory ||
                !stagedOsRelease.isFile
            ) {
                throw IllegalStateException("Extracted rootfs is incomplete (missing bin, usr, etc, or etc/os-release)")
            }
            if (distro == DEFAULT_DISTRO) {
                val release = stagedOsRelease.readText()
                if (!Regex("(?m)^ID=ubuntu$").containsMatchIn(release) ||
                    !Regex("(?m)^VERSION_CODENAME=noble$").containsMatchIn(release)
                ) {
                    val detected = release.lineSequence().firstOrNull { it.startsWith("PRETTY_NAME=") }
                        ?: "unknown distribution"
                    throw IllegalStateException("Rootfs validation failed: expected Ubuntu 24.04 noble; found $detected")
                }
            }

            onProgress(0.7, "Configuring Linux environment...")
            // Configure the staging tree before replacing the currently installed rootfs.
            val liveRootfs = rootfsDir
            val originalRootfsPath = rootfsDir
            // configureRootfs() uses rootfsDir, so swap only after the staged tree validates.
            if (liveRootfs.exists()) {
                previousRootfs = File(baseDir, "rootfs-previous-${System.currentTimeMillis()}")
                if (!liveRootfs.renameTo(previousRootfs!!)) {
                    throw IllegalStateException("Could not safely move the previous Linux rootfs")
                }
            }
            if (!stagingRootfs.renameTo(originalRootfsPath)) {
                previousRootfs?.let { backup -> backup.renameTo(originalRootfsPath) }
                throw IllegalStateException("Could not move validated staging rootfs into place")
            }
            stagingRootfs = null

            try {
                configureRootfs()
                setupCompleteFile.writeText("done")
            } catch (error: Throwable) {
                rootfsDir.deleteRecursively()
                previousRootfs?.let { backup ->
                    if (backup.exists() && !backup.renameTo(rootfsDir)) {
                        Log.e(TAG, "Failed to restore previous rootfs after configuration error")
                    }
                }
                throw error
            }

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
            stagingRootfs?.let { staging ->
                if (staging.exists() && !staging.deleteRecursively()) {
                    Log.w(TAG, "Could not clean failed staging rootfs: ${staging.absolutePath}")
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
