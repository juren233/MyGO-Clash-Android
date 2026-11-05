package io.mygo.clash.android.ui

import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** 1536 → "1.5 KB". */
fun bytes(n: Long): String {
    if (n < 1024) return "$n B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = n / 1024.0
    var i = 0
    while (v >= 1024 && i < units.size - 1) {
        v /= 1024
        i++
    }
    return String.format(Locale.ROOT, if (v < 10) "%.1f %s" else "%.0f %s", v, units[i])
}

fun speed(n: Long): String = bytes(n) + "/s"

/** A date of Unix seconds. */
fun date(seconds: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(seconds * 1000))

fun dateTime(seconds: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(seconds * 1000))
