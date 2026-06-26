package io.github.thatsfguy.reticulum.android.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import io.github.thatsfguy.reticulum.android.MainActivity
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.window.Dialog
import io.github.thatsfguy.reticulum.android.platform.BlePermissions
import io.github.thatsfguy.reticulum.android.platform.BleScanKind
import io.github.thatsfguy.reticulum.android.platform.BleScanner
import io.github.thatsfguy.reticulum.android.platform.DiscoveredDevice
import io.github.thatsfguy.reticulum.android.platform.DiscoveredNode
import io.github.thatsfguy.reticulum.android.platform.NodeDiscovery
import io.github.thatsfguy.reticulum.android.platform.NodeTransport
import io.github.thatsfguy.reticulum.android.platform.Qr
import io.github.thatsfguy.reticulum.transport.ConnectionMemory
import io.github.thatsfguy.reticulum.transport.SavedNode
import io.github.thatsfguy.reticulum.android.service.ReticulumService
import io.github.thatsfguy.reticulum.android.ui.ReticulumViewModel
import io.github.thatsfguy.reticulum.transport.TransportState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Settings is a grouped index that drills into focused sub-screens
 *  (docs/REDESIGN.md §6) — a null route shows the index. */
private enum class SettingsRoute(val title: String) {
    Connection("Connection"),
    Identity("Identity"),
    Features("Features"),
    Privacy("Privacy & security"),
    Appearance("Appearance"),
    About("About & diagnostics"),
}

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
    val keysStoredPlaintext by viewModel.keysStoredPlaintext.collectAsState(initial = false)
    val ourDest by viewModel.ourDestHash.collectAsState()
    val cardJson by viewModel.myCardJson.collectAsState()
    val qrBitmap = remember(cardJson) {
        cardJson?.let { runCatching { Qr.encode(it, sizePx = 512) }.getOrNull() }
    }
    val service by viewModel.service.collectAsState()
    val pendingKinds by (service?.pendingKinds
        ?: kotlinx.coroutines.flow.MutableStateFlow(emptySet<io.github.thatsfguy.reticulum.engine.ReticulumEngine.TransportKind>())).collectAsState()
    val savedHost by (service?.prefs?.tcpHost
        ?: kotlinx.coroutines.flow.MutableStateFlow("RNS.MichMesh.net")).collectAsState()
    val savedPort by (service?.prefs?.tcpPort
        ?: kotlinx.coroutines.flow.MutableStateFlow(7822)).collectAsState()
    val savedBtAddress by (service?.prefs?.btClassicAddress
        ?: kotlinx.coroutines.flow.MutableStateFlow("")).collectAsState()
    val savedBtName by (service?.prefs?.btClassicName
        ?: kotlinx.coroutines.flow.MutableStateFlow("")).collectAsState()
    val savedBleAddress by (service?.prefs?.bleAddress
        ?: kotlinx.coroutines.flow.MutableStateFlow("")).collectAsState()
    val savedBleName by (service?.prefs?.bleName
        ?: kotlinx.coroutines.flow.MutableStateFlow("")).collectAsState()
    val savedAgnLoraAddress by (service?.prefs?.agnosticLoraAddress
        ?: kotlinx.coroutines.flow.MutableStateFlow("")).collectAsState()
    val savedAgnLoraName by (service?.prefs?.agnosticLoraName
        ?: kotlinx.coroutines.flow.MutableStateFlow("")).collectAsState()
    // agnostic-LoRa-Net isn't released yet — its connect UI is gated
    // behind this opt-in toggle (Optional features section).
    val agnLoraEnabled by (service?.prefs?.agnosticLoraEnabled
        ?: kotlinx.coroutines.flow.MutableStateFlow(false)).collectAsState()
    // The node id we're attached to (32-hex), learned from the
    // register-ack/heartbeat after connect — display-only reference.
    val agnLoraNodeId by (service?.agnosticLoraNodeId
        ?: kotlinx.coroutines.flow.MutableStateFlow<String?>(null)).collectAsState()
    val savedLastKinds by (service?.prefs?.lastTransportKinds
        ?: kotlinx.coroutines.flow.MutableStateFlow(emptySet<String>())).collectAsState()
    val savedNodes by (service?.prefs?.savedNodes
        ?: kotlinx.coroutines.flow.MutableStateFlow(emptyList<SavedNode>())).collectAsState()

    // The keys make these fields refresh whenever the persisted value
    // changes (e.g. after the user successfully connects, the prefs
    // update, and the screen reflects the new "current" host/port).
    var tcpHost by remember(savedHost) { mutableStateOf(savedHost) }
    var tcpPort by remember(savedPort) { mutableStateOf(savedPort.toString()) }
    // agnostic-LoRa-Net node: BLE MAC + display name come from the scan and
    // re-seed from prefs after a successful connect. There is no user-set
    // uplink any more — routing is identity-addressed via the mesh directory,
    // and the connected node id is shown read-only for reference (it's learned
    // post-connect; the attached node is never a routing target, §0.5/BR-5).
    var agnLoraAddress by remember(savedAgnLoraAddress) { mutableStateOf(savedAgnLoraAddress) }
    var agnLoraName by remember(savedAgnLoraName) { mutableStateOf(savedAgnLoraName) }
    var nameDraft by remember(displayName) { mutableStateOf(displayName) }
    var showResetConfirm by remember { mutableStateOf(false) }
    // null = the Settings index; non-null = a drilled-in sub-screen.
    var route by remember { mutableStateOf<SettingsRoute?>(null) }
    val anyConnected = connections.any { it.transport == TransportState.Connected }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val current = route
        if (current == null) {
            SettingsIndex(connected = anyConnected, onNavigate = { route = it })
        } else {
            SettingsBackHeader(current.title) { route = null }
        }

        if (route == SettingsRoute.Connection) Section("Connection") {
            // Tick-driven elapsed timer so a slow-but-working "Connecting…"
            // shows e.g. "Connecting (12s)" instead of looking wedged.
            var nowTick by remember { mutableStateOf(System.currentTimeMillis()) }
            androidx.compose.runtime.LaunchedEffect(connections) {
                while (true) {
                    nowTick = System.currentTimeMillis()
                    kotlinx.coroutines.delay(500L)
                }
            }

            // Multi-transport status: one line per attached or pending kind.
            // Pending kinds (supervisor running but not yet attached, e.g.
            // RNode is offline so the connect throws before engine.attach)
            // get rendered as "Connecting…" too — without this the user sees
            // "Disconnected" while the BT Classic loop is actively retrying.
            val attachedKinds = connections.mapNotNull { it.kind }.toSet()
            val pendingOnly = pendingKinds - attachedKinds
            if (connections.isEmpty() && pendingOnly.isEmpty()) {
                Text("Status: ${statusLabel(TransportState.Disconnected)}")
            } else {
                Text("Status:")
                // Each attached/pending kind gets its own Disconnect/Cancel
                // button here, so the unified "Add node" flow below doesn't
                // need per-transport teardown controls.
                connections.forEach { conn ->
                    val elapsed = ((nowTick - conn.changedAtMs).coerceAtLeast(0L)) / 1000L
                    // Which node this kind is attached to. The saved-* for a
                    // kind is the node currently connected on it (connect
                    // persists it), so it labels the status line.
                    val nodeName = when (conn.kind) {
                        io.github.thatsfguy.reticulum.engine.ReticulumEngine.TransportKind.Ble ->
                            savedBleName.ifBlank { savedBleAddress }
                        io.github.thatsfguy.reticulum.engine.ReticulumEngine.TransportKind.BtClassic ->
                            savedBtName.ifBlank { savedBtAddress }
                        io.github.thatsfguy.reticulum.engine.ReticulumEngine.TransportKind.Tcp ->
                            "$savedHost:$savedPort"
                        io.github.thatsfguy.reticulum.engine.ReticulumEngine.TransportKind.AgnosticLora ->
                            savedAgnLoraName.ifBlank { savedAgnLoraAddress }
                        else -> ""
                    }
                    val line = buildString {
                        append("  · ")
                        append(transportKindLabel(conn.kind))
                        if (nodeName.isNotBlank()) append(" (").append(nodeName).append(")")
                        append(" — ")
                        append(statusLabel(conn.transport))
                        if (conn.transport == TransportState.Connecting && conn.changedAtMs > 0L) {
                            append(" (").append(elapsed).append("s)")
                        } else if (conn.transport == TransportState.Connected && conn.changedAtMs > 0L) {
                            append(" · up ").append(formatDuration(elapsed))
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            line,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        conn.kind?.let { k ->
                            TextButton(onClick = { ReticulumService.disconnectKind(context, k) }) {
                                Text(if (conn.transport == TransportState.Connected) "Disconnect" else "Cancel")
                            }
                        }
                    }
                }
                pendingOnly.forEach { kind ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "  · ${transportKindLabel(kind)} — ${statusLabel(TransportState.Connecting)}",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { ReticulumService.disconnectKind(context, kind) }) {
                            Text("Cancel")
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            // Battery-optimization status. Samsung / Xiaomi / OnePlus
            // skins kill optimized foreground services after a few
            // minutes of screen-off despite the AOSP spec saying
            // foreground services are exempt. Show a one-tap shortcut
            // to add the app to the exempt list while we're in this
            // state; hide the row once the user has accepted (or
            // already had us exempt). Polls the PowerManager every
            // 2s so the row clears immediately when the user returns
            // from the system dialog.
            val pm = remember {
                context.getSystemService(android.content.Context.POWER_SERVICE)
                    as android.os.PowerManager
            }
            var batteryOptIgnored by remember {
                mutableStateOf(pm.isIgnoringBatteryOptimizations(context.packageName))
            }
            androidx.compose.runtime.LaunchedEffect(Unit) {
                while (true) {
                    batteryOptIgnored = pm.isIgnoringBatteryOptimizations(context.packageName)
                    kotlinx.coroutines.delay(2_000L)
                }
            }
            if (!batteryOptIgnored) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            RoundedCornerShape(8.dp),
                        )
                        .padding(10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "⚠ Battery optimization is ON for Reticulum",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "Samsung / Xiaomi / OnePlus skins will kill the background BLE/TCP "
                                + "service after a few minutes of screen-off if optimization is on. "
                                + "Tap below to add Reticulum to the exempt list — the app keeps "
                                + "mesh traffic flowing while the screen is off.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = {
                    // System dialog with "Allow" / "Cancel"; user can
                    // decline and the app still works, just with the
                    // vendor-specific aggressive killing.
                    val intent = android.content.Intent(
                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        android.net.Uri.parse("package:${context.packageName}"),
                    )
                    runCatching { context.startActivity(intent) }
                }) { Text("Disable battery optimization") }
                Spacer(Modifier.height(8.dp))
            }

            // Identity-key storage warning. Normally the private keys are
            // sealed at rest with an Android Keystore (hardware-backed) key.
            // On some devices the Keystore refuses every key spec we try, and
            // the storage layer degrades to saving the keys UNENCRYPTED in the
            // DB rather than bricking the app (see IdentityRepoImpl.save). That
            // degrade used to be silent (logcat only) — surface it here so the
            // user knows their keys aren't hardware-protected and can fix it.
            // The flag clears itself once a successful save migrates the keys
            // into the sealed columns (e.g. after a secure lock screen is set).
            if (keysStoredPlaintext) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.errorContainer,
                            RoundedCornerShape(8.dp),
                        )
                        .padding(10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "⚠ Your identity keys are stored unencrypted",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Text(
                            "This device's secure keystore refused to protect your private "
                                + "keys, so they're saved without hardware encryption. The app "
                                + "still works, but anyone with deep access to this device could "
                                + "read your identity. Set a secure screen lock (PIN / password / "
                                + "biometric), then restart the app — it will re-seal the keys "
                                + "automatically. Also export an encrypted backup "
                                + "(Identity → Export) so you can recover if the key is lost.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = {
                    val intent = android.content.Intent(
                        android.provider.Settings.ACTION_SECURITY_SETTINGS,
                    )
                    runCatching { context.startActivity(intent) }
                }) { Text("Open security settings") }
                Spacer(Modifier.height(8.dp))
            }

            // Saved nodes (Phase 4): one-tap (re)connect to a node you've
            // used before, regardless of transport, plus forget. Populated
            // as you connect; the auto-reconnect-on-launch toggle below
            // still drives the silent cold-start reconnect of the last one.
            if (savedNodes.isNotEmpty()) {
                Text("Saved nodes", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Tap a node to connect; tap ✕ to forget it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                savedNodes.forEach { node ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            Modifier.weight(1f).clickable {
                                when (node.kind) {
                                    ConnectionMemory.KIND_BT_CLASSIC ->
                                        ReticulumService.connectBtClassic(context, node.address, node.name)
                                    ConnectionMemory.KIND_TCP ->
                                        node.port?.let { ReticulumService.connectTcp(context, node.address, it) }
                                    else -> // KIND_BLE (and any future Bluetooth kind)
                                        ReticulumService.connectBle(context, node.address, node.name)
                                }
                            },
                        ) {
                            Text(
                                node.name?.takeIf { it.isNotBlank() }
                                    ?: if (node.kind == ConnectionMemory.KIND_TCP) node.address else "(unnamed)",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                savedNodeSubtitle(node),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { service?.prefs?.removeSavedNode(node.key) }) { Text("✕") }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                Spacer(Modifier.height(12.dp))
            }

            Text("RNode (Bluetooth)", style = MaterialTheme.typography.titleMedium)
            // Physical-proximity threat-model notice. The Nordic UART
            // BLE profile we attach to (the RNode's NUS) is
            // unauthenticated by default — anyone in BLE range (~30 m)
            // who can impersonate the RNode could write arbitrary
            // KISS frames into our parser. The KISS parser has a 64 KB
            // ceiling (MED-1) so the OOM vector is closed, but
            // packet-injection is still possible. Bonding the RNode
            // via Android Settings → Bluetooth limits this — pre-
            // bonded RNodes are visible here either way. Audit
            // reference: 2026-05-13 MED-3.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        RoundedCornerShape(8.dp),
                    )
                    .padding(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text("⚠", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.width(8.dp))
                Text(
                    "BLE attaches to your RNode over the Nordic UART (NUS) profile, which is " +
                        "unauthenticated by default. Anyone within ~30 m who can impersonate the " +
                        "RNode could inject crafted packets. To harden against this, pair the " +
                        "RNode in Android Settings → Bluetooth first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "Scan and add your RNode — the app auto-detects whether it speaks BLE or " +
                    "Bluetooth Classic. Classic RNodes must be paired in Android Settings first.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            var showAddNodeDialog by remember { mutableStateOf(false) }
            // One picker for both BLE and Bluetooth-Classic RNodes — the
            // transport is auto-detected from where the device is found
            // (advertising NUS over BLE vs. bonded as Classic). TCP is
            // added separately below (you can't scan for an internet host).
            val bleBusy = connections.any {
                it.kind == io.github.thatsfguy.reticulum.engine.ReticulumEngine.TransportKind.Ble
            } || io.github.thatsfguy.reticulum.engine.ReticulumEngine.TransportKind.Ble in pendingKinds
            val btClassicBusy = connections.any {
                it.kind == io.github.thatsfguy.reticulum.engine.ReticulumEngine.TransportKind.BtClassic
            } || io.github.thatsfguy.reticulum.engine.ReticulumEngine.TransportKind.BtClassic in pendingKinds
            val btTransportBusy = bleBusy || btClassicBusy
            // "Reconnect last" routes by whichever Bluetooth transport
            // kind is in the remembered set (at most one of BLE/BtClassic
            // is Bluetooth; non-BT kinds like TCP/AgnLoRa are unaffected).
            val lastBleSaved = ConnectionMemory.KIND_BLE in savedLastKinds && savedBleAddress.isNotBlank()
            val lastBtSaved = ConnectionMemory.KIND_BT_CLASSIC in savedLastKinds && savedBtAddress.isNotBlank()
            if (lastBleSaved || lastBtSaved) {
                val lastName = (if (lastBtSaved) savedBtName else savedBleName)
                    .takeIf { it.isNotBlank() } ?: "(unnamed)"
                val lastAddr = if (lastBtSaved) savedBtAddress else savedBleAddress
                Text(
                    "Last: $lastName · $lastAddr",
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
                            showAddNodeDialog = true
                        }
                    },
                    enabled = !btTransportBusy,
                ) { Text("Add node") }
                if ((lastBleSaved || lastBtSaved) && !btTransportBusy) {
                    OutlinedButton(onClick = {
                        if (lastBtSaved) {
                            ReticulumService.connectBtClassic(context, savedBtAddress, savedBtName.ifBlank { null })
                        } else {
                            ReticulumService.connectBle(context, savedBleAddress, savedBleName.ifBlank { null })
                        }
                    }) { Text("Reconnect last") }
                }
            }
            if (showAddNodeDialog) {
                AddNodeDialog(
                    onPick = { node ->
                        showAddNodeDialog = false
                        when (node.transport) {
                            NodeTransport.BtClassic ->
                                ReticulumService.connectBtClassic(context, node.address, node.name)
                            else -> // Ble or Dual → prefer BLE
                                ReticulumService.connectBle(context, node.address, node.name)
                        }
                    },
                    onDismiss = { showAddNodeDialog = false },
                )
            }

            // agnostic-LoRa-Net (ALN) isn't released yet — the whole
            // connect block is gated behind the Optional-features toggle.
            if (agnLoraEnabled) {
            Spacer(Modifier.height(8.dp))
            Text("agnostic-LoRa-Net node (BLE)", style = MaterialTheme.typography.titleMedium)
            Text(
                "Attach over BLE to an agnostic-LoRa-Net node (advertises as ALN-…). The " +
                    "node carries your Reticulum traffic into its LoRa mesh — above this link the " +
                    "app behaves just like a TCP transport node: announces arrive, contacts and " +
                    "nodes populate, messages flow. The node drives its own radio, so there's no " +
                    "radio-config UI and no per-message RSSI/SNR.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            var showAgnLoraScan by remember { mutableStateOf(false) }
            val agnLoraEntry = connections.firstOrNull {
                it.kind == io.github.thatsfguy.reticulum.engine.ReticulumEngine.TransportKind.AgnosticLora
            }
            val agnLoraPending =
                io.github.thatsfguy.reticulum.engine.ReticulumEngine.TransportKind.AgnosticLora in pendingKinds
            val agnLoraAttached = agnLoraEntry != null || agnLoraPending
            val agnLoraConnected = agnLoraEntry?.transport == TransportState.Connected

            if (agnLoraAddress.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Node: ${agnLoraName.takeIf { it.isNotBlank() } ?: "(unnamed)"} · $agnLoraAddress",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // The connected node's full id, learned from the directory after
            // connect (the adv name only carries a label/8-hex). Read-only —
            // routing is identity-addressed via the mesh directory.
            agnLoraNodeId?.let { nodeId ->
                Text(
                    "Node id: $nodeId",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val agnLoraReady = agnLoraAddress.isNotBlank()
            Spacer(Modifier.height(4.dp))
            // Idle → [Scan] [Connect]; attached → [Disconnect/Cancel].
            // At most two buttons so labels never wrap to vertical text.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!agnLoraAttached) {
                    Button(
                        onClick = {
                            val missing = BlePermissions.missing(context)
                            if (missing.isNotEmpty()) onRequestPermissions(missing.toTypedArray())
                            else showAgnLoraScan = true
                        },
                    ) { Text("Scan", maxLines = 1) }
                    Button(
                        onClick = {
                            ReticulumService.connectAgnosticLora(
                                context, agnLoraAddress.trim(), agnLoraName.ifBlank { null },
                                uplink = null, // no user-set uplink — directory addressing
                            )
                        },
                        enabled = agnLoraReady,
                    ) { Text("Connect", maxLines = 1) }
                } else {
                    OutlinedButton(onClick = {
                        ReticulumService.disconnectKind(
                            context,
                            io.github.thatsfguy.reticulum.engine.ReticulumEngine.TransportKind.AgnosticLora,
                        )
                    }) {
                        Text(if (agnLoraConnected) "Disconnect" else "Cancel", maxLines = 1)
                    }
                }
            }

            if (showAgnLoraScan) {
                BleScanDialog(
                    kind = BleScanKind.AgnLoRa,
                    onPick = { device ->
                        showAgnLoraScan = false
                        agnLoraAddress = device.address
                        agnLoraName = device.name ?: ""
                        // Deliberately NO uplink auto-fill: the attached node
                        // is almost never the right place to pin traffic to
                        // (mobile-app-testing.md §0.5 — "a wrong uplink looks
                        // like success"). Identity addressing handles routing.
                    },
                    onDismiss = { showAgnLoraScan = false },
                )
            }
            } // end if (agnLoraEnabled)

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "TCP transport node",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                // "Pick another" — re-rolls the host/port to a different
                // entry in the curated rotation. Useful if the current
                // default is overloaded or down. Only meaningful when a
                // service is bound (the prefs object lives there).
                service?.let { svc ->
                    TextButton(onClick = { svc.prefs.pickAnotherTcpNode() }) {
                        Text("Pick another")
                    }
                }
            }
            // Operator-trust notice. Lifted from below the connect
            // button to here so the user sees it BEFORE picking
            // host/port and tapping Connect. Reticulum's spec assumes
            // the transport node operator can observe destination
            // hashes and announces — this is by design, not specific
            // to our implementation, but worth surfacing for LoRa
            // users who have a stronger off-grid intuition. Audit
            // reference: 2026-05-13 MED-5.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        RoundedCornerShape(8.dp),
                    )
                    .padding(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text("⚠", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.width(8.dp))
                Text(
                    "TCP attaches to a remote rnsd transport node over the internet. " +
                        "Whoever operates that node can observe your destination hash, " +
                        "see every announce you emit, and log when you're online. " +
                        "Message contents stay end-to-end encrypted, but metadata is not.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
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
            val tcpEntry = connections.firstOrNull {
                it.kind == io.github.thatsfguy.reticulum.engine.ReticulumEngine.TransportKind.Tcp
            }
            val tcpPending = io.github.thatsfguy.reticulum.engine.ReticulumEngine.TransportKind.Tcp in pendingKinds
            val tcpAttached = tcpEntry != null || tcpPending
            val tcpConnected = tcpEntry?.transport == TransportState.Connected
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val port = tcpPort.toIntOrNull() ?: return@Button
                        if (tcpHost.isNotBlank() && port > 0) {
                            ReticulumService.connectTcp(context, tcpHost.trim(), port)
                            // Remember it in the saved-node list (explicit
                            // connect only — not the first-launch default or
                            // the "Pick another" shuffle).
                            service?.prefs?.addSavedNode(
                                SavedNode(ConnectionMemory.KIND_TCP, tcpHost.trim(), port),
                            )
                        }
                    },
                    enabled = !tcpAttached,
                ) { Text("Connect TCP") }
                if (tcpAttached) {
                    OutlinedButton(onClick = {
                        ReticulumService.disconnectKind(
                            context,
                            io.github.thatsfguy.reticulum.engine.ReticulumEngine.TransportKind.Tcp,
                        )
                    }) {
                        Text(if (tcpConnected) "Disconnect TCP" else "Cancel")
                    }
                }
            }

            // Operator-trust notice was here pre-MED-5; lifted above
            // the host/port fields so the user reads it before picking
            // a destination, not after tapping Connect.

            Spacer(Modifier.height(12.dp))
            // Auto-reconnect-on-launch toggle. On by default; the
            // service persists the last Connected transport and
            // re-establishes it on a cold start. An explicit Disconnect
            // clears the saved transport, so this never overrides a
            // deliberate "go offline".
            val autoReconnect by (service?.prefs?.autoReconnect
                ?: kotlinx.coroutines.flow.MutableStateFlow(true)).collectAsState()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Reconnect on app launch",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "When ON, the app re-establishes the BLE / Bluetooth / TCP "
                            + "transport it was last connected to when it starts, so you "
                            + "don't have to tap Connect every launch. An explicit "
                            + "Disconnect is always remembered — it won't reconnect after "
                            + "you deliberately go offline.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                androidx.compose.material3.Switch(
                    checked = autoReconnect,
                    onCheckedChange = { service?.prefs?.setAutoReconnect(it) },
                )
            }
        }

        if (route == SettingsRoute.Connection) Section("Transports") {
            Text(
                "Turn off any transport you don't use. A disabled transport is "
                    + "never started — it won't scan, connect, or parse incoming "
                    + "bytes — so this install only exposes the paths you actually "
                    + "rely on.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            val bleEnabled by (service?.prefs?.bleEnabled
                ?: kotlinx.coroutines.flow.MutableStateFlow(true)).collectAsState()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("BLE (RNode over Bluetooth LE)", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Nordic-UART RNode scan + GATT connection.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                androidx.compose.material3.Switch(
                    checked = bleEnabled,
                    onCheckedChange = { service?.prefs?.setBleEnabled(it) },
                )
            }

            Spacer(Modifier.height(16.dp))

            val btClassicEnabled by (service?.prefs?.btClassicEnabled
                ?: kotlinx.coroutines.flow.MutableStateFlow(true)).collectAsState()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Bluetooth Classic (RNode SPP)", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "RFCOMM / SPP for older RNode firmwares without BLE NUS.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                androidx.compose.material3.Switch(
                    checked = btClassicEnabled,
                    onCheckedChange = { service?.prefs?.setBtClassicEnabled(it) },
                )
            }

            Spacer(Modifier.height(16.dp))

            val tcpEnabled by (service?.prefs?.tcpEnabled
                ?: kotlinx.coroutines.flow.MutableStateFlow(true)).collectAsState()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("TCP (direct rnsd over the internet)", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Plain-TCP HDLC attachment to a remote Reticulum transport node.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                androidx.compose.material3.Switch(
                    checked = tcpEnabled,
                    onCheckedChange = { service?.prefs?.setTcpEnabled(it) },
                )
            }

            Spacer(Modifier.height(16.dp))

            val usbEnabled by (service?.prefs?.usbEnabled
                ?: kotlinx.coroutines.flow.MutableStateFlow(false)).collectAsState()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("USB serial (RNode over USB-OTG)", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Experimental — wired RNode over a USB-OTG cable (no Bluetooth, "
                            + "no over-the-air eavesdropping). Supports CDC-ACM and CP210x "
                            + "chips; verify on your hardware.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                androidx.compose.material3.Switch(
                    checked = usbEnabled,
                    onCheckedChange = { service?.prefs?.setUsbEnabled(it) },
                )
            }

            if (usbEnabled) {
                var usbRescanTick by remember { mutableStateOf(0) }
                val usbDevices = remember(usbRescanTick) {
                    val mgr = context.getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager
                    mgr.deviceList.values.filter {
                        io.github.thatsfguy.reticulum.platform.usbserial.UsbSerialProber.isSupported(it)
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (usbDevices.isEmpty()) {
                    Text(
                        "No supported USB serial device detected. Attach an RNode via a "
                            + "USB-OTG cable, then Rescan.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    usbDevices.forEach { dev ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(dev.productName ?: "USB device", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "VID 0x${dev.vendorId.toString(16)} PID 0x${dev.productId.toString(16)}"
                                        + " — ${io.github.thatsfguy.reticulum.platform.usbserial.UsbSerialProber.driverName(dev) ?: "?"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { ReticulumService.connectUsb(context, dev.deviceName) }) {
                                Text("Connect")
                            }
                        }
                    }
                }
                TextButton(onClick = { usbRescanTick++ }) { Text("Rescan USB") }
            }
        }

        if (route == SettingsRoute.Connection) Section("Radio config (RNode)") {
            val savedRadio by (service?.prefs?.radioConfig
                ?: kotlinx.coroutines.flow.MutableStateFlow(io.github.thatsfguy.reticulum.platform.RadioConfig())).collectAsState()
            var freqMhz by remember(savedRadio) { mutableStateOf((savedRadio.frequencyHz / 1_000_000.0).toString()) }
            var bwKhz   by remember(savedRadio) {
                val khz = savedRadio.bandwidthHz / 1000.0
                mutableStateOf(if (khz % 1.0 == 0.0) khz.toLong().toString() else khz.toString())
            }
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
                    value = bwKhz, onValueChange = { bwKhz = it.filter { c -> c.isDigit() || c == '.' } },
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
                        bandwidthHz = (bwKhz.toDoubleOrNull()?.let { Math.round(it * 1000) }) ?: savedRadio.bandwidthHz,
                        spreadingFactor = sf.toIntOrNull() ?: savedRadio.spreadingFactor,
                        codingRate = cr.toIntOrNull() ?: savedRadio.codingRate,
                        txPowerDbm = txp.toIntOrNull() ?: savedRadio.txPowerDbm,
                    )
                    svc.prefs.setRadioConfig(cfg)
                    scopeUi.launch { runCatching { svc.reapplyRadioConfig() } }
                }) { Text("Save & apply") }
            }
        }

        if (route == SettingsRoute.Identity) Section("Identity") {
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
            // Issue #31: the Send announce button gave no visual feedback,
            // so users couldn't tell whether anything happened. Show a
            // spinner while the announce is in flight, then a transient
            // confirmation line stating which transport(s) it went out on
            // (or that nothing was sent because no transport is connected).
            val announceScope = rememberCoroutineScope()
            var announcing by remember { mutableStateOf(false) }
            var announceStatus by remember { mutableStateOf<String?>(null) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    enabled = !announcing,
                    onClick = {
                        announceScope.launch {
                            announcing = true
                            announceStatus = null
                            val result = viewModel.announce()
                            announcing = false
                            announceStatus = result
                        }
                    },
                ) {
                    if (announcing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = androidx.compose.material3.LocalContentColor.current,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Sending…")
                    } else {
                        Text("Send announce")
                    }
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { showResetConfirm = true }) { Text("Reset identity…") }
            }
            announceStatus?.let { status ->
                // Auto-dismiss the confirmation after a few seconds so it
                // doesn't linger as stale state.
                androidx.compose.runtime.LaunchedEffect(status) {
                    kotlinx.coroutines.delay(4000)
                    announceStatus = null
                }
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status.startsWith("Announce sent")) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                )
            }
            Text(
                "An announce is sent automatically every 5 minutes while connected.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))
            IdentityBackupBlock(viewModel)
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

        if (route == SettingsRoute.Connection) Section("Propagation") {
            val propagationNodes by viewModel.propagationNodes.collectAsState(initial = emptyList())
            val preferredHash by viewModel.preferredPropagationNode.collectAsState(initial = "")
            val visibleNodes = remember(propagationNodes) {
                propagationNodes.filter { !it.hidden }
                    .sortedWith(compareBy({ it.hopCount }, { -it.lastSeen }))
            }
            var showPicker by remember { mutableStateOf(false) }

            if (visibleNodes.isEmpty()) {
                Text(
                    "No propagation nodes seen yet. Once a peer announces with " +
                        "name_hash 'lxmf.propagation' it'll show up here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val picked = visibleNodes.firstOrNull { it.hash == preferredHash }
                val isAuto = preferredHash.isEmpty()
                Text(
                    "${visibleNodes.size} propagation node(s) seen. Automatic picks the " +
                        "closest by hop count and falls through up to 5 candidates if one " +
                        "doesn't respond. Pick a specific node to talk to that one only.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().clickable { showPicker = true }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Propagation node", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            when {
                                isAuto -> {
                                    val best = visibleNodes.first()
                                    "Automatic — best now: ${best.hopCount} hops, " +
                                        "last seen ${(System.currentTimeMillis() - best.lastSeen) / 60_000}m ago"
                                }
                                picked != null ->
                                    "${picked.effectiveDisplayName.ifBlank { picked.hash.take(8) }} " +
                                        "(${picked.hopCount} hops)"
                                else ->
                                    "Picked node ${preferredHash.take(8)}… is no longer seen — tap to re-pick"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(Icons.Default.KeyboardArrowRight, contentDescription = null)
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = { viewModel.syncPropagationAuto() }) {
                    Text("Sync now")
                }
            }

            if (showPicker) {
                PropagationPickerDialog(
                    nodes = visibleNodes,
                    selectedHash = preferredHash,
                    onPick = { hashOrEmpty ->
                        viewModel.setPropagationNode(hashOrEmpty)
                        showPicker = false
                    },
                    onDismiss = { showPicker = false },
                )
            }
        }

        if (route == SettingsRoute.Privacy) Section("Privacy & security") {
            // MED-6 toggle. Off by default — preserves the legacy
            // "show as unverified, retroactively flip to verified
            // once the sender's announce arrives" UX. Users who
            // want stronger first-contact phishing resistance can
            // flip this and never see an unverified message at all.
            // Audit reference: 2026-05-13 MED-6.
            val dropUnverified by (service?.prefs?.dropUnverified
                ?: kotlinx.coroutines.flow.MutableStateFlow(false)).collectAsState()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Drop unverified messages",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "When ON, inbound messages whose signature can't be checked against a "
                            + "known announce are silently dropped. Default OFF — they're shown "
                            + "as 'Unverified sender' and re-verified retroactively once the "
                            + "sender's announce arrives. Turn ON to harden against display-"
                            + "name phishing on first contact.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                androidx.compose.material3.Switch(
                    checked = dropUnverified,
                    onCheckedChange = { service?.prefs?.setDropUnverified(it) },
                )
            }
        }

        if (route == SettingsRoute.Features) Section("Features") {
            // NomadNet browser — a real feature, off by default to keep
            // the default app lean (docs/REDESIGN.md §9). Enabling it
            // adds a Nomad tab to the bottom bar.
            val nomadEnabled by (service?.prefs?.nomadEnabled
                ?: kotlinx.coroutines.flow.MutableStateFlow(false)).collectAsState()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "NomadNet browser",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Browse NomadNet pages — micron-markup sites hosted on "
                            + "the mesh. Off by default to keep the app lean; "
                            + "enabling it adds a Nomad tab to the bottom bar.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                androidx.compose.material3.Switch(
                    checked = nomadEnabled,
                    onCheckedChange = { service?.prefs?.setNomadEnabled(it) },
                )
            }

            Spacer(Modifier.height(16.dp))

            // RRC (Reticulum Relay Chat) is a new wire protocol still
            // under development — gated so it stays invisible to
            // ordinary users until it's interop-verified. When ON it
            // adds a Rooms tab to the bottom bar.
            val experimentalRrc by (service?.prefs?.experimentalRrc
                ?: kotlinx.coroutines.flow.MutableStateFlow(false)).collectAsState()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Reticulum Relay Chat",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "IRC-style group chat over Reticulum hubs. In active "
                            + "development and not yet interop-verified — enable only "
                            + "to help test it. Adds a Rooms tab to the bottom bar.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                androidx.compose.material3.Switch(
                    checked = experimentalRrc,
                    onCheckedChange = { service?.prefs?.setExperimentalRrc(it) },
                )
            }

            Spacer(Modifier.height(16.dp))

            // agnostic-LoRa-Net BLE transport — not released yet, so its
            // connect UI in Settings → Connection stays hidden until this
            // is enabled.
            val agnLoraEnabledPref by (service?.prefs?.agnosticLoraEnabled
                ?: kotlinx.coroutines.flow.MutableStateFlow(false)).collectAsState()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "agnostic-LoRa-Net (BLE)",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Attach over BLE to an agnostic-LoRa-Net node (ALN-…) that "
                            + "carries your traffic into its LoRa mesh. Still in "
                            + "development — enabling it shows the connect option under "
                            + "Connection.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                androidx.compose.material3.Switch(
                    checked = agnLoraEnabledPref,
                    onCheckedChange = { service?.prefs?.setAgnosticLoraEnabled(it) },
                )
            }
        }

        if (route == SettingsRoute.Appearance) Section("Appearance") {
            val themePref by (service?.prefs?.themePreference
                ?: kotlinx.coroutines.flow.MutableStateFlow("system")).collectAsState()
            Text("Theme", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Use the light or dark palette, or follow the system setting. " +
                    "The dark theme uses a pure-black background — best for AMOLED " +
                    "screens (deeper blacks, lower battery use).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val opts = listOf(
                    "system" to "System", "light" to "Light",
                    "dark" to "Dark",
                )
                opts.forEachIndexed { i, (value, label) ->
                    SegmentedButton(
                        selected = themePref == value,
                        onClick = { service?.prefs?.setThemePreference(value) },
                        shape = SegmentedButtonDefaults.itemShape(index = i, count = opts.size),
                    ) { Text(label) }
                }
            }
        }

        if (route == SettingsRoute.About) Section("About") {
            Text("Reticulum Mobile · ${io.github.thatsfguy.reticulum.android.BuildConfig.VERSION_NAME} (${io.github.thatsfguy.reticulum.android.BuildConfig.VERSION_CODE})")
            Text(
                "Source: github.com/thatsfguy/reticulum-mobile-app",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "On first launch the app generates a fresh Reticulum identity and " +
                    "stores its private keys in the local Room database, inside the " +
                    "app's private storage. Use Export / Import identity in the " +
                    "Identity section above to back it up or move it to another " +
                    "device — archives are passphrase-encrypted.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (route == SettingsRoute.About) Section("Diagnostics log") {
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
        io.github.thatsfguy.reticulum.engine.ReticulumEngine.TransportKind.AgnosticLora -> "AgnLoRa"
        null                                                                          -> "—"
    }

private fun formatDuration(seconds: Long): String = when {
    seconds < 60          -> "${seconds}s"
    seconds < 3600        -> "${seconds / 60}m ${seconds % 60}s"
    seconds < 86400       -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
    else                  -> "${seconds / 86400}d"
}

/**
 * Unified "Add node" picker. Merges the BLE NUS scan with the bonded
 * Bluetooth-Classic list ([NodeDiscovery]) into one transport-agnostic
 * list; the caller routes the connect by [DiscoveredNode.transport]. TCP
 * is intentionally not here — it's add-by-host, not scannable.
 */
@Composable
private fun AddNodeDialog(
    onPick: (DiscoveredNode) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var nodes by remember { mutableStateOf<List<DiscoveredNode>>(emptyList()) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        NodeDiscovery.scan(context)
            .collectLatest { nodes = it }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a node") },
        text = {
            Column {
                if (nodes.isEmpty()) {
                    Text(
                        "Scanning… Make sure your RNode is powered on and in range. A " +
                            "Bluetooth-Classic RNode only appears after you've paired it in " +
                            "Android Settings. Some firmware doesn't advertise the NUS UUID " +
                            "until connected.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Box(Modifier.fillMaxWidth().height(280.dp)) {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(nodes, key = { it.address }) { node ->
                                Row(
                                    Modifier.fillMaxWidth().clickable { onPick(node) }.padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            node.name?.takeIf { it.isNotBlank() } ?: "(unnamed)",
                                            style = MaterialTheme.typography.titleMedium,
                                        )
                                        Text(
                                            node.address,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Text(
                                        transportChipLabel(node),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Don't see a Classic RNode? Pair it in Android Bluetooth settings first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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

/** Short transport badge for a node row, e.g. "BLE −63 dBm",
 *  "Bluetooth", "BLE + Bluetooth", optionally prefixed "LoRa mesh · ". */
private fun transportChipLabel(node: DiscoveredNode): String {
    val base = when (node.transport) {
        NodeTransport.Ble -> "BLE"
        NodeTransport.BtClassic -> "Bluetooth"
        NodeTransport.Dual -> "BLE + Bluetooth"
    }
    val rssi = node.rssi?.let { " $it dBm" } ?: ""
    return "$base$rssi"
}

/** Transport + address subtitle for a saved-node row. */
private fun savedNodeSubtitle(node: SavedNode): String = when (node.kind) {
    ConnectionMemory.KIND_BLE -> "BLE · ${node.address}"
    ConnectionMemory.KIND_BT_CLASSIC -> "Bluetooth · ${node.address}"
    ConnectionMemory.KIND_TCP -> "TCP · ${node.address}:${node.port ?: ""}"
    ConnectionMemory.KIND_AGNOSTIC_LORA -> "AgnLoRa · ${node.address}"
    else -> node.address
}

/**
 * BLE-only NUS scan dialog, parameterised by [kind] (the discriminator on
 * the advertised-name prefix). Used by the agnostic-LoRa connect flow; the
 * unified RNode + Bluetooth-Classic picker is [AddNodeDialog].
 */
@Composable
private fun BleScanDialog(
    onPick: (DiscoveredDevice) -> Unit,
    onDismiss: () -> Unit,
    kind: BleScanKind = BleScanKind.RNode,
) {
    val context = LocalContext.current
    var devices by remember { mutableStateOf<List<DiscoveredDevice>>(emptyList()) }

    androidx.compose.runtime.LaunchedEffect(kind) {
        BleScanner.scan(context, kind).collectLatest { devices = it }
    }

    val title = when (kind) {
        BleScanKind.RNode   -> "Scan for RNode (Nordic UART)"
        BleScanKind.AgnLoRa -> "Scan for AgnLoRa node"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (devices.isEmpty()) {
                    Text(
                        "Scanning… If your node doesn't show up, make sure it's powered on, " +
                            "in range, and (for BLE) paired with this phone.",
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
private fun PropagationPickerDialog(
    nodes: List<io.github.thatsfguy.reticulum.store.StoredDestination>,
    selectedHash: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Propagation node") },
        text = {
            Box(Modifier.fillMaxWidth().height(360.dp)) {
                LazyColumn(Modifier.fillMaxSize()) {
                    item(key = "_auto") {
                        PropagationPickerRow(
                            title = "Automatic",
                            subtitle = "Closest by hops, with up to 5 fallbacks",
                            selected = selectedHash.isEmpty(),
                            onClick = { onPick("") },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    items(nodes, key = { it.hash }) { node ->
                        val ageMin = (System.currentTimeMillis() - node.lastSeen) / 60_000
                        PropagationPickerRow(
                            title = node.effectiveDisplayName.ifBlank { node.hash.take(8) },
                            subtitle = "${node.hopCount} hops · ${node.hash.take(8)}… · ${ageMin}m ago",
                            selected = selectedHash == node.hash,
                            onClick = { onPick(node.hash) },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun PropagationPickerRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/** The Settings root — a tappable index of the grouped sub-screens. */
@Composable
private fun SettingsIndex(connected: Boolean, onNavigate: (SettingsRoute) -> Unit) {
    Column {
        SettingsIndexRow(
            "Connection",
            if (connected) "Connected" else "Tap to connect a transport",
        ) { onNavigate(SettingsRoute.Connection) }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SettingsIndexRow("Identity", "Keys, display name, backup & QR") {
            onNavigate(SettingsRoute.Identity)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SettingsIndexRow("Features", "Optional features") {
            onNavigate(SettingsRoute.Features)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SettingsIndexRow("Privacy & security", "Message verification & safety") {
            onNavigate(SettingsRoute.Privacy)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SettingsIndexRow("Appearance", "Theme — light, dark, or system") {
            onNavigate(SettingsRoute.Appearance)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SettingsIndexRow("About & diagnostics", "Version, diagnostics log, links") {
            onNavigate(SettingsRoute.About)
        }
    }
}

@Composable
private fun SettingsIndexRow(label: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.Default.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Back header shown at the top of a Settings sub-screen. */
@Composable
private fun SettingsBackHeader(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Spacer(Modifier.width(4.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        content()
    }
}

/**
 * Identity export / import. Encrypted with a user-supplied passphrase
 * via PBKDF2-SHA256 + AES-256-CBC + HMAC-SHA256. File extension `.rmid`
 * (Reticulum Mobile Identity).
 *
 * Three transitive states:
 *  - [pendingExport]: passphrase dialog open for export
 *  - [pendingImport]: archive bytes loaded from disk, passphrase dialog open
 *  - [pendingReplaceConfirm]: passphrase entered, awaiting "I really mean it"
 *    confirmation before overwriting current identity
 *
 * Errors surface as inline [errorText] under whichever dialog produced
 * them; the dialog stays open so the user can correct passphrase
 * without re-picking the file.
 */
@Composable
private fun IdentityBackupBlock(viewModel: ReticulumViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var pendingExport by remember { mutableStateOf<String?>(null) }    // passphrase being typed
    var pendingImport by remember { mutableStateOf<ByteArray?>(null) } // archive read from disk, awaiting passphrase
    var importPass by remember { mutableStateOf("") }
    var pendingReplaceConfirm by remember { mutableStateOf(false) }
    var pendingRnsImport by remember { mutableStateOf<ByteArray?>(null) } // raw RNS identity awaiting replace-confirm
    var pendingRawExportConfirm by remember { mutableStateOf(false) }      // raw RNS export warning gate
    var errorText by remember { mutableStateOf<String?>(null) }
    var successText by remember { mutableStateOf<String?>(null) }
    // Non-null while a slow crypto op (the archive KDF) or the file
    // write is running. Drives a blocking spinner so the UI never
    // *looks* frozen — the freeze with no feedback is what made users
    // multi-tap, which in turn produced 0-byte export files.
    var busyLabel by remember { mutableStateOf<String?>(null) }
    val busy = busyLabel != null

    // Identity export writes via MainActivity's Activity-level save
    // launcher (MainActivity.saveFile). The per-composition CreateDocument
    // launcher dropped its result on some devices (Activity recreation
    // mid-pick), producing a 0-byte .rmid that looked like a successful
    // backup — a real data-loss trap. The encrypted bytes are built below
    // and handed straight to the Activity, which writes + toasts the result.

    // SAF open: user picks the archive; we load bytes into pendingImport
    // and prompt for passphrase. Filter to octet-stream — most file
    // pickers also surface "any file" as an escape hatch.
    val openDocLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openInputStream(uri).use { it!!.readBytes() }
        }.onSuccess { bytes ->
            errorText = null
            successText = null
            when {
                // Encrypted .rmid → passphrase path.
                io.github.thatsfguy.reticulum.crypto.IdentityArchive.isEncryptedArchive(bytes) -> {
                    pendingImport = bytes
                    importPass = ""
                }
                // Raw RNS identity blob (X25519||Ed25519, 64 plaintext
                // bytes) from rnsd / Sideband / NomadNet — no passphrase.
                bytes.size == 64 -> pendingRnsImport = bytes
                else -> errorText =
                    "Unrecognized identity file — expected a .rmid archive " +
                        "or a 64-byte RNS identity."
            }
        }.onFailure {
            errorText = "Couldn't read archive: ${it.message}"
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(
            onClick = {
                errorText = null
                successText = null
                pendingExport = ""
            },
            enabled = !busy,
        ) { Text("Export identity…") }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(
            onClick = {
                errorText = null
                successText = null
                openDocLauncher.launch(arrayOf("application/octet-stream", "*/*"))
            },
            enabled = !busy,
        ) { Text("Import identity…") }
    }
    Text(
        "Encrypted with a passphrase. Save the .rmid file somewhere safe " +
            "(Drive, password manager, etc.) — anyone with both the file " +
            "AND the passphrase can impersonate you. Import also accepts a " +
            "raw RNS identity file (rnsd / Sideband / NomadNet) — those are " +
            "unencrypted, so handle them carefully.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // Raw RNS export (issue #33) — de-emphasised below the encrypted
    // default, gated behind a warning dialog because it's unencrypted.
    TextButton(
        onClick = {
            errorText = null
            successText = null
            pendingRawExportConfirm = true
        },
        enabled = !busy,
    ) {
        Text(
            "Export raw identity (RNS, unencrypted)…",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    // Result feedback for the steps that have no dialog of their own.
    successText?.let {
        Spacer(Modifier.height(4.dp))
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = androidx.compose.ui.graphics.Color(0xFF1D9E75),
        )
    }
    if (pendingExport == null && pendingImport == null && !pendingReplaceConfirm &&
        pendingRnsImport == null && !pendingRawExportConfirm
    ) {
        errorText?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    // Raw RNS identity import (issue #33). Detected by the file picker
    // (exactly 64 bytes, not a .rmid). No passphrase — the RNS file is
    // plaintext — so we go straight to the replace-confirm.
    pendingRnsImport?.let { rnsBytes ->
        AlertDialog(
            onDismissRequest = { if (!busy) pendingRnsImport = null },
            title = { Text("Import RNS identity?") },
            text = {
                Text(
                    "This is an unencrypted RNS identity file (e.g. from rnsd, " +
                        "Sideband or NomadNet). Importing it permanently replaces your " +
                        "current identity — anyone messaging your old destination hash " +
                        "won't reach you, and active links are torn down. Message history " +
                        "stays. If you haven't exported your current identity, this can't " +
                        "be undone.",
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        scope.launch {
                            busyLabel = "Importing…"
                            val res = viewModel.importRnsIdentity(rnsBytes)
                            busyLabel = null
                            res.onSuccess {
                                pendingRnsImport = null
                                errorText = null
                                successText = "Identity imported."
                            }.onFailure {
                                pendingRnsImport = null
                                errorText = it.message ?: "Import failed"
                            }
                        }
                    },
                ) { Text("Replace") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRnsImport = null }, enabled = !busy) {
                    Text("Cancel")
                }
            },
        )
    }

    // Export passphrase dialog → runs export → opens SAF save sheet
    pendingExport?.let { current ->
        // Live strength assessment. Recomputed cheaply on every
        // keystroke; gate the Export button on `acceptable` so a weak
        // passphrase can't reach IdentityArchive.pack (which also
        // re-checks). Audit reference: 2026-05-13 HIGH-3.
        val assessment = io.github.thatsfguy.reticulum.crypto.assessPassphrase(current)
        AlertDialog(
            onDismissRequest = { if (!busy) pendingExport = null },
            title = { Text("Export identity") },
            text = {
                Column {
                    Text(
                        "Pick a strong passphrase. You'll need this exact passphrase " +
                            "to import the archive on another device. Anyone with the " +
                            "file AND the passphrase can impersonate you on the mesh.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = current,
                        onValueChange = { pendingExport = it; errorText = null },
                        singleLine = true,
                        label = { Text("Passphrase") },
                        // The .rmid passphrase is an impersonation key — mask it
                        // on screen (shoulder-surf / screen-record exposure).
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (current.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        val meterColor = when (assessment.strength) {
                            io.github.thatsfguy.reticulum.crypto.PassphraseStrength.TooWeak ->
                                MaterialTheme.colorScheme.error
                            io.github.thatsfguy.reticulum.crypto.PassphraseStrength.Acceptable ->
                                androidx.compose.ui.graphics.Color(0xFFFFB300)  // amber 700
                            io.github.thatsfguy.reticulum.crypto.PassphraseStrength.Strong ->
                                androidx.compose.ui.graphics.Color(0xFF1D9E75)  // green accent
                        }
                        val meterLabel = when (assessment.strength) {
                            io.github.thatsfguy.reticulum.crypto.PassphraseStrength.TooWeak    -> "Too weak"
                            io.github.thatsfguy.reticulum.crypto.PassphraseStrength.Acceptable -> "OK"
                            io.github.thatsfguy.reticulum.crypto.PassphraseStrength.Strong     -> "Strong"
                        }
                        Text(
                            meterLabel,
                            color = meterColor,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        assessment.reason?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = meterColor)
                        }
                    }
                    errorText?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = assessment.acceptable && !busy,
                    onClick = {
                        scope.launch {
                            busyLabel = "Encrypting…"
                            val res = viewModel.exportIdentityArchive(current)
                            busyLabel = null
                            res.onSuccess { bytes ->
                                pendingExport = null
                                errorText = null
                                val activity = context.findActivity() as? MainActivity
                                if (activity != null) {
                                    activity.saveFile("reticulum-identity.rmid", bytes)
                                } else {
                                    errorText = "Couldn't open the save dialog."
                                }
                            }.onFailure { errorText = it.message ?: "Export failed" }
                        }
                    },
                ) { Text("Export") }
            },
            dismissButton = {
                TextButton(onClick = { pendingExport = null }, enabled = !busy) { Text("Cancel") }
            },
        )
    }

    // Raw RNS export — gated behind an explicit "unencrypted" warning (#33).
    if (pendingRawExportConfirm) {
        AlertDialog(
            onDismissRequest = { if (!busy) pendingRawExportConfirm = false },
            title = { Text("Export UNENCRYPTED identity?") },
            text = {
                Text(
                    "This writes your identity in the raw RNS format (for rnsd / " +
                        "Sideband / NomadNet) with NO passphrase and NO encryption. " +
                        "Anyone who gets this file IS you — they can read your messages " +
                        "and impersonate you. Prefer \"Export identity…\" (encrypted .rmid) " +
                        "unless you specifically need to move this identity into another " +
                        "Reticulum app. Store it somewhere only you control.",
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        scope.launch {
                            busyLabel = "Exporting…"
                            val res = viewModel.exportRnsIdentity()
                            busyLabel = null
                            pendingRawExportConfirm = false
                            res.onSuccess { bytes ->
                                errorText = null
                                val activity = context.findActivity() as? MainActivity
                                if (activity != null) {
                                    activity.saveFile("reticulum-identity", bytes)
                                } else {
                                    errorText = "Couldn't open the save dialog."
                                }
                            }.onFailure { errorText = it.message ?: "Export failed" }
                        }
                    },
                ) { Text("Export unencrypted", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRawExportConfirm = false }, enabled = !busy) {
                    Text("Cancel")
                }
            },
        )
    }

    // Import passphrase dialog
    pendingImport?.let { _ ->
        AlertDialog(
            onDismissRequest = { if (!busy) { pendingImport = null; importPass = "" } },
            title = { Text("Import identity") },
            text = {
                Column {
                    Text(
                        "Enter the passphrase the archive was encrypted with.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = importPass,
                        onValueChange = { importPass = it; errorText = null },
                        singleLine = true,
                        label = { Text("Passphrase") },
                        // Mask the import passphrase too (same key material).
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    errorText?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = importPass.isNotEmpty() && !busy,
                    onClick = {
                        // Move on to the replace-confirmation dialog —
                        // we don't actually call importIdentity until the
                        // user confirms they understand the implication.
                        pendingReplaceConfirm = true
                    },
                ) { Text("Continue") }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingImport = null; importPass = "" },
                    enabled = !busy,
                ) { Text("Cancel") }
            },
        )
    }

    if (pendingReplaceConfirm) {
        AlertDialog(
            onDismissRequest = { if (!busy) pendingReplaceConfirm = false },
            title = { Text("Replace current identity?") },
            text = {
                Text(
                    "This permanently overwrites your current identity with the imported one. " +
                        "Anyone messaging your old destination hash won't reach you anymore. " +
                        "Active link sessions will be torn down. Existing message history stays. " +
                        "If you didn't already export your current identity, this can't be undone.",
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        val bytes = pendingImport ?: return@TextButton
                        val pass = importPass
                        // Close this dialog up front so only the spinner
                        // shows during the import.
                        pendingReplaceConfirm = false
                        scope.launch {
                            busyLabel = "Importing…"
                            val res = viewModel.importIdentityArchive(bytes, pass)
                            busyLabel = null
                            res.onSuccess {
                                pendingImport = null
                                importPass = ""
                                errorText = null
                                successText = "Identity imported."
                            }.onFailure {
                                // Keep pendingImport set — the passphrase
                                // dialog reappears with the error so the
                                // user can retry without re-picking.
                                errorText = it.message ?: "Import failed"
                            }
                        }
                    },
                ) { Text("Replace") }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingReplaceConfirm = false },
                    enabled = !busy,
                ) { Text("Cancel") }
            },
        )
    }

    // Blocking progress overlay — covers the KDF and the file write so
    // the UI never looks wedged. Non-dismissable: the op runs to a
    // result. Entered last so it layers above any open dialog.
    if (busy) {
        Dialog(onDismissRequest = {}) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
            ) {
                Row(
                    Modifier.padding(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.width(16.dp))
                    Text(busyLabel ?: "Working…")
                }
            }
        }
    }
}
