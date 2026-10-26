package com.nativOS.bridge

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/** Forwards Android notifications after the user explicitly enables notification access. */
class BridgeNotificationListenerService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString().orEmpty()
        try {
            BridgeService.publishAndroidNotification(
                sbn.packageName, title, text, sbn.postTime, sbn.key
            )
        } catch (error: Exception) {
            Log.w("MT.BridgeNotifications", "Could not forward notification", error)
        }
    }
}
