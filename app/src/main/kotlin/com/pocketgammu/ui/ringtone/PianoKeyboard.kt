package com.pocketgammu.ui.ringtone

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private val WHITE = listOf(0 to "C", 2 to "D", 4 to "E", 5 to "F", 7 to "G", 9 to "A", 11 to "B")
/** Black keys: semitone and the white-key index they sit after. */
private val BLACK = listOf(1 to 0, 3 to 1, 6 to 3, 8 to 4, 10 to 5)

/** One-octave keyboard. Keys use the theme colours so it follows the selected palette. */
@Composable
fun PianoKeyboard(octave: Int, onKey: (Int) -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val gap = 3.dp
        val whiteW = (maxWidth - gap * 6) / 7
        val blackW = whiteW * 0.62f
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            WHITE.forEach { (semi, label) ->
                Key(
                    label = if (semi == 0) "C$octave" else label,
                    description = "$label$octave",
                    white = true,
                    onClick = { onKey(semi) },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
        BLACK.forEach { (semi, after) ->
            Key(
                label = null,
                description = "${WHITE[after].second} sharp $octave",
                white = false,
                onClick = { onKey(semi) },
                modifier = Modifier
                    .offset(x = whiteW * (after + 1) + gap * after + gap / 2 - blackW / 2)
                    .width(blackW)
                    .fillMaxHeight(0.6f),
            )
        }
    }
}

@Composable
private fun Key(label: String?, description: String, white: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scheme = MaterialTheme.colorScheme
    val base = if (white) scheme.surfaceContainerHighest else scheme.inverseSurface
    val color by animateColorAsState(if (pressed) scheme.primary else base, label = "key")
    val shape = RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp, topStart = 4.dp, topEnd = 4.dp)
    Box(
        modifier
            .clip(shape)
            .background(color)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.BottomCenter,
    ) {
        if (label != null) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = if (pressed) scheme.onPrimary else scheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
    }
}
