package com.calliran.gateway.ui

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.calliran.gateway.core.BridgeController
import com.calliran.gateway.notification.KeyHolder
import com.calliran.gateway.reporting.DtmfReporter
import com.calliran.gateway.reporting.ReportingConfig
import com.calliran.gateway.util.BridgeLog
import com.calliran.gateway.watchdog.PingTracker
import com.calliran.gateway.watchdog.WatchdogConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), BridgeController.BridgeListener {

    private lateinit var inputKey: EditText
    private lateinit var inputReportingNumber: EditText
    private lateinit var inputEmergencyNumber: EditText
    private lateinit var btnToggle: Button
    private lateinit var logView: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var statusText: TextView

    private var serviceRunning = false

    private val bgColor = Color.parseColor("#1a1a1a")
    private val textColor = Color.parseColor("#cccccc")
    private val errorColor = Color.parseColor("#ff4444")
    private val accentColor = Color.parseColor("#4a9eff")

    private val requiredPermissions = arrayOf(
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.SEND_SMS
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val denied = results.filter { !it.value }.keys
        if (denied.isNotEmpty()) {
            BridgeLog.e("MainActivity", "Permissions denied: $denied")
        }
    }

    private val dialerRoleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            BridgeLog.i("MainActivity", "Default dialer role granted")
        } else {
            BridgeLog.e("MainActivity", "Default dialer role denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bgColor
        buildUi()
        requestPermissionsIfNeeded()
        requestDialerRole()
        BridgeController.listener = this

        BridgeLog.live.observe(this) { entries ->
            val sdf = SimpleDateFormat("HH:mm:ss", Locale.US)
            val text = entries.joinToString("\n") { e ->
                val ts = sdf.format(Date(e.timestamp))
                "[$ts ${e.level}] ${e.tag}: ${e.msg}"
            }
            logView.text = text
            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }

        BridgeLog.i("MainActivity", "v3 started")
    }

    override fun onDestroy() {
        BridgeController.listener = null
        super.onDestroy()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor)
            setPadding(48, 48, 48, 48)
        }

        val title = TextView(this).apply {
            text = "Call2Iran Gateway v3"
            textSize = 22f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        root.addView(title, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            bottomMargin = 32
        })

        statusText = TextView(this).apply {
            text = "IDLE"
            textSize = 14f
            setTextColor(accentColor)
            gravity = Gravity.CENTER
        }
        root.addView(statusText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            bottomMargin = 24
        })

        inputKey = EditText(this).apply {
            hint = "Encryption key (64 hex chars)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine = true
            setTextColor(textColor)
            setHintTextColor(Color.parseColor("#666666"))
            setBackgroundColor(Color.parseColor("#2a2a2a"))
            typeface = Typeface.MONOSPACE
            setPadding(24, 20, 24, 20)
        }
        root.addView(inputKey, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            bottomMargin = 16
        })

        inputReportingNumber = EditText(this).apply {
            hint = "Reporting number"
            inputType = InputType.TYPE_CLASS_PHONE
            setTextColor(textColor)
            setHintTextColor(Color.parseColor("#666666"))
            setBackgroundColor(Color.parseColor("#2a2a2a"))
            setPadding(24, 20, 24, 20)
        }
        root.addView(inputReportingNumber, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            bottomMargin = 16
        })

        inputEmergencyNumber = EditText(this).apply {
            hint = "Emergency number (watchdog)"
            inputType = InputType.TYPE_CLASS_PHONE
            setTextColor(textColor)
            setHintTextColor(Color.parseColor("#666666"))
            setBackgroundColor(Color.parseColor("#2a2a2a"))
            setPadding(24, 20, 24, 20)
        }
        root.addView(inputEmergencyNumber, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            bottomMargin = 24
        })

        btnToggle = Button(this).apply {
            text = "Start Service"
            setBackgroundColor(accentColor)
            setTextColor(Color.WHITE)
            setOnClickListener { onToggleClicked() }
        }
        root.addView(btnToggle, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            bottomMargin = 24
        })

        val logLabel = TextView(this).apply {
            text = "Log"
            textSize = 14f
            setTextColor(textColor)
        }
        root.addView(logLabel, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            bottomMargin = 8
        })

        scrollView = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#111111"))
            setPadding(16, 16, 16, 16)
        }

        logView = TextView(this).apply {
            textSize = 11f
            setTextColor(textColor)
            typeface = Typeface.MONOSPACE
        }
        scrollView.addView(logView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        root.addView(scrollView, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        setContentView(root, LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    private fun onToggleClicked() {
        if (serviceRunning) {
            KeyHolder.key = null
            ReportingConfig.reportingNumber = null
            WatchdogConfig.emergencyNumber = null
            DtmfReporter.cancelPendingRetries()
            PingTracker.stop()
            serviceRunning = false
            btnToggle.text = "Start Service"
            btnToggle.setBackgroundColor(accentColor)
            inputKey.isEnabled = true
            inputReportingNumber.isEnabled = true
            inputEmergencyNumber.isEnabled = true
            BridgeLog.i("MainActivity", "Service stopped")
            return
        }

        val key = inputKey.text.toString().trim()
        if (!key.matches(Regex("^[0-9a-fA-F]{64}$"))) {
            BridgeLog.e("MainActivity", "Invalid key: must be exactly 64 hex characters")
            return
        }

        KeyHolder.key = key
        val reportNum = inputReportingNumber.text.toString().trim()
        ReportingConfig.reportingNumber = reportNum.ifEmpty { null }
        val emergencyNum = inputEmergencyNumber.text.toString().trim()
        WatchdogConfig.emergencyNumber = emergencyNum.ifEmpty { null }

        val hasAccess = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        if (!hasAccess) {
            BridgeLog.i("MainActivity", "Opening notification access settings...")
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            return
        }

        PingTracker.start(this)

        serviceRunning = true
        btnToggle.text = "Stop Service"
        btnToggle.setBackgroundColor(errorColor)
        inputKey.isEnabled = false
        inputReportingNumber.isEnabled = false
        inputEmergencyNumber.isEnabled = false
        BridgeLog.i("MainActivity", "Service started — waiting for jobs")
    }

    private fun requestPermissionsIfNeeded() {
        val needed = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun requestDialerRole() {
        val rm = getSystemService(RoleManager::class.java)
        if (!rm.isRoleHeld(RoleManager.ROLE_DIALER)) {
            dialerRoleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_DIALER))
        }
    }

    override fun onStateChanged(state: String, message: String) {
        runOnUiThread {
            statusText.text = if (message.isEmpty()) state else "$state — $message"
            statusText.setTextColor(
                if (state == "BRIDGED") Color.parseColor("#44ff44")
                else accentColor
            )
        }
    }

    override fun onFailed(reason: String) {
        runOnUiThread {
            statusText.text = "FAILED: $reason"
            statusText.setTextColor(errorColor)
        }
    }

    override fun onComplete() {
        runOnUiThread {
            statusText.text = "DONE"
            statusText.setTextColor(accentColor)
        }
    }

    override fun onTimerTick(remainingSeconds: Int) {
        runOnUiThread {
            statusText.text = "BRIDGED — ${remainingSeconds}s left"
            statusText.setTextColor(
                if (remainingSeconds < 10) errorColor
                else Color.parseColor("#44ff44")
            )
        }
    }

    override fun onBridgeDuration(durationSeconds: Int) {
        runOnUiThread {
            statusText.text = "DONE — ${durationSeconds}s call"
            BridgeLog.i("MainActivity", "Call duration: ${durationSeconds}s")
        }
    }
}
