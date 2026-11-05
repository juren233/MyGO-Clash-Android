package io.mygo.clash.android

import android.content.Context
import android.content.SharedPreferences

/** The app's settings, in SharedPreferences. */
class Settings(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun str(key: String, def: String) = prefs.getString(key, def) ?: def

    var mode: String
        get() = str("mode", "rule")
        set(v) = prefs.edit().putString("mode", v).apply()

    /** The TUN stack of mihomo: gvisor, mixed or system. */
    var stack: String
        get() = str("stack", "gvisor")
        set(v) = prefs.edit().putString("stack", v).apply()

    var ipv6: Boolean
        get() = prefs.getBoolean("ipv6", false)
        set(v) = prefs.edit().putBoolean("ipv6", v).apply()

    /** The HTTP and SOCKS port; 0 turns it off. */
    var mixedPort: Int
        get() = prefs.getInt("mixedPort", 7897)
        set(v) = prefs.edit().putInt("mixedPort", v).apply()

    var allowLan: Boolean
        get() = prefs.getBoolean("allowLan", false)
        set(v) = prefs.edit().putBoolean("allowLan", v).apply()

    var logLevel: String
        get() = str("logLevel", "info")
        set(v) = prefs.edit().putString("logLevel", v).apply()

    /** Which apps the VPN takes: all, only [apps] (allow) or all but [apps] (deny). */
    var appMode: String
        get() = str("appMode", APPS_ALL)
        set(v) = prefs.edit().putString("appMode", v).apply()

    var apps: Set<String>
        get() = prefs.getStringSet("apps", emptySet()).orEmpty().toSet()
        set(v) = prefs.edit().putStringSet("apps", v).apply()

    /** Turns the proxy on when the device starts, when it was on. */
    var startOnBoot: Boolean
        get() = prefs.getBoolean("startOnBoot", false)
        set(v) = prefs.edit().putBoolean("startOnBoot", v).apply()

    /** Whether the proxy was on, for [startOnBoot]. */
    var wasOn: Boolean
        get() = prefs.getBoolean("wasOn", false)
        set(v) = prefs.edit().putBoolean("wasOn", v).apply()

    companion object {
        const val APPS_ALL = "all"
        const val APPS_ALLOW = "allow"
        const val APPS_DENY = "deny"
    }
}
