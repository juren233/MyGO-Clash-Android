package io.mygo.clash.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.mygo.clash.android.Core
import io.mygo.clash.android.R
import io.mygo.clash.android.Settings
import io.mygo.clash.android.VpnState
import kotlinx.coroutines.launch
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val modes = listOf("rule", "global", "direct")

@Composable
fun HomePage(behavior: ScrollBehavior, padding: PaddingValues, onToggle: (Boolean) -> Unit, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by Core.state.collectAsState()
    val error by Core.error.collectAsState()
    var profileName by remember { mutableStateOf<String?>(null) }
    var mode by remember { mutableStateOf(Settings(context).mode) }
    var up by remember { mutableLongStateOf(0) }
    var down by remember { mutableLongStateOf(0) }

    LaunchedEffect(state) {
        profileName = runCatching {
            val idx = Core.profiles()
            val cur = idx.optString("current")
            val items = idx.optJSONArray("items")
            (0 until (items?.length() ?: 0)).map { items!!.getJSONObject(it) }
                .firstOrNull { it.optString("uid") == cur }?.optString("name")
        }.getOrNull()
    }
    // The speed, while the core runs.
    DisposableEffect(state) {
        val stream = if (state == VpnState.On) {
            runCatching {
                Core.subscribe("/traffic") { line ->
                    val o = runCatching { JSONObject(line) }.getOrNull() ?: return@subscribe
                    up = o.optLong("up")
                    down = o.optLong("down")
                }
            }.getOrNull()
        } else {
            null
        }
        onDispose {
            stream?.close()
            up = 0
            down = 0
        }
    }

    PageList(behavior, padding) {
        item {
            StatusCard(state, profileName, error, onToggle)
        }
        item {
            SmallTitle(stringResource(R.string.mode))
            Card {
                TabRow(
                    tabs = listOf(stringResource(R.string.mode_rule), stringResource(R.string.mode_global), stringResource(R.string.mode_direct)),
                    selectedTabIndex = modes.indexOf(mode).coerceAtLeast(0),
                    onTabSelected = { i ->
                        mode = modes[i]
                        scope.launch { runCatching { Core.setMode(context, modes[i]) } }
                    },
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
        item {
            SmallTitle(stringResource(R.string.traffic))
            Card {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Stat(stringResource(R.string.upload), speed(up))
                    Stat(stringResource(R.string.download), speed(down))
                }
            }
        }
        item {
            SmallTitle(stringResource(R.string.tools))
            Card {
                ArrowPreference(title = stringResource(R.string.connections), onClick = { onOpen(SUB_CONNECTIONS) })
                ArrowPreference(title = stringResource(R.string.rules), onClick = { onOpen(SUB_RULES) })
                ArrowPreference(
                    title = stringResource(R.string.checks),
                    summary = stringResource(R.string.checks_summary),
                    onClick = { onOpen(SUB_CHECKS) },
                )
                ArrowPreference(
                    title = stringResource(R.string.logs),
                    summary = stringResource(R.string.logs_summary),
                    onClick = { onOpen(SUB_LOGS) },
                )
            }
        }
        item {
            SmallTitle(stringResource(R.string.about))
            Card {
                BasicComponent(title = stringResource(R.string.core_version), summary = remember { runCatching { Core.version }.getOrDefault("") })
            }
        }
    }
}

@Composable
private fun StatusCard(state: VpnState, profileName: String?, error: String?, onToggle: (Boolean) -> Unit) {
    val on = state == VpnState.On
    val colors = if (on) {
        CardDefaults.defaultColors(color = MiuixTheme.colorScheme.primary, contentColor = Color.White)
    } else {
        CardDefaults.defaultColors()
    }
    Spacer(Modifier.height(12.dp))
    Card(colors = colors, onClick = { if (state != VpnState.Starting) onToggle(!on) }) {
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(
                        when (state) {
                            VpnState.On -> R.string.status_on
                            VpnState.Starting -> R.string.status_starting
                            VpnState.Off -> R.string.status_off
                        },
                    ),
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (on) Color.White else MiuixTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = profileName?.let { stringResource(R.string.vpn_switch_summary, it) } ?: stringResource(R.string.no_profile),
                    color = if (on) Color.White.copy(alpha = 0.85f) else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                if (error != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(text = error, color = MiuixTheme.colorScheme.error)
                }
            }
            Switch(checked = state != VpnState.Off, onCheckedChange = { onToggle(it) }, enabled = state != VpnState.Starting)
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Spacer(Modifier.height(4.dp))
        Text(text = value, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    }
}
