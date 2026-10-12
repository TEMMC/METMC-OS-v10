package com.nativOS.settings

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.nativOS.runtime.RootShell
import java.io.File

/**
 * A deliberately limited package manager for the Linux chroot.
 * All apt/dpkg commands are executed inside filesDir/rootfs, never on Android.
 */
class PackageManagerActivity : Activity() {
    private lateinit var output: TextView
    private lateinit var scroll: ScrollView
    private lateinit var refreshButton: MaterialButton
    private lateinit var checkButton: MaterialButton
    private lateinit var upgradeButton: MaterialButton
    private lateinit var packageInput: EditText
    private lateinit var installButton: MaterialButton
    private var busy = false
    private val rootShell by lazy { RootShell(this) }
    private val rootfs by lazy { File(filesDir, "rootfs") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = PremiumUi.background
        window.navigationBarColor = PremiumUi.background

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(28))
            setBackgroundColor(PremiumUi.background)
        }
        page.addView(PremiumUi.text(this, "Linux packages", 30f, bold = true))
        page.addView(PremiumUi.text(
            this,
            "Manage packages inside METMC OS's Linux root filesystem. Android system packages are not changed here.",
            14f, PremiumUi.muted
        ).apply { setPadding(0, dp(8), 0, dp(14)) })

        val warning = PremiumUi.card(this)
        warning.addView(PremiumUi.cardContent(this).apply {
            addView(PremiumUi.text(this@PackageManagerActivity, "Before updating", 16f, PremiumUi.warning, true))
            addView(PremiumUi.text(
                this@PackageManagerActivity,
                "Use a stable internet connection and keep the phone charged. Close the Linux desktop before upgrading packages. Package changes can affect the desktop and are not automatically reversible.",
                13f, PremiumUi.muted
            ).apply { setPadding(0, dp(6), 0, 0) })
        })
        page.addView(warning, PremiumUi.matchWidth())

        refreshButton = button("Refresh package lists", true) { runPackageCommand("Refresh package lists", "apt-get update") }
        page.addView(refreshButton, PremiumUi.matchWidth(dp(12)))
        checkButton = button("Check available updates") {
            runPackageCommand(
                "Check available updates",
                "apt-get update && apt list --upgradable 2>/dev/null"
            )
        }
        page.addView(checkButton, PremiumUi.matchWidth(dp(8)))
        upgradeButton = button("Install Linux updates") { confirmUpgrade() }
        page.addView(upgradeButton, PremiumUi.matchWidth(dp(8)))

        page.addView(PremiumUi.verticalSpace(this, 22))
        page.addView(PremiumUi.sectionLabel(this, "Install a package"))
        packageInput = EditText(this).apply {
            hint = "Package name, e.g. htop"
            setSingleLine(true)
            textSize = 16f
            setTextColor(PremiumUi.text)
            setHintTextColor(PremiumUi.muted)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(PremiumUi.surface)
                setStroke(dp(1), PremiumUi.border)
            }
        }
        page.addView(packageInput, PremiumUi.matchWidth())
        installButton = button("Install package", true) { confirmInstall() }
        page.addView(installButton, PremiumUi.matchWidth(dp(8)))

        page.addView(PremiumUi.verticalSpace(this, 22))
        page.addView(PremiumUi.sectionLabel(this, "Operation log"))
        output = PremiumUi.text(this, "Ready. Select an operation above.", 12f, PremiumUi.text).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(PremiumUi.surface)
            }
            textIsSelectable = true
        }
        scroll = ScrollView(this).apply {
            isFillViewport = false
            addView(output)
        }
        page.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(260)
        ).apply { topMargin = dp(8) })

        page.addView(PremiumUi.verticalSpace(this, 16))
        page.addView(button("Back to Settings") { finish() }, PremiumUi.matchWidth())
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(page)
        })
        appendLog("Root filesystem: ${rootfs.absolutePath}")
        if (!rootShell.hasRoot()) appendLog("Root access is unavailable. Grant METMC OS root access in Magisk/your root manager and reopen this screen.")
        if (!isRootfsReady()) appendLog("Linux root filesystem is not ready. Start/provision the Linux environment first.")
    }

    private fun button(label: String, primary: Boolean = false, action: () -> Unit): MaterialButton =
        PremiumUi.button(this, label, primary).apply {
            setOnClickListener { action() }
        }

    private fun confirmUpgrade() {
        AlertDialog.Builder(this)
            .setTitle("Upgrade Linux packages?")
            .setMessage(
                "This runs apt-get upgrade only inside METMC OS's Linux root filesystem. It does not update Android itself. Close the Linux desktop first; package upgrades can break or restart Linux applications. Continue?"
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Upgrade") { _, _ ->
                runPackageCommand(
                    "Upgrade Linux packages",
                    "dpkg --configure -a && apt-get upgrade -y --no-install-recommends"
                )
            }
            .show()
    }

    private fun confirmInstall() {
        val name = packageInput.text.toString().trim()
        if (!name.matches(Regex("[a-z0-9][a-z0-9+.-]{0,99}"))) {
            packageInput.error = "Enter one valid Debian package name"
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Install $name?")
            .setMessage("Install this package and its dependencies inside the Linux root filesystem?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Install") { _, _ ->
                runPackageCommand(
                    "Install package: $name",
                    "apt-get update && apt-get install -y --no-install-recommends -- $name"
                )
            }
            .show()
    }

    private fun isRootfsReady(): Boolean =
        File(rootfs, "bin/bash").exists() &&
            File(rootfs, "usr/bin/apt-get").exists() &&
            File(rootfs, "var/lib/dpkg/status").exists()

    private fun runPackageCommand(label: String, command: String) {
        if (busy) return
        if (!rootShell.hasRoot()) {
            Toast.makeText(this, "Root access required", Toast.LENGTH_LONG).show()
            appendLog("ERROR: root access is unavailable.")
            return
        }
        if (!isRootfsReady()) {
            Toast.makeText(this, "Linux root filesystem is not ready", Toast.LENGTH_LONG).show()
            appendLog("ERROR: missing apt/dpkg rootfs at ${rootfs.absolutePath}")
            return
        }
        setBusy(true)
        appendLog("\n$ $label")
        Thread({
            val result = runCatching {
                val env = "/usr/bin/env -i PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin HOME=/root TMPDIR=/tmp DEBIAN_FRONTEND=noninteractive"
                val fullCommand = "chroot ${shellQuote(rootfs.absolutePath)} $env /bin/bash -lc ${shellQuote(command)}"
                rootShell.exec(fullCommand) { chunk ->
                    runOnUiThread { appendLog(chunk) }
                }
            }
            runOnUiThread {
                result.fold(
                    onSuccess = { code ->
                        appendLog("\nFinished with exit code $code.")
                        Toast.makeText(
                            this,
                            if (code == 0) "Operation completed" else "Operation failed (exit $code)",
                            Toast.LENGTH_LONG
                        ).show()
                    },
                    onFailure = { error ->
                        appendLog("\nERROR: ${error.message ?: error.javaClass.simpleName}")
                        Toast.makeText(this, "Package operation failed", Toast.LENGTH_LONG).show()
                    }
                )
                setBusy(false)
            }
        }, "metmc-package-manager").start()
    }

    private fun setBusy(value: Boolean) {
        busy = value
        refreshButton.isEnabled = !value
        checkButton.isEnabled = !value
        upgradeButton.isEnabled = !value
        installButton.isEnabled = !value
        refreshButton.text = if (value) "Working…" else "Refresh package lists"
    }

    private fun appendLog(value: String) {
        if (!::output.isInitialized) return
        output.append(value)
        if (!value.endsWith("\n")) output.append("\n")
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    private fun dp(value: Int) = PremiumUi.dp(this, value)
}
