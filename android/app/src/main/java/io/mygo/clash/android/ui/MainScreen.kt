package io.mygo.clash.android.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import io.mygo.clash.android.ClashVpnService
import io.mygo.clash.android.Import
import io.mygo.clash.android.R
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Layers
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.utils.overScrollVertical

private enum class Page { Home, Proxies, Profiles, Settings }

@Composable
fun MainScreen(onToggle: (Boolean) -> Unit, pendingImport: Import?, onImportHandled: () -> Unit) {
    var page by rememberSaveable { mutableIntStateOf(Page.Home.ordinal) }
    // A link to a subscription opens the profiles, with the dialog.
    if (pendingImport != null && page != Page.Profiles.ordinal) page = Page.Profiles.ordinal

    val titles = listOf(
        stringResource(R.string.tab_home),
        stringResource(R.string.tab_proxies),
        stringResource(R.string.tab_profiles),
        stringResource(R.string.tab_settings),
    )
    val icons = listOf(MiuixIcons.Home, MiuixIcons.Layers, MiuixIcons.ListView, MiuixIcons.Settings)
    val behaviors = Page.entries.map { MiuixScrollBehavior() }
    val subBehavior = MiuixScrollBehavior()
    val current = Page.entries[page]
    val profiles = rememberProfilesState()
    // A page over a tab: the app chooser of the settings, the connections
    // and the logs of the home page.
    var sub by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    BackHandler(enabled = sub != null) { sub = null }
    val subTitle = when (sub) {
        SUB_APPS -> stringResource(R.string.choose_apps)
        SUB_CONNECTIONS -> stringResource(R.string.connections)
        SUB_LOGS -> stringResource(R.string.logs)
        SUB_CHECKS -> stringResource(R.string.checks)
        SUB_RULES -> stringResource(R.string.rules)
        else -> null
    }

    Scaffold(
        topBar = {
            if (subTitle != null) {
                TopAppBar(
                    title = subTitle,
                    scrollBehavior = subBehavior,
                    navigationIcon = {
                        IconButton(onClick = { sub = null }, modifier = Modifier.padding(start = 12.dp)) {
                            Icon(MiuixIcons.Back, contentDescription = stringResource(R.string.back))
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = if (current == Page.Home) stringResource(R.string.app_name) else titles[page],
                    scrollBehavior = behaviors[page],
                    actions = { if (current == Page.Profiles) ProfilesActions(profiles) },
                )
            }
        },
        bottomBar = {
            NavigationBar {
                Page.entries.forEach {
                    NavigationBarItem(
                        selected = it == current,
                        onClick = {
                            page = it.ordinal
                            sub = null
                        },
                        icon = icons[it.ordinal],
                        label = titles[it.ordinal],
                    )
                }
            }
        },
    ) { padding ->
        when (sub) {
            SUB_APPS -> AppsPage(subBehavior, padding, onChanged = { ClashVpnService.restart(context) })
            SUB_CONNECTIONS -> ConnectionsPage(subBehavior, padding)
            SUB_LOGS -> LogsPage(subBehavior, padding)
            SUB_CHECKS -> ChecksPage(subBehavior, padding)
            SUB_RULES -> RulesPage(subBehavior, padding)
            else -> when (current) {
                Page.Home -> HomePage(behaviors[page], padding, onToggle, onOpen = { sub = it })
                Page.Proxies -> ProxiesPage(behaviors[page], padding)
                Page.Profiles -> ProfilesPage(profiles, behaviors[page], padding, pendingImport, onImportHandled)
                Page.Settings -> SettingsPage(behaviors[page], padding, onChooseApps = { sub = SUB_APPS })
            }
        }
    }
}

const val SUB_APPS = "apps"
const val SUB_CONNECTIONS = "connections"
const val SUB_LOGS = "logs"
const val SUB_CHECKS = "checks"
const val SUB_RULES = "rules"

/** The scrolling list of a page, which collapses the large title. */
@Composable
fun PageList(behavior: ScrollBehavior, padding: PaddingValues, content: LazyListScope.() -> Unit) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .overScrollVertical()
            .nestedScroll(behavior.nestedScrollConnection)
            .padding(horizontal = 12.dp),
        contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 12.dp),
        content = content,
    )
}
