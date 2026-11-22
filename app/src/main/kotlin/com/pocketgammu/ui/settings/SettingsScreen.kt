package com.pocketgammu.ui.settings

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketgammu.BuildConfig
import com.pocketgammu.LocalAppContainer
import com.pocketgammu.core.designsystem.components.ConnectedToggleGroup
import com.pocketgammu.core.designsystem.components.PgCard
import com.pocketgammu.core.designsystem.components.SectionLabel
import com.pocketgammu.core.designsystem.components.SwitchRow
import com.pocketgammu.core.designsystem.components.ToggleOption
import com.pocketgammu.core.designsystem.haptics.LocalPgHaptics
import com.pocketgammu.core.designsystem.theme.PgShapeSet
import com.pocketgammu.core.designsystem.theme.colorSchemeFor
import com.pocketgammu.core.designsystem.theme.isDarkTheme
import com.pocketgammu.core.designsystem.theme.rememberShape
import com.pocketgammu.core.model.ColorPalette
import com.pocketgammu.core.model.ThemeMode
import com.pocketgammu.core.model.UserSettings
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val settings by container.settings.settings.collectAsStateWithLifecycle(UserSettings())
    val scope = rememberCoroutineScope()
    val haptics = LocalPgHaptics.current

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Settings", style = MaterialTheme.typography.headlineMedium) },
            navigationIcon = {
                IconButton(onClick = { haptics.click(); onBack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionLabel("Appearance")
            PgCard {
                Text("Theme", style = MaterialTheme.typography.titleMedium)
                ConnectedToggleGroup(
                    options = listOf(
                        ToggleOption(ThemeMode.SYSTEM, "System", Icons.Rounded.AutoAwesome),
                        ToggleOption(ThemeMode.LIGHT, "Light", Icons.Rounded.LightMode),
                        ToggleOption(ThemeMode.DARK, "Dark", Icons.Rounded.DarkMode),
                    ),
                    selected = settings.themeMode,
                    onSelect = { scope.launch { container.settings.setThemeMode(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            PgCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Palette, null, tint = MaterialTheme.colorScheme.primary)
                    Text("  Colour scheme", style = MaterialTheme.typography.titleMedium)
                }
                // Three per row, so six schemes fit narrow phones too.
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ColorPalette.entries.chunked(3).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            row.forEach { palette ->
                                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                    PaletteSwatch(
                                        palette = palette,
                                        dark = isDarkTheme(settings.themeMode),
                                        selected = settings.palette == palette,
                                        onClick = { haptics.tick(); scope.launch { container.settings.setPalette(palette) } },
                                    )
                                }
                            }
                        }
                    }
                }
                val current = settings.palette
                Text(
                    if (current == ColorPalette.SYSTEM && Build.VERSION.SDK_INT < Build.VERSION_CODES.S)
                        "System · Pastel Blue (dynamic colour needs Android 12)"
                    else "${current.label} · ${current.description}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionLabel("Interaction")
            PgCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Vibration, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 12.dp))
                    SwitchRow(
                        title = "Haptic feedback",
                        subtitle = "Vibration on taps, tool changes and results",
                        checked = settings.hapticsEnabled,
                        onCheckedChange = { scope.launch { container.settings.setHaptics(it) } },
                    )
                }
            }

            SectionLabel("About")
            PgCard {
                Text("App Version: ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium)
                Text(
                    "PocketGammu is an experimental / proof-of-concept project that ports the open-source " +
                        "Gammu library (Gammu All Mobile Management Utilities) to Android for use with tinkering " +
                        "with old Nokia DCT-3 and DCT-4 era Series 20, 30, and 40 phones.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "This app is conceptualized by Noki-min (@waifusonoldnokias) with major assistance from Claude Opus.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PaletteSwatch(palette: ColorPalette, dark: Boolean, selected: Boolean, onClick: () -> Unit) {
    val scheme = colorSchemeFor(palette, dark)
    val rotation by animateFloatAsState(if (selected) 45f else 0f, label = "swatchRot")
    val ring by animateColorAsState(if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant, label = "ring")
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = onClick)
            .semantics { role = Role.RadioButton; this.selected = selected }
            .padding(4.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(46.dp)) {
            val shape = rememberShape(if (selected) PgShapeSet.swatchSelected else PgShapeSet.swatch)
            Box(Modifier.size(46.dp).rotate(rotation).clip(shape).background(ring))
            Box(Modifier.size(41.dp).rotate(rotation).clip(shape).background(scheme.primaryContainer))
            Row(Modifier.size(20.dp).clip(MaterialTheme.shapes.small)) {
                Box(Modifier.weight(1f).fillMaxSize().background(scheme.primary))
                Box(Modifier.weight(1f).fillMaxSize().background(scheme.secondary))
                Box(Modifier.weight(1f).fillMaxSize().background(scheme.tertiary))
            }
            if (selected) Icon(Icons.Rounded.Check, null, tint = scheme.onPrimary, modifier = Modifier.size(18.dp))
        }
        val style = MaterialTheme.typography.labelMedium
        Text(
            palette.label, style = style, textAlign = TextAlign.Center, maxLines = 1, softWrap = false,
            autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = style.fontSize, stepSize = 0.5.sp),
        )
    }
}

