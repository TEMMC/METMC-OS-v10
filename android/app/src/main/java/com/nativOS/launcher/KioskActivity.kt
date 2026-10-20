package com.nativOS.launcher

import android.Manifest
import android.app.Activity
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.nativOS.bridge.AndroidAppIntegration
import com.nativOS.bridge.BridgeService
import com.nativOS.runtime.ChrootManager
import com.nativOS.runtime.RootfsManager
import com.nativOS.storage.SharedFolderSync
import com.nativOS.settings.NativOSPreferences
import com.nativOS.settings.SettingsActivity
import com.nativOS.settings.SystemToolsActivity
import com.nativOS.x11.X11ServiceClient
import com.nativOS.x11.X11InputController
import com.nativOS.x11.X11ServerService
import com.termux.x11.MainActivity
import com.termux.x11.LorieView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.lang.ref.WeakReference

/**
 * Full-screen kiosk activity that replaces the Android home screen.
 *
 * Orchestrates the full NativOS boot sequence:
 *   1. Initialize X11 surface (LorieView)
 *   2. Download Linux rootfs (if first boot)
 *   3. Extract and configure rootfs
 *   4. Install Xfce desktop (if not installed)
 *   5. Start the Linux desktop session
 *
 * Escape hatch: press Volume Up 5 times rapidly to show Android.
 */
class KioskActivity : Activity() {

