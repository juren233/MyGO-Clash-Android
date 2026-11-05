package io.mygo.clash.android.ui

import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as SystemSettings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.mygo.clash.android.ClashVpnService
import io.mygo.clash.android.R
import io.mygo.clash.android.Settings
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.window.WindowDialog

private val stacks = listOf("gvisor", "mixed", "system")
private val logLevels = listOf("debug", "info", "warning", "error", "silent")
private val appModes = listOf(Settings.APPS_ALL, Settings.APPS_ALLOW, Settings.APPS_DENY)

@Composable
fun SettingsPage(behavior: ScrollBehavior, padding: PaddingValues, onChooseApps: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { Settings(context) }
    // A change of the TUN reaches the running VPN by a restart of the TUN.
    val applied = stringResource(R.string.applies_on_restart)
    fun changed() {
        ClashVpnService.restart(context)
        if (io.mygo.clash.android.Core.state.value == io.mygo.clash.android.VpnState.On) {
            Toast.makeText(context, applied, Toast.LENGTH_SHORT).show()
        }
    }

    var stack by remember { mutableStateOf(settings.stack) }
    var ipv6 by remember { mutableStateOf(settings.ipv6) }
    var port by remember { mutableStateOf(settings.mixedPort) }
    var lan by remember { mutableStateOf(settings.allowLan) }
    var logLevel by remember { mutableStateOf(settings.logLevel) }
    var appMode by remember { mutableStateOf(settings.appMode) }
    var boot by remember { mutableStateOf(settings.startOnBoot) }
    var editingPort by remember { mutableStateOf(false) }
    val chosen = remember(appMode) { settings.apps.size }

    PageList(behavior, padding) {
        item {
            SmallTitle(stringResource(R.string.section_network))
            Card {
                WindowDropdownPreference(
                    items = stacks,
                    selectedIndex = stacks.indexOf(stack).coerceAtLeast(0),
                    title = stringResource(R.string.stack),
                    summary = stringResource(R.string.stack_summary),
                    onSelectedIndexChange = {
                        stack = stacks[it]
                        settings.stack = stack
                        changed()
                    },
                )
                SwitchPreference(
                    checked = ipv6,
                    onCheckedChange = {
                        ipv6 = it
                        settings.ipv6 = it
                        changed()
                    },
                    title = stringResource(R.string.ipv6),
                    summary = stringResource(R.string.ipv6_summary),
                )
                ArrowPreference(
                    title = stringResource(R.string.mixed_port),
                    summary = stringResource(R.string.mixed_port_summary, port.toString()),
                    onClick = { editingPort = true },
                )
                SwitchPreference(
                    checked = lan,
                    onCheckedChange = {
                        lan = it
                        settings.allowLan = it
                        changed()
                    },
                    title = stringResource(R.string.allow_lan),
                    summary = stringResource(R.string.allow_lan_summary),
                )
            }
        }
        item {
            SmallTitle(stringResource(R.string.section_apps))
            Card {
                WindowDropdownPreference(
                    items = listOf(
                        stringResource(R.string.app_mode_all),
                        stringResource(R.string.app_mode_allow),
                        stringResource(R.string.app_mode_deny),
                    ),
                    selectedIndex = appModes.indexOf(appMode).coerceAtLeast(0),
                    title = stringResource(R.string.app_mode),
                    onSelectedIndexChange = {
                        appMode = appModes[it]
                        settings.appMode = appMode
                        changed()
                    },
                )
                if (appMode != Settings.APPS_ALL) {
                    ArrowPreference(
                        title = stringResource(R.string.choose_apps),
                        summary = stringResource(R.string.apps_chosen, chosen),
                        onClick = onChooseApps,
                    )
                }
            }
        }
        item {
            SmallTitle(stringResource(R.string.section_general))
            Card {
                SwitchPreference(
                    checked = boot,
                    onCheckedChange = {
                        boot = it
                        settings.startOnBoot = it
                    },
                    title = stringResource(R.string.start_on_boot),
                    summary = stringResource(R.string.start_on_boot_summary),
                )
                val pm = context.getSystemService(PowerManager::class.java)
                if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                    ArrowPreference(
                        title = stringResource(R.string.battery),
                        summary = stringResource(R.string.battery_summary),
                        onClick = {
                            runCatching {
                                context.startActivity(Intent(SystemSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                            }
                        },
                    )
                }
                WindowDropdownPreference(
                    items = logLevels,
                    selectedIndex = logLevels.indexOf(logLevel).coerceAtLeast(0),
                    title = stringResource(R.string.log_level),
                    onSelectedIndexChange = {
                        logLevel = logLevels[it]
                        settings.logLevel = logLevel
                        changed()
                    },
                )
            }
        }
    }

    if (editingPort) {
        PortDialog(port, onDismiss = { editingPort = false }) {
            port = it
            settings.mixedPort = it
            editingPort = false
            changed()
        }
    }
}

@Composable
private fun PortDialog(port: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var text by remember { mutableStateOf(port.toString()) }
    val value = text.toIntOrNull()?.takeIf { it in 0..65535 && (it == 0 || it >= 1024) }
    WindowDialog(show = true, title = stringResource(R.string.mixed_port), onDismissRequest = onDismiss) {
        Column {
            TextField(
                value = text,
                onValueChange = { text = it.filter(Char::isDigit).take(5) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(text = stringResource(R.string.cancel), onClick = onDismiss, modifier = Modifier.weight(1f))
                TextButton(
                    text = stringResource(R.string.save),
                    onClick = { value?.let(onSave) },
                    enabled = value != null,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
