package com.phonerelay.phonebridge

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ViewerActivity : AppCompatActivity() {

    private lateinit var tvDeviceModel: TextView
    private lateinit var tvOnlineStatus: TextView
    private lateinit var tvBattery: TextView
    private lateinit var tvCharging: TextView
    private lateinit var tvNetwork: TextView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var rvEvents: RecyclerView

    private lateinit var btnFilterAll: Button
    private lateinit var btnFilterSms: Button
    private lateinit var btnFilterCalls: Button
    private lateinit var btnFilterNotif: Button

    private var allEvents = listOf<NativeEvent>()
    private var currentFilter = "ALL"
    private val handler = Handler(Looper.getMainLooper())

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}

    private val newEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            syncData()
        }
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            syncData()
            handler.postDelayed(this, 5000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_viewer)

        tvDeviceModel = findViewById(R.id.tvDeviceModel)
        tvOnlineStatus = findViewById(R.id.tvOnlineStatus)
        tvBattery = findViewById(R.id.tvBattery)
        tvCharging = findViewById(R.id.tvCharging)
        tvNetwork = findViewById(R.id.tvNetwork)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        rvEvents = findViewById(R.id.rvEvents)

        btnFilterAll = findViewById(R.id.btnFilterAll)
        btnFilterSms = findViewById(R.id.btnFilterSms)
        btnFilterCalls = findViewById(R.id.btnFilterCalls)
        btnFilterNotif = findViewById(R.id.btnFilterNotif)

        rvEvents.layoutManager = LinearLayoutManager(this)

        findViewById<Button>(R.id.btnViewerSettings).setOnClickListener {
            startActivity(Intent(this, ModeSelectionActivity::class.java))
            finish()
        }

        findViewById<Button>(R.id.btnCheckUpdate).setOnClickListener {
            AppUpdater.checkForUpdate(this, silentIfNone = false) { info ->
                AppUpdater.promptUpdateDialog(this, info)
            }
        }

        swipeRefresh.setOnRefreshListener {
            syncData()
        }

        btnFilterAll.setOnClickListener { setFilter("ALL") }
        btnFilterSms.setOnClickListener { setFilter("SMS") }
        btnFilterCalls.setOnClickListener { setFilter("CALL") }
        btnFilterNotif.setOnClickListener { setFilter("NOTIF") }

        // Request notification permission on Android 13+ so heads-up alerts appear on Xperia
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Start background receiver service so Xperia receives calls & OTPs even when app closed
        val serviceIntent = Intent(this, ViewerForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        syncData()
    }

    private fun setFilter(filter: String) {
        currentFilter = filter
        btnFilterAll.backgroundTintList = getColorStateList(if (filter == "ALL") R.color.primary_blue else R.color.card_dark)
        btnFilterSms.backgroundTintList = getColorStateList(if (filter == "SMS") R.color.primary_blue else R.color.card_dark)
        btnFilterCalls.backgroundTintList = getColorStateList(if (filter == "CALL") R.color.primary_blue else R.color.card_dark)
        btnFilterNotif.backgroundTintList = getColorStateList(if (filter == "NOTIF") R.color.primary_blue else R.color.card_dark)

        btnFilterAll.setTextColor(getColor(if (filter == "ALL") R.color.bg_dark else R.color.text_muted))
        btnFilterSms.setTextColor(getColor(if (filter == "SMS") R.color.bg_dark else R.color.text_muted))
        btnFilterCalls.setTextColor(getColor(if (filter == "CALL") R.color.bg_dark else R.color.text_muted))
        btnFilterNotif.setTextColor(getColor(if (filter == "NOTIF") R.color.bg_dark else R.color.text_muted))

        updateList()
    }

    private fun syncData() {
        FirebaseRelay.fetchTelemetry(this) { telemetry ->
            runOnUiThread {
                if (telemetry != null) {
                    tvDeviceModel.text = telemetry.deviceModel
                    if (telemetry.isOnline) {
                        tvOnlineStatus.text = "Online 🟢"
                        tvOnlineStatus.setTextColor(getColor(R.color.accent_green))
                    } else {
                        tvOnlineStatus.text = "Standby ⚪"
                        tvOnlineStatus.setTextColor(getColor(R.color.text_muted))
                    }

                    val b = telemetry.battery
                    tvBattery.text = if (b >= 0) "$b%" else "--%"
                    if (b in 0..19) {
                        tvBattery.setTextColor(getColor(R.color.accent_red))
                    } else if (b in 20..49) {
                        tvBattery.setTextColor(getColor(R.color.accent_amber))
                    } else {
                        tvBattery.setTextColor(getColor(R.color.accent_green))
                    }

                    tvCharging.text = if (telemetry.isCharging) "Charging ⚡" else "On Battery 🔋"
                    tvNetwork.text = telemetry.network
                }
            }
        }

        FirebaseRelay.fetchEvents(this) { events ->
            runOnUiThread {
                swipeRefresh.isRefreshing = false
                allEvents = events
                updateList()
            }
        }
    }

    private fun updateList() {
        val filtered = when (currentFilter) {
            "SMS" -> allEvents.filter { it.type == "SMS" }
            "CALL" -> allEvents.filter { it.type.startsWith("CALL") }
            "NOTIF" -> allEvents.filter { it.type == "NOTIFICATION" }
            else -> allEvents
        }

        rvEvents.adapter = EventAdapter(filtered) { otp ->
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("OTP", otp)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "OTP $otp copied to clipboard!", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(newEventReceiver, IntentFilter(ViewerForegroundService.ACTION_NEW_EVENT), RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(newEventReceiver, IntentFilter(ViewerForegroundService.ACTION_NEW_EVENT))
        }
        handler.post(pollRunnable)
        AppUpdater.checkForUpdate(this, silentIfNone = true) { info ->
            AppUpdater.promptUpdateDialog(this, info)
        }
    }

    override fun onPause() {
        try {
            unregisterReceiver(newEventReceiver)
        } catch (_: Exception) {}
        handler.removeCallbacks(pollRunnable)
        super.onPause()
    }

    class EventAdapter(
        private val events: List<NativeEvent>,
        private val onCopyOtp: (String) -> Unit
    ) : RecyclerView.Adapter<EventAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvBadge: TextView = view.findViewById(R.id.tvEventBadge)
            val tvTime: TextView = view.findViewById(R.id.tvEventTime)
            val tvTitle: TextView = view.findViewById(R.id.tvEventTitle)
            val tvBody: TextView = view.findViewById(R.id.tvEventBody)
            val layoutOtp: LinearLayout = view.findViewById(R.id.layoutOtp)
            val tvOtpText: TextView = view.findViewById(R.id.tvOtpText)
            val btnCopyOtp: Button = view.findViewById(R.id.btnCopyOtp)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_event, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val ev = events[position]
            val context = holder.itemView.context

            val isCall = ev.type.startsWith("CALL")
            val isMissed = ev.type == "CALL_MISSED"
            val isNotif = ev.type == "NOTIFICATION"
            val isBattery = ev.type == "BATTERY_ALERT"

            if (isCall) {
                holder.tvBadge.text = if (isMissed) "Missed Call" else "Incoming Ringing"
                holder.tvBadge.setTextColor(context.getColor(if (isMissed) R.color.accent_red else R.color.accent_green))
            } else if (isNotif) {
                holder.tvBadge.text = "App Alert"
                holder.tvBadge.setTextColor(context.getColor(R.color.accent_amber))
            } else if (isBattery) {
                holder.tvBadge.text = "Power Alert"
                holder.tvBadge.setTextColor(context.getColor(R.color.accent_amber))
            } else {
                holder.tvBadge.text = "SMS"
                holder.tvBadge.setTextColor(context.getColor(R.color.primary_blue))
            }

            val timeStr = if (ev.timestamp > 0) {
                SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(ev.timestamp))
            } else ""

            holder.tvTime.text = timeStr
            holder.tvTitle.text = ev.title
            holder.tvBody.text = ev.body

            if (ev.otp.isNotBlank()) {
                holder.layoutOtp.visibility = View.VISIBLE
                holder.tvOtpText.text = "🔑 OTP: ${ev.otp}"
                holder.btnCopyOtp.setOnClickListener { onCopyOtp(ev.otp) }
            } else {
                holder.layoutOtp.visibility = View.GONE
            }
        }

        override fun getItemCount(): Int = events.size
    }
}
