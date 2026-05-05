package io.github.thatsfguy.reticulum.android.ui.screens

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.thatsfguy.reticulum.android.platform.BlePermissions
import io.github.thatsfguy.reticulum.android.platform.BleScanner
import io.github.thatsfguy.reticulum.android.platform.BondedDevice
import io.github.thatsfguy.reticulum.android.platform.BtClassicDevices
import io.github.thatsfguy.reticulum.android.platform.DiscoveredDevice
import io.github.thatsfguy.reticulum.android.platform.Qr
import io.github.thatsfguy.reticulum.android.service.ReticulumService
import io.github.thatsfguy.reticulum.android.ui.ReticulumViewModel
import io.github.thatsfguy.reticulum.transport.TransportState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    viewModel: ReticulumViewModel,
    onRequestPermissions: (Array<String>) -> Unit,
) {
    val context: Context = LocalContext.current
    val connection by viewModel.connectionState.collectAsState(
        initial = io.github.thatsfguy.reticulum.engine.ReticulumEngine.ConnectionState(TransportState.Disconnected, null),
    )
    val connections by viewModel.connectionStates.collectAsState(initial = emptyList())
    val rawLog by viewModel.logLines.collectAsState()
    val verboseLog by viewModel.verboseLog.collectAsState()
    val log by viewModel.displayedLog.collectAsState(initial = emptyList())
    val displayName by viewModel.displayName.collectAsState(initial = "Reticulum Mobile")
    val ourDest by viewModel.ourDestHash.collectAsState()
    val cardJson by viewModel.myCardJson.collectAsState()
    val qrBitmap = remember(cardJson) {
        cardJson?.let { runCatching { Qr.encode(it, sizePx = 512) }.getOrNull() }
    }
    val service by viewModel.service.collectAsState()
    val savedHost by (service?.prefs?.tcpHost
        ?: kotlinx.coroutines.flow.MutableStateFlow("RNS.MichMesh.net")).collectAsState()
    val savedPort by (service?.prefs?.tcpPort
        ?: kotlinx.coroutines.flow.MutableStateFlow(7822)).collectAsState()
    val savedBtAddress by (service?.prefs?.btClassicAddress
        ?: kotlinx.coroutines.flow.MutableStateFlow("")).collectAsState()
    val savedBtName by (service?.prefs?.btClassicName
        ?: kotlinx.coroutines.flow.MutableStateFlow("")).collectAsState()

    // The keys make these fields refresh whenever the persisted value
    // changes (e.g. after the user successfully connects, the prefs
    // update, and the screen reflects the new "current" host/port).
    var tcpHost by remember(savedHost) { mutableStateOf(savedHost) }
    var tcpPort by remember(savedPort) { mutableStateOf(savedPort.toString()) }
    var nameDraft by remember(displayName) { mutableStateOf(displayName) }
    var showResetConfirm by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Section("Connection") {
            // Tick-driven elapsed timer so a slow-but-working "Connecting…"
            // shows e.g. "Connecting (12s)" instead of looking wedged.
            var nowTick by remember { mutableStateOf(System.currentTimeMillis()) }
            androidx.compose.runtime.LaunchedEffect(connections) {
                while (true) {
                    nowTick = System.currentTimeMillis()
                    kotlinx.coroutines.delay(500L)
                }
            }

            // Multi-transport status: one line per attached kind.
            // Empty list = nothing attached, render the legacy "Disconnected".
            if (connections.isEmpty()) {
                Text("Status: ${statusLabel(TransportState.Disconnected)}")
            } else {
                Text("Status:")
                connections.forEach { conn ->
                    val elapsed = ((nowTick - conn.changedAtMs).coerceAtLeast(0L)) / 1000L
                    val line = buildString {
                        append("  · ")
                        append(transportKindLabel(conn.kind))
                        append(" — ")
                        append(statusLabel(conn.transport))
                        if (conn.transport == TransportState.Connecting && conn.changedAtMs > 0L) {
                            append(" (").append(elapsed).append("s)")
                        } else if (conn.transport == TransportState.Connected && conn.changedAtMs > 0L) {
                            append(" · up ").append(formatDuration(elapsed))
                        }
                    }
                    Text(line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(8.dp))

            Text("BLE", style = MaterialTheme.typography.titleMedium)
            var showBleScanDialog by remember { mutableStateOf(false) }
            val bleConnected = connections.any {
                it.transport == TransportState.Connected &&
                    it.kind == io.github.thatsfguy.reticulum.engine.ReticulumEngine.TransportKind.Ble
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val missing = BlePermissions.missing(context)
                        if (missing.isNotEmpty()) {
                            onRequestPermissions(missing.toTypedArray())
                        } else {
                            showBleScanDialog = true
                        }
                    },
                    enabled = !bleConnected,
                ) { Text("Scan for RNode") }
                if (bleConnected) {
                    OutlinedButton(onClick = {
                        ReticulumService.disconnectKind(
                            context,
                            io.github.thatsfguy.reticulum.engine.ReticulumEngine.TransportKind.Ble,
                        )
                    }) {
                        Text("Disconnect BLE")
                    }
                }
            }
            if (showBleScanDialog) {
                BleScanDialog(
                    onPick = { device ->
                        showBleScanDialog = false
                        ReticulumService.connectBle(context, device.address)
                    },
                    onDismiss = { showBleScanDialog = false },
                )
            }

            Spacer(Modifier.height(8.dp))
            Text("Bluetooth Classic (RFCOMM)", style = MaterialTheme.typography.titleMedium)
            var showBtClassicDialog by remember { mutableStateOf(false) }
            val btClassicConnected = connections.any {
                it.transport == TransportState.Connected &&
                    it.kind == io.github.thatsfguy.reticulum.engine.ReticulumEngine.TransportKind.BtClassic
            }
            Text(
                "Pair your RNode in Android Settings first, then pick it here. Higher and " +
                    "steadier throughput than BLE for chatty radio config and bulk transfers.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (savedBtAddress.isNotBlank()) {
                Text(
                    "Last: ${savedBtName.takeIf { it.isNotBlank() } ?: "(unnamed)"} · $savedBtAddress",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val missing = BlePermissions.missing(context)
                        if (missing.isNotEmpty()) {
                            onRequestPermissions(missing.toTypedArray())
                        } else {
                            showBtClassicDialog = true
                        }
                    },
                    enabled = !btClassicConnected,
                ) { Text("Pick paired device") }
                if (savedBtAddress.isNotBlank() && !btClassicConnected) {
                    OutlinedButton(onClick = {
                        ReticulumService.connectBtClassic(
                            context, savedBtAddress, savedBtName.ifBlank { null },
                        )
                    }) { Text("Reconnect last") }
                }
                if (btClassicConnected) {
                    OutlinedButton(onClick = {
                        ReticulumService.disconnectKind(
                            context,
                            io.github.thatsfguy.reticulum.engine.ReticulumEngine.TransportKind.BtClassic,
                        )
                    }) {
                        Text("Disconnect BT")
                    }
                }
            }
            if (showBtClassicDialog) {
                BtClassicPickerDialog(
                    onPick = { device ->
                        showBtClassicDialog = false
                        ReticulumService.connectBtClassic(context, device.address, device.name)
                    },
                    onDismiss = { showBtClassicDialog = false },
                )
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
            val tcpConnected = connections.any {
                it.transport == TransportState.Connected &&
                    it.kind == io.github.thatsfguy.reticulum.engine.ReticulumEngine.TransportKind.Tcp
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val port = tcpPort.toIntOrNull() ?: return@Button
                        if (tcpHost.isNotBlank() && port > 0) {
                            ReticulumService.connectTcp(context, tcpHost.trim(), port)
                        }
                    },
                    enabled = !tcpConnected,
                ) { Text("Connect TCP") }
                if (tcpConnected) {
                    OutlinedButton(onClick = {
                        ReticulumService.disconnectKind(
                            context,
                            io.github.thatsfguy.reticulum.engine.ReticulumEngine.TransportKind.Tcp,
                        )
                    }) {
                        Text("Disconnect TCP")
                    }
                }
            }

            Text(
                "TCP attaches to a remote rnsd transport node. Anyone running that node can " +
                    "observe your announces and destination hash.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Section("Radio config (RNode)") {
            val savedRadio by (service?.prefs?.radioConfig
                ?: kotlinx.coroutines.flow.MutableStateFlow(io.github.thatsfguy.reticulum.platform.RadioConfig())).collectAsState()
            var freqMhz by remember(savedRadio) { mutableStateOf((savedRadio.frequencyHz / 1_000_000.0).toString()) }
            var bwKhz   by remember(savedRadio) { mutableStateOf((savedRadio.bandwidthHz / 1000).toString()) }
            var sf      by remember(savedRadio) { mutableStateOf(savedRadio.spreadingFactor.toString()) }
            var cr      by remember(savedRadio) { mutableStateOf(savedRadio.codingRate.toString()) }
            var txp     by remember(savedRadio) { mutableStateOf(savedRadio.txPowerDbm.toString()) }
            val scopeUi = androidx.compose.runtime.rememberCoroutineScope()

            Text(
                "Applied automatically when BLE connects to an RNode. Match these to the " +
                    "rest of your mesh (your RatDeck / Sideband / NomadNet peers) — wrong " +
                    "freq/BW/SF means no one hears you.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = freqMhz, onValueChange = { freqMhz = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Freq (MHz)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = bwKhz, onValueChange = { bwKhz = it.filter { c -> c.isDigit() } },
                    label = { Text("BW (kHz)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = sf, onValueChange = { sf = it.filter { c -> c.isDigit() } },
                    label = { Text("SF (7-12)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = cr, onValueChange = { cr = it.filter { c -> c.isDigit() } },
                    label = { Text("CR (5-8)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = txp, onValueChange = { txp = it.filter { c -> c.isDigit() || c == '-' } },
                    label = { Text("TX (dBm)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val svc = service ?: return@Button
                    val cfg = io.github.thatsfguy.reticulum.platform.RadioConfig(
                        frequencyHz = (freqMhz.toDoubleOrNull()?.let { (it * 1_000_000).toLong() }) ?: savedRadio.frequencyHz,
                        bandwidthHz = (bwKhz.toLongOrNull()?.let { it * 1000 }) ?: savedRadio.bandwidthHz,
                        spreadingFactor = sf.toIntOrNull() ?: savedRadio.spreadingFactor,
                        codingRate = cr.toIntOrNull() ?: savedRadio.codingRate,
                        txPowerDbm = txp.toIntOrNull() ?: savedRadio.txPowerDbm,
                    )
                    svc.prefs.setRadioConfig(cfg)
                    scopeUi.launch { runCatching { svc.reapplyRadioConfig() } }
                }) { Text("Save & apply") }
            }
        }

        Section("Identity") {
            val identityClipboard = LocalClipboardManager.current
            var hashCopyFeedback by remember { mutableStateOf<String?>(null) }
            androidx.compose.runtime.LaunchedEffect(hashCopyFeedback) {
                if (hashCopyFeedback != null) {
                    kotlinx.coroutines.delay(1500)
                    hashCopyFeedback = null
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Destination hash: " + (ourDest ?: "(unknown — connect first)"),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (!ourDest.isNullOrEmpty()) {
                    OutlinedButton(
                        onClick = {
                            identityClipboard.setText(AnnotatedString(ourDest!!))
                            hashCopyFeedback = "Copied"
                        },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 12.dp,
                            vertical = 4.dp,
                        ),
                    ) { Text("Copy", style = MaterialTheme.typography.bodySmall) }
                }
            }
            if (hashCopyFeedback != null) {
                Text(
                    hashCopyFeedback!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

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

        Section("Propagation") {
            val propagationNodes by viewModel.propagationNodes.collectAsState(initial = emptyList())

            if (propagationNodes.isEmpty()) {
                Text(
                    "No propagation nodes seen yet. Once a peer announces with " +
                        "name_hash 'lxmf.propagation' it'll show up here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val ranked = propagationNodes.sortedWith(compareBy({ it.hopCount }, { -it.lastSeen }))
                val best = ranked.first()
                Text(
                    "${propagationNodes.size} propagation node(s) seen. Auto-sync tries " +
                        "the closest by hop count, falling through up to 5 candidates " +
                        "if one doesn't respond.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Best candidate: ${best.hopCount} hops, last seen ${(System.currentTimeMillis() - best.lastSeen) / 60_000}m ago",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { viewModel.syncPropagationAuto() }) {
                    Text("Sync now")
                }
            }
        }

        Section("About") {
            Text("Reticulum Mobile · ${io.github.thatsfguy.reticulum.android.BuildConfig.VERSION_NAME} (${io.github.thatsfguy.reticulum.android.BuildConfig.VERSION_CODE})")
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
            val clipboard = LocalClipboardManager.current
            var copyFeedback by remember { mutableStateOf<String?>(null) }
            // Auto-clear the "Copied N lines" confirmation after a moment.
            androidx.compose.runtime.LaunchedEffect(copyFeedback) {
                if (copyFeedback != null) {
                    kotlinx.coroutines.delay(1800)
                    copyFeedback = null
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    // Always copy the FULL underlying buffer regardless of
                    // current filter — that's what's most useful for debugging.
                    val n = rawLog.size
                    clipboard.setText(AnnotatedString(rawLog.joinToString("\n")))
                    copyFeedback = "Copied $n lines"
                }, enabled = rawLog.isNotEmpty()) { Text("Copy log") }
                OutlinedButton(onClick = {
                    viewModel.clearLog()
                    copyFeedback = "Cleared"
                }, enabled = rawLog.isNotEmpty()) { Text("Clear") }
                Text(
                    copyFeedback ?: if (verboseLog) "${log.size} lines" else "${log.size} of ${rawLog.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (copyFeedback != null)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Switch(
                    checked = verboseLog,
                    onCheckedChange = { viewModel.setVerboseLog(it) },
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (verboseLog) "Verbose: showing all protocol events"
                    else "Filtered: messages + connection only",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                // SelectionContainer makes the lines long-press-selectable
                // for partial copies; the Copy log button above grabs the
                // whole buffer.
                SelectionContainer {
                    LazyColumn(reverseLayout = true, modifier = Modifier.fillMaxSize().padding(8.dp)) {
                        items(log.reversed()) { line ->
                            Text(line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        }
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

private fun transportKindLabel(kind: io.github.thatsfguy.reticulum.engine.ReticulumEngine.TransportKind?): String =
    when (kind) {
        io.github.thatsfguy.reticulum.engine.ReticulumEngine.TransportKind.Ble        -> "BLE"
        io.github.thatsfguy.reticulum.engine.ReticulumEngine.TransportKind.BtClassic  -> "BT Classic"
        io.github.thatsfguy.reticulum.engine.ReticulumEngine.TransportKind.Tcp        -> "TCP"
        io.github.thatsfguy.reticulum.engine.ReticulumEngine.TransportKind.Usb        -> "USB"
        null                                                                          -> "—"
    }

private fun formatDuration(seconds: Long): String = when {
    seconds < 60          -> "${seconds}s"
    seconds < 3600        -> "${seconds / 60}m ${seconds % 60}s"
    seconds < 86400       -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
    else                  -> "${seconds / 86400}d"
}

@Composable
private fun BleScanDialog(
    onPick: (DiscoveredDevice) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var devices by remember { mutableStateOf<List<DiscoveredDevice>>(emptyList()) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        BleScanner.scan(context).collectLatest { devices = it }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Scan for RNode (Nordic UART)") },
        text = {
            Column {
                if (devices.isEmpty()) {
                    Text(
                        "Scanning… If your RNode doesn't show up, make sure it's powered on, " +
                            "in range, and not already paired with another phone. Some firmware " +
                            "doesn't advertise the NUS UUID until a connection is established — " +
                            "in that case use Connect by MAC.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Box(Modifier.fillMaxWidth().height(280.dp)) {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(devices, key = { it.address }) { dev ->
                                Row(
                                    Modifier.fillMaxWidth().clickable { onPick(dev) }.padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            dev.name?.takeIf { it.isNotBlank() } ?: "(unnamed)",
                                            style = MaterialTheme.typography.titleMedium,
                                        )
                                        Text(
                                            dev.address,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Text(
                                        "${dev.rssi} dBm",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun BtClassicPickerDialog(
    onPick: (BondedDevice) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    // Bonded list is instant — read once when the dialog opens. If the
    // user pairs a device while this is open they can dismiss and reopen.
    val devices = remember { BtClassicDevices.bonded(context) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pick paired device") },
        text = {
            Column {
                if (devices.isEmpty()) {
                    Text(
                        "No paired Classic Bluetooth devices found. Pair your RNode in " +
                            "Android Settings first — it will appear here once bonded.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Box(Modifier.fillMaxWidth().height(280.dp)) {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(devices, key = { it.address }) { dev ->
                                Row(
                                    Modifier.fillMaxWidth().clickable { onPick(dev) }.padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            dev.name?.takeIf { it.isNotBlank() } ?: "(unnamed)",
                                            style = MaterialTheme.typography.titleMedium,
                                        )
                                        Text(
                                            dev.address,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }) { Text("Open Bluetooth settings") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        content()
    }
}
