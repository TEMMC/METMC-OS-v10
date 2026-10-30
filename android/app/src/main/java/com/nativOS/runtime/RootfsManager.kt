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
        rootfsDir.isDirectory &&
        File(rootfsDir, "bin").isDirectory &&
        File(rootfsDir, "usr").isDirectory &&
        File(rootfsDir, "etc").isDirectory &&
        File(rootfsDir, "etc/os-release").isFile

    /** Accept supported local systems without forcing a distro migration during an APK upgrade. */
    fun isSupportedRootfsReady(): Boolean = isSupportedRootfsAt(rootfsDir)

    private fun isSupportedRootfsAt(directory: File): Boolean {
        if (!directory.isDirectory ||
            !File(directory, "bin").isDirectory ||
            !File(directory, "usr").isDirectory ||
            !File(directory, "etc").isDirectory ||
            !File(directory, "etc/os-release").isFile) return false
        val release = runCatching { File(directory, "etc/os-release").readText() }.getOrDefault("")
        return Regex("""(?m)^ID=["']?(alpine|ubuntu)["']?$""").containsMatchIn(release)
    }

    /** Compatibility name: readiness no longer means a particular APK-bundled distro. */
    fun isBundledDistroReady(): Boolean = isSupportedRootfsReady()

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
        if (rootfsDir.exists()) {
            Log.w(TAG, "Refusing to stage an APK-bundled rootfs over an existing installation")
            onProgress(-1.0, "An existing Linux rootfs is protected; APK updates cannot replace it")
            return false
        }
        val assetName = BUNDLED_ROOTFS_ASSET.substringAfterLast('/')
        val available = runCatching {
            context.assets.list("rootfs")?.contains(assetName) == true
        }.getOrDefault(false)
        if (!available) return false

        return try {
            downloadDir.mkdirs()
            val target = File(downloadDir, "$DEFAULT_DISTRO-rootfs.tar")
            val temporary = File(downloadDir, "$DEFAULT_DISTRO-rootfs.tar.part")
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

    /**
     * Validate the desktop selected by the installed rootfs. Phosh/Phoc must
     * not be rejected merely because optional Labwc fallback packages are absent.
     * Validation is read-only except for legacy Labwc touch-layout seeding.
     */
    fun ensureDesktopRuntime(chrootManager: ChrootManager): Boolean {
        val phoshRequired = listOf("usr/bin/phosh-session", "usr/bin/phoc", "usr/libexec/phosh", "usr/local/bin/nativOS-bridge")
        val labwcRequired = listOf("usr/bin/labwc", "usr/bin/waybar", "usr/bin/fuzzel", "usr/bin/foot", "usr/bin/thunar", "usr/bin/curl", "usr/bin/htop", "usr/local/bin/nativOS-bridge")
        val isPhosh = chrootManager.isPhoshInstalled()
        val required = if (isPhosh) phoshRequired else labwcRequired
        val missing = required.filterNot { File(rootfsDir, it).exists() }
        if (missing.isNotEmpty()) {
            Log.w(TAG, "Installed desktop runtime is incomplete: ${missing.joinToString()}")
            return false
        }
        if (isPhosh) {
            Log.i(TAG, "MT-Phosh/MT-Phoc runtime validated independently of Labwc")
            return true
        }
        Log.i(TAG, "Labwc desktop and compact tools are present")
        return configureLabwcTouchLayout(chrootManager)
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
        // Installation only. Never extract over the active rootfs.
        if (rootfsDir.exists()) {
            Log.e(TAG, "Refusing rootfs extraction because the destination already exists")
            onProgress(-1.0, "Existing rootfs protected; use an explicit recovery workflow to replace it")
            return
        }

        val distro = getInstalledDistro().ifEmpty { DEFAULT_DISTRO }
        val tarball = File(downloadDir, if (distro == "alpine") "${distro}-rootfs.tar" else "${distro}-rootfs.tar.gz")
        val stagingRootfs = File(baseDir, "rootfs-installing")
        try {
            if (!tarball.isFile || tarball.length() == 0L) {
                throw IllegalStateException("Rootfs archive is missing or empty")
            }
            // This directory is never used as the live chroot. A failed extraction
            // can be discarded without affecting the current installation.
            if (stagingRootfs.exists() && !stagingRootfs.deleteRecursively()) {
                throw IllegalStateException("Could not clear incomplete rootfs staging directory")
            }
            if (!stagingRootfs.mkdirs()) {
                throw IllegalStateException("Could not create rootfs staging directory")
            }

            onProgress(0.1, "Extracting Linux filesystem into isolated staging...")
            Log.i(TAG, "Extracting rootfs archive into ${stagingRootfs.absolutePath}")
            val tarArgs = if (distro == "alpine") "xf" else "zxf"
            val process = ProcessBuilder("tar", tarArgs, tarball.absolutePath, "-C", stagingRootfs.absolutePath)
                .redirectErrorStream(true).start()
            val reader = process.inputStream.bufferedReader()
            var line: String?
            var lineCount = 0
            var lastLine = ""
            while (reader.readLine().also { line = it } != null) {
                lastLine = line!!
                lineCount++
                if (lineCount % 500 == 0) {
                    onProgress(0.1 + (lineCount % 5000) / 10000.0, "Extracting files...")
                }
            }
            val exitCode = process.waitFor()
            if (exitCode != 0) throw RuntimeException("tar failed (code $exitCode): $lastLine")

            onProgress(0.7, "Configuring staged Linux environment...")
            configureRootfs(stagingRootfs)
            if (!isSupportedRootfsAt(stagingRootfs)) {
                throw IllegalStateException("Extracted rootfs is not a supported, structurally valid Alpine or Ubuntu installation")
            }

            // Atomic same-filesystem promotion. The live path appears only after
            // extraction and structural validation have both succeeded.
            if (rootfsDir.exists()) {
                throw IllegalStateException("An active rootfs appeared during installation; refusing to replace it")
            }
            if (!stagingRootfs.renameTo(rootfsDir)) {
                throw IllegalStateException("Could not atomically activate the validated rootfs")
            }
            setupCompleteFile.writeText("done")
            tarball.delete()
            onProgress(1.0, "Linux filesystem installed and verified")
        } catch (error: Throwable) {
            if (stagingRootfs.exists() && !stagingRootfs.deleteRecursively()) {
                Log.w(TAG, "Could not remove failed staging rootfs: ${stagingRootfs.absolutePath}")
            }
            Log.e(TAG, "Rootfs installation failed; active rootfs was not changed", error)
            onProgress(-1.0, "Rootfs installation failed: ${error.message}")
        }
    }

    private fun configureRootfs(targetRootfs: File = rootfsDir) {
        File(targetRootfs, "etc/resolv.conf").apply {
            parentFile?.mkdirs()
            writeText("nameserver 8.8.8.8\nnameserver 8.8.4.4\nnameserver 1.1.1.1\n")
        }
        File(targetRootfs, "etc/hostname").writeText("metmc-os\n")
        File(targetRootfs, "etc/hosts").apply {
            parentFile?.mkdirs()
            writeText("127.0.0.1 localhost\n127.0.1.1 metmc-os\n::1 localhost\n")
        }
        File(targetRootfs, "etc/profile.d/nativOS.sh").delete()
        File(targetRootfs, "etc/profile.d/metmc-os.sh").apply {
            parentFile?.mkdirs()
            writeText("#!/bin/bash\nexport DISPLAY=:0\n")
        }
        File(targetRootfs, "etc/sudoers.d/nativOS").delete()
        File(targetRootfs, "etc/sudoers.d/metmc").apply {
            parentFile?.mkdirs()
            writeText("Defaults !requiretty\nmetmc ALL=(ALL:ALL) NOPASSWD: ALL\n")
        }
        listOf("tmp", "tmp/runtime-root", "tmp/runtime-metmc", "run", "var/run", "dev/shm").forEach { File(targetRootfs, it).mkdirs() }
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
