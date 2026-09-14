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

        val otpCode = EventCodec.extractOtp(fullBody) ?: ""

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
