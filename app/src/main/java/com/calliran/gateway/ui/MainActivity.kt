package com.calliran.gateway.ui

import android.Manifest
import android.app.role.RoleManager
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
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
import androidx.core.content.ContextCompat
import com.calliran.gateway.core.BridgeController
import com.calliran.gateway.core.CallBridgeService
import com.calliran.gateway.util.BridgeLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), BridgeController.BridgeListener {

    private lateinit var inputA: EditText
    private lateinit var inputB: EditText
    private lateinit var btnStart: Button
    private lateinit var btnAbort: Button
    private lateinit var logView: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var statusText: TextView

    private val bgColor = Color.parseColor("#1a1a1a")
    private val textColor = Color.parseColor("#cccccc")
    private val errorColor = Color.parseColor("#ff4444")
    private val accentColor = Color.parseColor("#4a9eff")

    private val requiredPermissions = arrayOf(
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_CALL_LOG
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

        inputA = EditText(this).apply {
            hint = "Number A (Iran relay)"
            inputType = InputType.TYPE_CLASS_PHONE
            setTextColor(textColor)
            setHintTextColor(Color.parseColor("#666666"))
            setBackgroundColor(Color.parseColor("#2a2a2a"))
            setPadding(24, 20, 24, 20)
        }
        root.addView(inputA, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            bottomMargin = 16
        })

        inputB = EditText(this).apply {
            hint = "Number B (international)"
            inputType = InputType.TYPE_CLASS_PHONE
            setTextColor(textColor)
            setHintTextColor(Color.parseColor("#666666"))
            setBackgroundColor(Color.parseColor("#2a2a2a"))
            setPadding(24, 20, 24, 20)
        }
        root.addView(inputB, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            bottomMargin = 24
        })

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        btnStart = Button(this).apply {
            text = "Start Bridge"
            setBackgroundColor(accentColor)
            setTextColor(Color.WHITE)
            setOnClickListener { onStartClicked() }
        }
        buttonRow.addView(btnStart, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
            rightMargin = 8
        })

        btnAbort = Button(this).apply {
            text = "Abort"
            setBackgroundColor(errorColor)
            setTextColor(Color.WHITE)
            isEnabled = false
            setOnClickListener { BridgeController.abort() }
        }
        buttonRow.addView(btnAbort, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
            leftMargin = 8
        })

        root.addView(buttonRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
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

    private fun onStartClicked() {
        val numA = inputA.text.toString().trim()
        val numB = inputB.text.toString().trim()
        if (numA.isEmpty() || numB.isEmpty()) {
            BridgeLog.e("MainActivity", "Both numbers are required")
            return
        }
        btnStart.isEnabled = false
        btnAbort.isEnabled = true
        BridgeController.startBridge(this, numA, numB)
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

    override fun onStateChanged(state: CallBridgeService.State) {
        runOnUiThread {
            statusText.text = state.name
            statusText.setTextColor(
                if (state == CallBridgeService.State.BRIDGED) Color.parseColor("#44ff44")
                else accentColor
            )
        }
    }

    override fun onBridgeFinished() {
        runOnUiThread {
            statusText.text = "DONE"
            btnStart.isEnabled = true
            btnAbort.isEnabled = false
        }
    }
}
