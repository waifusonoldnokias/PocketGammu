package com.pocketgammu.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.pocketgammu.LocalAppContainer
import com.pocketgammu.core.designsystem.blur.LocalOverlayBlur
import com.pocketgammu.core.designsystem.blur.OverlayBlurState
import com.pocketgammu.core.designsystem.blur.blurBehindOverlays
import com.pocketgammu.core.designsystem.haptics.LocalPgHaptics
import com.pocketgammu.core.designsystem.haptics.rememberPgHaptics
import com.pocketgammu.core.designsystem.theme.PocketGammuTheme
import com.pocketgammu.core.model.UserSettings
import com.pocketgammu.ui.files.FileManagerScreen
import com.pocketgammu.ui.home.HomeScreen
import com.pocketgammu.ui.logo.LogoEditorScreen
import com.pocketgammu.ui.navigation.Destination
import com.pocketgammu.ui.ringtone.RingtoneEditorScreen
import com.pocketgammu.ui.settings.SettingsScreen
import kotlin.reflect.KClass

private data class TopLevel(val route: Destination, val routeClass: KClass<*>, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

private val topLevel = listOf(
    TopLevel(Destination.Home, Destination.Home::class, "Home", Icons.Outlined.Home, Icons.Rounded.Home),
    TopLevel(Destination.Logo, Destination.Logo::class, "Logo", Icons.Outlined.Image, Icons.Rounded.Image),
    TopLevel(Destination.Ringtone, Destination.Ringtone::class, "Ringtone", Icons.Outlined.MusicNote, Icons.Rounded.MusicNote),
    TopLevel(Destination.Files, Destination.Files::class, "Files", Icons.Outlined.Folder, Icons.Rounded.Folder),
)

/** App-wide snackbar so any screen can report results. */
val LocalSnackbar = staticCompositionLocalOf { SnackbarHostState() }

@Composable
fun PocketGammuApp() {
    val container = LocalAppContainer.current
    val settings by container.settings.settings.collectAsStateWithLifecycle(UserSettings())
    val blurState = remember { OverlayBlurState() }
    val snackbar = remember { SnackbarHostState() }

    PocketGammuTheme(themeMode = settings.themeMode, palette = settings.palette) {
        CompositionLocalProvider(
            LocalPgHaptics provides rememberPgHaptics(settings.hapticsEnabled),
            LocalOverlayBlur provides blurState,
            LocalSnackbar provides snackbar,
        ) {
            AppScaffold(snackbar)
        }
    }
}

@Composable
private fun AppScaffold(snackbar: SnackbarHostState) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination
    val haptics = LocalPgHaptics.current
    val showBar = topLevel.any { t -> current?.hasRoute(t.routeClass) == true }

    Box(Modifier.fillMaxSize().blurBehindOverlays()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (showBar) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                        topLevel.forEach { item ->
                            val selected = current?.hasRoute(item.routeClass) == true
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    if (!selected) {
                                        haptics.tick()
                                        nav.navigate(item.route) {
                                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                },
                                icon = { Icon(if (selected) item.selectedIcon else item.icon, null) },
                                label = { Text(item.label) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            NavHost(nav, startDestination = Destination.Home, modifier = Modifier.padding(padding)) {
                composable<Destination.Home> {
                    HomeScreen(onOpenSettings = { nav.navigate(Destination.Settings) })
                }
                composable<Destination.Logo> { LogoEditorScreen(onGoHome = { nav.navigate(Destination.Home) }) }
                composable<Destination.Ringtone> { RingtoneEditorScreen(onGoHome = { nav.navigate(Destination.Home) }) }
                composable<Destination.Files> { FileManagerScreen(onGoHome = { nav.navigate(Destination.Home) }) }
                composable<Destination.Settings> { SettingsScreen(onBack = { nav.popBackStack() }) }
            }
        }
    }
}
