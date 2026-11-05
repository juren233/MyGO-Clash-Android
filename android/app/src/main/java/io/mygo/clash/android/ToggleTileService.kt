package io.mygo.clash.android

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** The quick settings tile: turns the proxy on and off. */
class ToggleTileService : TileService() {
    override fun onStartListening() {
        update()
    }

    override fun onClick() {
        when (Core.state.value) {
            VpnState.Off -> {
                if (VpnService.prepare(this) != null) {
                    // The VPN permission is asked in the app.
                    openApp()
                } else {
                    ClashVpnService.start(this)
                }
            }
            else -> ClashVpnService.stop(this)
        }
        update()
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                android.app.PendingIntent.getActivity(this, 0, intent, android.app.PendingIntent.FLAG_IMMUTABLE),
            )
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }

    private fun update() {
        val tile = qsTile ?: return
        tile.state = if (Core.state.value == VpnState.Off) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        tile.updateTile()
    }

    companion object {
        fun refresh(context: Context) {
            requestListeningState(context, ComponentName(context, ToggleTileService::class.java))
        }
    }
}
