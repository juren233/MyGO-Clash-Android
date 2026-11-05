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
import top.yukonga.miuix.kmp.icon.extended.Edit
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
    val url: String,
    val userAgent: String,
    /** Minutes; 0 for the provider's suggestion, -1 for never. */
    val interval: Int,
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
        url = o.optString("url"),
        userAgent = o.optJSONObject("option")?.optString("userAgent").orEmpty(),
        interval = o.optJSONObject("option")?.let { if (it.optBoolean("noAutoUpdate")) -1 else it.optInt("updateInterval") } ?: 0,
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
    var editing by mutableStateOf<ProfileItem?>(null) // the edit dialog, when shown

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
    onEdit: (uid: String, title: String) -> Unit,
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
    state.editing?.let { EditDialog(state, it, onEdit) }
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
                IconButton(onClick = { state.editing = p }) {
                    Icon(MiuixIcons.Edit, contentDescription = stringResource(R.string.edit_profile))
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

private val intervals = listOf(0, 6 * 60, 12 * 60, 24 * 60, 72 * 60, -1)

@Composable
private fun EditDialog(state: ProfilesState, p: ProfileItem, onEdit: (uid: String, title: String) -> Unit) {
    val scope = rememberCoroutineScope()
    val mergeTitle = p.name + " · " + stringResource(R.string.profile_merge)
    val scriptTitle = p.name + " · " + stringResource(R.string.profile_script)
    // Opens the profile's extension of a kind in the editor.
    fun edit(kind: String, title: String) {
        scope.launch {
            runCatching { Core.extensionOf(p.uid, kind) }
                .onSuccess {
                    state.editing = null
                    onEdit(it, title)
                }
                .onFailure { state.error = it.message }
        }
    }
    var name by remember(p) { mutableStateOf(p.name) }
    var url by remember(p) { mutableStateOf(p.url) }
    var ua by remember(p) { mutableStateOf(p.userAgent) }
    var interval by remember(p) { mutableStateOf(if (p.interval in intervals) p.interval else 0) }
    val saving = state.busy == p.uid
    val labels = intervals.map {
        when (it) {
            0 -> stringResource(R.string.interval_auto)
            -1 -> stringResource(R.string.interval_never)
            else -> stringResource(R.string.interval_hours, it / 60)
        }
    }
    WindowDialog(show = true, title = stringResource(R.string.edit_profile), onDismissRequest = { if (!saving) state.editing = null }) {
        Column {
            TextField(value = name, onValueChange = { name = it }, label = stringResource(R.string.profile_name), singleLine = true)
            if (p.type == "remote") {
                Spacer(Modifier.height(12.dp))
                TextField(value = url, onValueChange = { url = it }, label = stringResource(R.string.profile_url), singleLine = true)
                Spacer(Modifier.height(12.dp))
                TextField(value = ua, onValueChange = { ua = it }, label = stringResource(R.string.user_agent), singleLine = true)
                Spacer(Modifier.height(12.dp))
                Card {
                    top.yukonga.miuix.kmp.preference.WindowDropdownPreference(
                        items = labels,
                        selectedIndex = intervals.indexOf(interval).coerceAtLeast(0),
                        title = stringResource(R.string.update_interval),
                        onSelectedIndexChange = { interval = intervals[it] },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(text = stringResource(R.string.profile_merge), onClick = { edit("merge", mergeTitle) }, modifier = Modifier.weight(1f))
                TextButton(text = stringResource(R.string.profile_script), onClick = { edit("script", scriptTitle) }, modifier = Modifier.weight(1f))
            }
            state.error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MiuixTheme.colorScheme.error)
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(
                    text = stringResource(R.string.cancel),
                    onClick = { state.editing = null },
                    enabled = !saving,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = if (saving) stringResource(R.string.working) else stringResource(R.string.save),
                    onClick = {
                        val patch = JSONObject().put("name", name.trim())
                        if (p.type == "remote") {
                            patch.put("url", url.trim()).put("userAgent", ua.trim()).put("updateInterval", interval)
                        }
                        state.run(p.uid) {
                            Core.patchProfile(p.uid, patch)
                            state.editing = null
                        }
                    },
                    enabled = !saving && name.isNotBlank() && (p.type != "remote" || url.isNotBlank()),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
