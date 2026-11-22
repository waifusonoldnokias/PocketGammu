package com.pocketgammu.ui.navigation

import kotlinx.serialization.Serializable

/** Type-safe navigation routes. */
sealed interface Destination {
    @Serializable data object Home : Destination
    @Serializable data object Logo : Destination
    @Serializable data object Ringtone : Destination
    @Serializable data object Files : Destination
    @Serializable data object Settings : Destination
}
