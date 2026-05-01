package io.github.thatsfguy.reticulum.android.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.thatsfguy.reticulum.android.platform.BlePermissions
import io.github.thatsfguy.reticulum.android.platform.Qr
import io.github.thatsfguy.reticulum.android.service.ReticulumService
import io.github.thatsfguy.reticulum.android.ui.ReticulumViewModel
import io.github.thatsfguy.reticulum.transport.TransportState

@Composable
fun SettingsScreen(
    viewModel: ReticulumViewModel,
    onRequestPermissions: (Array<String>) -> Unit,
) {
    val context: Context = LocalContext.current
    val connection by viewModel.connectionState.collectAsState(
        initial = io.github.thatsfguy.reticulum.engine.ReticulumEngine.ConnectionState(TransportState.Disconnected, null),
    )
    val log by viewModel.logLines.collectAsState()
    val displayName by viewModel.displayName.collectAsState(initial = "Reticulum Mobile")
    val ourDest by viewModel.ourDestHash.collectAsState()
    val cardJson by viewModel.myCardJson.collectAsState()
    val qrBitmap = remember(cardJson) {
        cardJson?.let { runCatching { Qr.encode(it, sizePx = 512) }.getOrNull() }
    }

    var bleAddress by remember { mutableStateOf("") }
    var tcpHost by remember { mutableStateOf("RNS.MichMesh.net") }
    var tcpPort by remember { mutableStateOf("7822") }
    var nameDraft by remember(displayName) { mutableStateOf(displayName) }
    var showResetConfirm by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Section("Connection") {
            Text("Status: ${statusLabel(connection.transport)} ${connection.kind?.let { "($it)" } ?: ""}")
            Spacer(Modifier.height(8.dp))

            Text("BLE", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = bleAddress, onValueChange = { bleAddress = it },
                label = { Text("RNode MAC address") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row {
                Button(onClick = {
                    val missing = BlePermissions.missing(context)
                    if (missing.isNotEmpty()) {
                        onRequestPermissions(missing.toTypedArray())
                    } else if (bleAddress.isNotBlank()) {
                        ReticulumService.connectBle(context, bleAddress.trim())
                    }
                }) { Text("Connect BLE") }
            }

            Spacer(Modifier.height(8.dp))
            Text("TCP transport node", style = MaterialTheme.typography.titleMedium)
            Row {
                OutlinedTextField(
                    value = tcpHost, onValueChange = { tcpHost = it },
                    label = { Text("Host") },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = tcpPort, onValueChange = { tcpPort = it.filter { c -> c.isDigit() } },
                    label = { Text("Port") },
                    modifier = Modifier.width(110.dp),
                )
            }
            Row {
                Button(onClick = {
                    val port = tcpPort.toIntOrNull() ?: return@Button
                    if (tcpHost.isNotBlank() && port > 0) {
                        ReticulumService.connectTcp(context, tcpHost.trim(), port)
                    }
                }) { Text("Connect TCP") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { ReticulumService.disconnect(context) }) { Text("Disconnect") }
            }

            Text(
                "TCP attaches to a remote rnsd transport node. Anyone running that node can " +
                    "observe your announces and destination hash.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Section("Identity") {
            Text(
                "Destination hash: " + (ourDest ?: "(unknown — connect first)"),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            qrBitmap?.let { bmp ->
                Box(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Your Reticulum QR card",
                        modifier = Modifier.size(220.dp).clip(RoundedCornerShape(8.dp)),
                    )
                }
                Text(
                    "Have someone scan this from the Nodes tab of their app to add you as a contact.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OutlinedTextField(
                value = nameDraft,
                onValueChange = { nameDraft = it },
                label = { Text("Display name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                val unsaved = nameDraft != displayName
                Button(
                    onClick = { viewModel.setDisplayName(nameDraft) },
                    enabled = unsaved && nameDraft.isNotBlank(),
                ) { Text(if (unsaved) "Save name" else "Saved") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { nameDraft = displayName }, enabled = nameDraft != displayName) {
                    Text("Revert")
                }
            }
            Text(
                "Saved name is broadcast in your next announce so peers can label you. " +
                    "Editing triggers an immediate re-announce.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { viewModel.announce() }) { Text("Send announce") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { showResetConfirm = true }) { Text("Reset identity…") }
            }
            Text(
                "An announce is sent automatically every 5 minutes while connected.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (showResetConfirm) {
            AlertDialog(
                onDismissRequest = { showResetConfirm = false },
                title = { Text("Reset identity?") },
                text = {
                    Text(
                        "Generates a new keypair and a new destination hash. " +
                            "Anyone who knew your old hash will need to see a fresh announce from you. " +
                            "Contacts and message history stay.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        showResetConfirm = false
                        viewModel.resetIdentity()
                    }) { Text("Reset") }
                },
                dismissButton = {
                    TextButton(onClick = { showResetConfirm = false }) { Text("Cancel") }
                },
            )
        }

        Section("About") {
            Text("Reticulum Mobile · 0.1.0")
            Text(
                "Source: github.com/thatsfguy/reticulum-mobile-app",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Identity export/import is not yet wired into the UI. Until it is, " +
                    "the app generates a fresh identity on first launch and stores the " +
                    "private keys in the local Room database under the app's private storage.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Section("Diagnostics log") {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                LazyColumn(reverseLayout = true, modifier = Modifier.fillMaxSize().padding(8.dp)) {
                    items(log.reversed()) { line ->
                        Text(line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

private fun statusLabel(state: TransportState): String = when (state) {
    TransportState.Disconnected -> "Disconnected"
    TransportState.Connecting   -> "Connecting…"
    TransportState.Connected    -> "Connected"
    TransportState.Error        -> "Error"
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        content()
    }
}
