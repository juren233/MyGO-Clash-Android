package io.mygo.clash.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService

/** Turns the proxy on after the device starts, when the user asked for it. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val s = Settings(context)
        // Without the VPN permission (prepare asks for it), nothing starts.
        if (s.startOnBoot && s.wasOn && VpnService.prepare(context) == null) {
            ClashVpnService.start(context)
        }
    }
}
