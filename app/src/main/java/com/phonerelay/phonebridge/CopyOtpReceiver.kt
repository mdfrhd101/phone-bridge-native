package com.phonerelay.phonebridge

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast

class CopyOtpReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val otp = intent.getStringExtra("EXTRA_OTP") ?: return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("OTP", otp)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "OTP $otp copied to clipboard!", Toast.LENGTH_SHORT).show()
    }
}
