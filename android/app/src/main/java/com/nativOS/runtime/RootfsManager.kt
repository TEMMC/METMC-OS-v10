package com.nativOS.runtime

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
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

    /** The APK bundles Ubuntu Noble ARM64; startup never bootstraps Linux over the network. */
    fun isBundledDistroReady(): Boolean = isRootfsReady() && isUbuntuRootfs()

    private fun isUbuntuRootfs(): Boolean {
        val release = File(rootfsDir, "etc/os-release")
        if (!release.isFile) return false
        val contents = runCatching { release.readText() }.getOrDefault("")
        return Regex("(?m)^ID=ubuntu$").containsMatchIn(contents) &&
            Regex("(?m)^VERSION_CODENAME=noble$").containsMatchIn(contents)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Verify an archive against its adjacent SHA-256 sidecar before extraction. */
    private fun verifyChecksum(archive: File, checksumFile: File) {
        require(archive.isFile && archive.length() > 0L) { "Rootfs archive is missing or empty: ${archive.absolutePath}" }
        require(checksumFile.isFile) { "Rootfs SHA-256 sidecar is missing: ${checksumFile.absolutePath}" }
        val expected = checksumFile.readText().trim().split(Regex("\\s+")).firstOrNull()?.lowercase()
        require(expected != null && expected.matches(Regex("[0-9a-f]{64}"))) {
            "Rootfs SHA-256 sidecar has invalid format"
        }
        val actual = sha256(archive)
        require(actual == expected) {
            "Rootfs SHA-256 mismatch: expected $expected, got $actual"
        }
        Log.i(TAG, "Rootfs archive SHA-256 verified: $actual")
    }

    /**
     * Run Termux GNU tar as root when available. The app-private rootfs contains Linux
     * ownership and restrictive modes, so extraction must not run under the app UID.
     * Fall back to Android's tar only when Termux tar is unavailable.
     */
    private fun runTar(args: List<String>, phase: String, onProgress: ((Double, String) -> Unit)? = null): Int {
        fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
        val termuxTar = "/data/data/com.termux/files/usr/bin/tar"
        val tarArgs = (listOf(termuxTar) + args).joinToString(" ") { quote(it) }
        val fallbackArgs = (listOf("tar") + args).joinToString(" ") { quote(it) }
        val command = "if [ -x ${quote(termuxTar)} ]; then $tarArgs; else $fallbackArgs; fi"
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()

        var lineCount = 0
        val tail = ArrayDeque<String>()
        process.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                lineCount++
                if (tail.size == 40) tail.removeFirst()
                tail.addLast(line)
                if (phase == "extract" || line.contains("error", true) || line.contains("warning", true) ||
                    line.contains("cannot", true) || line.contains("failed", true)) {
                    Log.w(TAG, "tar[$phase]: $line")
                }
                if (lineCount % 500 == 0) onProgress?.invoke(
                    0.1 + (lineCount % 5000) / 10000.0, "Extracting files..."
                )
            }
        }
        val exitCode = process.waitFor()
        if (phase == "extract" && exitCode == 0) {
            val destination = args.indexOf("-C").let { index ->
                if (index >= 0 && index + 1 < args.size) args[index + 1] else null
            }
            if (destination != null) {
                val uid = android.os.Process.myUid()
                val ownership = ProcessBuilder(
                    "su", "-c", "chown -R $uid:$uid ${quote(destination)}"
                ).redirectErrorStream(true).start()
                val ownershipOutput = ownership.inputStream.bufferedReader().readText().trim()
                val ownershipExit = ownership.waitFor()
                if (ownershipExit != 0) {
                    Log.e(TAG, "Could not return staging ownership to app UID $uid: $ownershipOutput")
                    return ownershipExit
                }
            }
        }
        Log.i(TAG, "tar[$phase] exit=$exitCode outputLines=$lineCount tail=${tail.joinToString(" | ")}")
        return exitCode
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
            val checksumAsset = "$BUNDLED_ROOTFS_ASSET.sha256"
            val checksumTarget = File(downloadDir, "$DEFAULT_DISTRO-rootfs.tar.sha256")
            onProgress(0.0, "Preparing bundled Linux system...")
            var copied = 0L
            val digest = MessageDigest.getInstance("SHA-256")
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
                        digest.update(buffer, 0, count)
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
            val expectedChecksum = context.assets.open(checksumAsset).bufferedReader().use {
                it.readText().trim().split(Regex("\\s+")).first().lowercase()
            }
            require(expectedChecksum.matches(Regex("[0-9a-f]{64}"))) { "Bundled rootfs SHA-256 sidecar is invalid" }
            val actualChecksum = digest.digest().joinToString("") { "%02x".format(it) }
            require(actualChecksum == expectedChecksum) {
                "Bundled rootfs SHA-256 mismatch: expected $expectedChecksum, got $actualChecksum"
            }
            Log.i(TAG, "Bundled Ubuntu rootfs SHA-256 verified: $actualChecksum")
            if (runTar(listOf("-tf", temporary.absolutePath), "verify") != 0) {
                throw IllegalStateException("Bundled rootfs TAR integrity check failed")
            }
            val checksumTemporary = File(downloadDir, "$DEFAULT_DISTRO-rootfs.tar.sha256.tmp")
            context.assets.open(checksumAsset).use { input ->
                FileOutputStream(checksumTemporary, false).use { output -> input.copyTo(output); output.fd.sync() }
            }
            if (checksumTarget.exists() && !checksumTarget.delete()) throw IllegalStateException("Could not replace rootfs checksum")
            if (!checksumTemporary.renameTo(checksumTarget)) throw IllegalStateException("Could not finalize rootfs checksum")
            if (target.exists() && !target.delete()) {
                throw IllegalStateException("Could not replace staged rootfs")
            }
            if (!temporary.renameTo(target)) {
                throw IllegalStateException("Could not finalize staged rootfs")
            }
            configFile.writeText(DEFAULT_DISTRO)
            onProgress(1.0, "Bundled Ubuntu system ready")
            Log.i(TAG, "Staged bundled rootfs (${copied / (1024 * 1024)} MiB)")
            true
        } catch (error: Throwable) {
            File(downloadDir, "$DEFAULT_DISTRO-rootfs.tar.part").delete()
            File(downloadDir, "$DEFAULT_DISTRO-rootfs.tar.sha256.tmp").delete()
            Log.e(TAG, "Could not stage bundled Ubuntu rootfs", error)
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
        if (distro == DEFAULT_DISTRO) {
            if (!stageBundledRootfs(onProgress)) {
                onProgress(-1.0, "Bundled Ubuntu rootfs is missing, corrupt, or has an invalid checksum")
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
            val checksum = sha256(targetFile)
            File(downloadDir, "${distro}-rootfs.tar.gz.sha256").writeText("$checksum  ${targetFile.name}\n")
            configFile.writeText(distro)
            onProgress(1.0, "$distroName downloaded successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Download failed: ${e.message}", e)
            onProgress(-1.0, "Download failed: ${e.message}")
        }
    }

    /**
     * Extract the bundled rootfs with the same simple system-tar approach used by NativOS.
     * Keep extraction in a staging directory and reject every non-zero tar exit so a partial
     * filesystem can never be marked ready or replace a working installation.
     */
    fun extractRootfs(onProgress: (progress: Double, status: String) -> Unit) {
        var previousRootfs: File? = null
        var stagingRootfs: File? = null
        val previousSetupComplete = if (setupCompleteFile.isFile) setupCompleteFile.readText() else null
        try {
            val distro = getInstalledDistro().ifEmpty { DEFAULT_DISTRO }
            val bundledUbuntu = distro == DEFAULT_DISTRO
            val tarball = File(downloadDir, if (bundledUbuntu) "ubuntu-rootfs.tar" else "${distro}-rootfs.tar.gz")
            val checksumFile = File(downloadDir, if (bundledUbuntu) "ubuntu-rootfs.tar.sha256" else "${distro}-rootfs.tar.gz.sha256")
            if (!tarball.isFile || tarball.length() == 0L) {
                throw IllegalStateException("Rootfs archive is missing or empty: ${tarball.name}")
            }
            verifyChecksum(tarball, checksumFile)
            val isGzip = tarball.inputStream().use { input -> input.read() == 0x1f && input.read() == 0x8b }
            if (tarball.name.endsWith(".gz") != isGzip) {
                throw IllegalStateException("Archive compression does not match filename: ${tarball.name}")
            }

            // Match NativOS's tar invocation pattern, using -xf for MT's uncompressed .tar.
            // The CI packager dereferences hard links so Android Toybox tar doesn't need to
            // create hard links in app-private storage.
            if (runTar(if (isGzip) listOf("-tzf", tarball.absolutePath) else listOf("-tf", tarball.absolutePath), "verify") != 0) {
                throw IllegalStateException("Rootfs archive integrity check failed; see tar[verify] diagnostics")
            }

            val stagingDir = File(baseDir, "rootfs-staging-${System.currentTimeMillis()}")
            stagingRootfs = stagingDir
            if (!stagingDir.mkdirs()) throw IllegalStateException("Could not create rootfs staging directory")
            onProgress(0.1, "Extracting Linux filesystem...")
            val extractArgs = if (isGzip) {
                listOf("-zxf", tarball.absolutePath, "-C", stagingDir.absolutePath)
            } else {
                listOf("-xf", tarball.absolutePath, "-C", stagingDir.absolutePath)
            }
            val exitCode = runTar(extractArgs, "extract", onProgress)
            if (exitCode != 0) throw IllegalStateException("tar extraction failed (exit=$exitCode); see tar[extract] diagnostics")

            // Tar archives carry Linux ownership and restrictive modes (for example
            // /etc/sudoers.d may be 0700 and owned by an unrelated numeric UID).
            // In app-private storage the extracting Android app must own and traverse
            // the tree, otherwise configuration fails with EACCES. Normalize each
            // directory before descending so even locked-down parents can be repaired.
            normalizeExtractedPermissions(stagingDir)

            val osRelease = File(stagingDir, "etc/os-release")
            if (!File(stagingDir, "bin").exists() || !File(stagingDir, "usr").isDirectory ||
                !File(stagingDir, "etc").isDirectory || !osRelease.isFile) {
                throw IllegalStateException("Extracted rootfs is incomplete (missing bin, usr, etc, or etc/os-release)")
            }
            if (bundledUbuntu) {
                val release = osRelease.readText()
                if (!Regex("(?m)^ID=ubuntu$").containsMatchIn(release) ||
                    !Regex("(?m)^VERSION_CODENAME=noble$").containsMatchIn(release)) {
                    val detected = release.lineSequence().firstOrNull { it.startsWith("PRETTY_NAME=") } ?: "unknown distribution"
                    throw IllegalStateException("Expected Ubuntu 24.04 Noble; extracted $detected")
                }
            }

            if (rootfsDir.exists()) {
                previousRootfs = File(baseDir, "rootfs-previous-${System.currentTimeMillis()}")
                if (!rootfsDir.renameTo(previousRootfs!!)) {
                    throw IllegalStateException("Could not safely move the previous Linux rootfs")
                }
            }
            if (!stagingDir.renameTo(rootfsDir)) {
                previousRootfs?.let { if (it.exists()) it.renameTo(rootfsDir) }
                throw IllegalStateException("Could not promote validated rootfs staging directory")
            }
            stagingRootfs = null
            try {
                onProgress(0.7, "Configuring Linux environment...")
                configureRootfs()
                if (bundledUbuntu && !isUbuntuRootfs()) {
                    throw IllegalStateException("Configured rootfs failed Ubuntu Noble validation")
                }
                setupCompleteFile.writeText("done")
            } catch (error: Throwable) {
                rootfsDir.deleteRecursively()
                previousRootfs?.let { backup ->
                    if (backup.exists() && !backup.renameTo(rootfsDir)) {
                        Log.e(TAG, "Failed to restore previous rootfs after configuration error")
                    }
                }
                if (previousSetupComplete == null) setupCompleteFile.delete() else setupCompleteFile.writeText(previousSetupComplete)
                throw error
            }

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
                        if (!oldHome.deleteRecursively()) Log.w(TAG, "Old metmc home remains in previous-rootfs backup")
                    }
                }
            }
            previousRootfs?.let { if (it.exists() && !it.deleteRecursively()) Log.w(TAG, "Could not remove previous rootfs backup: ${it.absolutePath}") }
            tarball.delete()
            checksumFile.delete()
            onProgress(1.0, "Linux filesystem ready")
        } catch (error: Exception) {
            stagingRootfs?.let { if (it.exists() && !it.deleteRecursively()) Log.w(TAG, "Could not clean failed staging rootfs: ${it.absolutePath}") }
            if (previousSetupComplete == null) setupCompleteFile.delete() else setupCompleteFile.writeText(previousSetupComplete)
            previousRootfs?.let { if (it.exists() && !rootfsDir.exists() && !it.renameTo(rootfsDir)) Log.e(TAG, "Failed to restore previous rootfs from ${it.absolutePath}") }
            Log.e(TAG, "Extraction failed: ${error.message}", error)
            onProgress(-1.0, "Rootfs extraction failed: ${error.message}")
        }
    }

    /** Repair archive modes without granting access to other Android apps. */
    private fun normalizeExtractedPermissions(root: File) {
        fun repair(node: File) {
            if (node.isDirectory) {
                check(node.setReadable(true, true) || node.canRead()) { "Cannot read extracted directory: ${node.path}" }
                check(node.setWritable(true, true) || node.canWrite()) { "Cannot write extracted directory: ${node.path}" }
                check(node.setExecutable(true, true) || node.canExecute()) { "Cannot traverse extracted directory: ${node.path}" }
                val children = node.listFiles() ?: throw IllegalStateException("Cannot list extracted directory: ${node.path}")
                children.forEach(::repair)
            } else if (node.isFile) {
                val wasExecutable = node.canExecute()
                check(node.setReadable(true, true) || node.canRead()) { "Cannot read extracted file: ${node.path}" }
                check(node.setWritable(true, true) || node.canWrite()) { "Cannot write extracted file: ${node.path}" }
                if (wasExecutable) check(node.setExecutable(true, true) || node.canExecute()) { "Cannot execute extracted file: ${node.path}" }
            }
        }
        repair(root)
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
