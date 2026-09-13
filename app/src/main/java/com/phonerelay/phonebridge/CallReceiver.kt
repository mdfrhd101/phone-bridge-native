package com.phonerelay.phonebridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.TelephonyManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CallReceiver : BroadcastReceiver() {

    companion object {
        private var isIncoming = false
        private var ringingNumber: String? = null
        private var ringStartTime: Long = 0
        private var wasAnswered = false
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        if (!BridgePreferences.isForwardCalls(context)) return

        val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
        val timestamp = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date())

        when (stateStr) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                isIncoming = true
                wasAnswered = false
                ringStartTime = System.currentTimeMillis()
                ringingNumber = incomingNumber ?: ringingNumber

                val number = ringingNumber ?: "Unknown"
                val contactName = getContactName(context, number)
                val displayName = if (contactName.isNotBlank()) "$contactName ($number)" else number

                FirebaseRelay.sendEvent(
                    context = context,
                    eventType = "CALL_RINGING",
                    title = "Incoming Call: $displayName",
                    body = "Phone is currently ringing at home ($number)",
                    sender = displayName,
                    extra = timestamp
                )
            }

            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                if (isIncoming) {
                    wasAnswered = true
                }
            }

            TelephonyManager.EXTRA_STATE_IDLE -> {
                if (isIncoming) {
                    if (!wasAnswered) {
                        val durationSeconds = if (ringStartTime > 0) {
                            ((System.currentTimeMillis() - ringStartTime) / 1000).coerceAtLeast(1)
                        } else 0

                        val number = ringingNumber ?: "Unknown"
                        val contactName = getContactName(context, number)
                        val displayName = if (contactName.isNotBlank()) "$contactName ($number)" else number

                        FirebaseRelay.sendEvent(
                            context = context,
                            eventType = "CALL_MISSED",
                            title = "Missed Call: $displayName",
                            body = "Rang for ${durationSeconds}s without being answered.",
                            sender = displayName,
                            extra = "${durationSeconds}s"
                        )
                    }

                    isIncoming = false
                    wasAnswered = false
                    ringingNumber = null
                    ringStartTime = 0
                }
            }
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
