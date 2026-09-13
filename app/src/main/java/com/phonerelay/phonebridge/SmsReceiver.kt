package com.phonerelay.phonebridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        if (!BridgePreferences.isForwardSms(context)) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        val sender = messages[0].originatingAddress ?: "Unknown"
        val fullBody = buildString {
            for (msg in messages) {
                append(msg.messageBody)
            }
        }

        val contactName = getContactName(context, sender)
        val displayName = if (contactName.isNotBlank()) "$contactName ($sender)" else sender
        val timestamp = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date())

        val otpCode = extractOtp(fullBody) ?: ""

        FirebaseRelay.sendEvent(
            context = context,
            eventType = "SMS",
            title = "SMS: $displayName",
            body = fullBody,
            sender = displayName,
            otp = otpCode,
            extra = timestamp
        )
    }

    private fun extractOtp(text: String): String? {
        val lower = text.lowercase(Locale.getDefault())
        val isOtpMessage = lower.contains("otp") ||
                lower.contains("code") ||
                lower.contains("pin") ||
                lower.contains("verification") ||
                lower.contains("password") ||
                lower.contains("secret") ||
                lower.contains("bkash") ||
                lower.contains("nagad")

        val pattern = Pattern.compile("(?<!\\d)(\\d{4,8})(?!\\d)")
        val matcher = pattern.matcher(text)
        return if (matcher.find()) {
            matcher.group(1)
        } else if (isOtpMessage) {
            val fallbackPattern = Pattern.compile("\\b(\\d{4,8})\\b")
            val fallbackMatcher = fallbackPattern.matcher(text)
            if (fallbackMatcher.find()) fallbackMatcher.group(1) else null
        } else {
            null
        }
    }

    private fun getContactName(context: Context, phoneNumber: String): String {
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phoneNumber)
            )
            val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
            val cursor = context.contentResolver.query(uri, projection, null, null, null)
            var contactName = ""
            cursor?.use {
                if (it.moveToFirst()) {
                    contactName = it.getString(0) ?: ""
                }
            }
            contactName
        } catch (e: Exception) {
            ""
        }
    }
}
