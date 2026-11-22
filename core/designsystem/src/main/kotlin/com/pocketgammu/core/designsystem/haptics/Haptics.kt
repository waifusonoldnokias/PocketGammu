package com.pocketgammu.core.designsystem.haptics

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView

/**
 * Central haptics gate. Every component goes through this, so the single
 * "Haptic feedback" switch in Settings turns all of them off.
 */
@Stable
class PgHaptics internal constructor(private val view: View?, val enabled: Boolean) {

    /** Light tick: tool selection, toggles, stepping values. */
    fun tick() = perform(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.SEGMENT_TICK
        else HapticFeedbackConstants.CLOCK_TICK,
    )

    /** Standard press: buttons, keys. */
    fun click() = perform(HapticFeedbackConstants.VIRTUAL_KEY)

    fun longPress() = perform(HapticFeedbackConstants.LONG_PRESS)

    /** Success: connected, sent, saved. */
    fun confirm() = perform(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
        else HapticFeedbackConstants.VIRTUAL_KEY,
    )

    /** Failure: connection error, invalid input. */
    fun reject() = perform(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.REJECT
        else HapticFeedbackConstants.LONG_PRESS,
    )

    /** Very light, for continuous gestures like drawing pixels. */
    fun pixel() = perform(HapticFeedbackConstants.CLOCK_TICK)

    private fun perform(constant: Int) {
        if (enabled) view?.performHapticFeedback(constant)
    }

    companion object {
        val Disabled = PgHaptics(null, false)
    }
}

val LocalPgHaptics = staticCompositionLocalOf { PgHaptics.Disabled }

@Composable
fun rememberPgHaptics(enabled: Boolean): PgHaptics {
    val view = LocalView.current
    return remember(view, enabled) { PgHaptics(view, enabled) }
}
