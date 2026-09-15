package com.sensorstream.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sensorstream.ui.components.SsIcons
import com.sensorstream.ui.nav.AppNav
import com.sensorstream.ui.nav.Screen
import com.sensorstream.ui.nav.rememberAppNav
import com.sensorstream.ui.screens.ConnectionScreen
import com.sensorstream.ui.screens.HomeScreen
import com.sensorstream.ui.screens.SensorDetailScreen
import com.sensorstream.ui.screens.SensorsScreen
import com.sensorstream.ui.theme.Ss
import com.sensorstream.vm.StreamViewModel

private data class Tab(val screen: Screen, val label: String, val iconKey: String)

private val TABS = listOf(
    Tab(Screen.Home, "Home", "home"),
    Tab(Screen.Sensors, "Sensors", "sensors"),
    Tab(Screen.Connection, "Connection", "connection"),
    Tab(Screen.Settings, "Settings", "settings"),
)

@Composable
fun AppScaffold(vm: StreamViewModel) {
    val nav: AppNav = rememberAppNav()
    val current = nav.current

    BackHandler(enabled = true) { nav.back() }

    Scaffold(
        containerColor = Ss.colors.bg,
        bottomBar = {
            NavigationBar(containerColor = Ss.colors.surface) {
                TABS.forEach { tab ->
                    val selected = current::class == tab.screen::class
                    NavigationBarItem(
                        selected = selected,
                        onClick = { nav.go(tab.screen) },
                        icon = { Icon(SsIcons.forKey(tab.iconKey), contentDescription = tab.label, modifier = Modifier.size(22.dp)) },
                        label = { Text(tab.label, fontSize = 11.sp, fontWeight = FontWeight.Medium) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Ss.colors.accent,
                            selectedTextColor = Ss.colors.accent,
                            indicatorColor = Ss.colors.accentSoft,
                            unselectedIconColor = Ss.colors.muted,
                            unselectedTextColor = Ss.colors.muted,
                        ),
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad).background(Ss.colors.bg)) {
            AppContent(vm, nav, current)
        }
    }
}

// Screen routing lives in one place so Tasks 7/8 swap placeholders for real screens.
@Composable
private fun AppContent(vm: StreamViewModel, nav: AppNav, current: Screen) {
    when (current) {
        is Screen.Home -> HomeScreen(vm, nav)
        is Screen.Sensors -> SensorsScreen(vm, nav)
        is Screen.Connection -> ConnectionScreen(vm)
        is Screen.Settings -> Placeholder("Settings", "Theme, units and 3D preferences arrive in a later phase.")
        is Screen.Detail -> SensorDetailScreen(vm, nav, current.handle)
    }
}

@Composable
private fun Placeholder(title: String, sub: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, color = Ss.colors.fg, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(sub, color = Ss.colors.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp, start = 32.dp, end = 32.dp))
        }
    }
}