    companion object {
        private const val TAG = "NativOS.Kiosk"
        private const val ESCAPE_TAP_COUNT = 5
        private const val ESCAPE_WINDOW_MS = 3000L
        private const val REQUEST_NOTIFICATIONS = 1201
        @Volatile private var activeInstance: WeakReference<KioskActivity>? = null

        /**
         * Launch an Android app in a bounded task so Android's freeform-capable
         * window manager can present it as a floating app window above the Linux
         * desktop. Android may ignore launch bounds on devices without freeform
         * support; in that case the app opens using the device's normal mode.
         */
        fun launchAndroidApp(component: ComponentName): Boolean {
            val activity = activeInstance?.get() ?: return false
            if (activity.isFinishing || activity.isDestroyed) return false
            AndroidAppIntegration.markAndroidAppLaunched()
            activity.runOnUiThread {
                runCatching {
                    val metrics = activity.resources.displayMetrics
                    val width = metrics.widthPixels
                    val height = metrics.heightPixels
                    val windowWidth = (width * 0.88f).toInt().coerceAtLeast(320)
                    val windowHeight = (height * 0.76f).toInt().coerceAtLeast(480)
                    val left = ((width - windowWidth) / 2).coerceAtLeast(0)
                    val top = ((height - windowHeight) / 2).coerceAtLeast(0)
                    val bounds = Rect(left, top, left + windowWidth, top + windowHeight)
                    val intent = Intent.makeMainActivity(component).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    }
                    val options = ActivityOptions.makeBasic().setLaunchBounds(bounds)
                    activity.startActivity(intent, options.toBundle())
                }.onFailure {
                    Log.e(TAG, "Could not launch Android app $component", it)
                }
            }
            return true
        }
    }

    private lateinit var chrootManager: ChrootManager
    private lateinit var rootfsManager: RootfsManager
    private var x11ServiceClient: X11ServiceClient? = null
    private var x11InputController: X11InputController? = null
    private val volumeUpTimestamps = mutableListOf<Long>()
    private var clipboardManager: ClipboardManager? = null
    private var clipboardListenerRegistered = false
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        runCatching {
            val clip = clipboardManager?.primaryClip
            val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(this)?.toString().orEmpty() else ""
            BridgeService.publishClipboardText(text)
        }.onFailure { Log.d(TAG, "Clipboard sync unavailable: ${it.message}") }
    }

    // Error flag — set by lambda callbacks to halt the boot sequence
    @Volatile private var bootFailed = false
    @Volatile private var bootSequenceRunning = false
    @Volatile private var waitingForRoot = false

    // UI elements
    private var rootLayout: FrameLayout? = null
    private var overlayLayout: LinearLayout? = null
    private var statusText: TextView? = null
    private var progressBar: ProgressBar? = null
    private var detailText: TextView? = null
    private var keyboardButton: TextView? = null
    private var assistiveMenu: LinearLayout? = null
    private var navigationHandle: FrameLayout? = null
    private var desktopView: LorieView? = null
    private val desktopSafeInsets = Rect()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activeInstance = WeakReference(this)

        // CRITICAL: Set TMPDIR before ANY Termux/X11 classes are loaded.
        // If libXlorie is loaded before this, it will cache an empty TMPDIR and fail to create sockets.
        try {
            val tmpDir = java.io.File(filesDir, "tmp")
            tmpDir.mkdirs()
            val x11Dir = java.io.File(tmpDir, ".X11-unix")
            x11Dir.mkdirs()
            
            val staleSocket = java.io.File(x11Dir, "X0")
            if (staleSocket.exists() && !staleSocket.delete()) {
                Log.w(TAG, "Could not remove stale X11 socket " + staleSocket.absolutePath)
            } else if (!staleSocket.exists() || !staleSocket.exists()) {
                Log.i(TAG, "No stale X11 socket found or successfully deleted")
            }
            
            // CRITICAL: make it fully accessible to native library
            tmpDir.setExecutable(true, false)
            tmpDir.setReadable(true, false)
            tmpDir.setWritable(true, false)
            x11Dir.setExecutable(true, false)
            x11Dir.setReadable(true, false)
            x11Dir.setWritable(true, false)

            val osClass = Class.forName("android.system.Os")
            val setenvMethod = osClass.getMethod("setenv", String::class.java, String::class.java, Boolean::class.java)
            setenvMethod.invoke(null, "TMPDIR", tmpDir.absolutePath, true)
            setenvMethod.invoke(null, "XDG_RUNTIME_DIR", tmpDir.absolutePath, true)
            val prefixDir = java.io.File(filesDir, "usr")
            prefixDir.mkdirs()
            setenvMethod.invoke(null, "PREFIX", prefixDir.absolutePath, true)
            setenvMethod.invoke(null, "HOME", filesDir.absolutePath, true)
            
            // Create symlinks for X11 share and etc to point to rootfs natively
            val prefixShare = java.io.File(prefixDir, "share")
            prefixShare.mkdirs()
            val prefixX11 = java.io.File(prefixShare, "X11")
            if (!prefixX11.exists()) {
                val rootfsX11 = java.io.File(filesDir, "rootfs/usr/share/X11")
                try {
                    android.system.Os.symlink(rootfsX11.absolutePath, prefixX11.absolutePath)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to symlink X11 share: ${e.message}")
                }
            }
            
            val prefixEtc = java.io.File(prefixDir, "etc")
            if (!prefixEtc.exists()) {
                val rootfsEtc = java.io.File(filesDir, "rootfs/etc")
                try {
                    android.system.Os.symlink(rootfsEtc.absolutePath, prefixEtc.absolutePath)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to symlink etc: ${e.message}")
                }
            }

            // Create wrapper script for xkbcomp
            val binDir = java.io.File(prefixDir, "bin")
            binDir.mkdirs()
            val xkbcompWrapper = java.io.File(binDir, "xkbcomp")
            val filesPath = filesDir.absolutePath
            xkbcompWrapper.writeText("""
                #!/system/bin/sh
                log -t NativOS.Xkbcomp "Executing xkbcomp wrapper with args: ${'$'}@"
                ID=${'$'}RANDOM
                TMPDIR="$filesPath/tmp"
                ROOTFS="$filesPath/rootfs"
                SCRIPT="${'$'}TMPDIR/xkbcomp_${'$'}ID.sh"
                
                echo "#!/bin/sh" > ${'$'}SCRIPT
                echo -n "/usr/bin/xkbcomp " >> ${'$'}SCRIPT
                for arg in "${'$'}@"; do
                  escaped=`echo "${'$'}arg" | sed -e "s/'/'\\\\\\\\''/g"`
                  echo -n "'${'$'}escaped' " >> ${'$'}SCRIPT
                done
                echo "" >> ${'$'}SCRIPT
                chmod +x ${'$'}SCRIPT
                
                su -c "chroot ${'$'}ROOTFS ${'$'}SCRIPT"
                EXIT_CODE=${'$'}?
                log -t NativOS.Xkbcomp "xkbcomp wrapper exit code: ${'$'}EXIT_CODE"
                rm -f ${'$'}SCRIPT
                exit ${'$'}EXIT_CODE
            """.trimIndent())
            xkbcompWrapper.setExecutable(true, false)
            
            val xkbRoot = java.io.File(filesDir, "rootfs/usr/share/X11/xkb")
            setenvMethod.invoke(null, "XKB_CONFIG_ROOT", xkbRoot.absolutePath, true)
            Log.i(TAG, "Set XKB_CONFIG_ROOT unconditionally to " + xkbRoot.absolutePath)
            
            Log.i(TAG, "Early TMPDIR and PREFIX setup complete: ${tmpDir.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set early TMPDIR", e)
        }

        window.addFlags(
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
        applyScreenAwakePreference()

        rootLayout = FrameLayout(this).apply {
            // Safe-area margins intentionally expose this background around the
            // X11 surface instead of stretching Linux under a cutout or nav bar.
            setBackgroundColor(Color.BLACK)
            setOnApplyWindowInsetsListener { _, insets ->
                updateDesktopSafeInsets(insets)
                insets
            }
        }

        // Instead of embedding LorieView, we will launch the Termux:X11 companion app later.

        // Setup overlay
        overlayLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(220, 0, 0, 0))
            setPadding(60, 60, 60, 60)
        }
        val titleText = TextView(this).apply {
            text = "METMC OS V10"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            gravity = Gravity.CENTER
        }
        statusText = TextView(this).apply {
            text = "Initializing..."
            setTextColor(Color.parseColor("#AAAAAA"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            setPadding(0, 40, 0, 30)
        }
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progress = 0
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(40, 0, 40, 20) }
        }
        detailText = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#777777"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
        }
        overlayLayout!!.addView(titleText)
        overlayLayout!!.addView(TextView(this).apply {
            text = "Tinotenda Enock Mapfumo • Dr TEMMC"
            setTextColor(Color.parseColor("#8D9AA8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 12)
        })
        overlayLayout!!.addView(statusText)
        overlayLayout!!.addView(progressBar)
        overlayLayout!!.addView(detailText)
        rootLayout!!.addView(overlayLayout, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val density = resources.displayMetrics.density
        keyboardButton = TextView(this).apply {
            text = "⋮"
            contentDescription = "Open NativOS controls"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(220, 30, 30, 32))
                setStroke((1 * density).toInt(), Color.argb(80, 255, 255, 255))
            }
            elevation = 8f * density
            visibility = View.GONE
            setOnClickListener { toggleAssistiveMenu() }
        }
        makeDraggable(keyboardButton!!)
        rootLayout!!.addView(keyboardButton, FrameLayout.LayoutParams(
            (48 * density).toInt(),
            (48 * density).toInt(),
            Gravity.TOP or Gravity.END
        ).apply {
            topMargin = (52 * density).toInt()
            rightMargin = (12 * density).toInt()
        })

        assistiveMenu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (8 * density).toInt(),
                (8 * density).toInt(),
                (8 * density).toInt(),
                (8 * density).toInt()
            )
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 18f * density
                setColor(Color.argb(245, 24, 24, 26))
                setStroke((1 * density).toInt(), Color.argb(70, 255, 255, 255))
            }
            elevation = 12f * density
            visibility = View.GONE
            addAssistiveAction("⌂", "Home") { showLinuxHome() }
            addAssistiveAction("‹", "Back") { goLinuxBack() }
            addAssistiveAction("▦", "Recents") { showLinuxHome() }
            addAssistiveAction("□", "Maximize") { maximizeLinuxWindow() }
            addAssistiveAction("⌨", "Keyboard") {
                MainActivity.toggleKeyboardVisibility(this@KioskActivity)
            }
            addAssistiveAction("▤", "Tools") {
                startActivity(Intent(this@KioskActivity, SystemToolsActivity::class.java))
            }
            addAssistiveAction("⚙", "Settings") {
                startActivity(Intent(this@KioskActivity, SettingsActivity::class.java))
            }
        }
        rootLayout!!.addView(assistiveMenu, FrameLayout.LayoutParams(
            (184 * density).toInt(),
            FrameLayout.LayoutParams.WRAP_CONTENT
        ))

        // Xfce provides a Super-key launcher. Keep a small phone-friendly handle
        // to invoke it with the Super_L shortcut.
        navigationHandle = FrameLayout(this).apply {
            contentDescription = "Open Xfce app launcher"
            visibility = View.GONE
            isClickable = true
            isFocusable = true
            setOnClickListener { toggleXfceMenu() }
            addView(View(this@KioskActivity).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 3f * density
                    setColor(Color.argb(220, 255, 255, 255))
                }
            }, FrameLayout.LayoutParams(
                (48 * density).toInt(),
                (5 * density).toInt(),
                Gravity.CENTER
            ))
            val swipeThreshold = 12f * density
            var downY = 0f
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (downY - event.rawY >= swipeThreshold ||
                            kotlin.math.abs(downY - event.rawY) < swipeThreshold) {
                            view.performClick()
                        }
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> true
                    else -> true
                }
            }
        }
        rootLayout!!.addView(navigationHandle, FrameLayout.LayoutParams(
            (88 * density).toInt(),
            (28 * density).toInt(),
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ))

        setContentView(rootLayout)
        rootLayout?.requestApplyInsets()
        enterImmersiveMode()
        Log.i(TAG, "KioskActivity created")

        chrootManager = ChrootManager(this)
        rootfsManager = RootfsManager(this)

        requestNotificationPermission()
        startBridgeService()
        startBootSequence()
    }

    private fun LinearLayout.addAssistiveAction(icon: String, label: String, action: () -> Unit) {
        val density = resources.displayMetrics.density
        addView(TextView(this@KioskActivity).apply {
            text = "$icon   $label"
            contentDescription = label
            gravity = Gravity.CENTER_VERTICAL
            setPadding((14 * density).toInt(), 0, (14 * density).toInt(), 0)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 11f * density
                setColor(Color.TRANSPARENT)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                assistiveMenu?.visibility = View.GONE
                action()
            }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (46 * density).toInt()
        ))
    }

    private fun toggleAssistiveMenu() {
        val menu = assistiveMenu ?: return
        if (menu.visibility == View.VISIBLE) {
            menu.visibility = View.GONE
            return
        }
        menu.visibility = View.VISIBLE
        menu.bringToFront()
        keyboardButton?.bringToFront()
        menu.post { positionAssistiveMenu() }
    }

    private fun positionAssistiveMenu() {
        val parent = rootLayout ?: return
        val anchor = keyboardButton ?: return
        val menu = assistiveMenu ?: return
        val gap = 10f * resources.displayMetrics.density
        val menuWidth = menu.width.takeIf { it > 0 } ?: menu.measuredWidth
        val menuHeight = menu.height.takeIf { it > 0 } ?: menu.measuredHeight
        val proposedX = if (anchor.x + anchor.width / 2f > parent.width / 2f) {
            anchor.x - menuWidth - gap
        } else {
            anchor.x + anchor.width + gap
        }
        menu.x = proposedX.coerceIn(0f, (parent.width - menuWidth).coerceAtLeast(0).toFloat())
        menu.y = anchor.y.coerceIn(0f, (parent.height - menuHeight).coerceAtLeast(0).toFloat())
    }

    private fun makeDraggable(view: View) {
        val dragThreshold = 6f * resources.displayMetrics.density
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0f
        var startY = 0f
        var dragged = false

        view.setOnTouchListener { target, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (target === keyboardButton) assistiveMenu?.visibility = View.GONE
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = target.x
                    startY = target.y
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (kotlin.math.abs(dx) > dragThreshold || kotlin.math.abs(dy) > dragThreshold) {
                        dragged = true
                    }
                    val parent = target.parent as? View ?: return@setOnTouchListener true
                    target.x = (startX + dx).coerceIn(0f, (parent.width - target.width).coerceAtLeast(0).toFloat())
                    target.y = (startY + dy).coerceIn(0f, (parent.height - target.height).coerceAtLeast(0).toFloat())
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) target.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    /**
     * Keep the Linux display inside Android's physical safe area. System bars
     * may be hidden, but their stable geometry still protects gesture handles,
     * navigation buttons and landscape side bars. The display cutout protects
     * Xfce's centered clock from notches and hole-punch cameras.
     */
    private fun updateDesktopSafeInsets(insets: WindowInsets) {
        val cutout = insets.displayCutout
        val left: Int
        val right: Int
        val bottom: Int
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val navigation = insets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars())
            val waterfall = cutout?.waterfallInsets
            left = maxOf(cutout?.safeInsetLeft ?: 0, navigation.left, waterfall?.left ?: 0)
            right = maxOf(cutout?.safeInsetRight ?: 0, navigation.right, waterfall?.right ?: 0)
            bottom = maxOf(cutout?.safeInsetBottom ?: 0, navigation.bottom, waterfall?.bottom ?: 0)
        } else {
            @Suppress("DEPRECATION")
            left = maxOf(cutout?.safeInsetLeft ?: 0, insets.stableInsetLeft)
            @Suppress("DEPRECATION")
            right = maxOf(cutout?.safeInsetRight ?: 0, insets.stableInsetRight)
            @Suppress("DEPRECATION")
            bottom = maxOf(cutout?.safeInsetBottom ?: 0, insets.stableInsetBottom)
        }
        val top = cutout?.safeInsetTop ?: 0
        val changed = desktopSafeInsets.left != left || desktopSafeInsets.top != top ||
            desktopSafeInsets.right != right || desktopSafeInsets.bottom != bottom
        if (!changed) return

        desktopSafeInsets.set(left, top, right, bottom)
        Log.i(TAG, "Desktop safe area: left=$left top=$top right=$right bottom=$bottom")
        desktopView?.let(::applyDesktopSafeArea)
        navigationHandle?.let { handle ->
            val params = handle.layoutParams as FrameLayout.LayoutParams
            params.bottomMargin = bottom
            handle.layoutParams = params
        }
    }

    private fun applyDesktopSafeArea(view: LorieView) {
        val params = (view.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        params.width = FrameLayout.LayoutParams.MATCH_PARENT
        params.height = FrameLayout.LayoutParams.MATCH_PARENT
        params.setMargins(
            desktopSafeInsets.left,
            desktopSafeInsets.top,
            desktopSafeInsets.right,
            desktopSafeInsets.bottom
        )
        view.layoutParams = params
        view.requestLayout()
    }

    private fun toggleXfceMenu() {
        val view = desktopView ?: return
        view.sendKeyEvent(0, KeyEvent.KEYCODE_META_LEFT, true)
        view.postDelayed({
            if (view.isAttachedToWindow) {
                view.sendKeyEvent(0, KeyEvent.KEYCODE_META_LEFT, false)
            }
        }, 40)
    }

    private fun sendLinuxKey(keyCode: Int) {
        val view = desktopView ?: return
        view.sendKeyEvent(0, keyCode, true)
        view.postDelayed({
            if (view.isAttachedToWindow) view.sendKeyEvent(0, keyCode, false)
        }, 40)
    }

    private fun showLinuxHome() {
        // Xfce owns the Linux desktop and its window manager. The Super key
        // opens Xfce's launcher; avoid Phoc-specific X11 window manipulation.
        toggleXfceMenu()
        desktopView?.postDelayed({
            desktopView?.triggerCallback()
            MainActivity.getInstance().lorieView?.triggerCallback()
        }, 120)
    }

    private fun goLinuxBack() {
        Thread({
            val restored = chrootManager.restoreMaximizedX11Window(focusDesktop = false)
            runOnUiThread {
                desktopView?.postDelayed(
                    { sendLinuxKey(KeyEvent.KEYCODE_ESCAPE) },
                    if (restored) 180 else 0
                )
            }
        }, "LinuxBack").start()
    }

    /** Resize unmanaged X11 apps directly; they have no X11 WM to handle Alt+F10. */
    private fun maximizeLinuxWindow() {
        Thread({
            if (!chrootManager.maximizeActiveX11Window()) {
                Log.w(TAG, "No active X11 application could be maximized")
            }
        }, "MaximizeX11").start()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATIONS &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startForegroundService(Intent(this, BridgeService::class.java).apply {
                action = BridgeService.ACTION_REFRESH_NOTIFICATION
            })
        }
    }

    private fun runBootSequence() {
        try {
            // Step 1: Root check
            updateOverlay(0.0, "Checking root access...", "")
            if (!chrootManager.hasRoot()) {
                waitingForRoot = true
                updateOverlay(
                    -1.0,
                    "Root access required",
                    "Grant access in Magisk/KernelSU, then tap here to retry"
                )
                runOnUiThread {
                    overlayLayout?.apply {
                        isClickable = true
                        isFocusable = true
                        setOnClickListener { retryAfterRootGrant() }
                    }
                }
                return
            }
            waitingForRoot = false
            // Configure Android's own freeform window manager before Linux
            // desktop shortcuts launch Android activities. Firmware support is
            // device-dependent, so failure must not prevent the Linux desktop.
            val freeformReady = chrootManager.enableFreeformWindows()
            Log.i(TAG, "Android freeform windowing switches enabled=$freeformReady")
            SharedFolderSync.start(this)
            runOnUiThread {
                overlayLayout?.apply {
                    isClickable = false
                    setOnClickListener(null)
                }
            }
            updateOverlay(0.05, "Root access confirmed", "")

            // Step 2: Prepare the bundled rootfs only. Boot must never use the network.
            if (!rootfsManager.isRootfsReady()) {
                val bundled = rootfsManager.stageBundledRootfs { progress, status ->
                    updateOverlay(0.05 + progress * 0.45, status, "Bundled in this APK — no network required")
                }
                if (!bundled) {
                    updateOverlay(
                        -1.0,
                        "Offline root filesystem missing",
                        "This APK must include the prepared Linux rootfs. Rebuild it through GitHub Actions; boot will not download files."
                    )
                    return
                }

                updateOverlay(0.50, "Extracting bundled Linux filesystem...", "No network required")
                bootFailed = false
                rootfsManager.extractRootfs { progress, status ->
                    if (progress < 0) {
                        bootFailed = true
                        updateOverlay(-1.0, "Rootfs extraction failed", status)
                    } else {
                        updateOverlay(0.50 + progress * 0.20, status, "")
                    }
                }
                if (bootFailed) return
            } else {
                updateOverlay(0.70, "Local Linux filesystem found", "Offline startup")
            }

            if (!rootfsManager.isRootfsReady()) {
                updateOverlay(-1.0, "ERROR: Rootfs setup failed", "The Linux filesystem could not be prepared")
                return
            }

            // Step 3: Mount chroot
            updateOverlay(0.72, "Mounting Linux environment...", "")
            chrootManager.ensureMounts()
            chrootManager.bindX11Socket()
            AndroidAppIntegration.sync(this)

            // Step 4: Validate preinstalled desktop components. Never run apt or
            // configure online repositories during startup; the bundled rootfs must
            // already contain the complete desktop and essential tools.
            updateOverlay(0.74, "Checking offline desktop...", "")
            if (!chrootManager.isXfceInstalled()) {
                updateOverlay(
                    -1.0,
                    "Offline desktop components missing",
                    "The bundled Linux rootfs is incomplete. Rebuild the APK on GitHub with the prepared desktop; boot will not install packages."
                )
                return
            }

            updateOverlay(0.95, "Validating local desktop components...", "No downloads or updates")
            if (!rootfsManager.ensureXfceRuntime(chrootManager)) {
                updateOverlay(
                    -1.0,
                    "Offline desktop components missing",
                    "Xfce is incomplete in the bundled rootfs. Rebuild the APK with all desktop packages included."
                )
                return
            }

            // Keep startup limited to local configuration and launch operations.
            updateOverlay(0.95, "Preparing touch-friendly desktop...", "Offline startup")
            rootfsManager.configureXfceTouchLayout(chrootManager)

            // Step 5: Attach the display surface to the bundled X11 service process.
            updateOverlay(0.95, "Getting GUI ready...", "Preparing your desktop")
            
            // Android Views and the Termux Activity shim must be created on the UI thread.
            lateinit var lorieView: LorieView
            var viewCreationError: Throwable? = null
            val viewCreated = CountDownLatch(1)
            val surfaceReady = CountDownLatch(1)
            val surfaceReadyCallback = object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    surfaceReady.countDown()
                }

                override fun surfaceChanged(
                    holder: SurfaceHolder,
                    format: Int,
                    width: Int,
                    height: Int
                ) {
                    surfaceReady.countDown()
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
            }

            runOnUiThread {
                try {
                    val x11Activity = MainActivity.getInstance()
                    x11Activity.initLorieView(this@KioskActivity)
                    lorieView = x11Activity.lorieView
                    lorieView.holder.addCallback(surfaceReadyCallback)
                    (lorieView.parent as? ViewGroup)?.removeView(lorieView)
                    lorieView.layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    desktopView = lorieView
                    applyDesktopSafeArea(lorieView)
                    lorieView.setZOrderOnTop(false)
                    rootLayout?.addView(lorieView, 0)
                } catch (error: Throwable) {
                    viewCreationError = error
                } finally {
                    viewCreated.countDown()
                }
            }

            if (!viewCreated.await(10, TimeUnit.SECONDS)) {
                throw IllegalStateException("Timed out creating the X11 display surface")
            }
            viewCreationError?.let { throw it }

            if (!surfaceReady.await(10, TimeUnit.SECONDS)) {
                lorieView.holder.removeCallback(surfaceReadyCallback)
                throw IllegalStateException("Timed out waiting for the X11 display surface")
            }
            lorieView.holder.removeCallback(surfaceReadyCallback)
            
            val rendererAttached = CountDownLatch(1)
            var rendererError: Throwable? = null
            x11ServiceClient = X11ServiceClient(
                context = this,
                onConnected = { connectionFd, logcatFd ->
                    try {
                        lorieView.connect(connectionFd.detachFd())
                        logcatFd?.let { lorieView.startLogcat(it.detachFd()) }
                        x11InputController = X11InputController(lorieView)
                        lorieView.requestFocus()
                        Log.i(TAG, "LorieView attached to the bundled X11 service")
                        rendererAttached.countDown()
                    } catch (error: Throwable) {
                        rendererError = error
                        connectionFd.close()
                        logcatFd?.close()
                        rendererAttached.countDown()
                    }
                },
                onError = { message, error ->
                    rendererError = IllegalStateException(message, error)
                    rendererAttached.countDown()
                }
            ).also { it.connect() }

            if (!rendererAttached.await(10, TimeUnit.SECONDS)) {
                throw rendererError
                    ?: IllegalStateException("Display service connection timed out")
            }
            rendererError?.let { throw it }

            // Binder delivers the renderer file descriptor before the native
            // render thread has necessarily consumed it. Cold boot as the HOME
            // launcher makes that race visible, so wait briefly for native
            // readiness instead of treating a successful attachment as failed.
            val rendererReadyDeadline = SystemClock.elapsedRealtime() + 5_000L
            while (!lorieView.connected() &&
                SystemClock.elapsedRealtime() < rendererReadyDeadline) {
                Thread.sleep(50)
            }
            if (!lorieView.connected()) {
                throw IllegalStateException("Display renderer did not become ready")
            }

            Log.i(TAG, "Starting test session...")

            // Match the Xfce session to the current Android X11 surface dimensions.
            val screenWidth = lorieView.width
            val screenHeight = lorieView.height
            Log.i(TAG, "Detected screen: ${screenWidth}x${screenHeight}")

            chrootManager.startXfceSession(screenWidth, screenHeight)
            updateOverlay(0.98, "Starting Linux desktop...", "Waiting for Xfce")
            if (!chrootManager.awaitXfceReady()) {
                throw IllegalStateException("Xfce did not become ready")
            }
            runOnUiThread {
                overlayLayout?.visibility = View.GONE
                keyboardButton?.visibility = View.VISIBLE
                keyboardButton?.bringToFront()
                // Xfce has its own panel; keep the extra Android navigation handle hidden.
                navigationHandle?.visibility = View.GONE
            }

        } catch (e: Exception) {
            Log.e(TAG, "Boot sequence failed", e)
            updateOverlay(-1.0, "Boot failed: ${e.message}", "Press Vol Up x5 to escape to Android")
        }
    }

    private fun updateOverlay(progress: Double, status: String, detail: String) {
        Log.i(TAG, "BOOT: [${"%.0f".format(progress * 100)}%] $status ${if (detail.isNotEmpty()) "— $detail" else ""}")
        runOnUiThread {
            if (progress < 0) {
                statusText?.text = status
                statusText?.setTextColor(Color.parseColor("#FF5555"))
                detailText?.text = detail
                progressBar?.progress = 0
            } else {
                statusText?.text = status
                statusText?.setTextColor(Color.parseColor("#AAAAAA"))
                detailText?.text = detail
                progressBar?.progress = (progress * 1000).toInt()
            }
        }
    }

    @Synchronized
    private fun startBootSequence() {
        if (bootSequenceRunning || desktopView != null) return
        bootSequenceRunning = true
        Thread({
            try {
                runBootSequence()
            } finally {
                bootSequenceRunning = false
            }
        }, "nativOS-boot").start()
    }

    private fun retryAfterRootGrant() {
        waitingForRoot = false
        updateOverlay(0.0, "Checking root access...", "")
        startBootSequence()
    }

    override fun onResume() {
        super.onResume()
        enterImmersiveMode()
        applyScreenAwakePreference()
        registerClipboardSync()
        AndroidAppIntegration.sync(this)
        if (waitingForRoot) retryAfterRootGrant()

        val returningFromAndroid = AndroidAppIntegration.consumeAndroidAppReturn()

        // A recreated Android Surface may use a multi-buffer queue. Repaint a
        // few frames so every buffer contains the desktop instead of stale
        // black/transparent data from before the Android app switch.
        listOf(50L, 150L, 300L).forEach { delay ->
            rootLayout?.postDelayed({
                MainActivity.getInstance().lorieView?.triggerCallback()
            }, delay)
        }

        // Launching an Android shortcut closes Xfce's overview before Android
        // takes focus. Returning would therefore show Xfce's valid but empty
        // black home layer, which looks like a frozen renderer. Reopen the
        // overview after the recreated Surface has received its first frames.
        if (returningFromAndroid) {
            rootLayout?.postDelayed({
                if (hasWindowFocus() && desktopView?.isAttachedToWindow == true) {
                    toggleXfceMenu()
                }
            }, 400)
        }
    }

    private fun registerClipboardSync() {
        if (clipboardListenerRegistered) return
        clipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        runCatching {
            clipboardManager?.addPrimaryClipChangedListener(clipboardListener)
            clipboardListenerRegistered = true
        }.onFailure { Log.w(TAG, "Could not register clipboard sync listener", it) }
    }

    override fun onPause() {
        if (clipboardListenerRegistered) {
            runCatching { clipboardManager?.removePrimaryClipChangedListener(clipboardListener) }
            clipboardListenerRegistered = false
        }
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        enterImmersiveMode()

        // The activity is retained across rotation so the X11 connection and
        // Xfce session stay alive. Recalculate Android's cutout/navigation
        // safe area, then notify X11 after SurfaceView has its new dimensions.
        assistiveMenu?.visibility = View.GONE
        rootLayout?.requestApplyInsets()
        rootLayout?.post {
            val parent = rootLayout ?: return@post
            desktopView?.let { view ->
                applyDesktopSafeArea(view)
                view.post {
                    view.triggerCallback()
                    view.postDelayed({
                        val width = view.width
                        val height = view.height
                        // The embedded X11 server follows the resized SurfaceView via
                        // triggerCallback() above; ChrootManager has no separate resize API.
                    }, 150)
                }
            }
            keyboardButton?.let { button ->
                button.x = button.x.coerceIn(
                    0f,
                    (parent.width - button.width).coerceAtLeast(0).toFloat()
                )
                button.y = button.y.coerceIn(
                    0f,
                    (parent.height - button.height).coerceAtLeast(0).toFloat()
                )
            }
        }
        Log.i(
            TAG,
            "Configuration changed: orientation=${newConfig.orientation}, " +
                "surface=${rootLayout?.width}x${rootLayout?.height}"
        )
    }

    /**
     * Keep the kiosk activity alive after boot. Finishing this Activity on Back
     * recreates the boot overlay and repeats the mount sequence, which makes
     * the user land on the mounting screen instead of the Linux home screen.
     */
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        val view = desktopView
        if (view != null && view.isAttachedToWindow && view.connected()) {
            showLinuxHome()
            return
        }
        // During boot, never finish the activity: doing so would restart the
        // boot/mount screen when the HOME launcher recreates it.
        if (bootSequenceRunning || waitingForRoot) return
        // If the desktop has not started yet, keep the activity alive and let
        // the existing boot sequence report/recover from its current state.
        if (view == null) return
        super.onBackPressed()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            val now = SystemClock.elapsedRealtime()
            volumeUpTimestamps.add(now)
            volumeUpTimestamps.removeAll { now - it > ESCAPE_WINDOW_MS }
            if (volumeUpTimestamps.size >= ESCAPE_TAP_COUNT) {
                volumeUpTimestamps.clear()
                escapeToAndroid()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun applyScreenAwakePreference() {
        if (NativOSPreferences.keepScreenOn(this)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun enterImmersiveMode() {
        if (!NativOSPreferences.hideSystemBars(this)) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                window.insetsController?.show(
                    WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars()
                )
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            }
            return
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
        }
    }

    private fun startBridgeService() {
        Log.i(TAG, "Starting bridge service...")
        val intent = Intent(this, BridgeService::class.java)
        startForegroundService(intent)
    }

    private fun escapeToAndroid() {
        Log.i(TAG, "ESCAPE: Returning to Android temporarily")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.insetsController?.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
        }
        moveTaskToBack(true)
    }

    override fun onDestroy() {
        if (activeInstance?.get() === this) activeInstance = null
        x11ServiceClient?.disconnect()
        x11ServiceClient = null
        x11InputController = null
        navigationHandle = null
        assistiveMenu = null
        desktopView = null
        stopService(Intent(this, X11ServerService::class.java))
        if (::chrootManager.isInitialized) {
            Thread({ chrootManager.stopSession() }, "nativOS-stop").start()
        }
        super.onDestroy()
        Log.i(TAG, "KioskActivity destroyed; stopping display session")
    }
}
