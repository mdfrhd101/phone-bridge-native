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
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
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

    private lateinit var cbSelectAll: CheckBox
    private lateinit var tvSelectionCount: TextView
    private lateinit var btnDeleteSelected: Button
    private lateinit var btnClearAll: Button

    private var allEvents = listOf<NativeEvent>()
    private var currentFilter = "ALL"
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var eventAdapter: EventAdapter

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}

    private val newEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            syncData(isManual = false)
        }
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            syncData(isManual = false)
            handler.postDelayed(this, 5000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_viewer)
        findViewById<TextView>(R.id.tvHeaderTitle)?.text = "Xperia Receiver • v${BuildConfig.VERSION_NAME}"

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

        cbSelectAll = findViewById(R.id.cbSelectAll)
        tvSelectionCount = findViewById(R.id.tvSelectionCount)
        btnDeleteSelected = findViewById(R.id.btnDeleteSelected)
        btnClearAll = findViewById(R.id.btnClearAll)

        rvEvents.layoutManager = LinearLayoutManager(this)

        // Initialize adapter with local cache right away (screen is never blank)
        allEvents = EventCache.loadEvents(this)
        eventAdapter = EventAdapter(
            onCopyOtp = { otp ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("OTP", otp))
                Toast.makeText(this, "OTP $otp copied!", Toast.LENGTH_SHORT).show()
            },
            onCopyNumber = { number ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Phone Number", number))
                Toast.makeText(this, "Number $number copied!", Toast.LENGTH_SHORT).show()
            },
            onDeleteSingle = { event ->
                allEvents = EventCache.deleteEvent(this, event)
                updateList()
                Toast.makeText(this, "Notification deleted", Toast.LENGTH_SHORT).show()
            },
            onSelectionChanged = { count ->
                updateSelectionToolbar(count)
            }
        )
        rvEvents.adapter = eventAdapter
        eventAdapter.setEvents(getFilteredEvents())

        // Top Buttons
        findViewById<Button>(R.id.btnViewerSettings).setOnClickListener {
            startActivity(Intent(this, ModeSelectionActivity::class.java))
            finish()
        }

        findViewById<Button>(R.id.btnCheckUpdate).setOnClickListener {
            AppUpdater.checkForUpdate(this, silentIfNone = false) { info ->
                AppUpdater.promptUpdateDialog(this, info)
            }
        }

        // SwipeRefresh for manual user pull
        swipeRefresh.setOnRefreshListener {
            syncData(isManual = true)
        }

        // Filter Buttons
        btnFilterAll.setOnClickListener { setFilter("ALL") }
        btnFilterSms.setOnClickListener { setFilter("SMS") }
        btnFilterCalls.setOnClickListener { setFilter("CALL") }
        btnFilterNotif.setOnClickListener { setFilter("NOTIF") }

        // Selection Actions
        cbSelectAll.setOnClickListener {
            if (cbSelectAll.isChecked) {
                eventAdapter.selectAll()
            } else {
                eventAdapter.deselectAll()
            }
        }

        btnDeleteSelected.setOnClickListener {
            val selected = eventAdapter.getSelectedEvents()
            if (selected.isNotEmpty()) {
                allEvents = EventCache.deleteEvents(this, selected)
                eventAdapter.deselectAll()
                cbSelectAll.isChecked = false
                updateList()
                Toast.makeText(this, "${selected.size} notifications deleted", Toast.LENGTH_SHORT).show()
            }
        }

        btnClearAll.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear All Notifications")
                .setMessage("Are you sure you want to remove all notification history from the viewer?")
                .setPositiveButton("Clear All") { _, _ ->
                    allEvents = EventCache.clearAll(this)
                    eventAdapter.deselectAll()
                    cbSelectAll.isChecked = false
                    updateList()
                    Toast.makeText(this, "All notifications cleared", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // Notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Background service for heads-up alerts on Xperia
        val serviceIntent = Intent(this, ViewerForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        // Initial fetch
        syncData(isManual = false)
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

        eventAdapter.deselectAll()
        cbSelectAll.isChecked = false
        updateList()
    }

    private fun getFilteredEvents(): List<NativeEvent> {
        return when (currentFilter) {
            "SMS" -> allEvents.filter { it.type == "SMS" }
            "CALL" -> allEvents.filter { it.type.startsWith("CALL") }
            "NOTIF" -> allEvents.filter { it.type == "NOTIFICATION" }
            else -> allEvents
        }
    }

    private fun updateList() {
        eventAdapter.setEvents(getFilteredEvents())
    }

    private fun updateSelectionToolbar(count: Int) {
        if (count > 0) {
            tvSelectionCount.text = "$count selected"
            btnDeleteSelected.visibility = View.VISIBLE
            btnDeleteSelected.text = "Delete ($count)"
        } else {
            tvSelectionCount.text = ""
            btnDeleteSelected.visibility = View.GONE
            cbSelectAll.isChecked = false
        }
    }

    private fun syncData(isManual: Boolean = false) {
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

        FirebaseRelay.fetchEvents(this) { incoming ->
            runOnUiThread {
                if (isManual) {
                    swipeRefresh.isRefreshing = false
                }
                if (incoming.isNotEmpty()) {
                    // Smart deduplicated merge with local cache
                    allEvents = EventCache.mergeEvents(this, incoming)
                    updateList()
                }
            }
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
        private val onCopyOtp: (String) -> Unit,
        private val onCopyNumber: (String) -> Unit,
        private val onDeleteSingle: (NativeEvent) -> Unit,
        private val onSelectionChanged: (Int) -> Unit
    ) : RecyclerView.Adapter<EventAdapter.ViewHolder>() {

        private var events = listOf<NativeEvent>()
        private val selectedKeys = mutableSetOf<String>()

        fun setEvents(newEvents: List<NativeEvent>) {
            events = newEvents
            notifyDataSetChanged()
        }

        fun selectAll() {
            selectedKeys.clear()
            for (ev in events) {
                selectedKeys.add(getEventKey(ev))
            }
            notifyDataSetChanged()
            onSelectionChanged(selectedKeys.size)
        }

        fun deselectAll() {
            selectedKeys.clear()
            notifyDataSetChanged()
            onSelectionChanged(0)
        }

        fun getSelectedEvents(): Set<NativeEvent> {
            return events.filter { selectedKeys.contains(getEventKey(it)) }.toSet()
        }

        private fun getEventKey(ev: NativeEvent): String {
            return if (ev.id.isNotBlank()) ev.id else "${ev.type}_${ev.title}_${ev.timestamp}"
        }

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val cbSelect: CheckBox = view.findViewById(R.id.cbEventSelect)
            val tvBadge: TextView = view.findViewById(R.id.tvEventBadge)
            val tvTime: TextView = view.findViewById(R.id.tvEventTime)
            val btnDelete: ImageButton = view.findViewById(R.id.btnDeleteEvent)
            val tvTitle: TextView = view.findViewById(R.id.tvEventTitle)
            val tvBody: TextView = view.findViewById(R.id.tvEventBody)

            val layoutOtp: LinearLayout = view.findViewById(R.id.layoutOtp)
            val tvOtpText: TextView = view.findViewById(R.id.tvOtpText)
            val btnCopyOtp: Button = view.findViewById(R.id.btnCopyOtp)

            val layoutCopyNumber: LinearLayout = view.findViewById(R.id.layoutCopyNumber)
            val tvNumberText: TextView = view.findViewById(R.id.tvNumberText)
            val btnCopyNumber: Button = view.findViewById(R.id.btnCopyNumber)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_event, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val ev = events[position]
            val context = holder.itemView.context
            val key = getEventKey(ev)

            // Selection CheckBox
            holder.cbSelect.setOnCheckedChangeListener(null)
            holder.cbSelect.isChecked = selectedKeys.contains(key)
            holder.cbSelect.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) selectedKeys.add(key) else selectedKeys.remove(key)
                onSelectionChanged(selectedKeys.size)
            }

            // Single Delete Button
            holder.btnDelete.setOnClickListener {
                selectedKeys.remove(key)
                onDeleteSingle(ev)
            }

            // Event Badges
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

            // OTP Container
            if (ev.otp.isNotBlank()) {
                holder.layoutOtp.visibility = View.VISIBLE
                holder.tvOtpText.text = "🔑 OTP: ${ev.otp}"
                holder.btnCopyOtp.setOnClickListener { onCopyOtp(ev.otp) }
            } else {
                holder.layoutOtp.visibility = View.GONE
            }

            // Phone Number Container
            val extractedNumber = PhoneNumberHelper.extractNumber(ev)
            if (!extractedNumber.isNullOrBlank()) {
                holder.layoutCopyNumber.visibility = View.VISIBLE
                holder.tvNumberText.text = "📞 $extractedNumber"
                holder.btnCopyNumber.setOnClickListener { onCopyNumber(extractedNumber) }
            } else {
                holder.layoutCopyNumber.visibility = View.GONE
            }
        }

        override fun getItemCount(): Int = events.size
    }
}
