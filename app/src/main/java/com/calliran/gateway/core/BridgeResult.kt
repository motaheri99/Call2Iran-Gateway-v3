package com.calliran.gateway.core

import com.calliran.gateway.util.BridgeLog

data class BridgeResult(
    val numberA: String,
    val numberB: String,
    val bridgeStartTime: Long,
    val bridgeEndTime: Long,
    val durationSeconds: Int,
    val endReason: String
)

object BridgeResultHolder {
    var lastResult: BridgeResult? = null
        private set

    fun save(result: BridgeResult) {
        lastResult = result
        BridgeLog.i("BridgeResult", "Duration: ${result.durationSeconds}s | Reason: ${result.endReason}")
    }

    fun clear() {
        lastResult = null
    }
}
