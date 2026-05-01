package io.github.thatsfguy.reticulum.android.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.thatsfguy.reticulum.android.ui.ReticulumViewModel
import io.github.thatsfguy.reticulum.android.ui.graph.ForceLayout
import io.github.thatsfguy.reticulum.android.ui.graph.GraphEdge
import io.github.thatsfguy.reticulum.android.ui.graph.GraphNode

/**
 * Force-directed visualization of every known destination, with the local
 * identity pinned at centre. Edges form a star (every destination connects
 * to "us") for now; intermediate transport nodes appear once we start
 * tracking transport_id from inbound HEADER_2 packets.
 */
@Composable
fun GraphScreen(viewModel: ReticulumViewModel) {
    val destinations by viewModel.allDestinations.collectAsState(initial = emptyList())
    val ourHash by viewModel.ourDestHash.collectAsState()
    val primary = MaterialTheme.colorScheme.primary
    val primaryFaded = primary.copy(alpha = 0.5f)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val outline = MaterialTheme.colorScheme.outlineVariant
    val onSurface = MaterialTheme.colorScheme.onSurface

    var canvasSize by remember { mutableStateOf(Size(800f, 800f)) }

    val (nodes, edges) = remember(destinations, ourHash, canvasSize) {
        buildGraph(destinations, ourHash, primary, primaryFaded, muted)
    }

    val layout = remember(nodes, edges, canvasSize) { ForceLayout(nodes, edges, canvasSize) }

    var simElapsedMs by remember(layout) { mutableStateOf(0L) }
    LaunchedEffect(layout) {
        var lastFrame = 0L
        while (simElapsedMs < 6000) {
            withFrameNanos { now ->
                if (lastFrame != 0L) simElapsedMs += (now - lastFrame) / 1_000_000L
                lastFrame = now
            }
            layout.step()
        }
    }

    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 11.sp, color = onSurface)

    // Pan + zoom state. The transformable modifier produces zoom deltas
    // (multiplicative) and pan deltas (additive in screen px); we apply
    // them inside the Canvas DrawScope so the legend overlay stays at
    // native scale and never zooms with the graph.
    var scale by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(0.3f, 6f)
        pan += panChange
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.padding(start = 12.dp, top = 8.dp, end = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Legend(color = primary,      label = "LXMF favorite")
            Legend(color = primaryFaded, label = "LXMF other")
            Legend(color = muted,        label = "Non-LXMF")
            Text(
                "  ${"%.1fx".format(scale)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(Modifier.fillMaxSize().padding(8.dp)) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .transformable(state = transformState),
            ) {
                if (canvasSize != size) canvasSize = size

                val pivot = Offset(size.width / 2f, size.height / 2f)
                translate(left = pan.x, top = pan.y) {
                    scale(scaleX = scale, scaleY = scale, pivot = pivot) {
                        for (e in edges) {
                            val a = layout.nodes.firstOrNull { it.data.id == e.from } ?: continue
                            val b = layout.nodes.firstOrNull { it.data.id == e.to } ?: continue
                            drawLine(
                                color = outline,
                                start = Offset(a.x, a.y),
                                end = Offset(b.x, b.y),
                                strokeWidth = 1.5f / scale, // keep stroke visually 1.5px
                            )
                        }
                        for (n in layout.nodes) {
                            drawCircle(color = Color(n.data.color), radius = n.data.radius, center = Offset(n.x, n.y))
                            drawCircle(color = onSurface, radius = n.data.radius, center = Offset(n.x, n.y), style = Stroke(width = 1.2f / scale))
                        }
                        for (n in layout.nodes) {
                            if (n.data.radius < 6f) continue
                            val measured = textMeasurer.measure(AnnotatedString(n.data.label), style = labelStyle)
                            drawText(
                                textLayoutResult = measured,
                                topLeft = Offset(
                                    x = n.x - measured.size.width / 2f,
                                    y = n.y + n.data.radius + 4f,
                                ),
                            )
                        }
                    }
                }
            }
            if (destinations.isEmpty()) {
                Text(
                    "Connect a transport on Settings to populate the graph.",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(modifier = Modifier.size(10.dp)) {
            drawCircle(color = color, radius = size.minDimension / 2f, center = Offset(size.width / 2f, size.height / 2f))
        }
        Text(
            "  $label",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun buildGraph(
    destinations: List<io.github.thatsfguy.reticulum.store.StoredDestination>,
    ourHash: String?,
    primary: Color,
    primaryFaded: Color,
    muted: Color,
): Pair<List<GraphNode>, List<GraphEdge>> {
    val nodes = mutableListOf<GraphNode>()
    val edges = mutableListOf<GraphEdge>()
    if (ourHash != null) {
        nodes += GraphNode(
            id = ourHash, label = "me",
            color = primary.toArgbInt(),
            radius = 14f,
            fixed = true,
        )
    }
    for (d in destinations.take(60)) {
        if (d.hash == ourHash) continue
        val color = when {
            d.appName == "lxmf.delivery" && d.favorite -> primary.toArgbInt()
            d.appName == "lxmf.delivery"               -> primaryFaded.toArgbInt()
            else                                       -> muted.toArgbInt()
        }
        nodes += GraphNode(
            id = d.hash,
            label = d.displayName.take(14).ifBlank { d.hash.take(6) },
            color = color,
            radius = if (d.favorite) 11f else 7f,
        )
        if (ourHash != null) {
            edges += GraphEdge(from = ourHash, to = d.hash)
        }
    }
    return nodes to edges
}

private fun Color.toArgbInt(): Int {
    val a = (alpha * 255).toInt() and 0xFF
    val r = (red * 255).toInt() and 0xFF
    val g = (green * 255).toInt() and 0xFF
    val b = (blue * 255).toInt() and 0xFF
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}
