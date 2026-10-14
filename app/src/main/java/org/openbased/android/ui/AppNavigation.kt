package org.openbased.android.ui

import android.net.Uri
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import org.openbased.android.AppContainer
import org.openbased.android.OpenBasedApp

@Composable
fun rememberContainer(): AppContainer {
    val context = LocalContext.current
    return remember { (context.applicationContext as OpenBasedApp).container }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("home", "Home", Icons.Filled.Home),
    Tab("search", "Search", Icons.Filled.Search),
    Tab("downloads", "Downloads", Icons.Filled.Download),
    Tab("settings", "Settings", Icons.Filled.Settings),
)

object Routes {
    fun library(id: String) = "library/$id"
    fun series(libraryId: String, title: String) = "series/$libraryId/${Uri.encode(title)}"
    fun detail(id: String) = "detail/$id"
}

@Composable
fun AppNavigation(onSignOut: () -> Unit, onNeedNotifications: () -> Unit) {
    val nav = rememberNavController()
    val current by nav.currentBackStackEntryAsState()
    Scaffold(
        bottomBar = {
            NavigationBar {
                TABS.forEach { tab ->
                    NavigationBarItem(
                        selected = current?.destination?.route == tab.route,
                        onClick = { nav.navigateToTab(tab.route) },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = "home", modifier = Modifier.padding(padding)) {
            composable("home") {
                HomeScreen(
                    onOpenLibrary = { nav.navigate(Routes.library(it)) },
                    onOpenItem = { nav.navigate(Routes.detail(it)) },
                )
            }
            composable("search") { SearchScreen(onOpenItem = { nav.navigate(Routes.detail(it)) }) }
            composable("downloads") { DownloadsScreen(onOpenItem = { nav.navigate(Routes.detail(it)) }) }
            composable("settings") { SettingsScreen(onSignOut = onSignOut) }
            composable("library/{id}") { entry ->
                LibraryScreen(
                    libraryId = entry.arguments!!.getString("id")!!,
                    onOpenSeries = { libraryId, title -> nav.navigate(Routes.series(libraryId, title)) },
                    onOpenItem = { nav.navigate(Routes.detail(it)) },
                    onBack = { nav.popBackStack() },
                )
            }
            composable("series/{libraryId}/{title}") { entry ->
                SeriesScreen(
                    libraryId = entry.arguments!!.getString("libraryId")!!,
                    seriesTitle = Uri.decode(entry.arguments!!.getString("title")!!),
                    onOpenItem = { nav.navigate(Routes.detail(it)) },
                    onBack = { nav.popBackStack() },
                )
            }
            composable("detail/{id}") { entry ->
                DetailScreen(
                    mediaId = entry.arguments!!.getString("id")!!,
                    onBack = { nav.popBackStack() },
                    onNeedNotifications = onNeedNotifications,
                )
            }
        }
    }
}

private fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
