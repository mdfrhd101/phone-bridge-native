package com.phonerelay.phonebridge

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class HostActivity : AppCompatActivity() {

    private lateinit var tvStatusTitle: TextView
    private lateinit var switchService: SwitchCompat
    private lateinit var rvLogs: RecyclerView
    private lateinit var tvSmsStatus: TextView
    private lateinit var tvCallStatus: TextView
    private lateinit var tvNotifAccessStatus: TextView
    private lateinit var tvBatteryOptStatus: TextView
    private lateinit var btnGrantPerms: Button

    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshLogsAndStatus()
            handler.postDelayed(this, 4000)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshLogsAndStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_host)

        tvStatusTitle = findViewById(R.id.tvStatusTitle)
        switchService = findViewById(R.id.switchService)
        rvLogs = findViewById(R.id.rvLogs)
        tvSmsStatus = findViewById(R.id.tvSmsStatus)
        tvCallStatus = findViewById(R.id.tvCallStatus)
        tvNotifAccessStatus = findViewById(R.id.tvNotifAccessStatus)
        tvBatteryOptStatus = findViewById(R.id.tvBatteryOptStatus)
        btnGrantPerms = findViewById(R.id.btnGrantPerms)

        val tvPairCode = findViewById<TextView>(R.id.tvPairCode)
        tvPairCode.text = "Pair Code: ${BridgePreferences.getPairCode(this)}"
        findViewById<TextView>(R.id.tvHeaderTitle)?.text = "Realme Host • v${BuildConfig.VERSION_NAME}"

        findViewById<Button>(R.id.btnSwitchRole).setOnClickListener {
            startActivity(Intent(this, ModeSelectionActivity::class.java))
            finish()
        }

        findViewById<Button>(R.id.btnCheckUpdate).setOnClickListener {
            AppUpdater.checkForUpdate(this, silentIfNone = false) { info ->
                AppUpdater.promptUpdateDialog(this, info)
            }
        }

        btnGrantPerms.setOnClickListener {
            requestRequiredPermissions()
        }

        rvLogs.layoutManager = LinearLayoutManager(this)

        val isRunning = BridgeForegroundService.isServiceRunning()
        if (!isRunning) {
            val serviceIntent = Intent(this, BridgeForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            switchService.isChecked = true
            updateStatusText(true)
        } else {
            switchService.isChecked = true
            updateStatusText(true)
        }
        BridgeForegroundService.sendInstantTelemetry(this)
        rebindNotificationListener()

        switchService.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                requestRequiredPermissions()
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
            Toast.makeText(this, "Test SMS sent via Wi-Fi & Cloud! Check Xperia.", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, "Test Call sent via Wi-Fi & Cloud! Check Xperia.", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, "Test Notification sent via Wi-Fi & Cloud! Check Xperia.", Toast.LENGTH_SHORT).show()
            refreshLogsAndStatus()
        }

        // Auto request permissions if missing when opening Realme Host screen
        requestRequiredPermissions()
    }

    private fun getRequiredPermissions(): List<String> {
        val perms = mutableListOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_CONTACTS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return perms
    }

    private fun requestRequiredPermissions() {
        val missing = getRequiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun updateStatusText(running: Boolean) {
        if (running) {
            tvStatusTitle.text = "RELAY SERVICE RUNNING 🟢"
            tvStatusTitle.setTextColor(getColor(R.color.accent_green))
        } else {
            tvStatusTitle.text = "SERVICE STOPPED 🔴"
            tvStatusTitle.setTextColor(getColor(R.color.accent_red))
        }
    }

    private fun refreshLogsAndStatus() {
        // 1. Call Permissions Check
        val hasCallState = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        val hasCallLog = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED
        if (hasCallState && hasCallLog) {
            tvCallStatus.text = "✓ Call & Missed Call Monitor (Active)"
            tvCallStatus.setTextColor(getColor(R.color.text_muted))
        } else {
            tvCallStatus.text = "⚠️ Call Permissions Missing (Tap Grant button below)"
            tvCallStatus.setTextColor(getColor(R.color.accent_amber))
        }

        // 2. SMS Permissions Check
        val hasSms = ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
        if (hasSms) {
            tvSmsStatus.text = "✓ SMS & OTP Forwarder (Active)"
            tvSmsStatus.setTextColor(getColor(R.color.text_muted))
        } else {
            tvSmsStatus.text = "⚠️ SMS Permissions Missing (Tap Grant button below)"
            tvSmsStatus.setTextColor(getColor(R.color.accent_amber))
        }

        // 3. Notification Access Check
        val enabledListeners = NotificationManagerCompat.getEnabledListenerPackages(this)
        val isNotifEnabled = enabledListeners.contains(packageName)
        if (isNotifEnabled) {
            tvNotifAccessStatus.text = "✓ All App Notifications (Gmail, bKash active)"
            tvNotifAccessStatus.setTextColor(getColor(R.color.text_muted))
        } else {
            tvNotifAccessStatus.text = "⚠️ Notification Access Disabled (Tap button below)"
            tvNotifAccessStatus.setTextColor(getColor(R.color.accent_amber))
        }

        // 4. Battery Optimization Check
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isIgnoringBatteryOptimizations(packageName)) {
                tvBatteryOptStatus.text = "✓ Realme Battery Exemption (Active)"
                tvBatteryOptStatus.setTextColor(getColor(R.color.text_muted))
            } else {
                tvBatteryOptStatus.text = "⚠️ Battery Optimization Enabled (Tap Exemption below)"
                tvBatteryOptStatus.setTextColor(getColor(R.color.accent_amber))
            }
        }

        // 5. Update Grant Button Text
        val missing = getRequiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            btnGrantPerms.text = "✓ Call & SMS Permissions Allowed"
            btnGrantPerms.backgroundTintList = getColorStateList(R.color.accent_green)
            btnGrantPerms.setTextColor(getColor(R.color.bg_dark))
        } else {
            btnGrantPerms.text = "⚠️ Grant Call & SMS Permissions (${missing.size} Missing)"
            btnGrantPerms.backgroundTintList = getColorStateList(R.color.accent_amber)
            btnGrantPerms.setTextColor(getColor(R.color.bg_dark))
        }

        val logs = BridgePreferences.getLogs(this)
        rvLogs.adapter = LogAdapter(logs)
    }

    private fun rebindNotificationListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                val componentName = android.content.ComponentName(this, AppNotificationListener::class.java)
                val pm = packageManager
                pm.setComponentEnabledSetting(componentName, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED, android.content.pm.PackageManager.DONT_KILL_APP)
                pm.setComponentEnabledSetting(componentName, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED, android.content.pm.PackageManager.DONT_KILL_APP)
            } catch (_: Exception) {}
        }
    }

    override fun onResume() {
        super.onResume()
        rebindNotificationListener()
        BridgeForegroundService.sendInstantTelemetry(this)
        refreshLogsAndStatus()
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
