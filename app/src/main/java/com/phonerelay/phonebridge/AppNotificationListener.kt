package com.phonerelay.phonebridge

import android.app.Notification
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AppNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                requestRebind(ComponentName(this, AppNotificationListener::class.java))
            } catch (_: Exception) {}
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        val packageName = sbn.packageName ?: return
        if (packageName == applicationContext.packageName) return

        if (!BridgePreferences.isForwardNotifications(applicationContext)) return

        val notification = sbn.notification ?: return

        val isOngoing = (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0
        if (isOngoing) return

        val extras = notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: ""

        if (title.isBlank() && text.isBlank()) return

        val appName = getAppLabel(packageName)
        val timestamp = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())

        FirebaseRelay.sendEvent(
            context = applicationContext,
            eventType = "NOTIFICATION",
            title = "$appName: $title",
            body = text,
            sender = appName,
            extra = timestamp
        )
    }

    private fun getAppLabel(packageName: String): String {
        return try {
            val pm = applicationContext.packageManager
            val info = pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            pm.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            packageName.substringAfterLast('.')
        }
    }
}
