package com.phonerelay.phonebridge

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import androidx.appcompat.app.AppCompatActivity

class ModeSelectionActivity : AppCompatActivity() {

    private var selectedRole = "HOST"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val savedRole = BridgePreferences.getDeviceRole(this)
        val isServiceRunning = BridgePreferences.isServiceRunning(this)

        if (savedRole == "HOST" && isServiceRunning) {
            startActivity(Intent(this, HostActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_mode_selection)

        val cardHost = findViewById<LinearLayout>(R.id.cardHost)
        val cardViewer = findViewById<LinearLayout>(R.id.cardViewer)
        val rbHost = findViewById<RadioButton>(R.id.rbHost)
        val rbViewer = findViewById<RadioButton>(R.id.rbViewer)
        val btnContinue = findViewById<Button>(R.id.btnContinue)

        // Ensure default pair code is pre-configured
        if (BridgePreferences.getPairCode(this).isBlank()) {
            BridgePreferences.setPairCode(this, "realme-xperia")
        }

        fun updateSelection(role: String) {
            selectedRole = role
            rbHost.isChecked = role == "HOST"
            rbViewer.isChecked = role == "VIEWER"
            if (role == "HOST") {
                btnContinue.text = "Start as Realme Host"
                btnContinue.backgroundTintList = getColorStateList(R.color.accent_green)
            } else {
                btnContinue.text = "Open Xperia Viewer"
                btnContinue.backgroundTintList = getColorStateList(R.color.primary_blue)
            }
        }

        updateSelection(savedRole)

        cardHost.setOnClickListener { updateSelection("HOST") }
        rbHost.setOnClickListener { updateSelection("HOST") }

        cardViewer.setOnClickListener { updateSelection("VIEWER") }
        rbViewer.setOnClickListener { updateSelection("VIEWER") }

        btnContinue.setOnClickListener {
            BridgePreferences.setDeviceRole(this, selectedRole)
            if (BridgePreferences.getPairCode(this).isBlank()) {
                BridgePreferences.setPairCode(this, "realme-xperia")
            }

            if (selectedRole == "HOST") {
                startActivity(Intent(this, HostActivity::class.java))
            } else {
                startActivity(Intent(this, ViewerActivity::class.java))
            }
            finish()
        }
    }
}
