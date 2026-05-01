package io.github.thatsfguy.reticulum.android.ui.graph

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Tiny force-directed layout, kept platform-independent so the same logic
 * could later run in commonMain if iOS gets a Canvas-equivalent. Each
 * tick:
 *
 *   - Coulomb-like repulsion between every node pair (1/r²)
 *   - Hooke-like spring attraction along edges
 *   - Weak gravity towards canvas centre
 *   - Velocity damping each step
 *
 * Designed for ~50 nodes max (graph tab is exploratory, not a topology
 * map of the whole mesh). For bigger graphs swap in Barnes-Hut.
 */
class ForceLayout(
    nodes: List<GraphNode>,
    private val edges: List<GraphEdge>,
    private val canvasSize: Size,
    private val repulsion: Float = 22000f,
    private val springLength: Float = 140f,
    private val springStiffness: Float = 0.04f,
    private val gravity: Float = 0.005f,
    private val damping: Float = 0.85f,
    private val maxStep: Float = 12f,
) {
    val nodes: MutableList<MutableNode> = nodes.mapIndexed { i, n ->
        // Start nodes pseudo-randomly around the centre so the first frame is non-degenerate.
        val rng = Random(n.id.hashCode())
        val angle = rng.nextFloat() * 6.2831853f
        val radius = 50f + rng.nextFloat() * 200f
        MutableNode(
            data = n,
            x = canvasSize.width / 2f + (radius * kotlin.math.cos(angle)),
            y = canvasSize.height / 2f + (radius * kotlin.math.sin(angle)),
            vx = 0f, vy = 0f,
            fixed = n.fixed,
        )
    }.toMutableList()

    private val byId = nodes.associate { it.id to this.nodes.first { mn -> mn.data.id == it.id } }

    fun step() {
        val cx = canvasSize.width / 2f
        val cy = canvasSize.height / 2f

        // Repulsion
        for (i in nodes.indices) {
            val a = nodes[i]
            for (j in i + 1 until nodes.size) {
                val b = nodes[j]
                val dx = a.x - b.x
                val dy = a.y - b.y
                val dist2 = max(dx * dx + dy * dy, 1f)
                val force = repulsion / dist2
                val dist = sqrt(dist2)
                val fx = (dx / dist) * force
                val fy = (dy / dist) * force
                if (!a.fixed) { a.vx += fx; a.vy += fy }
                if (!b.fixed) { b.vx -= fx; b.vy -= fy }
            }
        }

        // Springs along edges
        for (e in edges) {
            val a = byId[e.from] ?: continue
            val b = byId[e.to] ?: continue
            val dx = b.x - a.x
            val dy = b.y - a.y
            val dist = max(sqrt(dx * dx + dy * dy), 0.0001f)
            val displacement = dist - springLength
            val fx = (dx / dist) * displacement * springStiffness
            val fy = (dy / dist) * displacement * springStiffness
            if (!a.fixed) { a.vx += fx; a.vy += fy }
            if (!b.fixed) { b.vx -= fx; b.vy -= fy }
        }

        // Gravity towards centre + damping + integration
        for (n in nodes) {
            if (n.fixed) { n.vx = 0f; n.vy = 0f; continue }
            n.vx += (cx - n.x) * gravity
            n.vy += (cy - n.y) * gravity
            n.vx *= damping
            n.vy *= damping
            // Cap step so the layout doesn't explode if forces blow up.
            val sx = n.vx.coerceIn(-maxStep, maxStep)
            val sy = n.vy.coerceIn(-maxStep, maxStep)
            n.x += sx
            n.y += sy
        }
    }
}

data class GraphNode(
    val id: String,
    val label: String,
    val color: Int,            // packed ARGB
    val radius: Float,
    val fixed: Boolean = false,
)

data class GraphEdge(
    val from: String,
    val to: String,
    val strength: Float = 1f,
)

class MutableNode(
    val data: GraphNode,
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    val fixed: Boolean,
) {
    fun position(): Offset = Offset(x, y)
}
