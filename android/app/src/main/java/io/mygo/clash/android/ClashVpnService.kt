package io.mygo.clash.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.net.Inet4Address

/**
 * The VPN: it opens the TUN and hands its descriptor to the core, which
 * runs in this process and reads and writes the packets itself.
 */
class ClashVpnService : VpnService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Settings(this).wasOn = false
                stop()
                return START_NOT_STICKY
            }
            ACTION_RESTART -> restart()
            else -> start()
        }
        return START_STICKY
    }

    private fun start() {
        if (Core.state.value != VpnState.Off) return
        Core.setState(VpnState.Starting)
        startForegroundCompat()
        scope.launch {
            try {
                Core.init(this@ClashVpnService)
                // The DNS of the network under the VPN, read before the
                // VPN becomes the default network.
                val dns = systemDNS()
                val tun = establish()
                Core.vpn = this@ClashVpnService
                Core.start(tun.detachFd(), dns, Core.options(this@ClashVpnService))
                watchNetwork()
                Settings(this@ClashVpnService).wasOn = true
                Logs.start(Settings(this@ClashVpnService).logLevel)
                Core.setState(VpnState.On)
                ToggleTileService.refresh(this@ClashVpnService)
            } catch (e: Exception) {
                Log.e(TAG, "start", e)
                Core.vpn = null
                Core.stop()
                Core.setState(VpnState.Off, e.message ?: e.toString())
                stopSelfCompat()
            }
        }
    }

    /**
     * Opens a new TUN with the settings as they are now: the core moves to
     * it and closes the old one, without stopping.
     */
    private fun restart() {
        if (Core.state.value != VpnState.On) return
        scope.launch {
            try {
                Core.setState(VpnState.Starting)
                val tun = establish()
                Core.start(tun.detachFd(), systemDNS(), Core.options(this@ClashVpnService))
                Logs.start(Settings(this@ClashVpnService).logLevel)
                Core.setState(VpnState.On)
            } catch (e: Exception) {
                Log.e(TAG, "restart", e)
                Core.stop()
                Core.vpn = null
                Core.setState(VpnState.Off, e.message ?: e.toString())
                stopSelfCompat()
            }
        }
    }

    private fun establish(): ParcelFileDescriptor {
        val s = Settings(this)
        val b = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(MTU)
            .addAddress(TUN_IPV4, 30)
            .addRoute("0.0.0.0", 0)
            .addDnsServer(TUN_DNS)
            .setBlocking(false)
            .setConfigureIntent(mainIntent())
        if (s.ipv6) {
            b.addAddress(TUN_IPV6, 126).addRoute("::", 0)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) b.setMetered(false)
        val apps = s.apps - packageName
        when (s.appMode) {
            // Only the chosen apps: the app itself is not among them.
            Settings.APPS_ALLOW -> apps.forEach { runCatching { b.addAllowedApplication(it) } }
            else -> {
                // The app's own traffic goes around the VPN: the core's
                // connections, and the downloads of subscriptions and geo
                // data while the core is still starting, before it reads
                // the TUN.
                b.addDisallowedApplication(packageName)
                if (s.appMode == Settings.APPS_DENY) {
                    // An app uninstalled since it was chosen is skipped.
                    apps.forEach { runCatching { b.addDisallowedApplication(it) } }
                }
            }
        }
        return b.establish() ?: throw IllegalStateException(getString(R.string.vpn_denied))
    }

    private fun stop() {
        Logs.stop()
        scope.launch {
            Core.stop() // closes the TUN
            Core.vpn = null
            Core.setState(VpnState.Off)
            ToggleTileService.refresh(this@ClashVpnService)
            stopSelfCompat()
        }
    }

    override fun onRevoke() {
        // Another VPN started, or the user turned this one off in Settings.
        stop()
    }

    override fun onDestroy() {
        Logs.stop()
        networkCallback?.let { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        networkCallback = null
        if (Core.state.value != VpnState.Off) {
            Core.stop()
            Core.vpn = null
            Core.setState(VpnState.Off)
        }
        scope.cancel()
        super.onDestroy()
    }

    private fun stopSelfCompat() {
        networkCallback?.let { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        networkCallback = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** The DNS servers of the default network that is not a VPN. */
    private fun systemDNS(): List<String> {
        val cm = getSystemService(ConnectivityManager::class.java)
        val net = cm.activeNetwork ?: return emptyList()
        val caps = cm.getNetworkCapabilities(net)
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) return emptyList()
        return cm.getLinkProperties(net)?.let(::dnsOf).orEmpty()
    }

    private fun dnsOf(lp: LinkProperties): List<String> =
        lp.dnsServers.sortedBy { if (it is Inet4Address) 0 else 1 }.mapNotNull { it.hostAddress }

    /**
     * Tells the system which network the VPN runs over, so that apps see
     * the right metering and the VPN follows Wi-Fi and mobile data.
     */
    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                setUnderlyingNetworks(arrayOf(network))
            }

            override fun onLost(network: Network) {
                setUnderlyingNetworks(null)
            }

            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                // Wi-Fi to mobile data, or a new DHCP lease: "system" DNS
                // in profiles follows.
                Core.setDNS(dnsOf(lp))
            }
        }
        // requestNetwork, not registerNetworkCallback: only the best network.
        cm.requestNetwork(request, cb)
        networkCallback = cb
    }

    private fun mainIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
    )

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ClashVpnService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val notification = builder
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(getString(R.string.notification_title))
            .setContentIntent(mainIntent())
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.notification_stop), stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "MyGO-Clash"
        private const val CHANNEL = "vpn"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "io.mygo.clash.android.STOP"
        private const val ACTION_RESTART = "io.mygo.clash.android.RESTART"

        // The TUN's addresses. The core's gvisor stack takes packets of any
        // address, and hijacks DNS on every address, port 53.
        private const val TUN_IPV4 = "172.19.0.1"
        private const val TUN_DNS = "172.19.0.2"
        private const val TUN_IPV6 = "fdfe:dcba:9876::1"
        private const val MTU = 9000

        /** Starts the VPN; [VpnService.prepare] must have been granted. */
        fun start(context: Context) {
            val intent = Intent(context, ClashVpnService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Applies changed settings to the running VPN. */
        fun restart(context: Context) {
            if (Core.state.value == VpnState.On) {
                context.startService(Intent(context, ClashVpnService::class.java).setAction(ACTION_RESTART))
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ClashVpnService::class.java).setAction(ACTION_STOP))
        }
    }
}
