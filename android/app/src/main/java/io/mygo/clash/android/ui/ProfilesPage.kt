package io.mygo.clash.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.mygo.clash.android.Core
import io.mygo.clash.android.Import
import io.mygo.clash.android.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/** A profile, as the Go side lists it. */
data class ProfileItem(
    val uid: String,
    val name: String,
    val type: String,
    val updated: Long,
    val proxies: Int,
    val used: Long,
    val total: Long,
    val expire: Long,
    val lastError: String,
)

private fun itemOf(o: JSONObject): ProfileItem {
    val usage = o.optJSONObject("usage")
    return ProfileItem(
        uid = o.optString("uid"),
        name = o.optString("name"),
        type = o.optString("type"),
        updated = o.optLong("updated"),
        proxies = o.optInt("proxies"),
        used = (usage?.optLong("upload") ?: 0) + (usage?.optLong("download") ?: 0),
        total = usage?.optLong("total") ?: 0,
        expire = usage?.optLong("expire") ?: 0,
        lastError = o.optString("lastError"),
    )
}

/** The profiles, and what is being done to them. */
@Stable
class ProfilesState(private val scope: CoroutineScope) {
    var items by mutableStateOf<List<ProfileItem>>(emptyList())
    var current by mutableStateOf("")
    var busy by mutableStateOf<String?>(null) // the uid being worked on, or "add"
    var error by mutableStateOf<String?>(null)
    var adding by mutableStateOf<Import?>(null) // the add dialog, when shown

    fun reload() = scope.launch { load() }

    suspend fun load() {
        runCatching { Core.profiles() }
            .onSuccess { idx ->
                val arr = idx.optJSONArray("items")
                items = (0 until (arr?.length() ?: 0)).map { itemOf(arr!!.getJSONObject(it)) }
                    .filter { it.type == "remote" || it.type == "local" }
                current = idx.optString("current")
            }
            .onFailure { error = it.message }
    }

    /** Runs a change of the profiles, then lists them again. */
    fun run(key: String, block: suspend () -> Unit) {
        if (busy != null) return
        busy = key
        error = null
        scope.launch {
            runCatching { block() }.onFailure { error = it.message ?: it.toString() }
            load()
            busy = null
        }
    }
}

@Composable
fun rememberProfilesState(): ProfilesState {
    val scope = rememberCoroutineScope()
    return remember { ProfilesState(scope) }
}

@Composable
fun ProfilesActions(state: ProfilesState) {
    IconButton(onClick = { state.adding = Import("", "") }, modifier = Modifier.padding(end = 12.dp)) {
        Icon(MiuixIcons.Add, contentDescription = stringResource(R.string.add_profile))
    }
}

@Composable
fun ProfilesPage(
    state: ProfilesState,
    behavior: ScrollBehavior,
    padding: PaddingValues,
    pendingImport: Import?,
    onImportHandled: () -> Unit,
) {
    LaunchedEffect(Unit) { state.load() }
    LaunchedEffect(pendingImport) {
        if (pendingImport != null) {
            state.adding = pendingImport
            onImportHandled()
        }
    }

    PageList(behavior, padding) {
        state.error?.let { item { Text(it, color = MiuixTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) } }
        if (state.items.isEmpty()) {
            item { Hint(stringResource(R.string.profiles_empty)) }
        }
        item { Spacer(Modifier.height(12.dp)) }
        state.items.forEach { p ->
            item(key = p.uid) {
                ProfileCard(p, current = p.uid == state.current, busy = state.busy == p.uid, state)
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    state.adding?.let { AddDialog(state, it) }
}

@Composable
private fun ProfileCard(p: ProfileItem, current: Boolean, busy: Boolean, state: ProfilesState) {
    val summary = buildList {
        if (busy) add(stringResource(R.string.working))
        if (current) add(stringResource(R.string.current))
        add(stringResource(R.string.proxies_count, p.proxies))
        if (p.total > 0) add(stringResource(R.string.usage, bytes(p.used), bytes(p.total)))
        if (p.expire > 0) add(stringResource(R.string.expires, date(p.expire)))
        if (p.updated > 0) add(stringResource(R.string.updated_at, dateTime(p.updated)))
    }.joinToString(" · ")
    Card(
        onClick = { if (!current) state.run(p.uid) { Core.selectProfile(p.uid) } },
        colors = if (current) {
            top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(color = MiuixTheme.colorScheme.primaryContainer)
        } else {
            top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors()
        },
    ) {
        BasicComponent(
            title = p.name,
            summary = summary + if (p.lastError.isNotEmpty()) "\n" + p.lastError else "",
            endActions = {
                if (p.type == "remote") {
                    IconButton(onClick = { state.run(p.uid) { Core.updateProfile(p.uid) } }) {
                        Icon(MiuixIcons.Refresh, contentDescription = stringResource(R.string.update))
                    }
                }
                IconButton(onClick = { state.run(p.uid) { Core.deleteProfile(p.uid) } }) {
                    Icon(MiuixIcons.Delete, contentDescription = stringResource(R.string.delete))
                }
            },
        )
    }
}

@Composable
private fun AddDialog(state: ProfilesState, initial: Import) {
    var url by remember(initial) { mutableStateOf(initial.url) }
    var name by remember(initial) { mutableStateOf(initial.name) }
    val adding = state.busy == "add"
    WindowDialog(
        show = true,
        title = stringResource(R.string.add_profile),
        onDismissRequest = { if (!adding) state.adding = null },
    ) {
        Column {
            TextField(value = url, onValueChange = { url = it }, label = stringResource(R.string.profile_url), singleLine = true)
            Spacer(Modifier.height(12.dp))
            TextField(value = name, onValueChange = { name = it }, label = stringResource(R.string.profile_name), singleLine = true)
            state.error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MiuixTheme.colorScheme.error)
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(
                    text = stringResource(R.string.cancel),
                    onClick = { state.adding = null },
                    enabled = !adding,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = if (adding) stringResource(R.string.working) else stringResource(R.string.add),
                    onClick = {
                        state.run("add") {
                            Core.addProfile(url, name)
                            state.adding = null
                        }
                    },
                    enabled = !adding && url.isNotBlank(),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
