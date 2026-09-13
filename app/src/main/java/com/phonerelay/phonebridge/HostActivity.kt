package com.phonerelay.phonebridge

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.NotificationManagerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class HostActivity : AppCompatActivity() {

    private lateinit var tvStatusTitle: TextView
    private lateinit var switchService: SwitchCompat
    private lateinit var rvLogs: RecyclerView
    private lateinit var tvNotifAccessStatus: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshLogsAndStatus()
            handler.postDelayed(this, 4000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_host)

        tvStatusTitle = findViewById(R.id.tvStatusTitle)
        switchService = findViewById(R.id.switchService)
        rvLogs = findViewById(R.id.rvLogs)
        tvNotifAccessStatus = findViewById(R.id.tvNotifAccessStatus)

        val tvPairCode = findViewById<TextView>(R.id.tvPairCode)
        tvPairCode.text = "Pair Code: ${BridgePreferences.getPairCode(this)}"

        findViewById<Button>(R.id.btnSwitchRole).setOnClickListener {
            startActivity(Intent(this, ModeSelectionActivity::class.java))
            finish()
        }

        findViewById<Button>(R.id.btnCheckUpdate).setOnClickListener {
            AppUpdater.checkForUpdate(this, silentIfNone = false) { info ->
                AppUpdater.promptUpdateDialog(this, info)
            }
        }

        rvLogs.layoutManager = LinearLayoutManager(this)

        val isRunning = BridgeForegroundService.isServiceRunning()
        switchService.isChecked = isRunning
        updateStatusText(isRunning)

        switchService.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                val serviceIntent = Intent(this, BridgeForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                updateStatusText(true)
            } else {
                val serviceIntent = Intent(this, BridgeForegroundService::class.java)
                stopService(serviceIntent)
                updateStatusText(false)
            }
        }

        findViewById<Button>(R.id.btnNotifAccess).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        findViewById<Button>(R.id.btnBatteryOpt).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } else {
                    Toast.makeText(this, "Already exempt from battery optimization!", Toast.LENGTH_SHORT).show()
                }
            }
        }

        findViewById<Button>(R.id.btnTestSms).setOnClickListener {
            FirebaseRelay.sendEvent(
                this,
                "SMS",
                "SMS: City Bank",
                "Your OTP for card transaction is 482910. Do not share it.",
                sender = "City Bank",
                otp = "482910",
                extra = "Test Mode"
            )
            Toast.makeText(this, "Test SMS sent to Firebase! Check Xperia.", Toast.LENGTH_SHORT).show()
            refreshLogsAndStatus()
        }

        findViewById<Button>(R.id.btnTestCall).setOnClickListener {
            FirebaseRelay.sendEvent(
                this,
                "CALL_RINGING",
                "Incoming Call: Baba (01711xxxxxx)",
                "Phone is ringing at home...",
                sender = "Baba",
                extra = "Ringing"
            )
            Toast.makeText(this, "Test Call sent to Firebase! Check Xperia.", Toast.LENGTH_SHORT).show()
            refreshLogsAndStatus()
        }

        findViewById<Button>(R.id.btnTestNotif).setOnClickListener {
            FirebaseRelay.sendEvent(
                this,
                "NOTIFICATION",
                "bKash: Money Received",
                "You have received Tk 2,500.00 from 017xxxxxxxx. TrxID: 9X8...",
                sender = "bKash",
                extra = "bKash Alert"
            )
            Toast.makeText(this, "Test Notification sent to Firebase! Check Xperia.", Toast.LENGTH_SHORT).show()
            refreshLogsAndStatus()
        }
    }

    private fun updateStatusText(running: Boolean) {
        if (running) {
            tvStatusTitle.text = "RELAY SERVICE RUNNING"
            tvStatusTitle.setTextColor(getColor(R.color.accent_green))
        } else {
            tvStatusTitle.text = "SERVICE STOPPED"
            tvStatusTitle.setTextColor(getColor(R.color.accent_red))
        }
    }

    private fun refreshLogsAndStatus() {
        val enabledListeners = NotificationManagerCompat.getEnabledListenerPackages(this)
        val isNotifEnabled = enabledListeners.contains(packageName)
        if (isNotifEnabled) {
            tvNotifAccessStatus.text = "✓ All App Notifications (Gmail, bKash active)"
            tvNotifAccessStatus.setTextColor(getColor(R.color.text_muted))
        } else {
            tvNotifAccessStatus.text = "⚠️ Notification Access Disabled (Tap button below)"
            tvNotifAccessStatus.setTextColor(getColor(R.color.accent_amber))
        }

        val logs = BridgePreferences.getLogs(this)
        rvLogs.adapter = LogAdapter(logs)
    }

    override fun onResume() {
        super.onResume()
        handler.post(refreshRunnable)
        AppUpdater.checkForUpdate(this, silentIfNone = true) { info ->
            AppUpdater.promptUpdateDialog(this, info)
        }
    }

    override fun onPause() {
        handler.removeCallbacks(refreshRunnable)
        super.onPause()
    }

    class LogAdapter(private val logs: List<Map<String, String>>) :
        RecyclerView.Adapter<LogAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvTitle: TextView = view.findViewById(R.id.tvLogTitle)
            val tvTime: TextView = view.findViewById(R.id.tvLogTime)
            val tvDetail: TextView = view.findViewById(R.id.tvLogDetail)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_log, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val log = logs[position]
            holder.tvTitle.text = log["title"] ?: ""
            holder.tvTime.text = log["time"] ?: ""
            holder.tvDetail.text = log["detail"] ?: ""
        }

        override fun getItemCount(): Int = logs.size
    }
}
