package com.calliran.gateway.watchdog

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.calliran.gateway.util.BridgeLog

object PingTracker {

    private const val TAG = "PingTracker"
    private const val CHECK_INTERVAL_MS = 60_000L

    private var lastPingTime: Long = 0L
    private var checkHandler: Handler? = null
    private var isRunning = false
    private var hasDone5min = false
    private var hasDone10min = false
    private var hasDone15min = false
    private var appContext: Context? = null
    private var checkCount = 0

    fun onPingReceived(timestamp: String) {
        lastPingTime = System.currentTimeMillis()
        hasDone5min = false
        hasDone10min = false
        hasDone15min = false
        BridgeLog.i(TAG, "Ping received (server timestamp: $timestamp)")
    }

    fun start(context: Context) {
        if (isRunning) return
        appContext = context.applicationContext
        isRunning = true
        lastPingTime = System.currentTimeMillis()
        checkCount = 0
        checkHandler = Handler(Looper.getMainLooper())
        scheduleCheck()
        BridgeLog.i(TAG, "Watchdog started")
    }

    fun stop() {
        isRunning = false
        checkHandler?.removeCallbacksAndMessages(null)
        checkHandler = null
        hasDone5min = false
        hasDone10min = false
        hasDone15min = false
        checkCount = 0
        BridgeLog.i(TAG, "Watchdog stopped")
    }

    private fun scheduleCheck() {
        checkHandler?.postDelayed({
            if (isRunning) {
                performCheck()
                scheduleCheck()
            }
        }, CHECK_INTERVAL_MS)
    }

    private fun performCheck() {
        val gapMinutes = (System.currentTimeMillis() - lastPingTime) / 60_000
        checkCount++

        if (checkCount % 5 == 0) {
            BridgeLog.d(TAG, "Watchdog check: last ping ${gapMinutes}m ago")
        }

        val ctx = appContext ?: return

        if (gapMinutes >= 5 && !hasDone5min) {
            BridgeLog.i(TAG, "Watchdog: no ping for 5+ min — opening Bale")
            openBale(ctx)
            hasDone5min = true
        }

        if (gapMinutes >= 10 && !hasDone10min) {
            BridgeLog.i(TAG, "Watchdog: no ping for 10+ min — opening Bale again")
            openBale(ctx)
            hasDone10min = true
        }

        if (gapMinutes >= 15 && !hasDone15min) {
            BridgeLog.i(TAG, "Watchdog: no ping for 15+ min — executing watchdog action")
            WatchdogAction.execute(ctx)
            hasDone15min = true
        }
    }

    private fun openBale(context: Context) {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage("ir.nasim")
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                BridgeLog.i(TAG, "Bale app opened")
            } else {
                BridgeLog.e(TAG, "Bale app not found")
            }
        } catch (e: Exception) {
            BridgeLog.e(TAG, "Failed to open Bale: ${e.message}")
        }
    }
}
