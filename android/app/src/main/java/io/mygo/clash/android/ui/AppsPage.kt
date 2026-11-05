package io.mygo.clash.android.ui

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.mygo.clash.android.R
import io.mygo.clash.android.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.preference.SwitchPreference

/** An installed app. */
private data class AppItem(val pkg: String, val label: String, val system: Boolean, val info: ApplicationInfo)

/** Chooses the apps of the per-app proxy; every change is saved at once. */
@Composable
fun AppsPage(behavior: ScrollBehavior, padding: PaddingValues, onChanged: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { Settings(context) }
    var chosen by remember { mutableStateOf(settings.apps) }
    var query by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }
    val apps by produceState(emptyList<AppItem>()) {
        value = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { it.packageName != context.packageName }
                .map {
                    AppItem(
                        pkg = it.packageName,
                        label = it.loadLabel(pm).toString(),
                        system = it.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                        info = it,
                    )
                }
                .sortedBy { it.label.lowercase() }
        }
    }
    // The chosen apps first, as they were when the page opened.
    val first = remember { settings.apps }
    val shown = apps
        .filter { showSystem || !it.system || it.pkg in first }
        .filter { query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true) }
        .sortedBy { if (it.pkg in first) 0 else 1 }

    LaunchedEffect(chosen) {
        if (chosen != settings.apps) settings.apps = chosen
    }
    // The VPN takes the new apps once, when the page closes.
    DisposableEffect(Unit) {
        onDispose { if (settings.apps != first) onChanged() }
    }

    PageList(behavior, padding) {
        item {
            Spacer(Modifier.height(12.dp))
            TextField(value = query, onValueChange = { query = it }, label = stringResource(R.string.search), singleLine = true)
            Spacer(Modifier.height(12.dp))
            Card {
                SwitchPreference(checked = showSystem, onCheckedChange = { showSystem = it }, title = stringResource(R.string.show_system))
            }
            Spacer(Modifier.height(12.dp))
        }
        items(shown, key = { it.pkg }) { app ->
            val on = app.pkg in chosen
            val toggle = { chosen = if (on) chosen - app.pkg else chosen + app.pkg }
            BasicComponent(
                title = app.label,
                summary = app.pkg,
                startAction = { AppIcon(app.info) },
                endActions = { Checkbox(state = androidx.compose.ui.state.ToggleableState(on), onClick = toggle) },
                onClick = toggle,
            )
        }
    }
}

@Composable
private fun AppIcon(info: ApplicationInfo) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, info.packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val d = info.loadIcon(context.packageManager)
                val size = 96
                val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                d.setBounds(0, 0, size, size)
                d.draw(Canvas(bmp))
                bmp.asImageBitmap()
            }.getOrNull()
        }
    }
    val m = Modifier.padding(end = 12.dp).size(40.dp)
    icon?.let { Image(it, contentDescription = null, modifier = m) } ?: Spacer(m)
}
