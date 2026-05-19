package io.github.thatsfguy.reticulum.android.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.thatsfguy.reticulum.android.platform.Qr
import io.github.thatsfguy.reticulum.store.StoredDestination

/**
 * The shared "destination detail" bottom sheet — Phase 1 item 4 of the
 * UI redesign (docs/REDESIGN.md §6). One consistent surface, invoked
 * from Nodes / Messages / Rooms, that holds everything pulled off the
 * list rows: the full destination hash (+ copy), the message / rename /
 * contact / delete actions, the routing/key facts, and a QR of the
 * address.
 *
 * Layout order is deliberate: the action buttons sit near the top so
 * the common taps never need a scroll; the facts and the (large) QR
 * are below the fold with a "details below" hint. The whole sheet
 * scrolls. Every action dismisses the sheet after firing so the
 * caller's own navigation takes over cleanly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DestinationDetailSheet(
    dest: StoredDestination,
    onDismiss: () -> Unit,
    onMessage: (hash: String) -> Unit,
    /** Non-null only when the experimental RRC feature is enabled. */
    onOpenAsRrcHub: ((StoredDestination) -> Unit)?,
    onRename: (StoredDestination) -> Unit,
    onToggleFavorite: (hash: String, favorite: Boolean) -> Unit,
    onDelete: (StoredDestination) -> Unit,
    /** Pin state + handler — only the Messages caller passes these;
     *  when onTogglePin is null no Pin action is shown. */
    pinned: Boolean = false,
    onTogglePin: ((hash: String, pinned: Boolean) -> Unit)? = null,
) {
    val isRrcHub = dest.appName == "rrc.hub"
    // Messagable: an LXMF delivery destination, or a manual stub whose
    // public key hasn't arrived via announce yet. Mirrors the predicate
    // the Nodes row used before the redesign.
    val messagable = dest.appName == "lxmf.delivery" || dest.publicKey.isEmpty()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                // No verticalScroll — the sheet wraps its content, so
                // the fully-expanded sheet is only as tall as it needs
                // (no forced full-screen, no dead space below the QR).
                // The sheet itself stays well clear of the nav bar.
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            // ── Header: avatar + name + type/hops summary ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                DetailAvatar(dest.effectiveDisplayName)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        dest.effectiveDisplayName.ifBlank { dest.appLabel ?: "(unnamed)" },
                        style = MaterialTheme.typography.titleLarge,
                    )
                    val summary = buildList {
                        add(dest.appName ?: "unknown")
                        if (dest.hopCount > 0) {
                            add("${dest.hopCount} hop${if (dest.hopCount != 1) "s" else ""}")
                        }
                    }.joinToString(" · ")
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // ── Hash — short label, the full value, and a compact copy
            // icon, all on one line. ──
            val clipboard = LocalClipboardManager.current
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "HASH",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    dest.hash,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { clipboard.setText(AnnotatedString(dest.hash)) },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        CopyIcon,
                        contentDescription = "Copy hash",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            // ── Actions — kept near the top so the common taps need no
            // scroll (docs/REDESIGN.md §6). ──
            if (isRrcHub && onOpenAsRrcHub != null) {
                Button(
                    onClick = { onOpenAsRrcHub(dest); onDismiss() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Open in Relay Chat") }
                Spacer(Modifier.height(8.dp))
            } else if (messagable) {
                Button(
                    onClick = { onMessage(dest.hash); onDismiss() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Message") }
                Spacer(Modifier.height(8.dp))
            }

            if (onTogglePin != null) {
                OutlinedButton(
                    onClick = { onTogglePin(dest.hash, !pinned); onDismiss() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        Icons.Default.KeyboardArrowUp,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (pinned) "Unpin conversation" else "Pin to top")
                }
                Spacer(Modifier.height(8.dp))
            }

            OutlinedButton(
                onClick = { onToggleFavorite(dest.hash, !dest.favorite); onDismiss() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (dest.favorite) "Remove from Contacts" else "Add to Contacts")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { onRename(dest); onDismiss() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (dest.userLabel.isNullOrBlank()) "Add a nickname" else "Edit nickname")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { onDelete(dest); onDismiss() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Delete destination")
            }

            // ── Scroll hint — facts + QR continue below the fold ──
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Details & QR code below",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }

            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(12.dp))

            // ── Facts ──
            DetailFact(
                "Public key",
                if (dest.publicKey.size == 64) "known" else "not yet known",
            )
            if (dest.lastSeen > 0) {
                DetailFact("Last seen", relativeAge(System.currentTimeMillis() - dest.lastSeen))
            }
            // Signal is hidden entirely for TCP-sourced destinations — they
            // report no RSSI (CLAUDE.md "Connect over Internet" note).
            dest.rssi?.let { DetailFact("Signal", "RSSI $it dBm") }
            DetailFact("Source", dest.source)

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(12.dp))

            // ── QR of the hash — at the bottom; scan to identify / share
            // the address. ──
            Text(
                "ADDRESS QR CODE",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            val qr = remember(dest.hash) { Qr.encode(dest.hash, 384) }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Image(
                    bitmap = qr.asImageBitmap(),
                    contentDescription = "QR code of the destination hash",
                    modifier = Modifier
                        .size(140.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.White)
                        .padding(8.dp),
                )
            }
        }
    }
}

@Composable
private fun DetailAvatar(name: String) {
    val initials = name.trim().take(2).uppercase().ifBlank { "?" }
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initials,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun DetailFact(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(116.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Compact relative age — mirrors NodesScreen.formatAge but scoped to
 *  this file (the screen-level one is private). */
private fun relativeAge(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "${s}s ago"
        s < 3600 -> "${s / 60}m ago"
        s < 86_400 -> "${s / 3600}h ago"
        else -> "${s / 86_400}d ago"
    }
}

/** Material "content_copy" glyph as a local ImageVector — the project
 *  deliberately avoids the material-icons-extended dependency, and the
 *  core set has no copy icon. */
private val CopyIcon: ImageVector = ImageVector.Builder(
    name = "ContentCopy",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(16f, 1f)
        horizontalLineTo(4f)
        curveTo(2.9f, 1f, 2f, 1.9f, 2f, 3f)
        verticalLineToRelative(14f)
        horizontalLineToRelative(2f)
        verticalLineTo(3f)
        horizontalLineToRelative(12f)
        verticalLineTo(1f)
        close()
        moveTo(19f, 5f)
        horizontalLineTo(8f)
        curveTo(6.9f, 5f, 6f, 5.9f, 6f, 7f)
        verticalLineToRelative(14f)
        curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
        horizontalLineToRelative(11f)
        curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
        verticalLineTo(7f)
        curveTo(21f, 5.9f, 20.1f, 5f, 19f, 5f)
        close()
        moveTo(19f, 21f)
        horizontalLineTo(8f)
        verticalLineTo(7f)
        horizontalLineToRelative(11f)
        verticalLineToRelative(14f)
        close()
    }
}.build()
