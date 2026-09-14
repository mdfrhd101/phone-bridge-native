package com.phonerelay.phonebridge

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.telecom.TelecomManager
import androidx.core.app.NotificationCompat
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

        // SMS and incoming calls are already forwarded by SmsReceiver and the call monitor.
        // Ignore the default SMS/dialer apps here so a single SMS/call is not reported twice.
        if (isIgnoredSource(packageName)) return

        if (!BridgePreferences.isForwardNotifications(applicationContext)) return

        val notification = sbn.notification ?: return

        // Skip ongoing system notifications (e.g. music playing, background service progress)
        val isOngoing = (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0
        if (isOngoing) return

        val isGroupSummary = (notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0

        val extras = notification.extras ?: return
        val appName = getAppLabel(packageName)
        val timestamp = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())

        var title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
        val convoTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim() ?: ""
        var text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()
            ?: ""

        // 1. Deep extraction using AndroidX NotificationCompat.MessagingStyle (WhatsApp, Telegram, Google Messages)
        try {
            val messagingStyle = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
            if (messagingStyle != null) {
                val msgs = messagingStyle.messages
                if (msgs.isNotEmpty()) {
                    val lines = msgs.mapNotNull { msg ->
                        val senderName = msg.person?.name?.toString()
                            ?: msg.sender?.toString()
                            ?: ""
                        val msgText = msg.text?.toString()?.trim() ?: ""
                        if (msgText.isNotBlank()) {
                            if (senderName.isNotBlank() && senderName != title) {
                                "$senderName: $msgText"
                            } else {
                                msgText
                            }
                        } else null
                    }
                    if (lines.isNotEmpty()) {
                        text = lines.joinToString("\n")
                    }
                }
                if (messagingStyle.conversationTitle != null && messagingStyle.conversationTitle.toString().isNotBlank()) {
                    if (convoTitle.isBlank()) {
                        title = messagingStyle.conversationTitle.toString().trim()
                    }
                }
            }
        } catch (_: Exception) {}

        // 2. Fallback: Multi-line notifications (Notification.EXTRA_TEXT_LINES)
        val summaryRegex = Regex("""^\d+\s+(new\s+)?messages?(\s+from\s+\d+\s+chats?)?""", RegexOption.IGNORE_CASE)
        if (text.isBlank() || summaryRegex.matches(text)) {
            val textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            if (textLines != null && textLines.isNotEmpty()) {
                val linesJoined = textLines.mapNotNull { it?.toString()?.trim() }
                    .filter { it.isNotBlank() }
                    .joinToString("\n")
                if (linesJoined.isNotBlank()) {
                    text = linesJoined
                }
            }
        }

        // 3. Skip generic summary wrapper notifications like "1 messages from 2 chats" or "2 new messages"
        if (summaryRegex.matches(text) && isGroupSummary) {
            return
        }
        if (title.equals("WhatsApp", ignoreCase = true) && (summaryRegex.matches(text) || text.isBlank())) {
            return
        }

        if (title.isBlank() && text.isBlank()) return

        // Format clean, descriptive title
        val finalTitle = when {
            convoTitle.isNotBlank() && convoTitle != title && title.isNotBlank() -> "$appName • $convoTitle ($title)"
            convoTitle.isNotBlank() -> "$appName • $convoTitle"
            title.isNotBlank() -> "$appName: $title"
            else -> appName
        }

        FirebaseRelay.sendEvent(
            context = applicationContext,
            eventType = "NOTIFICATION",
            title = finalTitle,
            body = text,
            sender = if (title.isNotBlank()) title else convoTitle,
            extra = timestamp
        )
    }

    private fun isIgnoredSource(pkg: String): Boolean {
        return try {
            val ctx: Context = applicationContext
            val defaultSms = Telephony.Sms.getDefaultSmsPackage(ctx)
            if (defaultSms != null && pkg == defaultSms) return true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val tm = ctx.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                val defaultDialer = tm?.defaultDialerPackage
                if (defaultDialer != null && pkg == defaultDialer) return true
            }
            // Common stock SMS/phone packages as a safety net.
            pkg == "com.android.messaging" || pkg == "com.google.android.apps.messaging" ||
                pkg == "com.android.mms" || pkg == "com.android.dialer" ||
                pkg == "com.google.android.dialer" || pkg == "com.android.server.telecom"
        } catch (_: Exception) {
            false
        }
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
