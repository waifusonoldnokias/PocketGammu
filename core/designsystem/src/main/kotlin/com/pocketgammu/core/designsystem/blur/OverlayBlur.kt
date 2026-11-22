package com.pocketgammu.core.designsystem.blur

import android.os.Build
import android.view.WindowManager
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider

/**
 * Counts open pop-ups (sheets, menus, dialogs). While any are open, the app content
 * behind them is blurred. Blur needs Android 12+; on 8–11 the normal scrim is used.
 */
@Stable
class OverlayBlurState {
    var openOverlays by mutableIntStateOf(0)
        private set

    internal fun push() { openOverlays++ }
    internal fun pop() { openOverlays = (openOverlays - 1).coerceAtLeast(0) }
}

val LocalOverlayBlur = staticCompositionLocalOf { OverlayBlurState() }

val BlurSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/** Apply to the root content that should blur when pop-ups are open. */
@Composable
fun Modifier.blurBehindOverlays(state: OverlayBlurState = LocalOverlayBlur.current): Modifier {
    val radius by animateDpAsState(if (state.openOverlays > 0 && BlurSupported) 18.dp else 0.dp, label = "overlayBlur")
    return if (radius > 0.dp) this.blur(radius) else this
}

/** Call inside any pop-up's content to blur what's behind it while it is shown. */
@Composable
fun RegisterOverlay() {
    val state = LocalOverlayBlur.current
    DisposableEffect(state) {
        state.push()
        onDispose { state.pop() }
    }
}

/**
 * Inside a Dialog: uses the platform cross-window blur when the device allows it
 * (it is disabled e.g. in battery saver), otherwise falls back to blurring app content.
 */
@Composable
fun DialogWindowBlur() {
    val view = LocalView.current
    val density = LocalDensity.current
    val window = (view.parent as? DialogWindowProvider)?.window
    val wm = view.context.getSystemService(WindowManager::class.java)
    val crossWindow = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && window != null &&
        wm?.isCrossWindowBlurEnabled == true
    if (crossWindow && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        DisposableEffect(window) {
            window!!.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.apply {
                blurBehindRadius = with(density) { 24.dp.roundToPx() }
            }
            onDispose { }
        }
    } else {
        RegisterOverlay()
    }
}
