package com.pocketgammu.core.designsystem.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pocketgammu.core.designsystem.blur.DialogWindowBlur
import com.pocketgammu.core.designsystem.blur.RegisterOverlay

/** Dialog with background blur (window blur on Android 12+). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PgDialog(
    title: String,
    onDismiss: () -> Unit,
    confirm: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismiss: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    BasicAlertDialog(onDismissRequest = onDismiss, modifier = modifier) {
        DialogWindowBlur()
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                Column(
                    Modifier.verticalScroll(rememberScrollState()).weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = content,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End)) {
                    dismiss?.invoke()
                    confirm()
                }
            }
        }
    }
}

/** Bottom sheet that blurs the content behind it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PgBottomSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        RegisterOverlay()
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}
