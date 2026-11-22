package com.pocketgammu.core.designsystem.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.pocketgammu.core.model.ColorPalette
import com.pocketgammu.core.model.ThemeMode

/** Colours for the Nokia monochrome LCD look used by the editors. */
data class LcdColors(val paper: Color, val ink: Color, val grid: Color)

val LocalLcdColors = staticCompositionLocalOf {
    LcdColors(paper = Color(0xFFC3CFA9), ink = Color(0xFF1E2A16), grid = Color(0x221E2A16))
}

@Composable
fun isDarkTheme(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun colorSchemeFor(palette: ColorPalette, dark: Boolean): ColorScheme = when (palette) {
    ColorPalette.SYSTEM ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val ctx = LocalContext.current
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        } else {
            if (dark) GeneratedSchemes.PastelBlueDark else GeneratedSchemes.PastelBlueLight
        }
    ColorPalette.TETO -> if (dark) GeneratedSchemes.TetoDark else GeneratedSchemes.TetoLight
    ColorPalette.MIKU -> if (dark) GeneratedSchemes.MikuDark else GeneratedSchemes.MikuLight
    ColorPalette.NERU -> if (dark) GeneratedSchemes.NeruDark else GeneratedSchemes.NeruLight
    ColorPalette.GUMI -> if (dark) GeneratedSchemes.GumiDark else GeneratedSchemes.GumiLight
    ColorPalette.REI -> if (dark) GeneratedSchemes.ReiDark else GeneratedSchemes.ReiLight
}

/**
 * Root theme. Every screen and component reads colours, shapes, type and motion from here,
 * so a palette change applies everywhere at once.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PocketGammuTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    palette: ColorPalette = ColorPalette.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark = isDarkTheme(themeMode)
    val scheme = colorSchemeFor(palette, dark)
    val lcd = if (dark) {
        LcdColors(paper = Color(0xFF9DAA86), ink = Color(0xFF141C0F), grid = Color(0x26141C0F))
    } else {
        LcdColors(paper = Color(0xFFC3CFA9), ink = Color(0xFF1E2A16), grid = Color(0x221E2A16))
    }
    SystemBarIcons(dark)
    CompositionLocalProvider(LocalLcdColors provides lcd) {
        MaterialExpressiveTheme(
            colorScheme = scheme,
            motionScheme = MotionScheme.expressive(),
            shapes = PgShapes,
            typography = PgTypography,
            content = content,
        )
    }
}

/**
 * Status and navigation bar icons follow the app's own light/dark choice. enableEdgeToEdge()
 * picks them from the phone's dark mode, which leaves white icons on a light screen when the
 * app is set to Light while the phone is dark (and the reverse).
 */
@Composable
private fun SystemBarIcons(dark: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val window = view.context.findActivity()?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
