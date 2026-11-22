package com.pocketgammu.ui.home

import android.content.ClipData
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.rounded.Cable
import androidx.compose.material.icons.rounded.CellTower
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SettingsRemote
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pocketgammu.BuildConfig
import com.pocketgammu.LocalAppContainer
import com.pocketgammu.R
import com.pocketgammu.core.designsystem.components.CardHeader
import com.pocketgammu.core.designsystem.components.PgButton
import com.pocketgammu.core.designsystem.components.PgButtonStyle
import com.pocketgammu.core.designsystem.components.PgCard
import com.pocketgammu.core.designsystem.components.PgDialog
import com.pocketgammu.core.designsystem.components.PgDropdown
import com.pocketgammu.core.designsystem.components.ShapeIcon
import com.pocketgammu.core.designsystem.components.SwitchRow
import com.pocketgammu.core.designsystem.haptics.LocalPgHaptics
import com.pocketgammu.core.designsystem.theme.MonoStyle
import com.pocketgammu.core.designsystem.theme.PgShapeSet
import com.pocketgammu.core.gammu.model.ConnectionState
import com.pocketgammu.core.gammu.model.ConnectionType
import com.pocketgammu.core.gammu.model.PhoneFamily
import com.pocketgammu.core.gammu.usb.DetectedDevice
import com.pocketgammu.engine.usb.UsbDrivers
import com.pocketgammu.ui.LocalSnackbar
import kotlinx.coroutines.launch

/**
 * Home: GammuDroid's connection flow. What is connected (status card), which cable or
 * dongle to use (device list), how to talk to the phone (connection type filtered to
 * the device, phone family, cable power) and tips.
 */
@Composable
fun HomeScreen(onOpenSettings: () -> Unit) {
    val container = LocalAppContainer.current
    val vm: HomeViewModel = viewModel { HomeViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    val haptics = LocalPgHaptics.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    var showLog by remember { mutableStateOf(false) }
    val report: (Boolean, String) -> Unit = { ok, message ->
        if (ok) haptics.confirm() else haptics.reject()
        scope.launch { snackbar.showSnackbar(message) }
    }

    if (showLog) ConnectionLogDialog(load = vm::connectionLog, onClear = vm::clearLog, onDismiss = { showLog = false })

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Header(onShowLog = { haptics.click(); showLog = true }, onOpenSettings = onOpenSettings)
        StatusCard(
            state = state,
            onDismiss = vm::dismissError,
            onReadDetails = { haptics.click(); vm.readDetails(report) },
            onShowLog = { haptics.click(); showLog = true },
        )
        DeviceCard(state, onSelect = { haptics.tick(); vm.selectDevice(it) }, onRefresh = { haptics.tick(); vm.refreshDevices() })
        ConnectionCard(
            state = state,
            onSelectType = vm::selectType,
            onSelectFamily = vm::selectFamily,
            onPower = vm::setPower,
            onConnect = { vm.connect(report) },
            onDisconnect = { vm.disconnect() },
        )
        TipsCard()
        Spacer(Modifier.padding(8.dp))
    }
}

