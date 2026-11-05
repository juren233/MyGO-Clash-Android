package io.mygo.clash.android

import io.mygo.clash.libmygo.Stream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** A message of the core. */
data class LogLine(val id: Long, val time: Long, val level: String, val text: String)

/**
 * The last messages of the core, kept while the proxy runs, so that the
 * logs page shows what happened before it opened.
 */
object Logs {
    private const val MAX = 500

    private val _lines = MutableStateFlow<List<LogLine>>(emptyList())
    val lines: StateFlow<List<LogLine>> = _lines.asStateFlow()

    private var stream: Stream? = null
    private var seq = 0L

    @Synchronized
    fun start(level: String) {
        stop()
        stream = runCatching {
            Core.subscribe("/logs?level=$level") { line ->
                val o = runCatching { JSONObject(line) }.getOrNull() ?: return@subscribe
                synchronized(this) {
                    val l = LogLine(seq++, System.currentTimeMillis(), o.optString("type"), o.optString("payload"))
                    _lines.value = (_lines.value + l).takeLast(MAX)
                }
            }
        }.getOrNull()
    }

    @Synchronized
    fun stop() {
        stream?.close()
        stream = null
    }

    fun clear() {
        _lines.value = emptyList()
    }
}
