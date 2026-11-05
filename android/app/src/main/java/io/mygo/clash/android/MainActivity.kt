package io.mygo.clash.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import io.mygo.clash.android.ui.MainScreen
import io.mygo.clash.android.ui.MygoTheme

class MainActivity : ComponentActivity() {
    /** A subscription from a clash://install-config link, to confirm. */
    private val pendingImport = mutableStateOf<Import?>(null)

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            ClashVpnService.start(this)
        } else {
            Toast.makeText(this, R.string.vpn_denied, Toast.LENGTH_LONG).show()
        }
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        // The VPN runs without the notification too; nothing to do.
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handle(intent)
        setContent {
            MygoTheme {
                MainScreen(
                    onToggle = ::toggle,
                    pendingImport = pendingImport.value,
                    onImportHandled = { pendingImport.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val uri: Uri = intent?.data ?: return
        if (uri.host != "install-config") return
        val url = uri.getQueryParameter("url") ?: return
        pendingImport.value = Import(url, uri.getQueryParameter("name").orEmpty())
    }

    private fun toggle(on: Boolean) {
        if (!on) {
            ClashVpnService.stop(this)
            return
        }
        Core.clearError()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val ask = VpnService.prepare(this)
        if (ask != null) vpnPermission.launch(ask) else ClashVpnService.start(this)
    }
}

/** A subscription to add: from a link, or typed. */
data class Import(val url: String, val name: String)
