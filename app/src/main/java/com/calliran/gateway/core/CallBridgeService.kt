package com.calliran.gateway.core

import android.net.Uri
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.InCallService
import android.telecom.TelecomManager
import com.calliran.gateway.reporting.DtmfReporter
import com.calliran.gateway.util.BridgeLog
import com.calliran.gateway.watchdog.WatchdogAction

class CallBridgeService : InCallService() {

    companion object {
        private const val TAG = "CallBridgeService"
        var instance: CallBridgeService? = null
            private set
        @Volatile var pendingMaxDuration: Int = 0
    }

    enum class State {
        IDLE, DIALING_A, A_ACTIVE, DIALING_B, B_ACTIVE, MERGING, BRIDGED, TEARING_DOWN, DONE
    }

    private var state = State.IDLE
    private var callA: Call? = null
    private var callB: Call? = null
    private var conferenceCall: Call? = null
    private var numberA: String? = null
    private var numberB: String? = null
    private var durationTimer: CountDownTimer? = null
    private var bridgeStartTime: Long = 0L
    private var endReason: String = "hangup"
    private val handler = Handler(Looper.getMainLooper())

    private val callbackA = object : Call.Callback() {
        override fun onStateChanged(call: Call, newState: Int) {
            BridgeLog.d(TAG, "callA state -> ${callStateStr(newState)}")
            when (newState) {
                Call.STATE_ACTIVE -> onCallAActive()
                Call.STATE_DISCONNECTED -> onCallDisconnected(call)
            }
        }
    }

    private val callbackB = object : Call.Callback() {
        override fun onStateChanged(call: Call, newState: Int) {
            BridgeLog.d(TAG, "callB state -> ${callStateStr(newState)}")
            when (newState) {
                Call.STATE_ACTIVE -> onCallBActive()
                Call.STATE_DISCONNECTED -> onCallDisconnected(call)
            }
        }
    }

