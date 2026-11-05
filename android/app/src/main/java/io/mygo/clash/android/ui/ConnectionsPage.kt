package io.mygo.clash.android.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.mygo.clash.android.Core
import io.mygo.clash.android.R
import io.mygo.clash.android.VpnState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Close

/** An open connection, as /connections reports it. */
private data class Conn(
    val id: String,
    val host: String,
    val network: String,
    val chain: String,
    val rule: String,
    val process: String,
    val up: Long,
    val down: Long,
)

private fun connsOf(o: JSONObject): List<Conn> {
    val arr = o.optJSONArray("connections") ?: return emptyList()
    return (0 until arr.length()).map { arr.getJSONObject(it) }.map { c ->
        val m = c.optJSONObject("metadata") ?: JSONObject()
        val host = m.optString("host").ifEmpty { m.optString("destinationIP") }
        val chains = c.optJSONArray("chains")
        Conn(
            id = c.optString("id"),
            host = host + ":" + m.optString("destinationPort"),
            network = m.optString("network"),
            // chains are listed from the proxy to the group.
            chain = (0 until (chains?.length() ?: 0)).map { chains!!.getString(it) }.reversed().joinToString(" → "),
            rule = c.optString("rule") + c.optString("rulePayload").let { if (it.isEmpty()) "" else "($it)" },
            process = m.optString("process"),
            up = c.optLong("upload"),
            down = c.optLong("download"),
        )
    }.sortedByDescending { it.up + it.down }
}

/** The open connections, refreshed every second while the page shows. */
@Composable
fun ConnectionsPage(behavior: ScrollBehavior, padding: PaddingValues) {
    val state by Core.state.collectAsState()
    val scope = rememberCoroutineScope()
    var conns by remember { mutableStateOf<List<Conn>>(emptyList()) }

    LaunchedEffect(state) {
        while (state == VpnState.On) {
            runCatching { connsOf(JSONObject(Core.request("GET", "/connections"))) }.onSuccess { conns = it }
            delay(1000)
        }
        conns = emptyList()
    }

    PageList(behavior, padding) {
        item {
            Spacer(Modifier.height(12.dp))
            if (conns.isEmpty()) {
                Hint(stringResource(if (state == VpnState.On) R.string.no_connections else R.string.proxies_off))
            } else {
                TextButton(
                    text = stringResource(R.string.close_all),
                    onClick = { scope.launch { runCatching { Core.request("DELETE", "/connections") } } },
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
        }
        items(conns, key = { it.id }) { c ->
            Card(modifier = Modifier.padding(bottom = 8.dp)) {
                BasicComponent(
                    title = c.host,
                    summary = listOf(
                        c.network.uppercase() + (if (c.process.isNotEmpty()) " · " + c.process else ""),
                        c.chain + "  ·  " + c.rule,
                        "↑ " + bytes(c.up) + "  ↓ " + bytes(c.down),
                    ).joinToString("\n"),
                    endActions = {
                        IconButton(onClick = {
                            scope.launch { runCatching { Core.request("DELETE", "/connections/" + c.id) } }
                        }) {
                            Icon(MiuixIcons.Basic.Close, contentDescription = null)
                        }
                    },
                )
            }
        }
    }
}
