package io.mygo.clash.android.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.mygo.clash.android.Core
import io.mygo.clash.android.R
import io.mygo.clash.android.VpnState
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextField

private data class Rule(val type: String, val payload: String, val proxy: String)

/** The rules of the running configuration, in order. */
@Composable
fun RulesPage(behavior: ScrollBehavior, padding: PaddingValues) {
    val state by Core.state.collectAsState()
    var rules by remember { mutableStateOf<List<Rule>>(emptyList()) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(state) {
        rules = if (state == VpnState.On) {
            runCatching {
                val arr = Core.rules()
                (0 until arr.length()).map { arr.getJSONObject(it) }
                    .map { Rule(it.optString("type"), it.optString("payload"), it.optString("proxy")) }
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
    }
    val shown = if (query.isBlank()) rules else rules.filter {
        it.payload.contains(query, true) || it.proxy.contains(query, true) || it.type.contains(query, true)
    }

    PageList(behavior, padding) {
        if (state != VpnState.On) {
            item { Hint(stringResource(R.string.proxies_off)) }
            return@PageList
        }
        item {
            Spacer(Modifier.height(12.dp))
            TextField(value = query, onValueChange = { query = it }, label = stringResource(R.string.search_rules), singleLine = true)
            SmallTitle(stringResource(R.string.rules_summary, rules.size))
        }
        itemsIndexed(shown) { _, r ->
            BasicComponent(title = r.payload.ifEmpty { r.type }, summary = r.type + "  →  " + r.proxy)
        }
    }
}