@Composable
private fun Header(onShowLog: () -> Unit, onOpenSettings: () -> Unit) {
    val haptics = LocalPgHaptics.current
    Column(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Vector headline (traced from Headline.png) in two layers on the same canvas: the
        // "PocketGammu" wordmark in the scheme's primary colour, the tagline in onSurface.
        val scheme = MaterialTheme.colorScheme
        val wordmark = readableOn(scheme.primary, scheme.background, fallback = scheme.onSurface)
        Box(
            Modifier.fillMaxWidth().aspectRatio(931f / 515f).semantics(mergeDescendants = true) {
                contentDescription = "PocketGammu, a portable utility based on Gammu for tinkering with old Nokia phones on the go"
            },
        ) {
            Image(
                painter = painterResource(R.drawable.headline_wordmark),
                contentDescription = null,
                colorFilter = ColorFilter.tint(wordmark),
                contentScale = ContentScale.Fit,
                modifier = Modifier.matchParentSize(),
            )
            Image(
                painter = painterResource(R.drawable.headline_tagline),
                contentDescription = null,
                colorFilter = ColorFilter.tint(scheme.onSurface),
                contentScale = ContentScale.Fit,
                modifier = Modifier.matchParentSize(),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer) {
                Text(
                    "Version ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            Surface(
                onClick = onShowLog,
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Rounded.Article, contentDescription = null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    Spacer(Modifier.width(6.dp))
                    Text("Log", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
            Spacer(Modifier.weight(1f))
            FilledTonalIconButton(onClick = { haptics.click(); onOpenSettings() }) {
                Icon(Icons.Rounded.Settings, contentDescription = "Settings")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StatusCard(
    state: HomeUiState,
    onDismiss: () -> Unit,
    onReadDetails: () -> Unit,
    onShowLog: () -> Unit,
) {
    val connection = state.connection
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when (connection) {
        is ConnectionState.Connected -> scheme.primaryContainer to scheme.onPrimaryContainer
        is ConnectionState.Connecting -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        is ConnectionState.Failed -> scheme.errorContainer to scheme.onErrorContainer
        ConnectionState.Disconnected -> scheme.surfaceContainerHigh to scheme.onSurface
    }
    val cardColor by animateColorAsState(container, label = "statusCard")
    val spin = rememberInfiniteTransition(label = "spin")
    val rotation by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(6000, easing = LinearEasing), RepeatMode.Restart), label = "rot")

    PgCard(containerColor = cardColor) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (icon, polygon) = when (connection) {
                is ConnectionState.Connected -> Icons.Rounded.PhoneAndroid to PgShapeSet.connected
                is ConnectionState.Connecting -> Icons.Rounded.Sync to PgShapeSet.connecting
                is ConnectionState.Failed -> Icons.Rounded.ErrorOutline to PgShapeSet.error
                ConnectionState.Disconnected -> Icons.Rounded.LinkOff to PgShapeSet.disconnected
            }
            ShapeIcon(
                icon = icon,
                polygon = polygon,
                size = 72.dp,
                rotation = if (connection is ConnectionState.Connected || connection is ConnectionState.Connecting) rotation else 0f,
                containerColor = if (connection == ConnectionState.Disconnected) scheme.surfaceContainerHighest else content.copy(alpha = 0.14f),
                contentColor = content,
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when (connection) {
                        is ConnectionState.Connected -> connection.info.model
                        is ConnectionState.Connecting -> "Connecting…"
                        is ConnectionState.Failed -> "Could not connect"
                        ConnectionState.Disconnected -> "Not connected"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    color = content,
                )
                Text(
                    when (connection) {
                        is ConnectionState.Connected -> "Connected"
                        is ConnectionState.Connecting -> "Using \"${connection.type.gammuId}\""
                        is ConnectionState.Failed -> connection.message
                        ConnectionState.Disconnected -> "Plug in a cable or dongle, then tap Connect"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = content.copy(alpha = 0.8f),
                )
            }
            if (connection is ConnectionState.Failed) {
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Dismiss", tint = content) }
            }
        }
        if (connection is ConnectionState.Connected) {
            val info = connection.info
            Surface(shape = MaterialTheme.shapes.large, color = content.copy(alpha = 0.08f)) {
                InfoRows(
                    listOfNotNull(
                        "Firmware" to listOf(info.firmware, info.firmwareDate.takeIf { it.isNotBlank() }?.let { "($it)" }).filterNotNull().joinToString(" "),
                        "Gammu module" to info.module,
                        "Connection" to info.connection,
                        "Cable" to info.cable,
                        info.imei?.let { "IMEI" to it },
                        info.network?.let { "Network" to it },
                    ),
                    content,
                    Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
            if (state.readingDetails) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { LoadingIndicator() }
            } else {
                PgButton("Read network & IMEI", onClick = onReadDetails, icon = Icons.Rounded.CellTower, style = PgButtonStyle.Tonal, modifier = Modifier.fillMaxWidth())
            }
        }
        if (connection is ConnectionState.Failed) {
            TextButton(onClick = onShowLog) {
                Icon(Icons.AutoMirrored.Rounded.Article, null, Modifier.size(18.dp), tint = content)
                Text("  View log", color = content)
            }
        }
    }
}

@Composable
private fun InfoRows(items: List<Pair<String, String>>, color: Color, modifier: Modifier = Modifier) {
    SelectionContainer {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items.filter { it.second.isNotBlank() }.forEach { (k, v) ->
                Row {
                    Text(k, style = MaterialTheme.typography.labelLarge, color = color.copy(alpha = 0.7f), modifier = Modifier.width(108.dp))
                    Text(v, style = MaterialTheme.typography.bodyMedium, color = color)
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(state: HomeUiState, onSelect: (DetectedDevice) -> Unit, onRefresh: () -> Unit) {
    PgCard {
        CardHeader(
            icon = Icons.Rounded.Usb,
            polygon = PgShapeSet.usb,
            title = "Cable or dongle",
            trailing = { IconButton(onClick = onRefresh) { Icon(Icons.Rounded.Refresh, "Refresh") } },
        )
        Text(
            "PL2303 and other serial FBUS cables (CA-42, DKU-5 clones), SigmaTel STIr4200 IrDA " +
                "dongles, and Nokia DKU-2 / CA-53 USB cables, through a USB OTG adapter.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.devices.isEmpty()) {
            DeviceRow(
                icon = Icons.Rounded.Usb,
                title = "No USB device attached",
                subtitle = "Connect it through a USB OTG adapter",
                selected = false,
                onClick = null,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                state.devices.forEach { d ->
                    DeviceRow(
                        icon = d.kind.icon(),
                        title = d.label,
                        subtitle = listOfNotNull(d.idLabel, d.productName).joinToString(" · "),
                        selected = d == state.selectedDevice,
                        onClick = { onSelect(d) },
                    )
                }
            }
        }
    }
}

private fun UsbDrivers.Kind.icon(): ImageVector = when {
    infrared -> Icons.Rounded.SettingsRemote
    this == UsbDrivers.Kind.NOKIA_USB -> Icons.Rounded.PhoneAndroid
    this == UsbDrivers.Kind.UNSUPPORTED -> Icons.Rounded.Usb
    else -> Icons.Rounded.Cable
}

@Composable
private fun DeviceRow(icon: ImageVector, title: String, subtitle: String, selected: Boolean, onClick: (() -> Unit)?) {
    val scheme = MaterialTheme.colorScheme
    val bg by animateColorAsState(if (selected) scheme.secondaryContainer else Color.Transparent, label = "deviceRow")
    Surface(shape = MaterialTheme.shapes.large, color = bg, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .then(if (onClick != null) Modifier.clickable(role = Role.RadioButton, onClick = onClick) else Modifier)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ShapeIcon(
                icon = icon,
                polygon = PgShapeSet.usb,
                size = 44.dp,
                containerColor = if (selected) scheme.primary else scheme.secondaryContainer,
                contentColor = if (selected) scheme.onPrimary else scheme.onSecondaryContainer,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
            if (selected) Icon(Icons.Rounded.CheckCircle, "Selected", tint = scheme.primary)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ConnectionCard(
    state: HomeUiState,
    onSelectType: (ConnectionType) -> Unit,
    onSelectFamily: (PhoneFamily) -> Unit,
    onPower: (Boolean) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    PgCard {
        CardHeader(icon = Icons.Rounded.Tune, polygon = PgShapeSet.app, title = "Connection")
        val type = state.connectionType
        if (type != null) {
            PgDropdown(
                label = "Gammu connection type",
                options = state.choices,
                selected = type,
                optionLabel = { "${it.gammuId} · ${it.label}" },
                supporting = { it.hint },
                onSelect = onSelectType,
                enabled = !state.connected && !state.connecting,
            )
        }
        PgDropdown(
            label = "Phone family (Gammu module)",
            options = PhoneFamily.entries,
            selected = state.family,
            optionLabel = { it.label },
            supporting = { it.examples },
            onSelect = onSelectFamily,
            enabled = !state.connected && !state.connecting,
        )
        AnimatedVisibility(state.showPower) {
            SwitchRow(
                title = "Power the cable from DTR/RTS",
                subtitle = "DAU-9P and most FBUS cables need this",
                checked = state.powerCable,
                onCheckedChange = onPower,
            )
        }
        when {
            state.connecting -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { LoadingIndicator() }
            state.connected -> PgButton(
                text = "Disconnect",
                icon = Icons.Rounded.LinkOff,
                onClick = onDisconnect,
                style = PgButtonStyle.Tonal,
                large = true,
                modifier = Modifier.fillMaxWidth(),
            )
            else -> PgButton(
                text = "Connect",
                icon = Icons.Rounded.Cable,
                onClick = onConnect,
                enabled = state.canConnect,
                large = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun TipsCard() {
    PgCard {
        CardHeader(icon = Icons.Rounded.Info, polygon = PgShapeSet.note, title = "Tips")
        Text(
            listOf(
                "3310 / 3330 / 3410: an FBUS cable, connection \"fbus\".",
                "6020, 6230 and other Pop-Port phones: a DKU-2 / CA-53 USB cable, \"dku2\". Choose PC Suite mode if the phone asks.",
                "CA-42 / DKU-5 clone cables for DCT-4 phones: \"dku5\" or \"fbuspl2303\".",
                "Infrared (6210, 6310i, 6510, 8210, 8310…): switch the phone's infrared on, point it at the dongle and use \"irdaphonet\". " +
                    "6110, 6130 and 6150 use \"fbusirda\" with an STIr4200.",
                "Series 40 phones (6100, 3510i, 3200, 6020…): manage their files on the Files tab.",
                "Every byte sent and received is in the Log (next to the version). Share it when something doesn't work.",
            ).joinToString("\n") { "•  $it" },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ConnectionLogDialog(load: () -> String, onClear: () -> Unit, onDismiss: () -> Unit) {
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { text = load() }
    val full = text.orEmpty()
    PgDialog(
        title = "Log",
        onDismiss = onDismiss,
        confirm = {
            TextButton(onClick = {
                if (full.isBlank()) return@TextButton
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "PocketGammu ${BuildConfig.VERSION_NAME} log")
                    putExtra(Intent.EXTRA_TEXT, full.takeLast(SHARE_LIMIT))
                }
                context.startActivity(Intent.createChooser(send, "Share log"))
            }) { Text("Share") }
        },
        dismiss = {
            Row {
                TextButton(onClick = { onClear(); onDismiss() }) { Text("Clear") }
                TextButton(onClick = {
                    if (full.isBlank()) return@TextButton
                    scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("PocketGammu log", full.takeLast(SHARE_LIMIT)))) }
                }) { Text("Copy") }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    ) {
        Text(
            "Every frame sent and received, plus driver and IrDA messages. Share it when reporting a problem with a phone or dongle.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
            Text(
                text?.takeLast(16_000)?.ifBlank { "The log is empty." } ?: "Loading…",
                style = MonoStyle,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}

/** Keeps the shared text well inside Android's 1 MB Binder transaction limit. */
private const val SHARE_LIMIT = 300_000

/** [color] if it reaches 4.5:1 against [background] (WCAG AA), otherwise [fallback]. */
internal fun readableOn(color: Color, background: Color, fallback: Color): Color {
    val a = color.luminance() + 0.05f
    val b = background.luminance() + 0.05f
    return if (maxOf(a, b) / minOf(a, b) >= 4.5f) color else fallback
}
