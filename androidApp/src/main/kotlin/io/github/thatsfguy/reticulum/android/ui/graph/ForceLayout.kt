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
 *
 * Cooling (issue #65): every step's movement is scaled by [alpha], which
 * decays geometrically to zero, so the simulation always comes to rest in
 * a stable, readable layout instead of being cut off mid-motion by a
 * wall-clock timer. [seed] carries positions over from a previous layout
 * so a topology change (a new announce) nudges the existing picture —
 * started at a low [initialAlpha] — rather than re-scrambling it from
 * random positions.
 */
class ForceLayout(
    nodes: List<GraphNode>,
    private val edges: List<GraphEdge>,
    val canvasSize: Size,
    private val repulsion: Float = 22000f,
    private val springLength: Float = 140f,
    private val springStiffness: Float = 0.04f,
    private val gravity: Float = 0.005f,
    private val damping: Float = 0.85f,
    private val maxStep: Float = 12f,
    seed: Map<String, Offset> = emptyMap(),
    initialAlpha: Float = 1f,
    private val alphaDecay: Float = 0.99f,
    private val alphaMin: Float = 0.005f,
) {
    /** Simulation "temperature"; movement per step is scaled by it. */
    var alpha: Float = initialAlpha
        private set

    /** True once the layout has cooled down; [step] is then a no-op. */
    val settled: Boolean get() = alpha < alphaMin

    val nodes: MutableList<MutableNode> = nodes.mapIndexed { i, n ->
        // Start nodes pseudo-randomly around the centre so the first frame is non-degenerate.
        val rng = Random(n.id.hashCode())
        val angle = rng.nextFloat() * 6.2831853f
        val radius = 50f + rng.nextFloat() * 200f
        val seeded = seed[n.id]
        MutableNode(
            data = n,
            x = seeded?.x ?: (canvasSize.width / 2f + (radius * kotlin.math.cos(angle))),
            y = seeded?.y ?: (canvasSize.height / 2f + (radius * kotlin.math.sin(angle))),
            vx = 0f, vy = 0f,
            fixed = n.fixed,
        )
    }.toMutableList()

    private val byId: Map<String, MutableNode> = this.nodes.associateBy { it.data.id }

    /** Node by id, for edge drawing without a linear scan per edge. */
    fun node(id: String): MutableNode? = byId[id]

    /** Current positions, used to [seed] the next layout. */
    fun positions(): Map<String, Offset> = nodes.associate { it.data.id to Offset(it.x, it.y) }

    fun step() {
        if (settled) return
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
            val sx = (n.vx * alpha).coerceIn(-maxStep, maxStep)
            val sy = (n.vy * alpha).coerceIn(-maxStep, maxStep)
            n.x += sx
            n.y += sy
        }
        alpha *= alphaDecay
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
