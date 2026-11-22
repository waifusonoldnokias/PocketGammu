package com.pocketgammu.core.model

enum class ThemeMode(val label: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark"),
}

/** Selectable colour schemes. SYSTEM = dynamic colour on Android 12+, Pastel Blue below. */
enum class ColorPalette(val label: String, val description: String) {
    SYSTEM("System", "Dynamic colour"),
    TETO("Teto", "Red & grey"),
    MIKU("Miku", "Turquoise & red-pink"),
    NERU("Neru", "Yellow & grey"),
    GUMI("Gumi", "Pastel green & red"),
    REI("Rei", "Orange, white & grey"),
}

data class UserSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val palette: ColorPalette = ColorPalette.SYSTEM,
    val hapticsEnabled: Boolean = true,
)

/** Values remembered between sessions to save typing on the send sheets / home page. */
data class SessionDefaults(
    val connectionTypeId: String? = null,
    val phoneFamilyId: String? = null,
    /** Power the cable from DTR/RTS (DAU-9P and most FBUS cables need it). */
    val powerCable: Boolean = true,
    val networkCode: String = "",
    val recipient: String = "",
)
