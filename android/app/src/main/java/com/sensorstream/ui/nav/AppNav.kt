package com.sensorstream.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember

/** Top-level and detail destinations. Kept as a tiny sealed model so no nav dependency is needed. */
sealed interface Screen {
    data object Home : Screen
    data object Sensors : Screen
    data object Connection : Screen
    data object Settings : Screen
    data object Diagnostics : Screen
    data class Detail(val handle: Int) : Screen
}

/** Minimal back-stack navigator. Top-level tabs replace the root; details push on top. */
class AppNav(initial: Screen = Screen.Home) {
    private val stack = mutableStateListOf(initial)
    val current: Screen get() = stack.last()

    /** The bottom-nav tab a pushed screen (Detail/Diagnostics) belongs under, so it stays lit. */
    val activeTab: Screen get() = stack.lastOrNull { isTab(it) } ?: Screen.Home

    private fun isTab(s: Screen) =
        s is Screen.Home || s is Screen.Sensors || s is Screen.Connection || s is Screen.Settings

    fun go(screen: Screen) {
        if (isTab(screen)) {
            stack.clear()
            stack.add(screen)
        } else {
            stack.add(screen)
        }
    }

    /** True when a back press has somewhere to go inside the app: a pushed screen to pop, or a
     *  non-Home tab to return from. False on Home -> the system handles back (exits the app).
     *  Reads snapshot state, so a BackHandler keyed on it updates automatically. */
    val canGoBack: Boolean get() = stack.size > 1 || stack.last() != Screen.Home

    /** Pop a pushed screen, else fall back from a non-Home tab to Home. Returns false at Home. */
    fun back(): Boolean {
        if (stack.size > 1) {
            stack.removeAt(stack.lastIndex)
            return true
        }
        if (stack.last() != Screen.Home) {
            go(Screen.Home)
            return true
        }
        return false
    }
}

@Composable
fun rememberAppNav(): AppNav = remember { AppNav() }
