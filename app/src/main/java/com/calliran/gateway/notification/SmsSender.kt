package com.calliran.gateway.notification

import android.telephony.SmsManager
import com.calliran.gateway.util.BridgeLog

object SmsSender {

    private const val TAG = "SmsSender"

    fun send(phoneNumber: String, message: String): Boolean {
        return try {
            val smsManager = SmsManager.getDefault()
            val parts = smsManager.divideMessage(message)
            if (parts.size == 1) {
                smsManager.sendTextMessage(phoneNumber, null, message, null, null)
            } else {
                smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null)
            }
            BridgeLog.i(TAG, "SMS sent to $phoneNumber (${parts.size} parts)")
            true
        } catch (e: Exception) {
            BridgeLog.e(TAG, "SMS failed: ${e.message}")
            false
        }
    }
}
