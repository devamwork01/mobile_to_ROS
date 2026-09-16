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

    /** Returns true if it consumed the back press (popped), false if at the root. */
    fun back(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }
}

@Composable
fun rememberAppNav(): AppNav = remember { AppNav() }
