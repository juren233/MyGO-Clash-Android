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
    val appsBehavior = MiuixScrollBehavior()
    val current = Page.entries[page]
    val profiles = rememberProfilesState()
    // The app chooser, over the settings.
    var choosingApps by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    BackHandler(enabled = choosingApps) { choosingApps = false }

    Scaffold(
        topBar = {
            if (choosingApps && current == Page.Settings) {
                TopAppBar(
                    title = stringResource(R.string.choose_apps),
                    scrollBehavior = appsBehavior,
                    navigationIcon = {
                        IconButton(onClick = { choosingApps = false }, modifier = Modifier.padding(start = 12.dp)) {
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
                        onClick = { page = it.ordinal },
                        icon = icons[it.ordinal],
                        label = titles[it.ordinal],
                    )
                }
            }
        },
    ) { padding ->
        when (current) {
            Page.Home -> HomePage(behaviors[page], padding, onToggle)
            Page.Proxies -> ProxiesPage(behaviors[page], padding)
            Page.Profiles -> ProfilesPage(profiles, behaviors[page], padding, pendingImport, onImportHandled)
            Page.Settings -> if (choosingApps) {
                AppsPage(appsBehavior, padding, onChanged = { ClashVpnService.restart(context) })
            } else {
                SettingsPage(behaviors[page], padding, onChooseApps = { choosingApps = true })
            }
        }
    }
}

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
