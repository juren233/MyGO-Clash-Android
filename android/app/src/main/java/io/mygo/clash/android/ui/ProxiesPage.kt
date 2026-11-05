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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.mygo.clash.android.Core
import io.mygo.clash.android.R
import io.mygo.clash.android.VpnState
import kotlinx.coroutines.launch
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** A group of proxies, as the core reports it. */
private data class Group(val name: String, val type: String, val now: String, val all: List<String>)

private val groupTypes = setOf("Selector", "URLTest", "Fallback", "LoadBalance", "Relay")

/** The groups in the order of the profile: the order of GLOBAL's members. */
private fun groupsOf(proxies: JSONObject): List<Group> {
    fun group(name: String): Group? {
        val p = proxies.optJSONObject(name) ?: return null
        if (p.optString("type") !in groupTypes) return null
        val all = p.optJSONArray("all")
        return Group(name, p.optString("type"), p.optString("now"), (0 until (all?.length() ?: 0)).map { all!!.getString(it) })
    }
    val order = proxies.optJSONObject("GLOBAL")?.optJSONArray("all")
    val names = (0 until (order?.length() ?: 0)).map { order!!.getString(it) }
    val groups = names.mapNotNull(::group)
    // GLOBAL itself matters in global mode only; it comes last.
    return groups + listOfNotNull(group("GLOBAL"))
}

@Composable
fun ProxiesPage(behavior: ScrollBehavior, padding: PaddingValues) {
    val state by Core.state.collectAsState()
    val scope = rememberCoroutineScope()
    var groups by remember { mutableStateOf<List<Group>>(emptyList()) }
    var types by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var error by remember { mutableStateOf<String?>(null) }
    val delays = remember { mutableStateMapOf<String, Int>() }
    var testing by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
        try {
            val p = Core.proxies()
            groups = groupsOf(p)
            types = p.keys().asSequence().associateWith { p.getJSONObject(it).optString("type") }
            error = null
        } catch (e: Exception) {
            error = e.message
        }
    }

    LaunchedEffect(state) {
        if (state == VpnState.On) load() else groups = emptyList()
    }

    PageList(behavior, padding) {
        if (state != VpnState.On) {
            item { Hint(stringResource(R.string.proxies_off)) }
            return@PageList
        }
        error?.let { item { Hint(it) } }
        groups.forEach { g ->
            item(key = "title:" + g.name) {
                SmallTitle(g.name + "  ·  " + g.now)
            }
            item(key = "card:" + g.name) {
                Card {
                    TextButton(
                        text = if (testing == g.name) stringResource(R.string.working) else stringResource(R.string.test_delay),
                        enabled = testing == null,
                        onClick = {
                            testing = g.name
                            scope.launch {
                                runCatching { Core.groupDelay(g.name) }.onSuccess { res ->
                                    g.all.forEach { delays[it] = res[it] ?: -1 }
                                }
                                testing = null
                            }
                        },
                        modifier = Modifier.padding(12.dp),
                    )
                    g.all.forEach { name ->
                        BasicComponent(
                            title = name,
                            summary = types[name],
                            onClick = if (g.type == "Selector") {
                                {
                                    scope.launch {
                                        runCatching { Core.select(g.name, name) }
                                        load()
                                    }
                                }
                            } else {
                                null
                            },
                            endActions = {
                                delays[name]?.let { Delay(it) }
                                if (name == g.now) {
                                    Icon(MiuixIcons.Basic.Check, contentDescription = null, tint = MiuixTheme.colorScheme.primary)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Delay(ms: Int) {
    val color = when {
        ms <= 0 -> MiuixTheme.colorScheme.error
        ms < 300 -> Color(0xFF34A853)
        ms < 800 -> Color(0xFFE8A33D)
        else -> MiuixTheme.colorScheme.error
    }
    Text(
        text = if (ms <= 0) stringResource(R.string.timeout) else "$ms ms",
        color = color,
        modifier = Modifier.padding(end = 8.dp),
    )
}

@Composable
fun Hint(text: String) {
    Text(
        text = text,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(24.dp),
    )
}
