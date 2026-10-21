package com.nativOS.settings

import android.app.Activity
import android.app.ActivityManager
import android.media.AudioManager
import android.Manifest
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.content.IntentFilter
import android.net.Uri
import android.widget.SeekBar
import android.widget.Toast
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.nativOS.bridge.AndroidAppIntegration
import com.nativOS.launcher.KioskActivity

class SettingsActivity : Activity() {
    private lateinit var summary: TextView
    private lateinit var desktopCheck: TextView
    private lateinit var homeCheck: TextView
    private lateinit var deGoogleStatus: TextView
    private lateinit var batteryStatus: TextView
    private lateinit var brightnessStatus: TextView
    private lateinit var brightnessSeekBar: SeekBar
    private lateinit var volumeStatus: TextView
    private lateinit var volumeSeekBar: SeekBar
    private lateinit var systemUsageStatus: TextView
    private var lastCpuCounters: Pair<Long, Long>? = null
    private var pendingDiagnosticReport: String? = null

    companion object {
        private const val REQUEST_HARDWARE_PERMISSIONS = 1202
        private const val REQUEST_EXPORT_DIAGNOSTICS = 1203
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = PremiumUi.background
        window.navigationBarColor = PremiumUi.background

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(40))
        }

        content.addView(PremiumUi.text(this, "Settings", 34f, bold = true))
        summary = PremiumUi.text(this, "", 15f, PremiumUi.muted).apply {
            setPadding(0, dp(6), 0, 0)
        }
        content.addView(summary)
        content.addView(PremiumUi.verticalSpace(this, 28))

        content.addView(PremiumUi.sectionLabel(this, "METMC OS v10 mode"))
        content.addView(group().apply {
            desktopCheck = PremiumUi.text(this@SettingsActivity, "", 20f, PremiumUi.primary)
            addView(row(
                title = "Linux desktop",
                subtitle = "Open METMC OS v10 like a normal app",
                trailing = desktopCheck
            ) {
                NativOSPreferences.setOperatingMode(this@SettingsActivity, NativOSPreferences.OperatingMode.DESKTOP_APP)
                if (HomeRoleManager.isDefaultHome(this@SettingsActivity)) {
                    startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
                }
                updateState()
            })
            addView(PremiumUi.separator(this@SettingsActivity))
            homeCheck = PremiumUi.text(this@SettingsActivity, "", 20f, PremiumUi.primary)
            addView(row(
                title = "Home launcher",
                subtitle = "Use METMC OS v10 as your phone's Home screen",
                trailing = homeCheck
            ) {
                NativOSPreferences.setOperatingMode(this@SettingsActivity, NativOSPreferences.OperatingMode.HOME_LAUNCHER)
                HomeRoleManager.requestDefaultHome(this@SettingsActivity)
            })
            addView(PremiumUi.separator(this@SettingsActivity))
            addView(row(
                title = "De-Googled mode",
                subtitle = "Coming later",
                trailingText = ""
            ).apply {
                isEnabled = false
                alpha = 0.42f
            })
        }, PremiumUi.matchWidth())

        content.addView(PremiumUi.verticalSpace(this, 26))
        content.addView(PremiumUi.sectionLabel(this, "Privacy"))
        content.addView(group().apply {
            deGoogleStatus = PremiumUi.text(this@SettingsActivity, "Not scanned", 13f, PremiumUi.muted)
            addView(row(
                title = "Google components",
                subtitleView = deGoogleStatus,
                trailingText = "Scan"
            ) { scanGoogleComponents() })
            addView(PremiumUi.separator(this@SettingsActivity))
            addView(row(
                title = "System App Remover",
                subtitle = "Review and safely disable preinstalled apps",
                trailingText = "›"
            ) {
                startActivity(Intent(this@SettingsActivity, SystemAppRemoverActivity::class.java))
            })
        }, PremiumUi.matchWidth())

        content.addView(PremiumUi.verticalSpace(this, 26))
        content.addView(PremiumUi.sectionLabel(this, "Hardware & power"))
        content.addView(group().apply {
            batteryStatus = PremiumUi.text(this@SettingsActivity, "Checking battery…", 13f, PremiumUi.muted)
            addView(row(
                title = "Battery & charging",
                subtitleView = batteryStatus,
                trailingText = "↻"
            ) { updateHardwareState() })
            addView(PremiumUi.separator(this@SettingsActivity))
            brightnessStatus = PremiumUi.text(this@SettingsActivity, "", 13f, PremiumUi.muted)
            addView(row(
                title = "Screen brightness permission",
                subtitleView = brightnessStatus,
                trailingText = if (Settings.System.canWrite(this@SettingsActivity)) "Allowed" else "Allow"
            ) { requestBrightnessPermission() })
            addView(LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(2), dp(16), dp(12))
                brightnessSeekBar = SeekBar(this@SettingsActivity).apply {
                    max = 255
                    progress = readBrightness()
                    isEnabled = Settings.System.canWrite(this@SettingsActivity)
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                        override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
                        override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                            if (fromUser && Settings.System.canWrite(this@SettingsActivity)) {
                                Settings.System.putInt(
                                    contentResolver,
                                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                                )
                                val saved = Settings.System.putInt(
                                    contentResolver, Settings.System.SCREEN_BRIGHTNESS, progress
                                )
                                if (saved) brightnessStatus.text = "Brightness: ${progress * 100 / 255}% · Android permission allowed"
                            }
                        }
                    })
                }
                addView(PremiumUi.text(this@SettingsActivity, "Brightness", 13f, PremiumUi.muted))
                addView(brightnessSeekBar, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ))
            })
            addView(PremiumUi.separator(this@SettingsActivity))
            addView(switchRow(
                "Keep screen awake",
                "Prevent the display from sleeping while METMC OS is active",
                NativOSPreferences.keepScreenOn(this@SettingsActivity)
            ) { checked ->
                NativOSPreferences.setKeepScreenOn(this@SettingsActivity, checked)
                Toast.makeText(
                    this@SettingsActivity,
                    if (checked) "Screen will stay awake in METMC OS" else "Android may sleep the screen normally",
                    Toast.LENGTH_SHORT
                ).show()
            })
            addView(PremiumUi.separator(this@SettingsActivity))
            addView(row(
                title = "Request hardware permissions",
                subtitle = "Camera, mic, GPS, Wi-Fi discovery, Bluetooth, sensors, calls, SMS and notifications",
                trailingText = "Review"
            ) { requestHardwarePermissions() })
            addView(PremiumUi.separator(this@SettingsActivity))
            volumeStatus = PremiumUi.text(this@SettingsActivity, "Media volume", 13f, PremiumUi.muted)
            addView(LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(10), dp(16), dp(12))
                addView(PremiumUi.text(this@SettingsActivity, "Audio & volume buttons", 16f))
                addView(volumeStatus)
                volumeSeekBar = SeekBar(this@SettingsActivity).apply {
                    max = (getSystemService(AUDIO_SERVICE) as AudioManager).getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    progress = (getSystemService(AUDIO_SERVICE) as AudioManager).getStreamVolume(AudioManager.STREAM_MUSIC)
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                        override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
                        override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                            if (fromUser) {
                                (getSystemService(AUDIO_SERVICE) as AudioManager).setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0)
                                updateVolumeState()
                            }
                        }
                    })
                }
                addView(volumeSeekBar, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ))
            })
            addView(PremiumUi.separator(this@SettingsActivity))
            systemUsageStatus = PremiumUi.text(this@SettingsActivity, "Checking system usage…", 13f, PremiumUi.muted)
            addView(row(
                title = "CPU & memory",
                subtitleView = systemUsageStatus,
                trailingText = "↻"
            ) { updateSystemUsage() })
            addView(PremiumUi.separator(this@SettingsActivity))
            addView(row(
                title = "Export system diagnostics",
                subtitle = "Save device, memory, storage and permission details to a text file",
                trailingText = "Export"
            ) { exportDiagnostics() })
            addView(PremiumUi.separator(this@SettingsActivity))
            addView(row(
                title = "Copy & paste bridge",
                subtitle = "Clipboard get/set actions are available to Linux bridge clients; Android restricts background clipboard reads",
                trailingText = "Enabled"
            ))
        }, PremiumUi.matchWidth())

        content.addView(PremiumUi.verticalSpace(this, 26))
        content.addView(PremiumUi.sectionLabel(this, "Android integration"))
        content.addView(group().apply {
            addView(switchRow(
                "Immersive desktop",
                "Hide Android system bars while Linux is active",
                NativOSPreferences.hideSystemBars(this@SettingsActivity)
            ) { checked ->
                NativOSPreferences.setHideSystemBars(this@SettingsActivity, checked)
            })
            addView(PremiumUi.separator(this@SettingsActivity))
            addView(switchRow(
                "Show Android apps",
                "Include installed Android apps in the Linux drawer",
                NativOSPreferences.showAndroidApps(this@SettingsActivity)
            ) { checked ->
                NativOSPreferences.setShowAndroidApps(this@SettingsActivity, checked)
                AndroidAppIntegration.sync(this@SettingsActivity)
            })
            addView(PremiumUi.separator(this@SettingsActivity))
            addView(row("Default Home app", "Choose which launcher Android uses", trailingText = "›") {
                HomeRoleManager.requestDefaultHome(this@SettingsActivity)
            })
            addView(PremiumUi.separator(this@SettingsActivity))
            addView(row("Linux package manager", "Check and install updates inside the Linux root filesystem", trailingText = "›") {
                startActivity(Intent(this@SettingsActivity, PackageManagerActivity::class.java))
            })
            addView(PremiumUi.separator(this@SettingsActivity))
            addView(row("System monitor, files & recovery", "Live CPU/RAM/battery, shared folder, software center, updates and config backup", trailingText = "›") {
                startActivity(Intent(this@SettingsActivity, SystemToolsActivity::class.java))
            })
            addView(PremiumUi.separator(this@SettingsActivity))
            addView(row("Android settings", "Open the underlying system settings", trailingText = "›") {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            })
        }, PremiumUi.matchWidth())

        content.addView(PremiumUi.verticalSpace(this, 26))
        content.addView(group().apply {
            addView(row("Return to desktop", trailingText = "›") {
                startActivity(Intent(this@SettingsActivity, KioskActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                })
                finish()
            })
        }, PremiumUi.matchWidth())

        content.addView(PremiumUi.verticalSpace(this, 26))
        content.addView(PremiumUi.sectionLabel(this, "About"))
        content.addView(group().apply {
            addView(row(
                title = "METMC OS V10",
                subtitle = "Created by Tinotenda Enock Mapfumo (Dr TEMMC); based on NativOS",
                trailingText = "v10.5"
            ))
            addView(PremiumUi.separator(this@SettingsActivity))
            addView(row(
                title = "Project source",
                subtitle = "github.com/TEMMC/METMC-OS-v10",
                trailingText = "↗"
            ) {
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/TEMMC/METMC-OS-v10")))
            })
        }, PremiumUi.matchWidth())

        content.addView(PremiumUi.text(this, "System changes are explicit and reversible.", 12f, PremiumUi.muted).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(20), dp(12), 0)
        }, PremiumUi.matchWidth())

        setContentView(ScrollView(this).apply {
            background = PremiumUi.pageBackground()
            isFillViewport = true
            addView(content)
        })
    }

    override fun onResume() {
        super.onResume()
        updateState()
        updateHardwareState()
        updateVolumeState()
        updateSystemUsage()
    }


    private fun exportDiagnostics() {
        pendingDiagnosticReport = buildDiagnosticReport()
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, "METMC-OS-system-diagnostics.txt")
        }
        try {
            startActivityForResult(intent, REQUEST_EXPORT_DIAGNOSTICS)
        } catch (_: Exception) {
            pendingDiagnosticReport = null
            Toast.makeText(this, "Could not open file picker", Toast.LENGTH_LONG).show()
        }
    }

    private fun buildDiagnosticReport(): String {
        val memory = ActivityManager.MemoryInfo()
        (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
        val data = android.os.StatFs(android.os.Environment.getDataDirectory().absolutePath)
        val display = resources.displayMetrics
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val batteryPercent = if (level >= 0 && scale > 0) "${level * 100 / scale}%" else "Unavailable"
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        @Suppress("DEPRECATION")
        val versionCode = if (Build.VERSION.SDK_INT >= 28) packageInfo.longVersionCode else packageInfo.versionCode.toLong()
        val versionName = packageInfo.versionName ?: "unknown"
        val permissions = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ).joinToString("\n") { permission ->
            "$permission: ${if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) "granted" else "not granted"}"
        }
        fun mb(bytes: Long) = bytes / (1024L * 1024L)
        return buildString {
            appendLine("METMC OS v10 — System diagnostics")
            appendLine("Generated: ${java.time.Instant.now()}")
            appendLine()
            appendLine("[App]")
            appendLine("Package: $packageName")
            appendLine("Version: $versionName ($versionCode)")
            appendLine()
            appendLine("[Device]")
            appendLine("Manufacturer: ${Build.MANUFACTURER}")
            appendLine("Model: ${Build.MODEL}")
            appendLine("Device: ${Build.DEVICE}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Build ID: ${Build.DISPLAY}")
            appendLine("Screen: ${display.widthPixels} x ${display.heightPixels} pixels")
            appendLine("Density: ${display.densityDpi} dpi")
            appendLine()
            appendLine("[Memory]")
            appendLine("RAM total: ${mb(memory.totalMem)} MB")
            appendLine("RAM available: ${mb(memory.availMem)} MB")
            appendLine("Low-memory state: ${memory.lowMemory}")
            appendLine()
            appendLine("[Internal storage]")
            appendLine("Total: ${mb(data.totalBytes)} MB")
            appendLine("Available: ${mb(data.availableBytes)} MB")
            appendLine()
            appendLine("[Battery]")
            appendLine("Level: $batteryPercent")
            appendLine()
            appendLine("[Permissions]")
            appendLine(permissions)
            appendLine()
            appendLine("Privacy: this report excludes contacts, messages, account names, Android ID, and file contents.")
            appendLine("Review the report before sharing it. Device/build details may still identify your device model.")
        }
    }

    @Deprecated("Deprecated in Android API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_EXPORT_DIAGNOSTICS) return
        val report = pendingDiagnosticReport
        pendingDiagnosticReport = null
        if (resultCode != Activity.RESULT_OK || data?.data == null || report == null) {
            Toast.makeText(this, "Diagnostic export cancelled", Toast.LENGTH_SHORT).show()
            return
        }
        val saved = runCatching {
            contentResolver.openOutputStream(data.data!!)?.bufferedWriter()?.use { it.write(report) }
                ?: error("Unable to open selected file")
        }
        Toast.makeText(
            this,
            if (saved.isSuccess) "Diagnostics exported" else "Export failed: ${saved.exceptionOrNull()?.message ?: "unknown error"}",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun group() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(PremiumUi.surface)
        }
        clipToOutline = true
    }

    private fun row(
        title: String,
        subtitle: String? = null,
        subtitleView: TextView? = null,
        trailingText: String = "",
        trailing: TextView? = null,
        destructive: Boolean = false,
        action: (() -> Unit)? = null
    ): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(if (subtitle != null || subtitleView != null) 66 else 54)
        setPadding(dp(16), dp(10), dp(14), dp(10))
        if (action != null) {
            isClickable = true
            isFocusable = true
            background = selectableBackground()
            setOnClickListener { action() }
        }

        addView(LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(PremiumUi.text(
                this@SettingsActivity,
                title,
                16f,
                if (destructive) PremiumUi.danger else PremiumUi.text
            ))
            if (subtitleView != null) {
                subtitleView.setPadding(0, dp(4), 0, 0)
                addView(subtitleView)
            } else if (subtitle != null) {
                addView(PremiumUi.text(this@SettingsActivity, subtitle, 13f, PremiumUi.muted).apply {
                    setPadding(0, dp(4), 0, 0)
                })
            }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        addView(trailing ?: PremiumUi.text(
            this@SettingsActivity,
            trailingText,
            if (trailingText == "›") 28f else 15f,
            if (trailingText == "›") PremiumUi.muted else PremiumUi.primary
        ).apply { gravity = Gravity.CENTER })
    }

    private fun switchRow(
        title: String,
        subtitle: String,
        checked: Boolean,
        onChanged: (Boolean) -> Unit
    ) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(66)
        setPadding(dp(16), dp(10), dp(10), dp(10))
        addView(LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(PremiumUi.text(this@SettingsActivity, title, 16f))
            addView(PremiumUi.text(this@SettingsActivity, subtitle, 13f, PremiumUi.muted).apply {
                setPadding(0, dp(4), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(SwitchMaterial(this@SettingsActivity).apply {
            text = ""
            isChecked = checked
            setOnCheckedChangeListener { _, value -> onChanged(value) }
        })
    }

    private fun readBrightness(): Int = runCatching {
        Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128).coerceIn(0, 255)
    }.getOrDefault(128)

    private fun updateHardwareState() {
        if (!::batteryStatus.isInitialized) return
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val state = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
            BatteryManager.BATTERY_STATUS_FULL -> "Fully charged"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
            else -> "Status unavailable"
        }
        val source = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_AC -> "AC power"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
            else -> "Battery"
        }
        batteryStatus.text = if (percent >= 0) "${percent}% · $state · $source" else state
        val canWrite = Settings.System.canWrite(this)
        brightnessStatus.text = if (canWrite) {
            "Allowed · Current brightness ${readBrightness() * 100 / 255}%"
        } else {
            "Not allowed yet · Tap to open Android's permission screen"
        }
        brightnessSeekBar.progress = readBrightness()
        brightnessSeekBar.isEnabled = canWrite
    }

    private fun updateVolumeState() {
        if (!::volumeStatus.isInitialized || !::volumeSeekBar.isInitialized) return
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        volumeStatus.text = "Media volume: ${current * 100 / maximum}% · ${current}/${maximum}"
        if (volumeSeekBar.max != maximum) volumeSeekBar.max = maximum
        if (volumeSeekBar.progress != current) volumeSeekBar.progress = current
    }

    private fun updateSystemUsage() {
        if (!::systemUsageStatus.isInitialized) return
        val manager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(memory)
        val totalMb = memory.totalMem / (1024L * 1024L)
        val availableMb = memory.availMem / (1024L * 1024L)
        val usedMb = (totalMb - availableMb).coerceAtLeast(0)
        val cpu = readCpuUsagePercent()
        val cpuText = cpu?.let { "CPU: ${it}%" } ?: "CPU: unavailable"
        systemUsageStatus.text = "${cpuText} · RAM: ${usedMb} MB / ${totalMb} MB (${if (totalMb > 0) usedMb * 100 / totalMb else 0}%)"
    }

    private fun readCpuUsagePercent(): Int? {
        return try {
            val fields = java.io.File("/proc/stat").bufferedReader().use { it.readLine().trim().split(Regex("\\s+")) }
            if (fields.firstOrNull() != "cpu" || fields.size < 5) return null
            val values = fields.drop(1).map { it.toLong() }
            val idle = values[3] + (values.getOrNull(4) ?: 0L)
            val total = values.sum()
            val previous = lastCpuCounters
            lastCpuCounters = idle to total
            if (previous == null) return null
            val idleDelta = idle - previous.first
            val totalDelta = total - previous.second
            if (totalDelta <= 0L) null else (((totalDelta - idleDelta).toDouble() / totalDelta) * 100).toInt().coerceIn(0, 100)
        } catch (_: Exception) {
            null
        }
    }

    private fun requestBrightnessPermission() {
        if (Settings.System.canWrite(this)) {
            updateHardwareState()
            return
        }
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun requestHardwarePermissions() {
        // Ask Android for user-approved access to the hardware APIs exposed by
        // the Linux bridge. These grants do not bypass Android's system-service
        // restrictions or SELinux; unsupported actions still fall back to Android UI.
        val requested = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.BODY_SENSORS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requested += Manifest.permission.BLUETOOTH_CONNECT
            requested += Manifest.permission.BLUETOOTH_SCAN
            requested += Manifest.permission.BLUETOOTH_ADVERTISE
        }
        if (Build.VERSION.SDK_INT >= 33) {
            requested += Manifest.permission.NEARBY_WIFI_DEVICES
            requested += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = requested.distinct().filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            Toast.makeText(this, "All requested hardware permissions are already granted", Toast.LENGTH_SHORT).show()
        } else {
            requestPermissions(missing.toTypedArray(), REQUEST_HARDWARE_PERMISSIONS)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_HARDWARE_PERMISSIONS) {
            val granted = grantResults.count { it == PackageManager.PERMISSION_GRANTED }
            val denied = grantResults.size - granted
            Toast.makeText(
                this,
                if (denied == 0) "Hardware permissions granted" else "$granted granted · $denied denied; you can change them in Android Settings",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun scanGoogleComponents() {
        deGoogleStatus.text = "Scanning…"
        Thread({
            val result = runCatching { DeGoogleManager(this).scan() }
            runOnUiThread {
                deGoogleStatus.text = result.fold(
                    onSuccess = { "${it.coreServices.size} core services · ${it.googleApps.size} Google apps" },
                    onFailure = { "Scan failed" }
                )
            }
        }, "metmc-degoogle-scan").start()
    }

    private fun updateState() {
        if (!::summary.isInitialized) return
        val mode = NativOSPreferences.operatingMode(this)
        desktopCheck.text = if (mode == NativOSPreferences.OperatingMode.DESKTOP_APP) "✓" else ""
        homeCheck.text = if (mode == NativOSPreferences.OperatingMode.HOME_LAUNCHER) "✓" else ""
        summary.text = when {
            mode == NativOSPreferences.OperatingMode.HOME_LAUNCHER && HomeRoleManager.isDefaultHome(this) ->
                "METMC OS v10 is your Home launcher"
            mode == NativOSPreferences.OperatingMode.HOME_LAUNCHER ->
                "METMC OS v10 selected · Android approval needed"
            else -> "Linux desktop with Android underneath"
        }
    }

    private fun selectableBackground() = android.util.TypedValue().let { value ->
        theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
        getDrawable(value.resourceId)
    }

    private fun dp(value: Int) = PremiumUi.dp(this, value)
}
