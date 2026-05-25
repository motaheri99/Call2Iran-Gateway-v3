package com.calliran.gateway.notification

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.calliran.gateway.core.BridgeController
import com.calliran.gateway.util.BridgeLog
import com.calliran.gateway.watchdog.PingTracker

class BaleNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "NotifListener"
        var instance: BaleNotificationListener? = null
            private set
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        BridgeLog.i(TAG, "Notification listener connected")
    }

    override fun onListenerDisconnected() {
        instance = null
        BridgeLog.i(TAG, "Notification listener disconnected")
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != "ir.nasim") return

        val extras = sbn.notification.extras
        val title = extras.getString(Notification.EXTRA_TITLE) ?: return
        if (!title.contains("Call Iran", ignoreCase = true)) return

        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: return

        if (text.startsWith("PING:")) {
            val pingData = text.removePrefix("PING:").trim()
            PingTracker.onPingReceived(pingData)
            return
        }

        if (!text.startsWith("JOB:")) return

        val payload = text.removePrefix("JOB:").trim()
        BridgeLog.i(TAG, "Job notification received, payload length=${payload.length}")

        val key = KeyHolder.key
        if (key.isNullOrEmpty()) {
            BridgeLog.e(TAG, "No encryption key set")
            return
        }

        if (BridgeController.isActive()) {
            BridgeLog.i(TAG, "Bridge already active, ignoring job")
            return
        }

        val csv = JobDecryptor.decrypt(payload, key)
        if (csv == null) {
            BridgeLog.e(TAG, "Decryption failed, skipping job")
            return
        }

        JobProcessor.process(csv, applicationContext)
    }
}
