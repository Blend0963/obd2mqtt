package com.frodrigues.obdmqtt.util

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-app connection log (last [MAX_LINES] lines), shown in the "Journal" screen so a
 * user can copy what happened between the app, the adapter and the car.
 */
object DebugLog {

    private const val MAX_LINES = 400
    private val format = SimpleDateFormat("HH:mm:ss.SSS", Locale.FRANCE)

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    fun add(message: String) {
        Log.d("DebugLog", message)
        val line = "${synchronized(format) { format.format(Date()) }}  $message"
        _lines.update { (it + line).takeLast(MAX_LINES) }
    }

    fun clear() {
        _lines.value = emptyList()
    }

    /** Makes CR/LF from ELM327 replies visible on one line. */
    fun printable(text: String): String = text.replace("\r", "⏎").replace("\n", "⏎").trim()
}