    private val watchdogCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            when (state) {
                Call.STATE_ACTIVE -> WatchdogAction.onCallConnected(call)
                Call.STATE_DISCONNECTED -> {
                    call.unregisterCallback(this)
                    WatchdogAction.onCallEnded()
                }
            }
        }
    }

    private val reportingCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, newState: Int) {
            when (newState) {
                Call.STATE_ACTIVE -> DtmfReporter.onReportingCallActive(call)
                Call.STATE_DISCONNECTED -> {
                    call.unregisterCallback(this)
                    if (DtmfReporter.isReporting) {
                        DtmfReporter.onReportingCallFailed()
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        BridgeLog.i(TAG, "Service created")
        BridgeController.onServiceReady()
    }

    override fun onDestroy() {
        instance = null
        BridgeLog.i(TAG, "Service destroyed")
        super.onDestroy()
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        val number = call.details?.handle?.schemeSpecificPart ?: "unknown"
        BridgeLog.i(TAG, "onCallAdded: $number state=${callStateStr(call.state)} total=${calls.size}")

        if (DtmfReporter.isReporting) {
            BridgeLog.d(TAG, "Reporting call added")
            call.registerCallback(reportingCallback)
            return
        }

        if (WatchdogAction.isWatchdogCalling) {
            BridgeLog.d(TAG, "Watchdog call added")
            call.registerCallback(watchdogCallback)
            return
        }

        if (call.details?.hasProperty(Call.Details.PROPERTY_CONFERENCE) == true) {
            BridgeLog.i(TAG, "Conference parent detected, tracking")
            conferenceCall = call
            call.registerCallback(object : Call.Callback() {
                override fun onChildrenChanged(parent: Call, children: List<Call>) {
                    BridgeLog.d(TAG, "Conference children: ${children.size}")
                }
                override fun onStateChanged(c: Call, newState: Int) {
                    BridgeLog.d(TAG, "Conference state -> ${callStateStr(newState)}")
                    if (newState == Call.STATE_DISCONNECTED) onCallDisconnected(c)
                }
            })
            return
        }

        when (state) {
            State.IDLE, State.DIALING_A -> {
                callA = call
                numberA = number
                call.registerCallback(callbackA)
                transition(State.DIALING_A)
                if (call.state == Call.STATE_ACTIVE) onCallAActive()
            }
            State.A_ACTIVE, State.DIALING_B -> {
                callB = call
                call.registerCallback(callbackB)
                transition(State.DIALING_B)
                if (call.state == Call.STATE_ACTIVE) onCallBActive()
            }
            else -> BridgeLog.e(TAG, "Unexpected onCallAdded in state $state")
        }
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        BridgeLog.i(TAG, "onCallRemoved, remaining=${calls.size}")
    }

    fun startBridge(numB: String) {
        numberB = numB
        transition(State.IDLE)
        BridgeLog.i(TAG, "Bridge armed, waiting for call A; will dial B=$numB")
    }

    private fun onCallAActive() {
        if (state != State.DIALING_A && state != State.IDLE) return
        transition(State.A_ACTIVE)
        BridgeLog.i(TAG, "Call A active, dialing B in 1s...")
        handler.postDelayed({ dialCallB() }, 1000)
    }

    private fun dialCallB() {
        val num = numberB ?: return
        transition(State.DIALING_B)
        BridgeLog.i(TAG, "Placing call B: $num")
        val uri = Uri.fromParts("tel", num, null)
        val extras = Bundle().apply {
            putBoolean(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, false)
        }
        val tm = getSystemService(TELECOM_SERVICE) as TelecomManager
        tm.placeCall(uri, extras)
    }

    private fun onCallBActive() {
        if (state != State.DIALING_B) return
        transition(State.B_ACTIVE)
        BridgeLog.i(TAG, "Call B active, attempting merge...")
        attemptMerge()
    }

    private fun attemptMerge() {
        transition(State.MERGING)
        val a = callA ?: run { BridgeLog.e(TAG, "callA null at merge"); tearDown(); return }
        val b = callB ?: run { BridgeLog.e(TAG, "callB null at merge"); tearDown(); return }

        val canMergeA = a.details?.callCapabilities?.and(Call.Details.CAPABILITY_MERGE_CONFERENCE) != 0
        val canMergeB = b.details?.callCapabilities?.and(Call.Details.CAPABILITY_MERGE_CONFERENCE) != 0
        BridgeLog.d(TAG, "Merge caps: A=$canMergeA B=$canMergeB")

        if (canMergeA) {
            BridgeLog.i(TAG, "conference(callB) via callA")
            a.conference(b)
        } else if (canMergeB) {
            BridgeLog.i(TAG, "conference(callA) via callB")
            b.conference(a)
        } else {
            BridgeLog.e(TAG, "Neither call has CAPABILITY_MERGE_CONFERENCE")
            BridgeLog.i(TAG, "Trying conference anyway...")
            a.conference(b)
        }

        handler.postDelayed({
            if (state == State.MERGING) {
                BridgeLog.i(TAG, "Post-merge: calls=${calls.size}")
                transition(State.BRIDGED)
                bridgeStartTime = System.currentTimeMillis()
                BridgeLog.i(TAG, "Bridge timer started")
                startDurationTimer()
                handler.postDelayed({
                    BridgeLog.i(TAG, "Muting mic")
                    setMuted(true)
                }, 1500)
            }
        }, 2000)
    }

    private fun onCallDisconnected(call: Call) {
        BridgeLog.i(TAG, "Call disconnected in state $state")
        when {
            state == State.BRIDGED || state == State.MERGING -> tearDown()
            call == callA && state.ordinal < State.BRIDGED.ordinal -> {
                BridgeLog.e(TAG, "Call A dropped before bridge complete")
                tearDown()
            }
            call == callB && state.ordinal < State.BRIDGED.ordinal -> {
                BridgeLog.e(TAG, "Call B dropped before bridge complete")
                tearDown()
            }
        }
    }

    private fun startDurationTimer() {
        val maxDuration = pendingMaxDuration
        if (maxDuration <= 0) {
            BridgeLog.d(TAG, "No max duration set, timer skipped")
            return
        }
        BridgeLog.i(TAG, "Timer started: ${maxDuration}s")
        durationTimer = object : CountDownTimer(maxDuration * 1000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val remaining = (millisUntilFinished / 1000).toInt()
                BridgeController.onTimerTick(remaining)
                if (remaining % 10 == 0) {
                    BridgeLog.d(TAG, "Remaining: ${remaining}s")
                }
            }
            override fun onFinish() {
                BridgeLog.i(TAG, "Max duration reached — hanging up")
                endReason = "max_duration"
                tearDown()
            }
        }.start()
    }

    private fun tearDown() {
        if (state == State.TEARING_DOWN || state == State.DONE) return
        durationTimer?.cancel()
        durationTimer = null
        transition(State.TEARING_DOWN)

        val now = System.currentTimeMillis()
        if (bridgeStartTime > 0) {
            val durationSec = ((now - bridgeStartTime) / 1000).toInt()
            BridgeLog.i(TAG, "Bridge lasted ${durationSec}s")
            val result = BridgeResult(
                numberA = numberA ?: "unknown",
                numberB = numberB ?: "unknown",
                bridgeStartTime = bridgeStartTime,
                bridgeEndTime = now,
                durationSeconds = durationSec,
                endReason = endReason
            )
            BridgeResultHolder.save(result)
        }

        BridgeLog.i(TAG, "Tearing down all calls")
        calls.forEach { c ->
            if (c.state != Call.STATE_DISCONNECTED) {
                c.disconnect()
            }
        }
        callA?.unregisterCallback(callbackA)
        callB?.unregisterCallback(callbackB)
        callA = null
        callB = null
        conferenceCall = null
        numberA = null
        numberB = null
        bridgeStartTime = 0L
        endReason = "hangup"
        transition(State.DONE)
        BridgeController.onBridgeFinished()
    }

    fun abort() {
        BridgeLog.i(TAG, "Abort requested")
        endReason = "abort"
        tearDown()
    }

    private fun transition(newState: State) {
        BridgeLog.d(TAG, "State: $state -> $newState")
        state = newState
        BridgeController.onStateChanged(newState)
    }

    private fun callStateStr(s: Int): String = when (s) {
        Call.STATE_NEW -> "NEW"
        Call.STATE_DIALING -> "DIALING"
        Call.STATE_RINGING -> "RINGING"
        Call.STATE_HOLDING -> "HOLDING"
        Call.STATE_ACTIVE -> "ACTIVE"
        Call.STATE_DISCONNECTED -> "DISCONNECTED"
        Call.STATE_CONNECTING -> "CONNECTING"
        Call.STATE_DISCONNECTING -> "DISCONNECTING"
        Call.STATE_SELECT_PHONE_ACCOUNT -> "SELECT_PHONE_ACCOUNT"
        Call.STATE_PULLING_CALL -> "PULLING_CALL"
        else -> "UNKNOWN($s)"
    }
}
