package com.nativOS.settings

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import java.io.File
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Live monitor and safe Linux software, file, update and recovery shortcuts. */
class SystemToolsActivity : Activity() {
    private lateinit var monitor: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val rootfs by lazy { File(filesDir, "rootfs") }
    private var lastCpu: Pair<Long, Long>? = null
    private val refresh = object : Runnable {
        override fun run() {
            if (!isFinishing) { refreshMonitor(); handler.postDelayed(this, 2000L) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = PremiumUi.background
        window.navigationBarColor = PremiumUi.background
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(28))
            setBackgroundColor(PremiumUi.background)
        }
        page.addView(PremiumUi.text(this, "METMC OS V10 Tools", 28f, bold = true))
        page.addView(PremiumUi.text(this, "NativOS desktop • device status • safe recovery helpers", 14f, PremiumUi.muted).apply {
            setPadding(0, dp(6), 0, dp(18))
        })
        page.addView(PremiumUi.sectionLabel(this, "Live system monitor"))
        monitor = PremiumUi.text(this, "Reading device status…", 14f, PremiumUi.text).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(PremiumUi.surface)
                setStroke(dp(1), PremiumUi.border)
            }
        }
        page.addView(monitor, PremiumUi.matchWidth())
        page.addView(action("Refresh now") { refreshMonitor() }, PremiumUi.matchWidth(dp(8)))
        page.addView(PremiumUi.verticalSpace(this, 22))
        page.addView(PremiumUi.sectionLabel(this, "Linux software & updates"))
        page.addView(action("Open Linux Software Center", true) {
            startActivity(Intent(this, PackageManagerActivity::class.java))
        }, PremiumUi.matchWidth(dp(8)))
        page.addView(action("Check METMC OS updates") {
            openUrl("https://github.com/TEMMC/METMC-OS-v10/releases")
        }, PremiumUi.matchWidth(dp(8)))
        page.addView(PremiumUi.verticalSpace(this, 22))
        page.addView(PremiumUi.sectionLabel(this, "Shared files"))
        page.addView(PremiumUi.text(this, "Android and Linux share files through the NativOS folder in internal storage. Choose a folder with Android's system picker; this tool does not browse or modify Android system files.", 13f, PremiumUi.muted).apply {
            setPadding(0, 0, 0, dp(8))
        })
        page.addView(action("Open shared-folder picker") {
            File(Environment.getExternalStorageDirectory(), "NativOS").mkdirs()
            runCatching { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQUEST_FOLDER) }
                .onFailure { toast("Could not open the folder picker") }
        }, PremiumUi.matchWidth(dp(8)))
        page.addView(action("Storage permissions") {
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) }
                .onFailure { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        }, PremiumUi.matchWidth(dp(8)))
        page.addView(PremiumUi.verticalSpace(this, 22))
        page.addView(PremiumUi.sectionLabel(this, "Recovery & validation"))
        page.addView(PremiumUi.text(this, "Export selected Linux configuration folders only—not the full rootfs or Android partitions. A SHA-256 digest is shown after the ZIP is written.", 13f, PremiumUi.muted).apply {
            setPadding(0, 0, 0, dp(8))
        })
        page.addView(action("Export Linux configuration backup", true) {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/zip"
                putExtra(Intent.EXTRA_TITLE, "METMC-OS-V10-linux-config-backup.zip")
            }, REQUEST_BACKUP)
        }, PremiumUi.matchWidth(dp(8)))
        page.addView(action("Validate Linux root filesystem") { validateRootfs() }, PremiumUi.matchWidth(dp(8)))
        page.addView(PremiumUi.verticalSpace(this, 22))
        page.addView(action("Back to Settings") { finish() }, PremiumUi.matchWidth())
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(page) })
        refreshMonitor()
    }

    private fun action(label: String, primary: Boolean = false, onClick: () -> Unit): MaterialButton =
        PremiumUi.button(this, label, primary).apply { setOnClickListener { onClick() } }

    private fun refreshMonitor() {
        if (!::monitor.isInitialized) return
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memory)
        val total = memory.totalMem.coerceAtLeast(1L)
        val used = total - memory.availMem
        val storage = StatFs(Environment.getDataDirectory().absolutePath)
        val battery = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = (battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100).coerceAtLeast(1)
        val tenths = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val temp = if (tenths >= 0) String.format("%.1f°C", tenths / 10.0) else "n/a"
        val cpu = readCpu()?.let { "$it%" } ?: "measuring…"
        monitor.text = "CPU usage     $cpu\nRAM used      " + formatBytes(used) + " / " + formatBytes(total) + " (" + (used * 100 / total) + "%)\nRAM available " + formatBytes(memory.availMem) + "\nStorage free  " + formatBytes(storage.availableBytes) + " / " + formatBytes(storage.totalBytes) + "\nBattery       " + (if (level >= 0) level * 100 / scale else "n/a") + "%\nBattery temp  $temp\nLinux rootfs  " + if (rootfs.isDirectory) "present" else "not provisioned"
    }

    private fun readCpu(): Int? = runCatching {
        val f = File("/proc/stat").bufferedReader().use { it.readLine() }
            .trim().split(Regex("\\s+")).drop(1).map { it.toLong() }
        val idle = f[3] + (f.getOrNull(4) ?: 0L)
        val total = f.sum()
        val prev = lastCpu
        lastCpu = total to idle
        if (prev == null) null else {
            val dt = total - prev.first
            val di = idle - prev.second
            if (dt <= 0) null else (((dt - di) * 100) / dt).toInt().coerceIn(0, 100)
        }
    }.getOrNull()

    @Deprecated("Use Activity Result APIs in a future update")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        if (requestCode == REQUEST_FOLDER) {
            toast("Folder selected. Shared files are stored in internal storage/NativOS.")
        } else if (requestCode == REQUEST_BACKUP) {
            Thread({
                val result = runCatching { exportConfigBackup(uri) }
                runOnUiThread {
                    result.fold(
                        onSuccess = { checksum ->
                            monitor.text = monitor.text.toString() + "\n\nBackup SHA-256: " + checksum
                            toast("Configuration backup saved")
                        },
                        onFailure = { error -> toast("Backup failed: " + error.message) }
                    )
                }
            }, "metmc-config-backup").start()
        }
    }

    private fun exportConfigBackup(uri: Uri): String {
        val dirs = listOf(File(rootfs, "etc"), File(rootfs, "root/.config"), File(rootfs, "home"))
            .filter { it.isDirectory }
        if (dirs.isEmpty()) error("No Linux configuration folders found; provision Linux first")
        val digest = MessageDigest.getInstance("SHA-256")
        val output = contentResolver.openOutputStream(uri) ?: error("Cannot write selected document")
        DigestOutputStream(output, digest).use { checked ->
            ZipOutputStream(checked.buffered()).use { zip ->
                dirs.forEach { dir ->
                    dir.walkTopDown().forEach fileLoop@ { file ->
                        if (!file.isFile || file.length() > 32L * 1024L * 1024L) return@fileLoop
                        zip.putNextEntry(ZipEntry("rootfs/" + file.relativeTo(rootfs).invariantSeparatorsPath))
                        file.inputStream().buffered().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun validateRootfs() {
        val checks = listOf(
            "bin/bash" to File(rootfs, "bin/bash").exists(),
            "etc/os-release" to File(rootfs, "etc/os-release").exists(),
            "usr/bin/apt-get" to File(rootfs, "usr/bin/apt-get").exists(),
            "var/lib/dpkg/status" to File(rootfs, "var/lib/dpkg/status").exists(),
            "usr/bin/phosh" to File(rootfs, "usr/bin/phosh").exists()
        )
        monitor.text = monitor.text.toString() + "\n\nRootfs validation:\n" +
            checks.joinToString("\n") { (name, ok) -> (if (ok) "PASS" else "MISSING") + "  " + name }
        toast(if (checks.take(4).all { it.second }) "Linux rootfs core checks passed" else "Linux rootfs is incomplete")
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { toast("Could not open update page") }
    }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = bytes.toDouble()
        var i = -1
        do { v /= 1024.0; i++ } while (v >= 1024.0 && i < units.lastIndex)
        return String.format("%.1f %s", v, units[i])
    }
    private fun dp(value: Int) = PremiumUi.dp(this, value)
    override fun onResume() { super.onResume(); handler.removeCallbacks(refresh); handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
    companion object { private const val REQUEST_FOLDER = 1501; private const val REQUEST_BACKUP = 1502 }
}
