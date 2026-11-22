package com.pocketgammu.core.designsystem.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketgammu.core.designsystem.haptics.LocalPgHaptics

/*
 * All Material 3 Expressive button APIs are wrapped here. If an experimental signature
 * changes between material3 releases, this is the only file that needs updating.
 */

enum class PgButtonStyle { Filled, Tonal, Outlined }

/** Shape-morphing button (round at rest, squarer when pressed) with built-in haptics. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PgButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    style: PgButtonStyle = PgButtonStyle.Filled,
    large: Boolean = false,
) {
    val haptics = LocalPgHaptics.current
    val click = { haptics.click(); onClick() }
    val height = if (large) 64.dp else 48.dp
    val padding = if (large) PaddingValues(horizontal = 28.dp, vertical = 16.dp) else ButtonDefaults.ContentPadding
    val content: @Composable RowScope.() -> Unit = {
        if (icon != null) {
            Icon(icon, null, Modifier.size(if (large) 24.dp else 18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text,
            style = if (large) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    val m = modifier.heightIn(min = height)
    when (style) {
        PgButtonStyle.Filled -> Button(
            onClick = click, shapes = ButtonDefaults.shapes(), modifier = m, enabled = enabled,
            contentPadding = padding, content = content,
        )
        PgButtonStyle.Tonal -> FilledTonalButton(
            onClick = click, shapes = ButtonDefaults.shapes(), modifier = m, enabled = enabled,
            contentPadding = padding, content = content,
        )
        PgButtonStyle.Outlined -> OutlinedButton(
            onClick = click, shapes = ButtonDefaults.shapes(), modifier = m, enabled = enabled,
            contentPadding = padding, content = content,
        )
    }
}

data class ToggleOption<T>(val value: T, val label: String? = null, val icon: ImageVector? = null)

/**
 * Connected button group (M3 Expressive): a row of toggle buttons where the selected one
 * morphs its shape. Single selection.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun <T> ConnectedToggleGroup(
    options: List<ToggleOption<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    compact: Boolean = false,
) {
    val haptics = LocalPgHaptics.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        options.forEachIndexed { index, option ->
            val shapes = when (index) {
                0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
            }
            ToggleButton(
                checked = option.value == selected,
                onCheckedChange = { if (option.value != selected) { haptics.tick(); onSelect(option.value) } },
                enabled = enabled,
                shapes = shapes,
                // Tighter than ButtonDefaults (24dp a side) so three labels fit with large font sizes.
                contentPadding = PaddingValues(horizontal = if (compact) 8.dp else 12.dp),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = if (compact) 40.dp else 48.dp)
                    .semantics { role = Role.RadioButton },
            ) {
                option.icon?.let { Icon(it, option.label, Modifier.size(18.dp)) }
                if (option.icon != null && option.label != null && !compact) Spacer(Modifier.width(6.dp))
                if (option.label != null && !(compact && option.icon != null)) {
                    // Shrinks to fit rather than clipping ("Sys", "Lig"…) with large system font sizes.
                    val style = MaterialTheme.typography.labelLarge
                    Text(
                        option.label,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                        style = style,
                        autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = style.fontSize, stepSize = 0.5.sp),
                    )
                }
            }
        }
    }
}

/** Single icon toggle used for editor tools. Round when idle, squircle when selected. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ToolToggle(
    icon: ImageVector,
    label: String,
    checked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val haptics = LocalPgHaptics.current
    ToggleButton(
        checked = checked,
        onCheckedChange = { haptics.tick(); onClick() },
        enabled = enabled,
        contentPadding = PaddingValues(0.dp),
        modifier = modifier.size(48.dp),
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(22.dp))
    }
}
