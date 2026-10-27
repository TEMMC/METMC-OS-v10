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

    /** The APK bundles the prepared Ubuntu ARM64 rootfs; startup is fully offline. */
    fun isBundledDistroReady(): Boolean = isRootfsReady() && isUbuntuRootfs()

    private fun isUbuntuRootfs(): Boolean {
        val release = File(rootfsDir, "etc/os-release")
        if (!release.exists()) return false
        val contents = runCatching { release.readText() }.getOrDefault("")
        return Regex("(?m)^ID=ubuntu$").containsMatchIn(contents)
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

    /** Validate MT-Phosh independently; do not require fallback Labwc components. */
    fun ensurePhoshRuntime(): Boolean {
        val required = listOf(
            "usr/bin/phosh-session", "usr/bin/phoc", "usr/libexec/phosh",
            "usr/bin/python3", "usr/local/bin/mt-bridge",
            "usr/lib/mt-os-bridge/mt_bridge.py"
        )
        val missing = required.filterNot { File(rootfsDir, it).exists() }
        if (missing.isNotEmpty()) {
            Log.w(TAG, "Offline MT-Phosh/MT-Phoc rootfs is missing: ${missing.joinToString()}")
            return false
        }
        val executable = listOf("usr/bin/phosh-session", "usr/bin/phoc", "usr/local/bin/mt-bridge")
        val notExecutable = executable.filterNot { File(rootfsDir, it).canExecute() }
        if (notExecutable.isNotEmpty()) {
            Log.w(TAG, "MT desktop/bridge entry points are not executable: ${notExecutable.joinToString()}")
            return false
        }
        Log.i(TAG, "MT-Phosh/MT-Phoc and bundled Android bridge client are present")
        return true
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

    /** Stage only the Ubuntu rootfs bundled in the APK; never download a distro at boot. */
    fun downloadRootfs(
        distro: String = DEFAULT_DISTRO,
        onProgress: (progress: Double, status: String) -> Unit
    ) {
        if (distro != DEFAULT_DISTRO) {
            onProgress(-1.0, "Unsupported Linux distribution: $distro. MT OS uses Ubuntu only.")
            return
        }
        if (!stageBundledRootfs(onProgress)) {
            onProgress(-1.0, "Bundled Ubuntu rootfs is missing from this APK")
        }
    }

    fun extractRootfs(onProgress: (progress: Double, status: String) -> Unit) {
        var previousRootfs: File? = null
        try {
            val distro = getInstalledDistro().ifEmpty { DEFAULT_DISTRO }
            if (distro != DEFAULT_DISTRO) throw IllegalStateException("Unsupported Linux distribution: $distro")
            val tarball = File(downloadDir, "ubuntu-rootfs.tar")
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
            val process = ProcessBuilder("tar", "xf", tarball.absolutePath, "-C", rootfsDir.absolutePath)
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
            if (!isUbuntuRootfs()) {
                throw IllegalStateException("Bundled rootfs is not an Ubuntu root filesystem")
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

    private fun calculateDirSize(dir: File): Long =
        if (!dir.exists()) 0 else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
