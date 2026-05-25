package com.calliran.gateway.core

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
import com.calliran.gateway.reporting.DtmfReporter
import com.calliran.gateway.reporting.ReportingConfig
import com.calliran.gateway.util.BridgeLog

object BridgeController {

    private const val TAG = "BridgeController"

    interface BridgeListener {
        fun onStateChanged(state: String, message: String)
        fun onFailed(reason: String)
        fun onComplete()
        fun onTimerTick(remainingSeconds: Int)
        fun onBridgeDuration(durationSeconds: Int)
    }

    var listener: BridgeListener? = null
    private var active = false
    private var appContext: Context? = null

    fun isActive(): Boolean = active

    fun startBridge(context: Context, numberA: String, numberB: String, maxDurationSeconds: Int): Boolean {
        if (active) {
            BridgeLog.e(TAG, "Bridge already active, ignoring")
            return false
        }
        active = true
        appContext = context.applicationContext
        BridgeLog.i(TAG, "Starting bridge: A=$numberA B=$numberB maxDuration=${maxDurationSeconds}s")

        CallBridgeService.pendingMaxDuration = maxDurationSeconds

        val service = CallBridgeService.instance
        if (service != null) {
            service.startBridge(numberB)
        } else {
            BridgeLog.e(TAG, "CallBridgeService not running — will arm when available")
            pendingNumberB = numberB
        }

        try {
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            telecom.placeCall(Uri.fromParts("tel", numberA, null), Bundle())
        } catch (e: Exception) {
            BridgeLog.e(TAG, "placeCall failed: ${e.message}")
            active = false
            pendingNumberB = null
            return false
        }
        return true
    }

    private var pendingNumberB: String? = null

    fun onServiceReady() {
        pendingNumberB?.let { numB ->
            BridgeLog.i(TAG, "Service now ready, arming bridge with B=$numB")
            CallBridgeService.instance?.startBridge(numB)
            pendingNumberB = null
        }
    }

    fun abort() {
        BridgeLog.i(TAG, "Abort requested via controller")
        CallBridgeService.instance?.abort()
        active = false
    }

    fun onStateChanged(state: CallBridgeService.State) {
        listener?.onStateChanged(state.name, "")
    }

    fun onFailed(reason: String) {
        BridgeLog.e(TAG, "Bridge failed: $reason")
        active = false
        listener?.onFailed(reason)
    }

    fun onTimerTick(remainingSeconds: Int) {
        listener?.onTimerTick(remainingSeconds)
    }

    fun onBridgeFinished() {
        BridgeLog.i(TAG, "Bridge finished")
        active = false
        val result = BridgeResultHolder.lastResult
        listener?.onComplete()
        if (result != null) {
            listener?.onBridgeDuration(result.durationSeconds)
            val reportNum = ReportingConfig.reportingNumber
            if (!reportNum.isNullOrBlank()) {
                val ctx = appContext
                if (ctx != null) {
                    DtmfReporter.reportDuration(ctx, result.durationSeconds)
                }
            }
        }
    }
}
