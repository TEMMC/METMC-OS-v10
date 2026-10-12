package com.nativOS

import android.app.Application
import android.util.Log

/**
 * METMC OS version 10 application class.
 *
 * Initializes the bridge service infrastructure and chroot runtime
 * at application startup.
 */
class NativOSApp : Application() {

    companion object {
        const val TAG = "METMCOSv10"

        @Volatile
        lateinit var instance: NativOSApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "METMC OS version 10 application initialized")
    }
}
