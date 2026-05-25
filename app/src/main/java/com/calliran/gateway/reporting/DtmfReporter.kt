package com.calliran.gateway.reporting

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.TelecomManager
import com.calliran.gateway.util.BridgeLog

object DtmfReporter {

    private const val TAG = "DtmfReporter"

    var isReporting: Boolean = false
        private set

    private var pendingDuration: Int = 0
    private var appContext: Context? = null
    private val handler = Handler(Looper.getMainLooper())

    fun reportDuration(context: Context, durationSeconds: Int) {
        pendingDuration = durationSeconds
        isReporting = true
        appContext = context.applicationContext
        val number = ReportingConfig.reportingNumber ?: return
        BridgeLog.i(TAG, "Reporting: calling $number to report ${durationSeconds}s")
        handler.postDelayed({ placeReportingCall() }, 3000)
    }

    private fun placeReportingCall() {
        val ctx = appContext ?: return
        val number = ReportingConfig.reportingNumber
        if (number.isNullOrBlank()) {
            BridgeLog.e(TAG, "No reporting number configured")
            isReporting = false
            return
        }
        try {
            val telecom = ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            telecom.placeCall(Uri.fromParts("tel", number, null), Bundle())
        } catch (e: Exception) {
            BridgeLog.e(TAG, "placeCall failed: ${e.message}")
            scheduleRetry()
        }
    }

    fun onReportingCallActive(call: Call) {
        val digits = pendingDuration.toString()
        BridgeLog.i(TAG, "Reporting call connected — waiting 5s before sending tones")
        handler.postDelayed({
            BridgeLog.i(TAG, "Playing DTMF: $digits")
            playDigits(call, digits, 0)
        }, 5000)
    }

    private fun playDigits(call: Call, digits: String, index: Int) {
        if (index >= digits.length) {
            handler.postDelayed({
                BridgeLog.i(TAG, "Report sent successfully")
                call.disconnect()
                isReporting = false
                pendingDuration = 0
                cancelPendingRetries()
            }, 500)
            return
        }
        val digit = digits[index]
        call.playDtmfTone(digit)
        handler.postDelayed({
            call.stopDtmfTone()
            handler.postDelayed({
                playDigits(call, digits, index + 1)
            }, 150)
        }, 200)
    }

    fun onReportingCallFailed() {
        BridgeLog.i(TAG, "Reporting call failed — retrying in 30s")
        scheduleRetry()
    }

    private fun scheduleRetry() {
        handler.postDelayed({ placeReportingCall() }, 30000)
    }

    fun cancelPendingRetries() {
        handler.removeCallbacksAndMessages(null)
    }
}
