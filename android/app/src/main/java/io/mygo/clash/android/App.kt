package io.mygo.clash.android

import android.app.Application
import android.util.Log

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // The core starts with the process: the pages read the profiles
        // before the VPN runs, and the VPN service shares this process.
        try {
            Core.init(this)
        } catch (e: Exception) {
            Log.e("MyGO-Clash", "init", e)
            Core.setState(VpnState.Off, e.message)
        }
    }
}
