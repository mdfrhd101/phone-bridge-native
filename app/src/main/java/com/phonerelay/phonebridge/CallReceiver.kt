package com.phonerelay.phonebridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CallReceiver : BroadcastReceiver() {

    companion object {
        private var isIncoming = false
        private var ringingNumber: String? = null
        private var ringStartTime: Long = 0
        private var wasAnswered = false
        private var lastRingEventTime: Long = 0

        fun resolveLatestCaller(context: Context, rawNumber: String?): Pair<String, String> {
            var number = rawNumber
            var contactName = ""

            // If number is missing (common on Android 10+ without explicit runtime permissions), try CallLog
            if (number.isNullOrBlank() || number == "Unknown") {
                try {
                    if (ContextCompat.checkSelfPermission(
                            context,
                            android.Manifest.permission.READ_CALL_LOG
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        val cursor = context.contentResolver.query(
                            CallLog.Calls.CONTENT_URI,
                            arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME),
                            null,
                            null,
                            "${CallLog.Calls.DATE} DESC"
                        )
                        cursor?.use {
                            if (it.moveToFirst()) {
                                val num = it.getString(0)
                                val name = it.getString(1)
                                if (!num.isNullOrBlank()) number = num
                                if (!name.isNullOrBlank()) contactName = name
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            // Look up Contacts if name not already found
            if (contactName.isBlank() && !number.isNullOrBlank() && number != "Unknown") {
                contactName = getContactName(context, number!!)
            }

            val finalNumber = if (!number.isNullOrBlank()) number!! else "Incoming Caller"
            val finalDisplayName = if (contactName.isNotBlank()) "$contactName ($finalNumber)" else finalNumber
            return Pair(finalNumber, finalDisplayName)
        }

        fun getContactName(context: Context, phoneNumber: String): String {
            return try {
                if (ContextCompat.checkSelfPermission(
                        context,
                        android.Manifest.permission.READ_CONTACTS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    return ""
                }
                val uri = Uri.withAppendedPath(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                    Uri.encode(phoneNumber)
                )
                val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
                val cursor = context.contentResolver.query(uri, projection, null, null, null)
                var name = ""
                cursor?.use {
                    if (it.moveToFirst()) {
                        name = it.getString(0) ?: ""
                    }
                }
                name
            } catch (_: Exception) {
                ""
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        if (!BridgePreferences.isForwardCalls(context)) return

        val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
        val timestamp = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date())

        when (stateStr) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                val now = System.currentTimeMillis()
                if (now - lastRingEventTime < 3000) return // De-bounce duplicate broadcasts
                lastRingEventTime = now

                isIncoming = true
                wasAnswered = false
                ringStartTime = now
                ringingNumber = incomingNumber ?: ringingNumber

                val (number, displayName) = resolveLatestCaller(context, ringingNumber)

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

                        val (number, displayName) = resolveLatestCaller(context, ringingNumber)

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
}
