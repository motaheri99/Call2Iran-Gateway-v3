package com.calliran.gateway.util

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

data class LogEntry(val timestamp: Long, val level: String, val tag: String, val msg: String)

object BridgeLog {

    private const val MAX_ENTRIES = 500
    private val entries = mutableListOf<LogEntry>()
    private val _live = MutableLiveData<List<LogEntry>>(emptyList())
    val live: LiveData<List<LogEntry>> get() = _live

    @Synchronized
    private fun append(level: String, tag: String, msg: String) {
        val entry = LogEntry(System.currentTimeMillis(), level, tag, msg)
        entries.add(entry)
        if (entries.size > MAX_ENTRIES) entries.removeAt(0)
        _live.postValue(ArrayList(entries))
        when (level) {
            "D" -> Log.d(tag, msg)
            "I" -> Log.i(tag, msg)
            "E" -> Log.e(tag, msg)
        }
    }

    fun d(tag: String, msg: String) = append("D", tag, msg)
    fun i(tag: String, msg: String) = append("I", tag, msg)
    fun e(tag: String, msg: String) = append("E", tag, msg)
}
