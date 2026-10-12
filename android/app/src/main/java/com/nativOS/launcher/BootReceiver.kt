package com.nativOS.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.nativOS.settings.HomeRoleManager
import com.nativOS.launcher.KioskActivity
import com.nativOS.settings.NativOSPreferences

/**
 * Observes boot without forcing NativOS over the user's selected Android Home app.
 *
 * Handles:
 * - BOOT_COMPLETED (normal boot)
 * - LOCKED_BOOT_COMPLETED (direct boot aware)
 * - QUICKBOOT_POWERON (HTC, Xiaomi quick boot)
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "NativOS.Boot"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.i(TAG, "Boot event received: $action")

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON" -> {
                val mode = NativOSPreferences.operatingMode(context)
                val isHome = HomeRoleManager.isDefaultHome(context)
                Log.i(TAG, "Boot mode=$mode, defaultHome=$isHome")

                // Android resolves the selected HOME activity at boot. This is a
                // guarded fallback after normal BOOT_COMPLETED only; never force it
                // in desktop-app mode or before credential-protected files unlock.
                if (action == Intent.ACTION_BOOT_COMPLETED && mode == NativOSPreferences.OperatingMode.HOME_LAUNCHER && isHome) {
                    runCatching {
                        context.startActivity(Intent(context, KioskActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        })
                    }.onFailure { Log.w(TAG, "Home launch fallback failed; Android should resolve HOME normally", it) }
                }
            }
        }
    }
}
