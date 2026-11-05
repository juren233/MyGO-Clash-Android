package io.mygo.clash.android.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.mygo.clash.android.Core
import io.mygo.clash.android.R
import io.mygo.clash.android.VpnState
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val views = listOf("domestic", "global", "cloudflare")

/** The exit IP as sites see it, and the unlocks of services. */
@Composable
fun ChecksPage(behavior: ScrollBehavior, padding: PaddingValues) {
    val state by Core.state.collectAsState()
    val ips = remember { mutableStateMapOf<String, String>() }
    val unlocks = remember {
        mutableStateMapOf<String, JSONObject>().apply {
            val list = Core.unlockList()
            for (i in 0 until list.length()) list.getJSONObject(i).let { put(it.optString("id"), it) }
        }
    }
    val order = remember { unlocks.keys.toList() }
    var checking by remember { mutableStateOf(false) }

    LaunchedEffect(state) {
        if (state != VpnState.On) return@LaunchedEffect
        views.forEach { v ->
            ips[v] = runCatching {
                val o = Core.exitIP(v)
                listOf(o.optString("ip"), listOf(o.optString("country"), o.optString("city")).filter(String::isNotEmpty).joinToString(" "), o.optString("isp"))
                    .filter(String::isNotEmpty).joinToString(" · ")
            }.getOrElse { it.message ?: it.toString() }
        }
    }

    PageList(behavior, padding) {
        if (state != VpnState.On) {
            item { Hint(stringResource(R.string.checks_need_port)) }
            return@PageList
        }
        item {
            SmallTitle(stringResource(R.string.exit_ip))
            Card {
                listOf(R.string.view_domestic, R.string.view_global, R.string.view_cloudflare).forEachIndexed { i, label ->
                    BasicComponent(title = stringResource(label), summary = ips[views[i]] ?: stringResource(R.string.working))
                }
            }
        }
        item {
            SmallTitle(stringResource(R.string.unlocks))
            Card {
                TextButton(
                    text = if (checking) stringResource(R.string.working) else stringResource(R.string.check),
                    enabled = !checking,
                    onClick = {
                        checking = true
                        runCatching {
                            Core.checkUnlock(onResult = { unlocks[it.optString("id")] = it }, onDone = { checking = false })
                        }.onFailure { checking = false }
                    },
                    modifier = Modifier.padding(12.dp),
                )
                order.forEach { id ->
                    val u = unlocks[id] ?: return@forEach
                    BasicComponent(
                        title = u.optString("name"),
                        summary = u.optString("detail").ifEmpty { null },
                        endActions = { UnlockStatus(u.optString("status"), u.optString("region")) },
                    )
                }
            }
        }
    }
}

@Composable
private fun UnlockStatus(status: String, region: String) {
    val (label, color) = when (status) {
        "yes" -> stringResource(R.string.unlock_yes) to Color(0xFF34A853)
        "partial" -> stringResource(R.string.unlock_partial) to Color(0xFFE8A33D)
        "no" -> stringResource(R.string.unlock_no) to MiuixTheme.colorScheme.error
        "failed" -> stringResource(R.string.unlock_failed) to MiuixTheme.colorScheme.error
        else -> stringResource(R.string.unlock_pending) to MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    Text(text = if (region.isNotEmpty()) "$label · $region" else label, color = color)
}
