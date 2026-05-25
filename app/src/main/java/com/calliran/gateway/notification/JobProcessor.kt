package com.calliran.gateway.notification

import android.content.Context
import com.calliran.gateway.core.BridgeController
import com.calliran.gateway.util.BridgeLog

object JobProcessor {

    private const val TAG = "JobProcessor"

    fun process(csv: String, context: Context) {
        val fields = csv.split(",")
        if (fields.size != 4) {
            BridgeLog.e(TAG, "Expected 4 CSV fields, got ${fields.size}: $csv")
            return
        }
        val numberA = fields[0].trim()
        val numberB = fields[1].trim()
        val maxSec = fields[2].trim().toIntOrNull()
        val delay = fields[3].trim()

        if (numberA.isEmpty() || numberB.isEmpty()) {
            BridgeLog.e(TAG, "Empty number field: A=$numberA B=$numberB")
            return
        }
        if (maxSec == null) {
            BridgeLog.e(TAG, "Invalid duration: ${fields[2]}")
            return
        }

        BridgeLog.i(TAG, "Job: A=$numberA B=$numberB maxDur=${maxSec}s delay=$delay")
        BridgeController.startBridge(context, numberA, numberB, maxSec)
    }
}
