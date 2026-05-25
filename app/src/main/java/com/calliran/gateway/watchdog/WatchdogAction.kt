package com.calliran.gateway.watchdog

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.TelecomManager
import com.calliran.gateway.util.BridgeLog

object WatchdogAction {

    private const val TAG = "WatchdogAction"

    var isWatchdogCalling: Boolean = false
        private set

    private val handler = Handler(Looper.getMainLooper())

    fun execute(context: Context) {
        val number = WatchdogConfig.emergencyNumber
        if (number.isNullOrBlank()) {
            BridgeLog.e(TAG, "No emergency number configured")
            return
        }

        isWatchdogCalling = true
        BridgeLog.i(TAG, "Watchdog: calling emergency number")

        try {
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            telecom.placeCall(Uri.fromParts("tel", number, null), Bundle())
        } catch (e: Exception) {
            BridgeLog.e(TAG, "Failed to place watchdog call: ${e.message}")
            isWatchdogCalling = false
        }
    }

    fun onCallConnected(call: Call) {
        BridgeLog.i(TAG, "Watchdog: emergency call connected — hanging up in 10s")
        handler.postDelayed({
            try {
                call.disconnect()
            } catch (e: Exception) {
                BridgeLog.e(TAG, "Failed to disconnect watchdog call: ${e.message}")
            }
        }, 10000)
    }

    fun onCallEnded() {
        BridgeLog.i(TAG, "Watchdog: emergency call ended")
        isWatchdogCalling = false
        handler.removeCallbacksAndMessages(null)
    }
}
