package com.pocketgammu.ui.common

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Cable
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketgammu.LocalAppContainer
import com.pocketgammu.core.designsystem.components.ConnectedToggleGroup
import com.pocketgammu.core.designsystem.components.PgBottomSheet
import com.pocketgammu.core.designsystem.components.PgButton
import com.pocketgammu.core.designsystem.components.PgButtonStyle
import com.pocketgammu.core.designsystem.components.PgTextField
import com.pocketgammu.core.designsystem.components.ToggleOption
import com.pocketgammu.core.designsystem.haptics.LocalPgHaptics
import com.pocketgammu.core.designsystem.theme.MonoStyle
import com.pocketgammu.core.gammu.model.ConnectionState
import com.pocketgammu.core.model.SessionDefaults
import com.pocketgammu.core.nokia.logo.NetworkCode
import com.pocketgammu.core.nokia.sms.SmartMessage
import com.pocketgammu.ui.LocalSnackbar
import kotlinx.coroutines.launch

enum class SendMode { Cable, Sms }

/** What the user filled in on the sheet. [slot] is the picture-message slot for cable sends. */
data class SendInput(val network: NetworkCode?, val text: String = "", val slot: Int = 1)

/** Picture-message slots offered for cable sends (3310-class phones). */
private val PICTURE_SLOTS = 1..10

/**
 * Shared "send" sheet for the logo and ringtone editors.
 *
 * @param needsNetworkCode operator logos are bound to a network (MCC MNC).
 * @param messageText picture messages carry a text (max [messageTextMax] characters).
 * @param pictureSlot cable sends go into a picture-message slot the user picks.
 * @param buildSms creates the Smart Message, or returns null when SMS isn't possible.
 * @param sendCable writes the item to the connected phone.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SendSheet(
    title: String,
    itemDescription: String,
    needsNetworkCode: Boolean,
    smsUnsupportedReason: String?,
    buildSms: (SendInput) -> SmartMessage?,
    sendCable: suspend (SendInput) -> Result<Unit>,
    onDismiss: () -> Unit,
    onGoHome: () -> Unit,
    messageText: Boolean = false,
    messageTextMax: Int = 120,
    pictureSlot: Boolean = false,
) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val haptics = LocalPgHaptics.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val connection by container.phone.state.collectAsStateWithLifecycle()
    val defaults by container.settings.sessionDefaults.collectAsStateWithLifecycle(SessionDefaults())

    var mode by rememberSaveable { mutableStateOf(if (connection is ConnectionState.Connected) SendMode.Cable else SendMode.Sms) }
    var networkText by rememberSaveable { mutableStateOf("") }
    var recipient by rememberSaveable { mutableStateOf("") }
    var text by rememberSaveable { mutableStateOf("") }
    var slot by rememberSaveable { mutableStateOf(1) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(defaults) {
        if (networkText.isEmpty()) networkText = defaults.networkCode
        if (recipient.isEmpty()) recipient = defaults.recipient
    }

    val network = NetworkCode.parse(networkText)
    val networkOk = !needsNetworkCode || network != null
    val input = SendInput(network, text, slot)
    val sms = if (networkOk) buildSms(input) else null
    val parts = remember(sms) { sms?.let { container.smsSender.preview(it) }.orEmpty() }

    fun report(result: Result<*>, success: String) {
        busy = false
        result.onSuccess { haptics.confirm(); onDismiss(); scope.launch { snackbar.showSnackbar(success) } }
            .onFailure { haptics.reject(); scope.launch { snackbar.showSnackbar(it.message ?: "Something went wrong") } }
    }

    fun doSendSms() {
        val message = sms ?: return
        busy = true
        scope.launch {
            container.settings.setRecipient(recipient)
            if (needsNetworkCode) container.settings.setNetworkCode(networkText)
            report(container.smsSender.send(recipient, message), "Sent ${parts.size} SMS to $recipient")
        }
    }

    val smsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) doSendSms() else scope.launch { snackbar.showSnackbar("SMS permission is needed to send //SCKL messages") }
    }

    PgBottomSheet(onDismiss = { if (!busy) onDismiss() }) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(itemDescription, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

        ConnectedToggleGroup(
            options = listOf(
                ToggleOption(SendMode.Cable, "To phone", Icons.Rounded.Cable),
                ToggleOption(SendMode.Sms, "By SMS", Icons.Rounded.Sms),
            ),
            selected = mode,
            onSelect = { mode = it },
            modifier = Modifier.fillMaxWidth(),
        )

        if (needsNetworkCode) {
            PgTextField(
                value = networkText,
                onValueChange = { networkText = it.filter { c -> c.isDigit() || c == ' ' }.take(6) },
                label = "Network code (MCC MNC)",
                placeholder = "e.g. 466 92",
                supporting = if (network == null && networkText.isNotBlank()) "Use 3 + 2 digits" else "The logo shows only on SIMs from this network",
                isError = network == null && networkText.isNotBlank(),
                keyboardType = KeyboardType.Number,
            )
        }

        if (messageText) {
            PgTextField(
                value = text,
                onValueChange = { text = it.take(messageTextMax) },
                label = "Message text",
                placeholder = "Shown with the picture (optional)",
                supporting = "${text.length} / $messageTextMax",
            )
        }

        when (mode) {
            SendMode.Cable -> {
                val connected = connection as? ConnectionState.Connected
                if (connected != null) {
                    InfoRow(Icons.Rounded.Usb, "Connected to ${connected.info.displayName}")
                    if (pictureSlot) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Picture slot $slot", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "Saved into the phone's picture messages (3310, 3330, 3410, 6150, 8210 class), replacing that slot.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            FilledTonalIconButton(onClick = { slot = (slot - 1).coerceAtLeast(PICTURE_SLOTS.first) }, enabled = slot > PICTURE_SLOTS.first) {
                                Icon(Icons.Rounded.Remove, "Previous slot")
                            }
                            FilledTonalIconButton(onClick = { slot = (slot + 1).coerceAtMost(PICTURE_SLOTS.last) }, enabled = slot < PICTURE_SLOTS.last) {
                                Icon(Icons.Rounded.Add, "Next slot")
                            }
                        }
                    }
                    PgButton(
                        text = if (busy) "Sending…" else "Send to phone",
                        icon = Icons.AutoMirrored.Rounded.Send,
                        enabled = !busy && networkOk,
                        large = true,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            busy = true
                            scope.launch {
                                if (needsNetworkCode) container.settings.setNetworkCode(networkText)
                                report(sendCable(input), "Sent to ${connected.info.displayName}")
                            }
                        },
                    )
                } else {
                    InfoRow(Icons.Rounded.Info, "No phone connected. Connect your Nokia from the Home page first.")
                    PgButton("Go to Home", onClick = { onDismiss(); onGoHome() }, icon = Icons.Rounded.Home, style = PgButtonStyle.Tonal, modifier = Modifier.fillMaxWidth())
                }
            }
            SendMode.Sms -> {
                if (smsUnsupportedReason != null) {
                    InfoRow(Icons.Rounded.Info, smsUnsupportedReason)
                } else {
                    PgTextField(
                        value = recipient,
                        onValueChange = { recipient = it.filter { c -> c.isDigit() || c in "+ " }.take(20) },
                        label = "Recipient number",
                        keyboardType = KeyboardType.Phone,
                    )
                    if (sms != null) {
                        Text(
                            "${parts.size} SMS · ${sms.sizeBytes} bytes · port ${"%04X".format(sms.destinationPort)}",
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                            Text(
                                parts.firstOrNull().orEmpty(),
                                style = MonoStyle,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
                    InfoRow(
                        Icons.Rounded.Info,
                        "Text-mode //SCKL messages work on many DCT3/DCT4 phones. Some models, such as the 3310, only accept binary Smart Messages and will show the text instead.",
                    )
                    PgButton(
                        text = if (busy) "Sending…" else "Send ${parts.size.coerceAtLeast(1)} SMS",
                        icon = Icons.Rounded.Sms,
                        enabled = !busy && sms != null && recipient.count(Char::isDigit) >= 3,
                        large = true,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
                            if (granted) doSendSms() else smsPermission.launch(Manifest.permission.SEND_SMS)
                        },
                    )
                }
            }
        }
        if (busy) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { LoadingIndicator() }
        }
    }
}

@Composable
private fun InfoRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
